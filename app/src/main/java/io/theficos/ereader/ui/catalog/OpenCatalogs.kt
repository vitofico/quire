package io.theficos.ereader.ui.catalog

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A free, legal catalog Quire suggests when adding one. The list is `catalogs/open-catalogs.json`
 * at the repository root, packaged as an app asset, so Android and Quire Web offer the same ones.
 */
data class OpenCatalog(
    val id: String,
    val name: String,
    val url: String,
    /** BCP 47 codes of the books it offers. */
    val languages: List<String>,
    val offers: String,
    val home: String,
    /** One line per app language; English is always there. */
    val descriptions: Map<String, String>,
) {
    fun description(language: String): String = descriptions[language] ?: descriptions.getValue("en")
}

object OpenCatalogs {
    const val ASSET = "open-catalogs.json"

    fun load(context: Context): List<OpenCatalog> =
        parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })

    fun parse(json: String): List<OpenCatalog> =
        Json.parseToJsonElement(json).jsonObject.getValue("catalogs").jsonArray.map { element ->
            val item = element.jsonObject
            fun text(key: String) = item.getValue(key).jsonPrimitive.content
            OpenCatalog(
                id = text("id"),
                name = text("name"),
                url = text("url"),
                languages = item.getValue("languages").jsonArray.map { it.jsonPrimitive.content },
                offers = text("offers"),
                home = text("home"),
                descriptions = item.getValue("description").jsonObject.mapValues { it.value.jsonPrimitive.content },
            )
        }
}

/** Those offering books in [language] first, the rest in the list's own order. */
fun List<OpenCatalog>.orderedFor(language: String): List<OpenCatalog> = sortedBy { language !in it.languages }
