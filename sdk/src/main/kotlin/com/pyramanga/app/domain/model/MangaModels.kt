package com.pyramanga.app.domain.model

import kotlinx.serialization.Serializable

enum class SourceType {
    HTML,
    API,
    BOTH
}

enum class MangaStatus(val arabicLabel: String) {
    ONGOING("مستمرة"),
    COMPLETED("مكتملة"),
    HIATUS("متوقفة مؤقتاً"),
    CANCELLED("ملغاة"),
    UNKNOWN("غير معروف")
}

/** A reader's own organization, independent from the source's publication status. */
@Serializable
enum class ReadingStatus { NONE, READING, PLANNED, COMPLETED, ON_HOLD, DROPPED }

enum class MangaSortOrder {
    /** Works ordered by their most recent chapter release. */
    LATEST,

    /** Works ordered by the source's own view counter. */
    POPULAR,

    /** Works ordered by the date they were added to the source. */
    NEWEST,

    ALPHABETICAL,

    /** The source's own catalogue order, whatever it is (not claimed to be "popular"). */
    DEFAULT
}

data class Paginated<T>(
    val items: List<T>,
    val currentPage: Int,
    val hasNextPage: Boolean
)

@Serializable
data class Manga(
    val id: String,
    val sourceId: String,
    val title: String,
    val coverUrl: String,
    val url: String,
    val author: String = "",
    val artist: String = "",
    val description: String = "",
    val genres: List<String> = emptyList(),
    val status: MangaStatus = MangaStatus.UNKNOWN,
    /** Source rating normalised to a 0..10 scale; null when the source has no real rating. */
    val rating: Float? = null,
    /** Number of votes behind [rating], when the source publishes it. */
    val ratingCount: Int? = null,
    val isAdult: Boolean = false,
    val isFavorite: Boolean = false,
    val lastUpdated: Long = 0L,
    val alternativeTitles: List<String> = emptyList(),
    /** Format as the source names it (manga / manhwa / manhua ...); blank when unknown. */
    val type: String = "",
    /**
     * Language of the content this source serves for the work (BCP-47, e.g. "ar").
     * Independent from the UI language and from the work's original language.
     */
    val contentLanguage: String = "",
    /** Original publication language, when explicitly supplied by the source. */
    val originalLanguage: String = "",
    val readingStatus: ReadingStatus = ReadingStatus.NONE
)

@Serializable
data class Chapter(
    val id: String,
    val mangaId: String,
    val sourceId: String,
    val title: String,
    val number: Float = -1f,
    val url: String,
    val dateUpload: Long = 0L,
    val scanlator: String = "",
    val isRead: Boolean = false,
    val lastPageRead: Int = 0,
    val totalPages: Int = 0,
    val isDownloaded: Boolean = false,
    /** Paid/locked on the source: the app cannot and does not open it. */
    val isLocked: Boolean = false,
    /** The source itself flags this chapter as machine translated. */
    val isMachineTranslated: Boolean = false,
    /** Language of this chapter as served by the source (BCP-47). */
    val language: String = "",
    /** Fraction scrolled through the last source page; survives different display sizes. */
    val lastPageProgress: Float = 0f,
    /** False when a successful catalogue refresh no longer lists this chapter. */
    val isAvailable: Boolean = true,
    /** Position in the source's newest-first catalogue; -1 for older cached snapshots. */
    val sourceOrder: Int = -1,
    /** Actual last entry/reading event, independent from source release time. */
    val lastReadTimestamp: Long = 0L,
    /** Display-only position; source metadata and persisted chapter identity stay unchanged. */
    @kotlinx.serialization.Transient val appOrder: Int = 0
)

/** Keep special/unnumbered chapters in the source sequence without inventing numbers. */
fun List<Chapter>.inSourceOrder(): List<Chapter> = when {
    all { it.appOrder > 0 } -> sortedByDescending { it.appOrder }
    any { it.number < 0f } && all { it.sourceOrder >= 0 } -> sortedBy { it.sourceOrder }
    any { it.number < 0f } -> this
    else -> sortedWith(compareByDescending<Chapter> { it.number }.thenByDescending { it.dateUpload })
}

fun List<Chapter>.inReadingOrder(): List<Chapter> = inSourceOrder().reversed()

/** Capture each edition's full reading sequence before filtering or reversing its display. */
fun List<Chapter>.withAppChapterOrder(): List<Chapter> {
    val positions = groupBy { it.sourceId to it.mangaId }.values.flatMap { chapters ->
        val available = chapters.filter { it.isAvailable }
        if (chapters.all { it.isAvailable } && available.all { it.appOrder > 0 }) available
        else available.inReadingOrder().mapIndexed { index, chapter -> chapter.copy(appOrder = index + 1) }
    }.associateBy { Triple(it.sourceId, it.mangaId, it.id) }
    return map { chapter ->
        chapter.copy(appOrder = positions[Triple(chapter.sourceId, chapter.mangaId, chapter.id)]?.appOrder ?: 0)
    }
}

@Serializable
data class Page(
    val index: Int,
    val pageUrl: String,
    val imageUrl: String = "",
    val headers: Map<String, String> = emptyMap()
)

/**
 * What a source really returned for a chapter. [withheldPageCount] is the number of pages the
 * source keeps behind its own gate; they are never fabricated or silently dropped.
 */
data class ChapterContent(
    val pages: List<Page>,
    val withheldPageCount: Int = 0,
    /** Address of the chapter on the source's own website. */
    val webUrl: String = ""
) {
    val isComplete: Boolean get() = withheldPageCount == 0
}

data class SourceDescriptor(
    val id: String,
    val name: String,
    val baseUrl: String,
    val type: SourceType,
    val lang: List<String>,
    val capabilities: com.pyramanga.app.domain.source.SourceCapabilities
)
