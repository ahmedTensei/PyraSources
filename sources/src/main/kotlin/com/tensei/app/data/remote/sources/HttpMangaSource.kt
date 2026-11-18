package com.tensei.app.data.remote.sources

import com.tensei.app.domain.source.MangaSource
import com.tensei.app.domain.source.SourceErrorKind
import com.tensei.app.domain.source.SourceException
import com.tensei.app.utils.CloudflareChallengeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.time.OffsetDateTime
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared HTTP plumbing for sources (HTML or JSON). It contains no parsing: every source
 * parses its own site. Its only job is to turn transport problems into [SourceException]
 * so a failure is always reported as what it really is.
 */
abstract class HttpMangaSource(
    protected val httpClient: OkHttpClient
) : MangaSource {

    protected open fun defaultHeaders(): Map<String, String> = mapOf(
        "Referer" to "$baseUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    protected fun resolveUrl(rawUrlOrPath: String): String {
        val trimmed = rawUrlOrPath.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$baseUrl$trimmed"
            else -> "$baseUrl/$trimmed"
        }
    }

    /** GET [urlOrPath] and return the body, or throw a typed [SourceException]. */
    protected suspend fun fetchText(
        urlOrPath: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val url = resolveUrl(urlOrPath)
        val request = Request.Builder().url(url).apply {
            defaultHeaders().forEach { (k, v) -> header(k, v) }
            extraHeaders.forEach { (k, v) -> header(k, v) }
        }.build()

        val response = try {
            httpClient.newCall(request).await()
        } catch (e: CloudflareChallengeException) {
            throw SourceException(SourceErrorKind.PROTECTION, id, e.challengeUrl, cause = e)
        } catch (e: IOException) {
            throw SourceException(SourceErrorKind.NETWORK, id, "${e.javaClass.simpleName}: ${e.message.orEmpty()} ($url)", cause = e)
        }

        return withContext(Dispatchers.IO) {
            response.use { res ->
                if (!res.isSuccessful) {
                    val kind = if (res.code == 404 || res.code == 410) SourceErrorKind.NOT_FOUND else SourceErrorKind.HTTP
                    throw SourceException(kind, id, "HTTP ${res.code} ($url)", httpCode = res.code)
                }
                try {
                    res.body.string()
                } catch (e: IOException) {
                    throw SourceException(SourceErrorKind.NETWORK, id, "${e.javaClass.simpleName}: ${e.message.orEmpty()} ($url)", cause = e)
                }
            }
        }
    }

    protected suspend fun fetchDocument(
        urlOrPath: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): Document {
        val url = resolveUrl(urlOrPath)
        val html = fetchText(url, extraHeaders)
        return withContext(Dispatchers.Default) { Jsoup.parse(html, url) }
    }

    private val recentLock = Mutex()
    private var recentUrl: String? = null
    private var recentHtml: String? = null
    private var recentAt: Long = 0L

    override suspend fun clearTransientCache() {
        recentLock.withLock { recentUrl = null; recentHtml = null; recentAt = 0L }
    }

    /**
     * Like [fetchDocument], but reuses the body when the very same URL was fetched a few seconds
     * ago. Details and chapter list often live on the same page; this avoids downloading it twice.
     */
    protected suspend fun fetchDocumentReusingRecent(urlOrPath: String): Document {
        val url = resolveUrl(urlOrPath)
        val html = recentLock.withLock {
            val cached = recentHtml
            if (cached != null && recentUrl == url && System.currentTimeMillis() - recentAt < RECENT_PAGE_TTL_MS) {
                cached
            } else {
                fetchText(url).also {
                    recentUrl = url
                    recentHtml = it
                    recentAt = System.currentTimeMillis()
                }
            }
        }
        return withContext(Dispatchers.Default) { Jsoup.parse(html, url) }
    }

    /** The site answered, but not with the structure this source understands. */
    protected fun structureChanged(what: String): SourceException =
        SourceException(SourceErrorKind.STRUCTURE_CHANGED, id, what)

    protected fun encodeQueryParam(value: String): String =
        URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20")

    companion object {
        private const val RECENT_PAGE_TTL_MS = 20_000L

        private val ARABIC_INDIC_DIGITS = mapOf(
            '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
            '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9'
        )
        private val NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")

        /** First number found in [text] (Arabic-Indic digits accepted); -1 when there is none. */
        fun parseChapterNumber(text: String): Float {
            val normalized = text.map { ARABIC_INDIC_DIGITS[it] ?: it }.joinToString("")
            return NUMBER_REGEX.find(normalized)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: -1f
        }

        /** ISO-8601 instant ("2026-11-08T17:37:43.406439Z") to epoch millis; 0 when unparsable. */
        fun parseIsoInstant(text: String?): Long {
            if (text.isNullOrBlank()) return 0L
            // OffsetDateTime accepts both "Z" and "+00:00" on every supported Android version.
            return runCatching { OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli() }.getOrDefault(0L)
        }
    }
}

/** Cancellable bridge from OkHttp to coroutines: cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

