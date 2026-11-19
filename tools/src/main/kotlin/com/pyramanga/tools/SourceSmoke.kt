package com.pyramanga.tools

import com.pyramanga.app.data.remote.sources.AsuraScansSource
import com.pyramanga.app.data.remote.sources.RawkumaSource
import com.pyramanga.app.domain.model.MangaSortOrder
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/** Optional live verification, deliberately separate from deterministic CI fixture tests. */
fun main() = runBlocking {
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36").build())
    }.build()
    try {
        for (source in listOf(AsuraScansSource(client), RawkumaSource(client))) {
            val catalogue = source.browseManga(1, MangaSortOrder.LATEST)
            check(catalogue.items.isNotEmpty())
            val details = source.getMangaDetails(catalogue.items.first().url)
            val chapters = source.getChapterList(details.url)
            val chapter = chapters.firstOrNull { !it.isLocked } ?: error("No public chapter for ${source.id}")
            val pages = source.getChapterContent(chapter.url)
            check(pages.pages.isNotEmpty())
            val search = source.searchManga(details.title, 1)
            check(search.items.any { it.id == details.id })
            val genres = source.getGenres()
            check(genres.isNotEmpty())
            println("${source.id}: catalogue=${catalogue.items.size} hasNext=${catalogue.hasNextPage} work=${details.id} chapters=${chapters.size} pages=${pages.pages.size} search=${search.items.size} genres=${genres.size}")
        }
    } finally {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
