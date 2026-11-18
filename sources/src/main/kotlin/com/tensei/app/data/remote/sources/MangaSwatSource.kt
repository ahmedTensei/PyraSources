package com.tensei.app.data.remote.sources

import com.tensei.app.domain.model.inSourceOrder
import com.tensei.app.domain.model.Chapter
import com.tensei.app.domain.model.ChapterContent
import com.tensei.app.domain.model.Manga
import com.tensei.app.domain.model.MangaSortOrder
import com.tensei.app.domain.model.MangaStatus
import com.tensei.app.domain.model.Page
import com.tensei.app.domain.model.Paginated
import com.tensei.app.domain.model.SourceType
import com.tensei.app.domain.source.PageAccess
import com.tensei.app.domain.source.SourceCapabilities
import com.tensei.app.domain.source.SourceErrorKind
import com.tensei.app.domain.source.SourceException
import com.tensei.app.domain.source.SourceGenre
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Swat Manga (meshmanga.com) — a client-rendered Next.js site. The site itself reads its data
 * from a JSON backend at appswat.com; this source reads the same public, unauthenticated
 * endpoints the website uses. That backend is undocumented and may change without notice, in
 * which case requests fail with a real error (never with placeholder data).
 *
 * The backend reports `rating: "0.0"` for most series and its scale is not published, so no
 * rating is exposed for this source.
 */
@Singleton
class MangaSwatSource @Inject constructor(
    httpClient: OkHttpClient
) : HttpMangaSource(httpClient) {

    override val id: String = ID
    override val name: String = "Swat Manga"
    override val baseUrl: String = "https://meshmanga.com"
    override val type: SourceType = SourceType.API
    override val lang: List<String> = listOf(CONTENT_LANGUAGE)

    override val capabilities = SourceCapabilities(
        sortOrders = listOf(
            MangaSortOrder.LATEST,
            MangaSortOrder.POPULAR,
            MangaSortOrder.NEWEST,
            MangaSortOrder.ALPHABETICAL
        ),
        supportsSearch = true,
        supportsGenreFilter = true,
        // The "latest releases" feed has no genre filter; the catalogue endpoint has.
        genreFilterOrders = listOf(MangaSortOrder.POPULAR, MangaSortOrder.NEWEST, MangaSortOrder.ALPHABETICAL),
        pageAccess = PageAccess.FULL
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cachedGenres: List<SourceGenre>? = null

    override fun defaultHeaders(): Map<String, String> = mapOf(
        "Referer" to "$baseUrl/",
        "Accept" to "application/json"
    )

    override suspend fun browseManga(page: Int, sortOrder: MangaSortOrder, genreId: String?): Paginated<Manga> {
        val genre = genreId?.takeIf { it.isNotBlank() }
        if (sortOrder == MangaSortOrder.LATEST && genre == null) {
            return parseSeriesPage(fetchText("$API/series/releases/?page=$page&page_size=$PAGE_SIZE"), page)
        }
        val orderBy = when (sortOrder) {
            MangaSortOrder.NEWEST -> "-created_at"
            MangaSortOrder.ALPHABETICAL -> "title"
            else -> "-views_count"
        }
        val genreParam = genre?.let { "&genres=${encodeQueryParam(it)}" }.orEmpty()
        return parseSeriesPage(
            fetchText("$API/series/?page=$page&page_size=$PAGE_SIZE&order_by=$orderBy$genreParam"),
            page
        )
    }

    override suspend fun searchManga(query: String, page: Int): Paginated<Manga> = parseSeriesPage(
        fetchText("$API/series/?page=$page&page_size=$PAGE_SIZE&search=${encodeQueryParam(query)}"),
        page
    )

    override suspend fun getGenres(): List<SourceGenre> {
        cachedGenres?.let { return it }
        return parseGenres(fetchText("$API/genres/")).also { cachedGenres = it }
    }

    override suspend fun getMangaDetails(mangaUrlOrId: String): Manga =
        parseSeriesDetails(fetchText("$API/series/${numericId(mangaUrlOrId)}/"))

    override suspend fun getChapterList(mangaUrlOrId: String): List<Chapter> {
        val seriesId = numericId(mangaUrlOrId)
        val chapters = mutableListOf<Chapter>()
        var nextUrl: String? = "$API/series/$seriesId/chapters/?page_size=$CHAPTER_PAGE_SIZE"
        var requests = 0
        while (nextUrl != null && requests < MAX_CHAPTER_REQUESTS) {
            val (items, next) = parseChapterPage(fetchText(nextUrl), seriesId)
            chapters += items
            nextUrl = next
            requests++
        }
        if (nextUrl != null) throw structureChanged("Chapter list exceeds $MAX_CHAPTER_REQUESTS pages; refusing to return a truncated list")
        return chapters.distinctBy { it.id }.inSourceOrder()
    }

    override suspend fun getChapterContent(chapterUrlOrId: String): ChapterContent {
        val chapterId = numericId(chapterUrlOrId)
        return parseChapterContent(fetchText("$API/chapters/$chapterId/"), "$baseUrl/chapter/$chapterId")
    }

    // ---- parsing (internal so it can be tested against saved real responses) ----

    fun parseSeriesPage(body: String, page: Int): Paginated<Manga> {
        val root = parseObject(body)
        val results = root["results"] as? JsonArray ?: throw structureChanged("series list without results")
        val items = results.map { element ->
            val series = element as? JsonObject ?: throw structureChanged("series list contains a non-object row")
            seriesToManga(series) ?: throw structureChanged("series list contains a row without id/title")
        }.distinctBy { it.id }
        return Paginated(items = items, currentPage = page, hasNextPage = parseNextUrl(root) != null)
    }

    fun parseSeriesDetails(body: String): Manga {
        val series = parseObject(body)
        val base = seriesToManga(series) ?: throw structureChanged("series without id/title")
        val alternativeTitles = series.str("alternative")
            .split('|', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != base.title }
            .distinct()
        return base.copy(
            coverUrl = series.obj("poster")?.str("medium").orEmpty().ifBlank { base.coverUrl },
            author = series.str("author").trim(),
            artist = series.str("artist").trim(),
            description = series.str("story").replace("\r\n", "\n").trim(),
            alternativeTitles = alternativeTitles,
            lastUpdated = parseIsoInstant(series.str("updated_at"))
        )
    }

    fun parseChapterPage(body: String, seriesId: String): Pair<List<Chapter>, String?> {
        val root = parseObject(body)
        val results = root["results"] as? JsonArray ?: throw structureChanged("chapter list without results")
        val chapters = results.map { element ->
            val chapter = element as? JsonObject ?: throw structureChanged("chapter list contains a non-object row")
            val chapterId = chapter.str("id")
            if (chapterId.isBlank()) throw structureChanged("chapter row without id")
            val number = chapter.str("chapter")
            val parsedNumber = parseChapterNumber(number)
            Chapter(
                id = chapterId,
                mangaId = seriesId,
                sourceId = id,
                title = chapter.str("title").trim(),
                number = parsedNumber,
                url = "$baseUrl/chapter/$chapterId",
                dateUpload = parseIsoInstant(chapter.str("created_at")),
                // The account that published the chapter on the source.
                scanlator = chapter.obj("created_by")?.let { it.str("name").ifBlank { it.str("username") } }.orEmpty(),
                language = CONTENT_LANGUAGE
            )
        }
        return chapters to parseNextUrl(root)
    }

    fun parseChapterContent(body: String, webUrl: String): ChapterContent {
        val chapter = parseObject(body)
        val images = chapter["images"] as? JsonArray ?: throw structureChanged("chapter without images")
        val urls = images.map { it as? JsonObject ?: throw structureChanged("chapter contains a non-object image row") }
            .sortedBy { it.int("order") ?: Int.MAX_VALUE }
            .map { image ->
                image.str("image").takeIf { it.toHttpUrlOrNull() != null }
                    ?: throw structureChanged("chapter contains an invalid image URL")
            }
        val declared = chapter.int("images_count") ?: if (chapter.containsKey("images_count")) {
            throw structureChanged("chapter image count is invalid")
        } else urls.size
        if (declared < urls.size) throw structureChanged("chapter image count is smaller than its image list")
        if (urls.isEmpty()) {
            throw if (declared > 0) SourceException(SourceErrorKind.LOCKED, id, "The source withheld chapter images: $webUrl")
                else structureChanged("chapter has no readable page images")
        }
        val headers = imageRequestHeaders(webUrl)
        return ChapterContent(
            pages = urls.mapIndexed { index, url -> Page(index = index, pageUrl = webUrl, imageUrl = url, headers = headers) },
            withheldPageCount = (declared - urls.size).coerceAtLeast(0),
            webUrl = webUrl
        )
    }

    fun parseGenres(body: String): List<SourceGenre> {
        val root: JsonElement = runCatching { json.parseToJsonElement(body) }
            .getOrElse { throw structureChanged("genres: not JSON") }
        val list = root as? JsonArray ?: (root as? JsonObject)?.get("results") as? JsonArray
            ?: throw structureChanged("genres: unexpected shape")
        return list.map { element ->
            val genre = element as? JsonObject ?: throw structureChanged("genres contains a non-object row")
            val genreId = genre.str("id")
            val name = genre.str("name")
            if (genreId.isBlank() || name.isBlank()) throw structureChanged("genre row without id/name")
            SourceGenre(id = genreId, name = name)
        }
    }

    private fun seriesToManga(series: JsonObject): Manga? {
        // The catalogue uses "id"; the releases feed uses "serie_id".
        val seriesId = series.str("id").ifBlank { series.str("serie_id") }
        val title = series.str("title").trim()
        if (seriesId.isBlank() || title.isBlank()) return null
        val poster = series.obj("poster")
        return Manga(
            id = seriesId,
            sourceId = id,
            title = title,
            coverUrl = poster?.str("thumbnail").orEmpty().ifBlank { poster?.str("medium").orEmpty() },
            url = "$baseUrl/series/$seriesId",
            genres = series.arr("genres").mapNotNull { (it as? JsonObject)?.str("name")?.takeIf(String::isNotBlank) },
            status = statusOf(series.obj("status")?.str("name").orEmpty()),
            rating = null,
            type = series.obj("type")?.str("name").orEmpty(),
            contentLanguage = CONTENT_LANGUAGE
        )
    }

    private fun statusOf(name: String): MangaStatus = when {
        name.contains("مستمر") -> MangaStatus.ONGOING
        name.contains("مكتمل") || name.contains("منتهي") -> MangaStatus.COMPLETED
        name.contains("متوقف") -> MangaStatus.HIATUS
        name.contains("ملغ") || name.contains("متروك") -> MangaStatus.CANCELLED
        else -> MangaStatus.UNKNOWN
    }

    private fun parseObject(body: String): JsonObject {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (element == null || element is JsonNull) throw structureChanged("response is not JSON")
        return element as? JsonObject ?: throw structureChanged("response is not a JSON object")
    }

    /** A lost pagination field must not turn a partial response into a complete snapshot. */
    private fun parseNextUrl(root: JsonObject): String? {
        val next = root["next"] ?: throw structureChanged("list pagination field is missing")
        if (next is JsonNull) return null
        val raw = (next as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw structureChanged("list pagination must be a URL or null")
        val url = raw.toHttpUrlOrNull() ?: throw structureChanged("list pagination URL is invalid")
        if (url.scheme != "https" || url.host != "appswat.com" ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || !url.encodedPath.startsWith("/v2/api/v2/")) {
            throw structureChanged("list pagination URL is outside the source API")
        }
        return url.toString()
    }

    private fun numericId(urlOrId: String): String {
        val candidate = urlOrId.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (candidate.isBlank() || !candidate.all(Char::isDigit)) {
            throw SourceException(SourceErrorKind.NOT_FOUND, id, "not a numeric id: $urlOrId")
        }
        return candidate
    }

    companion object {
        const val ID = "mangaswat"
        private const val API = "https://appswat.com/v2/api/v2"
        private const val CONTENT_LANGUAGE = "ar"
        private const val PAGE_SIZE = 24
        private const val CHAPTER_PAGE_SIZE = 200
        private const val MAX_CHAPTER_REQUESTS = 25
    }
}

