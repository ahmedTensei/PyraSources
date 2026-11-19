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
        assertEquals(listOf("mangatek", "mangaswat", "teamx", "asurascans", "rawkuma"), sources.map { it.id })
        assertEquals(listOf("MangaTek", "Swat Manga", "Team X", "Asura Scans", "Rawkuma"), sources.map { it.name })
        assertEquals(5, sources.map { it.toDescriptor() }.distinctBy { it.id }.size)
        assertEquals(listOf("en"), sources.first { it.id == "asurascans" }.lang)
        assertEquals(listOf("ja"), sources.first { it.id == "rawkuma" }.lang)
        assertTrue(sources.all { it.baseUrl.startsWith("https://") })
    }
}
