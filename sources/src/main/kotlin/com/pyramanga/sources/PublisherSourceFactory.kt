package com.pyramanga.sources

import com.pyramanga.app.data.remote.sources.MangaSwatSource
import com.pyramanga.app.data.remote.sources.MangaTekSource
import com.pyramanga.app.data.remote.sources.TeamXSource
import com.pyramanga.app.data.remote.sources.AsuraScansSource
import com.pyramanga.app.data.remote.sources.RawkumaSource
import com.pyramanga.app.domain.source.MangaSource
import okhttp3.OkHttpClient

/** Source order and identities are publisher-controlled, without rebuilding the application. */
class PublisherSourceFactory : com.pyramanga.app.domain.source.PublisherSourceFactory {
    override fun create(client: OkHttpClient): List<MangaSource> = listOf(
        MangaTekSource(client), MangaSwatSource(client), TeamXSource(client), AsuraScansSource(client), RawkumaSource(client)
    )
}
