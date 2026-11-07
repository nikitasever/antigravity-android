package com.google.antigravity.model

import org.json.JSONObject
import java.util.Locale

/** Capabilities come from the API's input/output metadata, not model-name guesses. */
internal fun openRouterCategories(model: JSONObject): List<OpenRouterCategory> {
    val arch = model.optJSONObject("architecture")
    val modality = arch?.optString("modality", "text->text").orEmpty().lowercase(Locale.ROOT)
    fun modalities(key: String, fallback: String): Set<String> = arch?.optJSONArray(key)?.let { array ->
        (0 until array.length()).map { array.optString(it).lowercase(Locale.ROOT) }.toSet()
    } ?: fallback.split('+').toSet()
    val input = modalities("input_modalities", modality.substringBefore("->"))
    val output = modalities("output_modalities", modality.substringAfter("->", "text"))
    val parameters = model.optJSONArray("supported_parameters")?.let { array ->
        (0 until array.length()).map { array.optString(it).lowercase(Locale.ROOT) }
    }.orEmpty()
    val pricing = model.optJSONObject("pricing")
    val prices = pricing?.keys()?.asSequence()?.map { pricing.optString(it).toDoubleOrNull() }?.toList().orEmpty()
    val endpoints = model.optJSONArray("catalogEndpoints")
    val hasGenerationApi = endpoints != null && (0 until endpoints.length()).any { endpoints.optString(it) in setOf("images", "videos") }
    val free = model.optString("id").endsWith(":free") || (!hasGenerationApi && prices.isNotEmpty() && prices.all { it == 0.0 })
    return buildList {
        add(OpenRouterCategory.ALL)
        if (free) add(OpenRouterCategory.FREE)
        if ("reasoning" in parameters || "include_reasoning" in parameters) add(OpenRouterCategory.REASONING)
        if ("image" in input || "video" in input) add(OpenRouterCategory.VISION)
        if ("image" in output) add(OpenRouterCategory.IMAGE_GEN)
        if ("video" in output) add(OpenRouterCategory.VIDEO_GEN)
        if ("audio" in input || "audio" in output) add(OpenRouterCategory.AUDIO)
    }
}
