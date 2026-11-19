package com.tensei.app.data.remote.sources

import com.tensei.app.domain.model.*
import com.tensei.app.domain.source.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Native API observed on the current Asura Astro website, November 2026. */
class AsuraScansSource(client: OkHttpClient) : HttpMangaSource(client) {
    override val id = "asurascans"
    override val name = "Asura Scans"
    override val baseUrl = "https://asurascans.com"
    override val type = SourceType.API
    override val lang = listOf("en")
    override val capabilities = SourceCapabilities(
        sortOrders = listOf(MangaSortOrder.LATEST, MangaSortOrder.POPULAR, MangaSortOrder.NEWEST, MangaSortOrder.ALPHABETICAL),
        supportsSearch = true, supportsGenreFilter = true, pageAccess = PageAccess.FULL, hasLockedChapters = true
    )
    private val json = Json { ignoreUnknownKeys = true }

    override fun defaultHeaders() = super.defaultHeaders() + mapOf("Accept" to "application/json", "Origin" to baseUrl)
    override fun imageRequestHeaders(pageUrl: String) = mapOf("Referer" to "$baseUrl/", "Origin" to baseUrl)

    override suspend fun browseManga(page: Int, sortOrder: MangaSortOrder, genreId: String?): Paginated<Manga> {
        require(page > 0 && sortOrder in capabilities.sortOrders)
        val order = when (sortOrder) {
            MangaSortOrder.POPULAR -> "popular"
            MangaSortOrder.NEWEST -> "newest"
            MangaSortOrder.ALPHABETICAL -> "title"
            else -> "latest"
        }
        val direction = if (sortOrder == MangaSortOrder.ALPHABETICAL) "asc" else "desc"
        val genre = genreId?.let { "&genres=${encodeQueryParam(it)}" }.orEmpty()
        return parseMangaList(fetchText("$API/series?sort=$order&order=$direction&limit=$PAGE_SIZE&offset=${(page - 1).toLong() * PAGE_SIZE}$genre"), page)
    }

    override suspend fun searchManga(query: String, page: Int): Paginated<Manga> {
        require(page > 0)
        return parseMangaList(fetchText("$API/series?search=${encodeQueryParam(query)}&sort=latest&order=desc&limit=$PAGE_SIZE&offset=${(page - 1).toLong() * PAGE_SIZE}"), page)
    }

    override suspend fun getGenres(): List<SourceGenre> {
        val props = AstroProps.find(fetchDocument("/browse/comics"), "BrowseFilters")
            ?: throw structureChanged("Asura browse filters are missing")
        return props.arr("availableGenres").map { element ->
            val genre = element as? JsonObject ?: throw structureChanged("Invalid Asura genre")
            val key = genre.str("slug").ifBlank { genre.str("id") }
            val title = genre.str("name")
            if (key.isBlank() || title.isBlank()) throw structureChanged("Incomplete Asura genre")
            SourceGenre(key, title)
        }
    }

    override suspend fun getMangaDetails(mangaUrlOrId: String): Manga =
        parseMangaDetails(fetchText("$API/series/${encodeQueryParam(workSlug(mangaUrlOrId))}"))

    override suspend fun getChapterList(mangaUrlOrId: String): List<Chapter> {
        val slug = workSlug(mangaUrlOrId)
        val manga = getMangaDetails(slug)
        return parseChapters(fetchText("$API/series/${encodeQueryParam(slug)}/chapters"), manga)
    }

    override suspend fun getChapterContent(chapterUrlOrId: String): ChapterContent {
        val segments = chapterUrlOrId.toHttpUrlOrNull()?.pathSegments
            ?: throw structureChanged("Asura chapter requires its public URL")
        val chapterIndex = segments.indexOf("chapter")
        if (chapterIndex < 1 || chapterIndex + 1 >= segments.size) throw structureChanged("Invalid Asura chapter URL")
        val slug = undecoratedSlug(segments[chapterIndex - 1])
        val number = segments[chapterIndex + 1]
        return parseChapterContent(fetchText("$API/series/${encodeQueryParam(slug)}/chapters/${encodeQueryParam(number)}"), chapterUrlOrId)
    }

    fun parseMangaList(text: String, page: Int): Paginated<Manga> {
        val root = root(text)
        val elements = root["data"] as? JsonArray ?: throw structureChanged("Asura catalogue data is missing")
        val works = elements.map { work(it as? JsonObject ?: throw structureChanged("Invalid Asura catalogue entry")) }
        val meta = root.obj("meta") ?: throw structureChanged("Asura catalogue pagination is missing")
        val hasMore = (meta["has_more"] as? JsonPrimitive)?.booleanOrNull
            ?: meta.int("total")?.let { page.toLong() * (meta.int("per_page") ?: PAGE_SIZE) < it }
            ?: throw structureChanged("Asura catalogue pagination is incomplete")
        return Paginated(works, page, hasMore)
    }

    fun parseMangaDetails(text: String): Manga = work(root(text).obj("series") ?: throw structureChanged("Asura series data is missing"))

    fun parseChapters(text: String, manga: Manga): List<Chapter> {
        val elements = root(text)["data"] as? JsonArray ?: throw structureChanged("Asura chapter data is missing")
        return elements.mapIndexed { index, element ->
            val item = element as? JsonObject ?: throw structureChanged("Invalid Asura chapter")
            val key = item.str("id")
            val number = item.double("number")?.toFloat()?.takeIf { it.isFinite() } ?: -1f
            val numberLabel = item.str("number")
            if (key.isBlank() || numberLabel.isBlank()) throw structureChanged("Incomplete Asura chapter identity")
            Chapter(id = key, mangaId = manga.id, sourceId = id,
                title = item.str("title").ifBlank { item.str("name").ifBlank { "Chapter $numberLabel" } },
                number = number, url = "${manga.url.trimEnd('/')}/chapter/$numberLabel",
                dateUpload = parseIsoInstant(item.str("published_at")), totalPages = item.int("page_count") ?: 0,
                isLocked = item.flag("is_premium") || item.flag("is_locked"), language = "en", sourceOrder = index)
        }
    }

    fun parseChapterContent(text: String, chapterUrl: String): ChapterContent {
        val data = root(text).obj("data") ?: throw structureChanged("Asura reader data is missing")
        val chapter = data.obj("chapter") ?: throw structureChanged("Asura reader chapter is missing")
        if (data.flag("is_locked") || chapter.flag("is_premium") || data.str("access_gate").isNotBlank())
            throw SourceException(SourceErrorKind.LOCKED, id, "This chapter is locked by Asura")
        val images = chapter["pages"] as? JsonArray ?: throw structureChanged("Asura page data is missing")
        if (images.isEmpty()) throw structureChanged("Asura returned no readable page images")
        val pages = images.mapIndexed { index, element ->
            val url = (element as? JsonObject)?.str("url").orEmpty()
            if (url.toHttpUrlOrNull()?.isHttps != true) throw structureChanged("Asura returned an invalid page image")
            Page(index, chapterUrl, url, imageRequestHeaders(chapterUrl))
        }
        val total = chapter.int("page_count") ?: pages.size
        return ChapterContent(pages, (total - pages.size).coerceAtLeast(0), chapterUrl)
    }

    private fun work(item: JsonObject): Manga {
        val key = item.str("slug")
        val title = item.str("title")
        val publicUrl = item.str("public_url").ifBlank { item.str("source_url") }
        val cover = item.str("cover").ifBlank { item.str("cover_url") }
        if (key.isBlank() || title.isBlank() || publicUrl.isBlank() || cover.isBlank()) throw structureChanged("Incomplete Asura work")
        val genres = item.arr("genres").mapNotNull { (it as? JsonObject)?.str("name")?.takeIf(String::isNotBlank) }
        val state = when (item.str("status").lowercase()) {
            "ongoing" -> MangaStatus.ONGOING
            "completed" -> MangaStatus.COMPLETED
            "hiatus" -> MangaStatus.HIATUS
            "dropped", "axed" -> MangaStatus.CANCELLED
            else -> MangaStatus.UNKNOWN
        }
        return Manga(key, id, title, resolveUrl(cover), resolveUrl(publicUrl), author = item.str("author"), artist = item.str("artist"),
            description = Jsoup.parse(item.str("description")).text(), genres = genres, status = state,
            rating = item.double("rating")?.toFloat()?.takeIf { it.isFinite() && it in 0f..10f }, ratingCount = item.int("rating_count"),
            isAdult = genres.any { it.equals("adult", true) || it.equals("hentai", true) }, lastUpdated = parseIsoInstant(item.str("last_chapter_at")),
            alternativeTitles = item.arr("alt_titles").mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) },
            type = item.str("type"), contentLanguage = "en")
    }

    private fun root(text: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject ?: throw structureChanged("Asura response is not an object")
    } catch (failure: SourceException) { throw failure }
    catch (failure: Exception) { throw structureChanged("Asura response is not valid JSON") }

    private fun workSlug(value: String): String {
        val url = value.toHttpUrlOrNull()
        val raw = if (url != null) url.pathSegments.dropWhile { it != "comics" }.drop(1).firstOrNull()
            ?: throw structureChanged("Asura work requires its comic URL") else value.trim().trim('/')
        if (raw.isBlank()) throw structureChanged("Asura work slug is missing")
        return undecoratedSlug(raw)
    }
    private fun undecoratedSlug(value: String) = value.replace(Regex("-[0-9a-fA-F]{8}$"), "")

    companion object {
        private const val API = "https://api.asurascans.com/api"
        private const val PAGE_SIZE = 20
    }
}
