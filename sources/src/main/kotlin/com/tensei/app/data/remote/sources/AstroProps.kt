package com.tensei.app.data.remote.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.jsoup.nodes.Document

/**
 * Reads the `props` attribute of an Astro `<astro-island>`.
 *
 * Astro serialises every value as a `[type, value]` pair: type 0 is a scalar or a plain object
 * (whose own values are pairs again), type 1 is an array of pairs. Other types (dates, maps...)
 * are passed through untouched because no source needs them.
 */
object AstroProps {
    private val json = Json { ignoreUnknownKeys = true }

    /** Decoded props of the first island whose component URL contains [componentName]. */
    fun find(document: Document, componentName: String): JsonObject? {
        val island = document.select("astro-island[component-url*=$componentName]").firstOrNull()
            ?: return null
        val raw = island.attr("props")
        if (raw.isBlank()) return null
        return runCatching { decode(raw) }.getOrNull()
    }

    fun decode(rawProps: String): JsonObject {
        val root = json.parseToJsonElement(rawProps) as? JsonObject
            ?: throw IllegalArgumentException("Astro props must be an object")
        return JsonObject(root.mapValues { decodeValue(it.value) })
    }

    private fun decodeValue(element: JsonElement): JsonElement {
        if (element !is JsonArray || element.size != 2) return element
        val type = (element[0] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return element
        val value = element[1]
        return when (type) {
            0 -> if (value is JsonObject) JsonObject(value.mapValues { decodeValue(it.value) }) else value
            1 -> JsonArray((value as? JsonArray
                ?: throw IllegalArgumentException("Astro array encoding must contain an array")).map { decodeValue(it) })
            else -> value
        }
    }
}

internal fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

/** True for JSON `true` and for the numeric flag `1`. */
internal fun JsonObject.flag(key: String): Boolean {
    val primitive = this[key] as? JsonPrimitive ?: return false
    return primitive.booleanOrNull ?: (primitive.intOrNull == 1)
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

