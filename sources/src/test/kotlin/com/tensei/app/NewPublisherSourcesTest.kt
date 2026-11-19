package com.tensei.app

import com.tensei.app.data.remote.sources.AsuraScansSource
import com.tensei.app.data.remote.sources.RawkumaSource
import com.tensei.app.domain.source.SourceErrorKind
import com.tensei.app.domain.source.SourceException
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Trimmed public responses fetched on 2026-11-19, never shipped as application data. */
class NewPublisherSourcesTest {
    private val client = OkHttpClient()
    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream("sources/$name"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun asuraNativeCatalogueKeepsSourceIdentityOrderAndPagination() {
        val source = AsuraScansSource(client)
        val page = source.parseMangaList(fixture("asura-catalog.json"), 1)
        assertEquals("immortals-way-of-life", page.items.first().id)
        assertEquals("Immortal’s Way of Life", page.items.first().title)
        assertEquals("asurascans", page.items.first().sourceId)
        assertTrue(page.items.first().url.endsWith("/comics/immortals-way-of-life-7ba78ec7"))
        assertEquals("en", page.items.first().contentLanguage)
        assertNull(page.items.first().ratingCount)
        assertTrue(page.hasNextPage)
    }

    @Test fun asuraDetailsAndChaptersPreserveNativeMetadata() {
        val source = AsuraScansSource(client)
        val manga = source.parseMangaDetails(fixture("asura-details.json"))
        val chapters = source.parseChapters(fixture("asura-chapters.json"), manga)
        assertEquals("Winter Aircon", manga.author)
        assertEquals("Roby", manga.artist)
        assertTrue(manga.description.contains("Roberta"))
        assertTrue(manga.alternativeTitles.isNotEmpty())
        assertEquals("262854", chapters.first().id)
        assertEquals(37f, chapters.first().number, 0f)
        assertTrue(chapters.first().url.endsWith("/chapter/37"))
        assertEquals("en", chapters.first().language)
        assertTrue(chapters.first().dateUpload > 0)
    }

    @Test fun asuraReaderUsesActualPagesAndSourceImageHeaders() {
        val source = AsuraScansSource(client)
        val url = "${source.baseUrl}/comics/immortals-way-of-life-7ba78ec7/chapter/37"
        val content = source.parseChapterContent(fixture("asura-reader.json"), url)
        assertEquals(20, content.pages.size)
        assertTrue(content.isComplete)
        assertTrue(content.pages.first().imageUrl.startsWith("https://cdn.asurascans.com/asura-images/chapters/"))
        assertEquals("https://asurascans.com/", content.pages.first().headers["Referer"])
        assertEquals(0, content.pages.first().index)
    }

    @Test fun asuraPaidChapterIsReportedLockedWithoutInventingPages() {
        val source = AsuraScansSource(client)
        val failure = assertThrows(SourceException::class.java) {
            source.parseChapterContent(fixture("asura-locked.json"), "${source.baseUrl}/comics/immortals-way-of-life-7ba78ec7/chapter/38")
        }
        assertEquals(SourceErrorKind.LOCKED, failure.kind)
    }

    @Test fun rawkumaLatestAndAdvancedSearchPreserveActualResultsAndPagination() {
        val source = RawkumaSource(client)
        val latest = source.parseMangaList(Jsoup.parse(fixture("raw-latest.html"), "${source.baseUrl}/latest-update/"), 1)
        assertEquals("ninja-no-kishi", latest.items.first().id)
        assertEquals("ja", latest.items.first().contentLanguage)
        assertTrue(latest.hasNextPage)
        val search = source.parseMangaList(Jsoup.parse(fixture("raw-search.html"), "${source.baseUrl}/library/"), 1)
        assertEquals(1, search.items.size)
        assertEquals("one-piece", search.items.single().id)
        assertFalse(search.hasNextPage)
        val popular = source.parseMangaList(Jsoup.parse(fixture("raw-popular.html"), "${source.baseUrl}/library/"), 1)
        assertTrue(popular.hasNextPage)
    }

    @Test fun rawkumaSchemaDetailsAndChapterRowsRetainNativeFields() {
        val source = RawkumaSource(client)
        val url = "${source.baseUrl}/manga/one-piece/"
        val document = Jsoup.parse(fixture("raw-details.html"), url)
        val manga = source.parseMangaDetails(document)
        val chapters = source.parseChapters(document, url)
        assertEquals("One Piece", manga.title)
        assertEquals("ODA Eiichiro", manga.author)
        assertEquals(8.89f, manga.rating ?: 0f, 0.01f)
        assertEquals(893, manga.ratingCount)
        assertTrue(manga.alternativeTitles.contains("ワンピース"))
        assertEquals("rawkuma", manga.sourceId)
        assertEquals("ja", manga.contentLanguage)
        assertEquals(1197f, chapters.first().number, 0f)
        assertEquals("chapter-1197.430413", chapters.first().id)
        assertEquals("Chapter 1197", chapters.first().title)
        assertTrue(chapters.first().dateUpload > 0)
    }

    @Test fun rawkumaReaderExcludesSiteLogosAndUsesEveryActualPage() {
        val source = RawkumaSource(client)
        val url = "${source.baseUrl}/manga/one-piece/chapter-1197.430413/"
        val content = source.parseChapterContent(Jsoup.parse(fixture("raw-reader.html"), url), url)
        assertEquals(15, content.pages.size)
        assertTrue(content.isComplete)
        assertEquals("https://cdn.rawkuma.com/o/one-piece-raw/1197/1.jpg", content.pages.first().imageUrl)
        assertEquals("https://rawkuma.net/", content.pages.first().headers["Referer"])
    }

    @Test fun changedResponsesFailInsteadOfSilentlyReturningEmptyContent() {
        val asura = AsuraScansSource(client)
        assertEquals(SourceErrorKind.STRUCTURE_CHANGED, assertThrows(SourceException::class.java) {
            asura.parseMangaList("<html>Maintenance</html>", 1)
        }.kind)
        val raw = RawkumaSource(client)
        assertEquals(SourceErrorKind.STRUCTURE_CHANGED, assertThrows(SourceException::class.java) {
            raw.parseChapterContent(Jsoup.parse("<html><body>Maintenance</body></html>", raw.baseUrl), raw.baseUrl)
        }.kind)
    }
}
