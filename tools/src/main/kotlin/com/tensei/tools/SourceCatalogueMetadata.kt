package com.tensei.tools

import com.tensei.app.domain.source.PublisherSourceFactory
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.io.File

/** Build-only tool: descriptors come from the actual factory without fetching websites. */
fun main(args: Array<String>) {
    require(args.size == 2)
    val extensionId = args[0]
    val factory = Class.forName("com.tensei.sources.PublisherSourceFactory").getDeclaredConstructor().newInstance() as PublisherSourceFactory
    val sources = factory.create(OkHttpClient())
    require(sources.isNotEmpty() && sources.map { it.id }.distinct().size == sources.size)
    val catalog = JsonArray(sources.map { source ->
        JsonObject(linkedMapOf(
            "id" to JsonPrimitive(source.id), "name" to JsonPrimitive(source.name),
            "baseUrl" to JsonPrimitive(source.baseUrl), "lang" to JsonArray(source.lang.map(::JsonPrimitive)),
            "extensionId" to JsonPrimitive(extensionId)
        ))
    })
    File(args[1]).apply { parentFile.mkdirs(); writeText(catalog.toString()) }
}
