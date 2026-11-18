package com.tensei.app.domain.source

import com.tensei.app.domain.model.MangaSortOrder
import java.io.IOException

/**
 * How much of a chapter's pages a source really exposes to a third-party client.
 */
enum class PageAccess {
    /** Every page of a (non-locked) chapter can be read. */
    FULL,

    /** The source only hands out a few preview pages and gates the rest on its own site. */
    PREVIEW_ONLY
}

/**
 * What a source actually supports. The UI must only offer what is declared here:
 * sources are not interchangeable and none of them is assumed to support everything.
 */
data class SourceCapabilities(
    val sortOrders: List<MangaSortOrder>,
    val supportsSearch: Boolean,
    val supportsGenreFilter: Boolean,
    val pageAccess: PageAccess,
    /** Orders a genre filter can be combined with; null means every entry of [sortOrders]. */
    val genreFilterOrders: List<MangaSortOrder>? = null,
    val hasLockedChapters: Boolean = false,
    val flagsMachineTranslation: Boolean = false
) {
    val defaultSortOrder: MangaSortOrder
        get() = sortOrders.firstOrNull() ?: MangaSortOrder.LATEST
}

enum class SourceDiscoverySection { LATEST, POPULAR, NEWEST, GENRES }

/** Never display a shelf for an order the source does not actually supply. */
fun SourceCapabilities.orderedDiscoverySections(): List<SourceDiscoverySection> {
    val declared =
        sortOrders.mapNotNull { order -> when (order) {
            MangaSortOrder.LATEST -> SourceDiscoverySection.LATEST
            MangaSortOrder.POPULAR -> SourceDiscoverySection.POPULAR
            MangaSortOrder.NEWEST -> SourceDiscoverySection.NEWEST
            else -> null
        } } + if (supportsGenreFilter) listOf(SourceDiscoverySection.GENRES) else emptyList()
    val supported = declared.distinct()
    return supported.filter { it == SourceDiscoverySection.LATEST } + supported.filterNot { it == SourceDiscoverySection.LATEST }
}

fun SourceDiscoverySection.catalogueOrder(): MangaSortOrder? = when (this) {
    SourceDiscoverySection.LATEST -> MangaSortOrder.LATEST
    SourceDiscoverySection.POPULAR -> MangaSortOrder.POPULAR
    SourceDiscoverySection.NEWEST -> MangaSortOrder.NEWEST
    SourceDiscoverySection.GENRES -> null
}

/** A genre/category as the source itself defines it (ids are source-specific). */
data class SourceGenre(
    val id: String,
    val name: String
)

/**
 * Real, measured state of a source. Never set optimistically: a source is [Online] only
 * after a real request returned real parsed content.
 */
sealed interface SourceHealth {
    data object Unknown : SourceHealth
    data object Checking : SourceHealth
    data class Online(val checkedAt: Long, val latencyMs: Long) : SourceHealth
    data class Failing(val checkedAt: Long, val kind: SourceErrorKind, val detail: String) : SourceHealth
}

enum class SourceErrorKind {
    /** Device is offline / DNS / timeout. */
    NETWORK,

    /** The site answered with an HTTP error status. */
    HTTP,

    /** A bot-protection challenge (e.g. Cloudflare) stands between the app and the site. */
    PROTECTION,

    /** The site answered, but its structure no longer matches what this source understands. */
    STRUCTURE_CHANGED,

    /** The requested chapter/pages are locked or paid on the source. */
    LOCKED,

    /** The requested work/chapter no longer exists on the source. */
    NOT_FOUND
}

/**
 * Typed failure of a source request. [detail] is technical context (URL, status code);
 * user-facing wording is chosen by the UI from [kind] and the UI language.
 */
class SourceException(
    val kind: SourceErrorKind,
    val sourceId: String,
    val detail: String,
    val httpCode: Int? = null,
    cause: Throwable? = null
) : IOException("[$sourceId] $kind: $detail", cause)
