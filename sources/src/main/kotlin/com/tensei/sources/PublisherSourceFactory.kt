package com.tensei.sources

import com.tensei.app.data.remote.sources.MangaSwatSource
import com.tensei.app.data.remote.sources.MangaTekSource
import com.tensei.app.data.remote.sources.TeamXSource
import com.tensei.app.domain.source.MangaSource
import okhttp3.OkHttpClient

/** Source order and identities are publisher-controlled, without rebuilding the application. */
class PublisherSourceFactory : com.tensei.app.domain.source.PublisherSourceFactory {
    override fun create(client: OkHttpClient): List<MangaSource> = listOf(
        MangaTekSource(client), MangaSwatSource(client), TeamXSource(client)
    )
}
