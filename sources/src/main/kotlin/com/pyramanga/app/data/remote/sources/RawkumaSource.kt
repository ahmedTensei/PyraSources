package com.pyramanga.app.data.remote.sources

import com.pyramanga.app.domain.model.*
import com.pyramanga.app.domain.source.*
import com.pyramanga.app.utils.CloudflareChallengeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

/** Current Rawkuma WordPress/HTMX catalogue and HTML reader, November 2026. */
class RawkumaSource(client: OkHttpClient) : HttpMangaSource(client) {
    override val id = "rawkuma"
    override val name = "Rawkuma"
    override val baseUrl = "https://rawkuma.net"
    override val type = SourceType.HTML
    override val lang = listOf("ja")
    override val capabilities = SourceCapabilities(
        sortOrders = listOf(MangaSortOrder.LATEST, MangaSortOrder.POPULAR, MangaSortOrder.ALPHABETICAL, MangaSortOrder.DEFAULT),
        supportsSearch = true, supportsGenreFilter = true, pageAccess = PageAccess.FULL,
        genreFilterOrders = listOf(MangaSortOrder.DEFAULT), hasLockedChapters = true
    )
    private val json = Json { ignoreUnknownKeys = true }

    override fun imageRequestHeaders(pageUrl: String) = mapOf("Referer" to "$baseUrl/", "Origin" to baseUrl)

    override suspend fun browseManga(page: Int, sortOrder: MangaSortOrder, genreId: String?): Paginated<Manga> {
        require(page > 0 && sortOrder in capabilities.sortOrders)
        if (genreId != null) {
            require(sortOrder == MangaSortOrder.DEFAULT)
            return parseMangaList(fetchDocument("/genre/${encodeQueryParam(genreId)}/?the_page=$page"), page)
        }
        if (sortOrder == MangaSortOrder.LATEST) return parseMangaList(fetchDocument("/latest-update/?the_page=$page"), page)
        return advancedSearch("", page, if (sortOrder == MangaSortOrder.ALPHABETICAL) "title" else "popular")
    }

    override suspend fun searchManga(query: String, page: Int): Paginated<Manga> {
        require(page > 0)
        return advancedSearch(query, page, "popular")
    }

    override suspend fun getGenres(): List<SourceGenre> = fetchDocument("/").select("a[href*=/genre/]")
        .mapNotNull { link ->
            val slug = link.absUrl("href").toHttpUrlOrNull()?.pathSegments?.filter(String::isNotBlank)?.lastOrNull()
            val title = link.text().trim()
            if (slug.isNullOrBlank() || title.isBlank()) null else SourceGenre(slug, title)
        }.distinctBy { it.id }

    override suspend fun getMangaDetails(mangaUrlOrId: String): Manga = parseMangaDetails(fetchDocumentReusingRecent(workUrl(mangaUrlOrId)))

    override suspend fun getChapterList(mangaUrlOrId: String): List<Chapter> {
        val url = workUrl(mangaUrlOrId)
        return parseChapters(fetchDocumentReusingRecent(url), url)
    }

    override suspend fun getChapterContent(chapterUrlOrId: String): ChapterContent {
        val url = chapterUrlOrId.toHttpUrlOrNull()?.toString() ?: throw structureChanged("Rawkuma chapter requires its public URL")
        return parseChapterContent(fetchDocument(url), url)
    }

    private suspend fun advancedSearch(query: String, page: Int, order: String): Paginated<Manga> {
        val nonce = fetchDocument("/wp-admin/admin-ajax.php?type=search_form&action=get_nonce")
            .selectFirst("input[name=search_nonce]")?.attr("value").orEmpty()
        if (nonce.isBlank()) throw structureChanged("Rawkuma catalogue nonce is missing")
        val body = FormBody.Builder().add("nonce", nonce).add("page", page.toString())
            .add("query", query).add("orderby", order).add("order", if (order == "title") "asc" else "desc")
            .add("genre", "[]").add("type", "[]").add("status", "[]").add("inclusion", "OR").add("exclusion", "OR").build()
        val url = "$baseUrl/wp-admin/admin-ajax.php?action=advanced_search"
        val request = Request.Builder().url(url).post(body).tag(String::class.java, id).apply {
            defaultHeaders().forEach { (key, value) -> header(key, value) }
            header("Referer", "$baseUrl/library/")
        }.build()
        val response = try { httpClient.newCall(request).await() }
        catch (failure: CloudflareChallengeException) { throw SourceException(SourceErrorKind.PROTECTION, id, failure.challengeUrl, cause = failure) }
        catch (failure: IOException) { throw SourceException(SourceErrorKind.NETWORK, id, failure.message.orEmpty(), cause = failure) }
        val text = withContext(Dispatchers.IO) { response.use {
            if (!it.isSuccessful) throw SourceException(SourceErrorKind.HTTP, id, "HTTP ${it.code} ($url)", it.code)
            it.body.string()
        } }
        return parseMangaList(Jsoup.parse(text, "$baseUrl/library/"), page)
    }

    fun parseMangaList(document: Document, page: Int): Paginated<Manga> {
        val content = document.selectFirst("main") ?: document.body()
        val works = linkedMapOf<String, Manga>()
        content.select("a[href]").forEach { anchor ->
            val url = anchor.absUrl("href").toHttpUrlOrNull() ?: return@forEach
            val segments = url.pathSegments.filter(String::isNotBlank)
            if (url.host != "rawkuma.net" || segments.size != 2 || segments[0] != "manga") return@forEach
            val image = anchor.selectFirst("img") ?: return@forEach
            val cover = image.absUrl("src").ifBlank { image.absUrl("data-src") }
            val title = anchor.attr("title").ifBlank { image.attr("alt") }.trim()
            if (cover.isBlank() || title.isBlank()) throw structureChanged("Incomplete Rawkuma catalogue work")
            val key = segments[1]
            works.putIfAbsent(key, Manga(key, id, title, cover, url.toString(), contentLanguage = "ja"))
        }
        if (works.isEmpty() && !Regex("no (?:results|manga|series)|nothing found", RegexOption.IGNORE_CASE).containsMatchIn(content.text()))
            throw structureChanged("Rawkuma catalogue structure is missing")
        val next = document.select("a[href]").any { link ->
            val url = link.absUrl("href").toHttpUrlOrNull()
            (url?.queryParameter("the_page") ?: url?.queryParameter("page"))?.toIntOrNull()?.let { it > page } == true
        } || document.select("[hx-post], [hx-get]").any { element ->
            val url = (element.attr("hx-post").ifBlank { element.attr("hx-get") }).toHttpUrlOrNull()
            url?.queryParameter("page")?.toIntOrNull()?.let { it > page } == true
        } || document.select("button[onclick]").any { button ->
            Regex("addSingularFilter.*?['\"]page['\"]\\s*,\\s*['\"](\\d+)['\"]")
                .find(button.attr("onclick"))?.groupValues?.get(1)?.toIntOrNull()?.let { it > page } == true
        }
        return Paginated(works.values.toList(), page, next)
    }

    fun parseMangaDetails(document: Document): Manga {
        val data = document.select("script[type=application/ld+json]").mapNotNull { script ->
            runCatching { json.parseToJsonElement(script.data()) as? JsonObject }.getOrNull()
        }.firstOrNull { item ->
            item.str("@type") in setOf("ComicSeries", "CreativeWorkSeries") ||
                item.arr("@type").any { (it as? JsonPrimitive)?.content in setOf("ComicSeries", "CreativeWorkSeries") }
        }
        val article = document.selectFirst("article[itemprop=itemReviewed]") ?: throw structureChanged("Rawkuma work information is missing")
        val title = data?.str("name").orEmpty().ifBlank { article.selectFirst("h1[itemprop=name]")?.text().orEmpty() }
        val cover = data?.str("image").orEmpty().ifBlank { data?.obj("image")?.str("url").orEmpty() }
            .ifBlank { article.selectFirst("[itemprop=image] img")?.absUrl("src").orEmpty() }
        val url = data?.str("url").orEmpty().ifBlank { document.location() }
        val key = workSlug(url)
        if (title.isBlank() || cover.isBlank() || key.isBlank()) throw structureChanged("Incomplete Rawkuma details")
        val description = article.select("[itemprop=description]").maxByOrNull { it.text().length }?.text().orEmpty()
        val genres = data?.arr("genre")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: article.select("[itemprop=genre]").map { it.text() }
        val state = when (data?.str("creativeWorkStatus")?.lowercase()) {
            "ongoing" -> MangaStatus.ONGOING
            "completed" -> MangaStatus.COMPLETED
            "hiatus" -> MangaStatus.HIATUS
            "cancelled", "dropped" -> MangaStatus.CANCELLED
            else -> MangaStatus.UNKNOWN
        }
        val rating = data?.obj("aggregateRating")
        return Manga(key, id, title, resolveUrl(cover), workUrl(key), author = data?.obj("author")?.str("name").orEmpty(),
            artist = data?.obj("illustrator")?.str("name").orEmpty(), description = description, genres = genres, status = state,
            rating = rating?.double("ratingValue")?.toFloat()?.takeIf { it.isFinite() && it in 0f..10f }, ratingCount = rating?.int("ratingCount"),
            isAdult = genres.any { it.equals("adult", true) || it.equals("hentai", true) },
            alternativeTitles = data?.str("alternateName")?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty(),
            lastUpdated = parseIsoInstant(data?.str("dateModified")),
            type = article.select("h4").firstOrNull { it.text().trim().equals("Type", true) }?.parent()?.selectFirst("p")?.text().orEmpty().lowercase(),
            contentLanguage = "ja")
    }

    fun parseChapters(document: Document, mangaUrl: String): List<Chapter> {
        val container = document.selectFirst("#chapter-list") ?: throw structureChanged("Rawkuma chapter list is missing")
        val mangaId = workSlug(mangaUrl)
        return container.select("[data-chapter-number]").mapIndexed { index, element ->
            val link = element.selectFirst("a[href]") ?: throw structureChanged("Rawkuma chapter URL is missing")
            val url = link.absUrl("href")
            val key = url.toHttpUrlOrNull()?.pathSegments?.filter(String::isNotBlank)?.lastOrNull().orEmpty()
            val number = element.attr("data-chapter-number").toFloatOrNull()?.takeIf { it.isFinite() } ?: -1f
            val title = link.select("span").firstOrNull { it.text().startsWith("Chapter", true) }?.text()
                ?: link.text().substringBefore(element.selectFirst("time")?.text().orEmpty().ifBlank { "\u0000" }).trim()
            if (key.isBlank() || title.isBlank()) throw structureChanged("Incomplete Rawkuma chapter")
            Chapter(key, mangaId, id, title, number, url, dateUpload = parseIsoInstant(element.selectFirst("time[datetime]")?.attr("datetime")),
                isLocked = element.select("[data-locked=true], [data-premium=true], .fa-lock, .lucide-lock").isNotEmpty(),
                language = "ja", sourceOrder = index)
        }
    }

    fun parseChapterContent(document: Document, chapterUrl: String): ChapterContent {
        val content = document.selectFirst("section[data-image-data]")
        val images = content?.select("img").orEmpty()
        if (images.isEmpty()) {
            val message = (content ?: document.selectFirst("main") ?: document.body()).text()
            if (Regex("(?:this|premium) chapter (?:is |requires |locked)|unlock (?:this|the) chapter", RegexOption.IGNORE_CASE).containsMatchIn(message))
                throw SourceException(SourceErrorKind.LOCKED, id, "Rawkuma requires access to this chapter")
            throw structureChanged("Rawkuma returned no reader images")
        }
        val pages = images.mapIndexed { index, image ->
            val url = image.absUrl("src").ifBlank { image.absUrl("data-src") }
            if (url.toHttpUrlOrNull()?.isHttps != true) throw structureChanged("Invalid Rawkuma page image")
            Page(index, chapterUrl, url, imageRequestHeaders(chapterUrl))
        }
        return ChapterContent(pages, webUrl = chapterUrl)
    }

    private fun workUrl(value: String): String = value.toHttpUrlOrNull()?.toString() ?: "$baseUrl/manga/${value.trim().trim('/')}/"
    private fun workSlug(value: String): String = value.toHttpUrlOrNull()?.pathSegments?.filter(String::isNotBlank)
        ?.let { if (it.size >= 2 && it[0] == "manga") it[1] else "" } ?: value.trim().trim('/')
}
