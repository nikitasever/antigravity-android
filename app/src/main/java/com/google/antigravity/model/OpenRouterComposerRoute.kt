package com.google.antigravity.model

import org.json.JSONObject

enum class OpenRouterComposerRoute { TEXT, IMAGE, VIDEO }

/** Catalog output capability, never a substring of the model name or a video input. */
fun openRouterComposerRoute(model: OpenRouterModelItem?): OpenRouterComposerRoute {
    if (model == null) return OpenRouterComposerRoute.TEXT
    val capabilities = runCatching { JSONObject(model.generationCapabilitiesJson) }.getOrNull()
    if (capabilities?.has("videos") == true) return OpenRouterComposerRoute.VIDEO
    if (capabilities?.has("images") == true) return OpenRouterComposerRoute.IMAGE
    val outputs = model.modality.substringAfter("->", "text").lowercase().split('+').map { it.trim() }
    // General chat routers can advertise image/video output too. A normal message
    // must not silently become a paid media request merely because of that badge.
    if ("text" in outputs) return OpenRouterComposerRoute.TEXT
    return when {
        model.isVideoGen && "video" in outputs -> OpenRouterComposerRoute.VIDEO
        model.isImageGen && "image" in outputs -> OpenRouterComposerRoute.IMAGE
        else -> OpenRouterComposerRoute.TEXT
    }
}
