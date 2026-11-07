package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouterComposerRouteTest {
    @Test fun metadataRoutesGenerationWhileNamesAndVideoInputsDoNot() {
        assertEquals(OpenRouterComposerRoute.TEXT, openRouterComposerRoute(null))
        assertEquals(OpenRouterComposerRoute.TEXT, openRouterComposerRoute(
            OpenRouterModelItem("qa/video-understanding", "Vision", modality = "video+text->text", isVision = true)))
        assertEquals(OpenRouterComposerRoute.VIDEO, openRouterComposerRoute(
            OpenRouterModelItem("qa/generate", "Generate", isVideoGen = true, modality = "text->video")))
        assertEquals(OpenRouterComposerRoute.IMAGE, openRouterComposerRoute(
            OpenRouterModelItem("qa/image", "Image", isImageGen = true, modality = "text->image")))
        assertEquals(OpenRouterComposerRoute.TEXT, openRouterComposerRoute(
            OpenRouterModelItem("openrouter/auto", "Auto", isImageGen = true, modality = "text+image->text+image")))
        assertEquals(OpenRouterComposerRoute.TEXT, openRouterComposerRoute(
            OpenRouterModelItem("qa/omni", "Omni", isVideoGen = true, modality = "text+video->text+video")))
        assertEquals(OpenRouterComposerRoute.IMAGE, openRouterComposerRoute(
            OpenRouterModelItem("qa/dedicated", "Dedicated", isImageGen = true,
                modality = "text->text+image", generationCapabilitiesJson = """{"images":{"id":"qa/dedicated"}}""")))
    }
}
