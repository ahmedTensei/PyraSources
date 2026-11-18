package com.tensei.app.domain.source

import okhttp3.OkHttpClient

/** Compile-only host contract. The Tensei application supplies this type at runtime. */
interface PublisherSourceFactory {
    fun create(client: OkHttpClient): List<MangaSource>
}
