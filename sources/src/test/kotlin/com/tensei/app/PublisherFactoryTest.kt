package com.tensei.app

import com.tensei.app.domain.source.PublisherSourceFactory
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class PublisherFactoryTest {
    @Test fun factoryImplementsHostApiAndPreservesIndependentSourceIdentities() {
        val factory = Class.forName("com.tensei.sources.PublisherSourceFactory").getDeclaredConstructor().newInstance()
        assertTrue(factory is PublisherSourceFactory)
        val sources = (factory as PublisherSourceFactory).create(OkHttpClient())
        assertEquals(listOf("mangatek", "mangaswat", "teamx"), sources.map { it.id })
        assertEquals(listOf("MangaTek", "Swat Manga", "Team X"), sources.map { it.name })
        assertEquals(3, sources.map { it.toDescriptor() }.distinctBy { it.id }.size)
        assertTrue(sources.all { it.baseUrl.startsWith("https://") })
    }
}
