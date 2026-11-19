package com.pyramanga.app.domain.source

import com.pyramanga.app.domain.model.Chapter
import com.pyramanga.app.domain.model.ChapterContent
import com.pyramanga.app.domain.model.Manga
import com.pyramanga.app.domain.model.MangaSortOrder
import com.pyramanga.app.domain.model.Paginated
import com.pyramanga.app.domain.model.SourceDescriptor
import com.pyramanga.app.domain.model.SourceType

/**
 * One independent content source. Every source owns its own parsing and declares, through
 * [capabilities], what it really supports; nothing is assumed to be common between sources.
 *
 * All network failures surface as [SourceException] so the UI can tell the user what
 * actually went wrong instead of showing an empty or fake result.
 *
 * Publisher extensions implement this interface through the host's source factory contract.
 */
interface MangaSource {
    val id: String

    /** The source's own brand name (a proper noun, not translated). */
    val name: String
    val baseUrl: String
    val type: SourceType

    /** Languages of the content this source serves (BCP-47). */
    val lang: List<String>
    val capabilities: SourceCapabilities

    fun toDescriptor(): SourceDescriptor = SourceDescriptor(
        id = id,
        name = name,
        baseUrl = baseUrl,
        type = type,
        lang = lang,
        capabilities = capabilities
    )

    /**
     * @param sortOrder must be one of [SourceCapabilities.sortOrders].
     * @param genreId a [SourceGenre.id] from [getGenres]; only if [SourceCapabilities.supportsGenreFilter].
     */
    suspend fun browseManga(page: Int, sortOrder: MangaSortOrder, genreId: String? = null): Paginated<Manga>

    suspend fun searchManga(query: String, page: Int): Paginated<Manga>

    /** Genres exactly as the source defines them; empty when it has no genre filter. */
    suspend fun getGenres(): List<SourceGenre> = emptyList()

    suspend fun getMangaDetails(mangaUrlOrId: String): Manga

    suspend fun getChapterList(mangaUrlOrId: String): List<Chapter>

    suspend fun getChapterContent(chapterUrlOrId: String): ChapterContent

    /** Clears disposable parsed responses, preserving authentication and all user records. */
    suspend fun clearTransientCache() = Unit

    fun imageRequestHeaders(pageUrl: String): Map<String, String> = mapOf("Referer" to "$baseUrl/")
}
