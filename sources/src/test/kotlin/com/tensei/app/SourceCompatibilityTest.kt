package com.tensei.app

import com.tensei.app.data.remote.sources.MangaSwatSource
import com.tensei.app.data.remote.sources.MangaTekSource
import com.tensei.app.data.remote.sources.TeamXSource
import com.tensei.app.domain.model.Page
import com.tensei.app.domain.source.PageAccess
import com.tensei.app.domain.source.SourceErrorKind
import com.tensei.app.domain.source.SourceException

import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Deterministic parsing of trimmed public responses captured on 2026-11-14. No live network. */
class SourceCompatibilityTest {
    private val client = OkHttpClient()
    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream("sources/$name"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun `MangaTek parses its actual Astro catalogue and pagination`() {
        val source = MangaTekSource(client)
        val page = source.parseMangaList(Jsoup.parse(fixture("mangatek-catalog.html"), source.baseUrl), 1)
        assertEquals(1, page.items.size)
        assertEquals("his-majesty-is-my-ideal-boss", page.items.single().id)
        assertEquals("mangatek", page.items.single().sourceId)
        assertTrue(page.items.single().coverUrl.startsWith("https://"))
        assertTrue(page.hasNextPage)
    }

    @Test fun `MangaTek preserves real details chapter language and AI flags`() {
        val source = MangaTekSource(client)
        val document = Jsoup.parse(fixture("mangatek-details.html"), source.baseUrl)
        val manga = source.parseMangaDetails(document)
        val chapters = source.parseChapters(document)
        assertEquals("His Majesty Is My Ideal Boss!", manga.title)
        assertEquals(9.7f, manga.rating ?: 0f, 0.01f)
        assertEquals(3, manga.ratingCount)
        assertTrue(chapters.isNotEmpty())
        assertEquals(12f, chapters.first().number, 0f)
        assertEquals("ar", chapters.first().language)
        assertTrue(chapters.first().isMachineTranslated)
    }

    @Test fun `MangaTek reports withheld pages instead of inventing a complete chapter`() {
        val source = MangaTekSource(client)
        val url = "${source.baseUrl}/reader/his-majesty-is-my-ideal-boss/12"
        val content = source.parseChapterContent(Jsoup.parse(fixture("mangatek-reader.html"), url), url)
        assertEquals(2, content.pages.size)
        assertEquals(21, content.withheldPageCount)
        assertFalse(content.isComplete)
        assertEquals(PageAccess.PREVIEW_ONLY, source.capabilities.pageAccess)
        assertEquals("https://mangatek.com/", content.pages.first().headers["Referer"])
    }

    @Test fun `MangaTek fails clearly when its site structure changed`() {
        val source = MangaTekSource(client)
        val exception = assertThrows(SourceException::class.java) {
            source.parseMangaList(Jsoup.parse("<html><body>Maintenance</body></html>"), 1)
        }
        assertEquals(SourceErrorKind.STRUCTURE_CHANGED, exception.kind)
    }

    @Test fun `Swat parses its actual JSON API without fabricated ratings`() {
        val source = MangaSwatSource(client)
        val page = source.parseSeriesPage(fixture("swat-catalog.json"), 1)
        assertEquals("1702854", page.items.single().id)
        assertEquals("Logging Out Is Too Hard", page.items.single().title)
        assertNull(page.items.single().rating)
        assertTrue(page.hasNextPage)
        val content = source.parseChapterContent(fixture("swat-reader.json"), "https://meshmanga.com/chapter/1767847")
        assertEquals(15, content.pages.size)
        assertTrue(content.isComplete)
        assertEquals(0, content.pages.first().index)
    }

    @Test fun `Team X parses actual latest releases chapters and image elements`() {
        val source = TeamXSource(client)
        val latest = source.parseLatest(Jsoup.parse(fixture("teamx-latest.html"), source.baseUrl), 1)
        // The captured first latest-update box is The Superhuman Era. Murim Psychopath
        // appears earlier in the site's separate navigation links, outside the uta feed.
        assertEquals("the-superhuman-era", latest.items.single().id)
        assertEquals("The Superhuman Era", latest.items.single().title)
        assertTrue(latest.items.single().coverUrl.endsWith("thumbnail_8a287357795c2f399341d7a39ec8ea13.jpg"))
        val chapters = source.parseChapters(Jsoup.parse(fixture("teamx-chapters.html"), source.baseUrl), "murim-psychopath")
        assertEquals(2, chapters.size)
        assertEquals(50f, chapters.first().number, 0f)
        assertFalse(chapters.first().isLocked)
        val content = source.parseChapterContent(Jsoup.parse(fixture("teamx-reader.html"), source.baseUrl), "${source.baseUrl}/series/murim-psychopath/50")
        assertEquals(2, content.pages.size)
        assertTrue(content.pages.all { it.imageUrl.startsWith("https://cdn.olympustaff.com/") })
    }

    @Test fun `Source capabilities match actual independent endpoints`() {
        val sources = listOf(MangaTekSource(client), MangaSwatSource(client), TeamXSource(client))
        assertEquals(3, sources.map { it.id }.distinct().size)
        assertTrue(sources.all { "ar" in it.lang })
        assertTrue(TeamXSource(client).capabilities.hasLockedChapters)
        assertEquals(PageAccess.FULL, MangaSwatSource(client).capabilities.pageAccess)
    }


}
