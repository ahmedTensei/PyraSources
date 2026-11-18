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
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Team X (olympustaff.com) — classic server-rendered HTML.
 *
 * Some chapters are sold by the site (lock badge + purchase dialog). They are listed as locked
 * and never opened by the app.
 */
@Singleton
class TeamXSource @Inject constructor(
    httpClient: OkHttpClient
) : HttpMangaSource(httpClient) {

    override val id: String = ID
    override val name: String = "Team X"
    override val baseUrl: String = "https://olympustaff.com"
    override val type: SourceType = SourceType.HTML
    override val lang: List<String> = listOf(CONTENT_LANGUAGE)

    override val capabilities = SourceCapabilities(
        // LATEST = the home page feed; DEFAULT = the /series catalogue in the site's own order.
        sortOrders = listOf(MangaSortOrder.LATEST, MangaSortOrder.DEFAULT),
        supportsSearch = true,
        supportsGenreFilter = true,
        genreFilterOrders = listOf(MangaSortOrder.DEFAULT),
        pageAccess = PageAccess.FULL,
        hasLockedChapters = true
    )

    @Volatile
    private var cachedGenres: List<SourceGenre>? = null

    override suspend fun browseManga(page: Int, sortOrder: MangaSortOrder, genreId: String?): Paginated<Manga> {
        val genre = genreId?.takeIf { it.isNotBlank() }
        if (sortOrder == MangaSortOrder.LATEST && genre == null) {
            return parseLatest(fetchDocument("/?page=$page"), page)
        }
        val genreParam = genre?.let { "&genre=${encodeQueryParam(it)}" }.orEmpty()
        val document = fetchDocument("/series?page=$page$genreParam")
        if (cachedGenres == null) parseGenres(document).takeIf { it.isNotEmpty() }?.let { cachedGenres = it }
        return parseCatalogue(document, page)
    }

    /** The site's search is a single, unpaginated suggestion list. */
    override suspend fun searchManga(query: String, page: Int): Paginated<Manga> {
        if (page > 1) return Paginated(emptyList(), page, hasNextPage = false)
        val fragment = fetchText(
            "/ajax/search?keyword=${encodeQueryParam(query)}",
            mapOf("X-Requested-With" to "XMLHttpRequest")
        )
        return parseSearchFragment(fragment)
    }

    override suspend fun getGenres(): List<SourceGenre> {
        cachedGenres?.let { return it }
        return parseGenres(fetchDocument("/series")).also {
            if (it.isEmpty()) throw structureChanged("genre select missing")
            cachedGenres = it
        }
    }

    override suspend fun getMangaDetails(mangaUrlOrId: String): Manga {
        val slug = slugOf(mangaUrlOrId)
        return parseMangaDetails(fetchDocumentReusingRecent("/series/$slug"), slug)
    }

    override suspend fun getChapterList(mangaUrlOrId: String): List<Chapter> {
        val slug = slugOf(mangaUrlOrId)
        val chapters = mutableListOf<Chapter>()
        var document = fetchDocumentReusingRecent("/series/$slug")
        var page = 1
        while (true) {
            chapters += parseChapters(document, slug)
            if (!hasNextPage(document)) break
            if (page >= MAX_CHAPTER_PAGES) throw structureChanged("Chapter list exceeds $MAX_CHAPTER_PAGES pages; refusing to return a truncated list")
            page++
            document = fetchDocument("/series/$slug?page=$page")
        }
        return chapters.distinctBy { it.id }.inSourceOrder()
    }

    override suspend fun getChapterContent(chapterUrlOrId: String): ChapterContent {
        val url = resolveUrl(if (chapterUrlOrId.startsWith("http")) chapterUrlOrId else "/series/$chapterUrlOrId")
        return parseChapterContent(fetchDocument(url), url)
    }

    // ---- parsing (internal so it can be tested against saved real markup) ----

    fun parseCatalogue(document: Document, page: Int): Paginated<Manga> {
        val container = document.selectFirst("div.listupd") ?: throw structureChanged("catalogue: listupd container missing")
        val cards = container.select("div.bsx")
        val items = cards.map { card ->
            val link = card.selectFirst("a[href*=/series/]") ?: throw structureChanged("catalogue: card without work link")
            val slug = slugOf(link.attr("href"))
            val title = link.attr("title").trim().ifBlank { link.selectFirst(".tt")?.text()?.trim().orEmpty() }
            if (slug.isBlank() || title.isBlank()) throw structureChanged("catalogue: card without slug/title")
            Manga(
                id = slug,
                sourceId = id,
                title = title,
                coverUrl = link.selectFirst("img")?.absUrl("src").orEmpty(),
                url = "$baseUrl/series/$slug",
                status = statusOf(link.selectFirst("span.status")?.text().orEmpty()),
                type = link.selectFirst("span.type")?.text()?.trim().orEmpty(),
                contentLanguage = CONTENT_LANGUAGE
            )
        }.distinctBy { it.id }
        if (items.isEmpty() && (container.children().isNotEmpty() || container.text().isNotBlank())) {
            throw structureChanged("catalogue: unrecognized content inside listupd")
        }
        return Paginated(items, page, hasNextPage = items.isNotEmpty() && hasNextPage(document))
    }

    fun parseLatest(document: Document, page: Int): Paginated<Manga> {
        val items = document.select("div.uta").map { box ->
            val link = box.selectFirst("div.imgu a[href*=/series/]") ?: box.selectFirst("a[href*=/series/]")
                ?: throw structureChanged("home: update box without a work link")
            val slug = slugOf(link.attr("href"))
            val image = box.selectFirst("div.imgu img")
            val title = box.selectFirst("div.info h3")?.text()?.trim().orEmpty()
                .ifBlank { image?.attr("alt")?.trim().orEmpty() }
            if (slug.isBlank() || title.isBlank()) throw structureChanged("home: update box without slug/title")
            Manga(
                id = slug,
                sourceId = id,
                title = title,
                coverUrl = image?.absUrl("src").orEmpty(),
                url = "$baseUrl/series/$slug",
                contentLanguage = CONTENT_LANGUAGE
            )
        }.distinctBy { it.id }
        if (items.isEmpty()) throw structureChanged("home: no latest-update boxes")
        return Paginated(items, page, hasNextPage = items.isNotEmpty() && hasNextPage(document))
    }

    fun parseSearchFragment(fragment: String): Paginated<Manga> {
        val document = Jsoup.parseBodyFragment(fragment, baseUrl)
        val items = document.select("a[href*=/series/]").map { link ->
            val slug = slugOf(link.attr("href"))
            val image = link.selectFirst("img")
            val title = link.selectFirst("h4")?.text()?.trim().orEmpty().ifBlank { image?.attr("alt")?.trim().orEmpty() }
            if (slug.isBlank() || title.isBlank()) throw structureChanged("search: result without slug/title")
            Manga(
                id = slug,
                sourceId = id,
                title = title,
                coverUrl = image?.absUrl("src").orEmpty(),
                url = "$baseUrl/series/$slug",
                type = link.selectFirst("p span")?.text()?.trim().orEmpty(),
                contentLanguage = CONTENT_LANGUAGE
            )
        }.distinctBy { it.id }
        if (items.isEmpty() && document.selectFirst(".p-6.text-center > p:matchesOwn(^لا توجد نتائج$)") == null) {
            throw structureChanged("search: neither results nor the verified no-results message")
        }
        return Paginated(items, currentPage = 1, hasNextPage = false)
    }

    fun parseGenres(document: Document): List<SourceGenre> =
        document.select("select[name=genre] option").mapNotNull { option ->
            val value = option.attr("value").trim()
            val name = option.text().trim()
            if (value.isBlank() || name.isBlank()) null else SourceGenre(id = value, name = name)
        }

    fun parseMangaDetails(document: Document, slug: String): Manga {
        val title = document.selectFirst(".author-info-title h1, .author-info-title h6")?.text()?.trim().orEmpty()
            .ifBlank { document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty() }
        if (title.isBlank()) throw structureChanged("details: title missing")

        val info = document.select(".full-list-info").mapNotNull { row ->
            val cells = row.select("small")
            if (cells.size < 2) null else cells[0].text().trim().trimEnd(':').trim() to cells[1].text().trim()
        }
        fun infoValue(vararg labels: String): String =
            info.firstOrNull { (label, _) -> labels.any { label.contains(it) } }?.second.orEmpty()

        val ratingCount = infoValue("التقييمات").toIntOrNull() ?: 0
        // Shown by the site as "<value>/5"; normalised to the app's 0..10 scale.
        val rating = RATING_REGEX.find(document.selectFirst("#average_rating")?.text().orEmpty())?.let { match ->
            val value = match.groupValues[1].toFloatOrNull()
            val best = match.groupValues[2].toFloatOrNull()
            if (value == null || best == null || best <= 0f || value <= 0f) null else value / best * 10f
        }?.takeIf { ratingCount > 0 }

        return Manga(
            id = slug,
            sourceId = id,
            title = title,
            coverUrl = document.selectFirst(".whitebox img.shadow-sm, img.shadow-sm")?.absUrl("src").orEmpty(),
            url = "$baseUrl/series/$slug",
            author = infoValue("المؤلف", "الكاتب").unlessUnknown(),
            artist = infoValue("الرسام").unlessUnknown(),
            description = document.select(".review-content p").joinToString("\n") { it.text().trim() }.trim(),
            genres = document.select(".review-author-info a.subtitle").map { it.text().trim() }.filter { it.isNotBlank() }.distinct(),
            status = statusOf(infoValue("الحالة")),
            rating = rating,
            ratingCount = ratingCount.takeIf { rating != null },
            type = infoValue("النوع"),
            contentLanguage = CONTENT_LANGUAGE
        )
    }

    fun parseChapters(document: Document, slug: String): List<Chapter> {
        val cards = document.select("div.chapter-card")
        // No reliable empty-chapter contract has been observed for this provider yet.
        if (cards.isEmpty()) throw structureChanged("chapter list: no chapter cards")
        return cards.map { card ->
            val link = card.selectFirst("a.chapter-link") ?: throw structureChanged("chapter card without link")
            val numberLabel = card.selectFirst(".chapter-number")?.text()?.trim().orEmpty()
            val numberText = card.attr("data-number").trim()
            val number = numberText.toFloatOrNull() ?: parseChapterNumber(numberLabel)
            val href = link.attr("href").trim()
            val isLocked = href == "#" ||
                link.attr("data-bs-target") == "#buyModel" ||
                card.selectFirst(".status-badge.locked") != null
            if (href.isBlank() && !isLocked) throw structureChanged("chapter card without URL or an explicit lock")
            val key = numberText.ifBlank { href.trimEnd('/').substringAfterLast('/') }
            if (key.isBlank() || key == "#") throw structureChanged("chapter card without stable id")
            if (!isLocked && link.absUrl("href").toHttpUrlOrNull() == null) throw structureChanged("chapter card without valid URL")
            Chapter(
                id = "$slug/$key",
                mangaId = slug,
                sourceId = id,
                title = card.selectFirst(".chapter-title")?.text()?.trim().orEmpty().ifBlank { numberLabel },
                number = number,
                // A locked chapter has no page of its own; point at the work instead.
                url = if (isLocked) "$baseUrl/series/$slug" else link.absUrl("href"),
                dateUpload = (card.attr("data-date").toLongOrNull() ?: 0L) * 1000L,
                isLocked = isLocked,
                language = CONTENT_LANGUAGE
            )
        }
    }

    fun parseChapterContent(document: Document, chapterUrl: String): ChapterContent {
        val urls = document.select(".image_list img.manga-chapter-img, .image_list .page-break img")
            .map { image ->
                listOf(image.absUrl("src"), image.absUrl("data-src")).firstOrNull { it.toHttpUrlOrNull() != null }
                    ?: throw structureChanged("chapter: invalid page image URL")
            }
            .distinct()
        if (urls.isEmpty()) {
            throw if (document.selectFirst("#buyModel, [data-bs-target=#buyModel]") != null && document.selectFirst(".image_list") == null) {
                SourceException(SourceErrorKind.LOCKED, id, chapterUrl)
            } else {
                structureChanged("chapter: no page images")
            }
        }
        val headers = imageRequestHeaders(chapterUrl)
        return ChapterContent(
            pages = urls.mapIndexed { index, url -> Page(index = index, pageUrl = chapterUrl, imageUrl = url, headers = headers) },
            webUrl = chapterUrl
        )
    }

    private fun hasNextPage(document: Document): Boolean =
        document.selectFirst("ul.pagination a[rel=next]") != null

    private fun statusOf(text: String): MangaStatus = when {
        text.contains("مستمر") -> MangaStatus.ONGOING
        text.contains("مكتمل") -> MangaStatus.COMPLETED
        text.contains("متوقف") -> MangaStatus.HIATUS
        text.contains("متروك") || text.contains("ملغ") -> MangaStatus.CANCELLED
        else -> MangaStatus.UNKNOWN
    }

    private fun slugOf(urlOrSlug: String): String {
        val clean = urlOrSlug.substringBefore('?').substringBefore('#').trim()
        return if (clean.contains("/series/")) {
            clean.substringAfter("/series/").substringBefore('/')
        } else {
            clean.trim('/').substringBefore('/')
        }
    }

    private fun String.unlessUnknown(): String = trim().takeUnless { it == "غير معروف" || it == "-" }.orEmpty()

    companion object {
        const val ID = "teamx"
        private const val CONTENT_LANGUAGE = "ar"
        private const val MAX_CHAPTER_PAGES = 60
        private val RATING_REGEX = Regex("""(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)""")
    }
}

