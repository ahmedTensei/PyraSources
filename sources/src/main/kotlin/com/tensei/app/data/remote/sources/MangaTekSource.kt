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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mangatek.com — an Astro site. Lists are server-rendered cards; details, chapters and reader
 * data are JSON embedded in `<astro-island props="...">` (see [AstroProps]).
 *
 * Known restriction (verified 2026-11-13): the reader page only embeds the first pages of a
 * chapter; the remaining entries are `{"pending": true}` and are unlocked by the site itself.
 * This source does not work around that gate: it returns the real pages it was given and
 * reports the rest as withheld.
 */
@Singleton
class MangaTekSource @Inject constructor(
    httpClient: OkHttpClient
) : HttpMangaSource(httpClient) {

    override val id: String = ID
    override val name: String = "MangaTek"
    override val baseUrl: String = "https://mangatek.com"
    override val type: SourceType = SourceType.HTML
    override val lang: List<String> = listOf(CONTENT_LANGUAGE)

    override val capabilities = SourceCapabilities(
        sortOrders = listOf(MangaSortOrder.LATEST, MangaSortOrder.POPULAR, MangaSortOrder.ALPHABETICAL),
        supportsSearch = true,
        supportsGenreFilter = true,
        pageAccess = PageAccess.PREVIEW_ONLY,
        hasLockedChapters = true,
        flagsMachineTranslation = true
    )

    @Volatile
    private var cachedGenres: List<SourceGenre>? = null

    override suspend fun browseManga(page: Int, sortOrder: MangaSortOrder, genreId: String?): Paginated<Manga> {
        val sort = when (sortOrder) {
            MangaSortOrder.POPULAR -> "sort=views"
            MangaSortOrder.ALPHABETICAL -> "sort=title&sortOrder=ASC"
            else -> "sort=latest"
        }
        val genre = genreId?.takeIf { it.isNotBlank() }?.let { "&genres=${encodeQueryParam(it)}" }.orEmpty()
        val document = fetchDocument("/manga-list?$sort$genre&page=$page")
        cacheGenresFrom(document)
        return parseMangaList(document, page)
    }

    override suspend fun searchManga(query: String, page: Int): Paginated<Manga> {
        val document = fetchDocument("/manga-list?search=${encodeQueryParam(query)}&page=$page")
        return parseMangaList(document, page)
    }

    override suspend fun getGenres(): List<SourceGenre> {
        cachedGenres?.let { return it }
        val document = fetchDocument("/manga-list")
        return parseGenres(document).also {
            if (it.isEmpty()) throw structureChanged("FilterSidebar genres missing")
            cachedGenres = it
        }
    }

    override suspend fun getMangaDetails(mangaUrlOrId: String): Manga {
        val slug = slugOf(mangaUrlOrId)
        return parseMangaDetails(fetchDocumentReusingRecent("/manga/$slug"))
    }

    override suspend fun getChapterList(mangaUrlOrId: String): List<Chapter> {
        val slug = slugOf(mangaUrlOrId)
        return parseChapters(fetchDocumentReusingRecent("/manga/$slug"))
    }

    override suspend fun getChapterContent(chapterUrlOrId: String): ChapterContent {
        val url = resolveUrl(chapterUrlOrId)
        if (!url.contains("/reader/")) {
            throw SourceException(SourceErrorKind.NOT_FOUND, id, "not a reader address: $chapterUrlOrId")
        }
        return parseChapterContent(fetchDocument(url), url)
    }

    // ---- parsing (internal so it can be tested against saved real markup) ----

    fun parseMangaList(document: Document, page: Int): Paginated<Manga> {
        val items = document.select(".manga-card").map { card ->
            if (!card.attr("href").contains("/manga/")) throw structureChanged("manga-list: card without a work link")
            val slug = slugOf(card.attr("href"))
            val image = card.selectFirst("img")
            val title = image?.attr("alt")?.trim().orEmpty()
                .ifBlank { card.selectFirst("h3")?.text()?.trim().orEmpty() }
            if (slug.isBlank() || title.isBlank()) throw structureChanged("manga-list: card without slug/title")
            Manga(
                id = slug,
                sourceId = id,
                title = title,
                coverUrl = image?.absUrl("src").orEmpty().ifBlank { image?.absUrl("data-src").orEmpty() },
                url = "$baseUrl/manga/$slug",
                contentLanguage = CONTENT_LANGUAGE
            )
        }.distinctBy { it.id }

        val pagination = AstroProps.find(document, "Pagination")
        if (items.isEmpty() && (pagination == null || document.selectFirst("h3:matchesOwn(^لا توجد نتائج$)") == null)) {
            throw structureChanged("manga-list: neither cards nor the verified no-results message")
        }
        val currentPage = pagination?.int("currentPage") ?: page
        val totalPages = pagination?.int("totalPages") ?: currentPage
        return Paginated(items = items, currentPage = page, hasNextPage = items.isNotEmpty() && currentPage < totalPages)
    }

    fun parseGenres(document: Document): List<SourceGenre> {
        val props = AstroProps.find(document, "FilterSidebar") ?: return emptyList()
        val genres = props["genres"] as? JsonArray ?: throw structureChanged("FilterSidebar genres array missing")
        return genres.map { element ->
            val genre = element as? JsonObject ?: throw structureChanged("genre list contains a non-object row")
            val slug = genre.str("slug")
            val name = genre.str("name")
            if (slug.isBlank() || name.isBlank()) throw structureChanged("genre row without slug/name")
            SourceGenre(id = slug, name = name)
        }
    }

    fun parseMangaDetails(document: Document): Manga {
        val manga = mangaObject(document)
        val slug = manga.str("slug")
        if (slug.isBlank()) throw structureChanged("manga.slug missing")

        val title = manga.str("title").trim().ifBlank { manga.str("title_ar").trim() }
        if (title.isBlank()) throw structureChanged("manga.title missing")
        val alternativeTitles = buildList {
            add(manga.str("title_ar"))
            addAll(manga.str("alternative_titles").split('|'))
        }.map { it.trim() }.filter { it.isNotBlank() && it != title }.distinct()

        val ratingCount = manga.int("rating_count") ?: 0
        // "rating_blended" is the value the site publishes as its 0..10 aggregate rating.
        val rating = manga.double("rating_blended")?.toFloat()?.takeIf { ratingCount > 0 && it > 0f }

        return Manga(
            id = slug,
            sourceId = id,
            title = title,
            coverUrl = manga.str("cover_image").let { if (it.isBlank()) "" else resolveUrl(it) },
            url = "$baseUrl/manga/$slug",
            author = manga.str("author").unlessPlaceholder(),
            artist = manga.str("artist").unlessPlaceholder(),
            description = manga.str("description").trim(),
            genres = manga.arr("Tags").mapNotNull { (it as? JsonObject)?.str("name")?.takeIf(String::isNotBlank) },
            status = when (manga.str("status").lowercase()) {
                "ongoing" -> MangaStatus.ONGOING
                "completed", "finished" -> MangaStatus.COMPLETED
                "hiatus", "on_hold", "paused" -> MangaStatus.HIATUS
                "cancelled", "canceled", "dropped" -> MangaStatus.CANCELLED
                else -> MangaStatus.UNKNOWN
            },
            rating = rating,
            ratingCount = ratingCount.takeIf { rating != null },
            isAdult = manga.flag("is_mature"),
            lastUpdated = parseIsoInstant(manga.str("updated_at")),
            alternativeTitles = alternativeTitles,
            type = manga.str("type"),
            contentLanguage = CONTENT_LANGUAGE
        )
    }

    fun parseChapters(document: Document): List<Chapter> {
        val manga = mangaObject(document)
        val slug = manga.str("slug")
        if (slug.isBlank()) throw structureChanged("manga.slug missing")
        val chapters = manga["MangaChapters"] as? JsonArray ?: throw structureChanged("manga.MangaChapters array missing")
        return chapters.mapNotNull { element ->
            val chapter = element as? JsonObject ?: throw structureChanged("chapter list contains a non-object row")
            fun accessFlag(field: String): Boolean? = when (chapter.str(field).trim().lowercase()) {
                "" -> if (chapter.containsKey(field)) throw structureChanged("chapter $field flag is invalid") else null
                "true", "1" -> true
                "false", "0" -> false
                else -> throw structureChanged("chapter $field flag is invalid")
            }
            if (accessFlag("visible") == false) return@mapNotNull null
            val number = chapter.str("chapter_number")
            val chapterId = chapter.str("id")
            val parsedNumber = parseChapterNumber(number)
            if (chapterId.isBlank()) throw structureChanged("chapter row without id")
            val price = chapter.double("price") ?: if (chapter.containsKey("price")) throw structureChanged("chapter price is invalid") else 0.0
            if (!price.isFinite() || price < 0.0) throw structureChanged("chapter price is invalid")
            val accessDenied = accessFlag("access") == false
            Chapter(
                id = chapterId,
                mangaId = slug,
                sourceId = id,
                title = chapter.str("title").trim(),
                number = parsedNumber,
                url = chapter.str("url").ifBlank { if (number.isNotBlank()) "$baseUrl/reader/$slug/$number" else "" },
                dateUpload = parseIsoInstant(chapter.str("created_at")),
                isLocked = price > 0.0 || accessDenied,
                isMachineTranslated = chapter.flag("isAI"),
                language = CONTENT_LANGUAGE
            )
        }.distinctBy { it.id }.inSourceOrder()
    }

    fun parseChapterContent(document: Document, chapterUrl: String): ChapterContent {
        val props = AstroProps.find(document, "ChapterImageViewer")
            ?: throw structureChanged("ChapterImageViewer island missing")
        val entries = props["imageUrls"] as? JsonArray ?: throw structureChanged("ChapterImageViewer imageUrls array missing")
        val exposedUrls = entries.mapNotNull { entry ->
            if (entry is JsonObject && entry.flag("pending")) return@mapNotNull null
            val raw = when (entry) {
                is JsonPrimitive -> entry.content
                is JsonObject -> entry.str("url")
                else -> ""
            }
            raw.takeIf { it.toHttpUrlOrNull() != null }
                ?: throw structureChanged("ChapterImageViewer contains an invalid image row")
        }
        if (entries.isEmpty()) {
            val error = props.str("error")
            throw if (error.isNotBlank()) {
                SourceException(SourceErrorKind.LOCKED, id, error)
            } else {
                structureChanged("ChapterImageViewer has no imageUrls")
            }
        }
        if (exposedUrls.isEmpty()) throw SourceException(SourceErrorKind.LOCKED, id,
            props.str("error").ifBlank { "The source withheld every page of this chapter: $chapterUrl" })
        val urls = exposedUrls.distinct()
        val headers = imageRequestHeaders(chapterUrl)
        return ChapterContent(
            pages = urls.mapIndexed { index, url -> Page(index = index, pageUrl = chapterUrl, imageUrl = url, headers = headers) },
            withheldPageCount = entries.size - exposedUrls.size,
            webUrl = chapterUrl
        )
    }

    private fun mangaObject(document: Document): JsonObject {
        val props = AstroProps.find(document, "MangaChaptersLoader")
            ?: throw structureChanged("MangaChaptersLoader island missing")
        return props.obj("manga") ?: throw structureChanged("MangaChaptersLoader has no manga")
    }

    private fun slugOf(urlOrSlug: String): String =
        urlOrSlug.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast("/manga/").substringAfterLast('/')

    private fun String.unlessPlaceholder(): String =
        trim().takeUnless { it.equals("unknown", ignoreCase = true) || it.equals("null", ignoreCase = true) }.orEmpty()

    private fun cacheGenresFrom(document: Document) {
        if (cachedGenres == null) parseGenres(document).takeIf { it.isNotEmpty() }?.let { cachedGenres = it }
    }

    companion object {
        const val ID = "mangatek"
        private const val CONTENT_LANGUAGE = "ar"
    }
}
