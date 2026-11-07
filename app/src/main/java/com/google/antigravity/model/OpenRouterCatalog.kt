package com.google.antigravity.model

import org.json.JSONArray
import org.json.JSONObject

/** Dedicated discovery endpoints establish output capability; a video input alone never does. */
internal fun mergeOpenRouterCatalogs(chat: JSONArray, images: JSONArray, videos: JSONArray): List<JSONObject> {
    val merged = linkedMapOf<String, JSONObject>()
    listOf("chat" to chat, "images" to images, "videos" to videos).forEach { (kind, rows) ->
        for (index in 0 until rows.length()) {
            val raw = rows.optJSONObject(index) ?: continue
            val id = raw.optString("id").trim().takeIf { it.isNotEmpty() } ?: continue
            val previous = merged[id]
            val row = previous ?: JSONObject(raw.toString()).put("id", id)
            val arch = row.optJSONObject("architecture") ?: JSONObject()
            val rawArch = raw.optJSONObject("architecture")
            val input = arch.optJSONArray("input_modalities") ?: rawArch?.optJSONArray("input_modalities")
                ?: JSONArray().put("text")
            val output = linkedSetOf<String>()
            val knownOutput = arch.optJSONArray("output_modalities") ?: if (previous == null && kind != "chat") JSONArray() else
                JSONArray(arch.optString("modality", "text->text").substringAfter("->", "text").split('+'))
            for (i in 0 until knownOutput.length()) output.add(knownOutput.optString(i))
            if (kind == "images") output.add("image")
            if (kind == "videos") output.add("video")
            arch.put("input_modalities", input).put("output_modalities", JSONArray(output.toList()))
                .put("modality", (0 until input.length()).joinToString("+") { input.optString(it) } + "->" + output.joinToString("+"))
            row.put("architecture", arch)
            val endpoints = row.optJSONArray("catalogEndpoints") ?: JSONArray()
            if ((0 until endpoints.length()).none { endpoints.optString(it) == kind }) endpoints.put(kind)
            row.put("catalogEndpoints", endpoints)
            val capabilities = row.optJSONObject("generationCapabilities") ?: JSONObject()
            if (kind != "chat") capabilities.put(kind, JSONObject(raw.toString()))
            row.put("generationCapabilities", capabilities)
            if (previous == null && kind != "chat" && !row.has("context_length")) row.put("context_length", 0)
            merged[id] = row
        }
    }
    return merged.values.toList()
}

/** Partial refresh keeps the last known capabilities until a complete discovery can replace them. */
internal fun mergePartialOpenRouterModels(previous: List<OpenRouterModelItem>, fresh: List<OpenRouterModelItem>): List<OpenRouterModelItem> {
    val merged = previous.associateBy { it.id }.toMutableMap()
    fresh.forEach { item ->
        val old = merged[item.id]
        merged[item.id] = if (old == null) item else {
            val capabilities = runCatching { JSONObject(old.generationCapabilitiesJson) }.getOrDefault(JSONObject())
            val newer = runCatching { JSONObject(item.generationCapabilitiesJson) }.getOrDefault(JSONObject())
            newer.keys().forEach { key -> capabilities.put(key, newer.get(key)) }
            val free = old.isFree && item.isFree
            item.copy(categories = (old.categories + item.categories).distinct().filter { it != OpenRouterCategory.FREE || free },
                isFree = old.isFree && item.isFree,
                priceLabel = if (!free && item.isFree) "Тариф OpenRouter" else item.priceLabel,
                isImageGen = old.isImageGen || item.isImageGen, isVideoGen = old.isVideoGen || item.isVideoGen,
                isVision = old.isVision || item.isVision, isAudio = old.isAudio || item.isAudio,
                isReasoning = old.isReasoning || item.isReasoning, generationCapabilitiesJson = capabilities.toString())
        }
    }
    return merged.values.toList()
}
