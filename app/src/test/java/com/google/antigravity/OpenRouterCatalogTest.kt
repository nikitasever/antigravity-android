package com.google.antigravity

import com.google.antigravity.model.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class OpenRouterCatalogTest {
    @Test fun partialPricingCannotReclassifyAKnownPaidGeneratorAsFree() {
        val paid = OpenRouterModelItem("shared", "Paid", isImageGen = true)
        val incomplete = OpenRouterModelItem("shared", "Current", isFree = true, priceLabel = "Бесплатно (Free)",
            categories = listOf(OpenRouterCategory.ALL, OpenRouterCategory.FREE))
        val merged = mergePartialOpenRouterModels(listOf(paid), listOf(incomplete)).single()
        assertFalse(merged.isFree)
        assertFalse(OpenRouterCategory.FREE in merged.categories)
        assertNotEquals("Бесплатно (Free)", merged.priceLabel)
    }
    @Test fun partialRefreshRetainsMissingModelsAndTheirGenerationCapabilities() {
        val old = OpenRouterModelItem("shared", "Old", isVideoGen = true, categories = listOf(OpenRouterCategory.ALL, OpenRouterCategory.VIDEO_GEN),
            generationCapabilitiesJson = """{"videos":{"supported_durations":[4,8]}}""")
        val current = OpenRouterModelItem("shared", "Current", isImageGen = true, categories = listOf(OpenRouterCategory.ALL, OpenRouterCategory.IMAGE_GEN),
            generationCapabilitiesJson = """{"images":{"supports_streaming":false}}""")
        val merged = mergePartialOpenRouterModels(listOf(old, old.copy(id = "missing")), listOf(current))
        assertEquals(2, merged.size)
        assertTrue(merged[0].isVideoGen && merged[0].isImageGen)
        assertTrue(merged[0].generationCapabilitiesJson.contains("supported_durations"))
        assertTrue(merged[0].generationCapabilitiesJson.contains("supports_streaming"))
    }
    @Test fun dedicatedModelsAreMergedWithoutLosingChatContextOrGenerationOptions() {
        val chat = JSONArray("""[{"id":"shared","context_length":4096,"architecture":{"input_modalities":["text","image"],"output_modalities":["text"]}}]""")
        val images = JSONArray("""[{"id":"shared","supported_parameters":{"n":{"min":1,"max":1}}}]""")
        val videos = JSONArray("""[{"id":"video-only","supported_durations":[4,8]}]""")
        val before = chat.toString()
        val merged = mergeOpenRouterCatalogs(chat, images, videos)
        assertEquals(2, merged.size)
        assertEquals(4096, merged[0].getInt("context_length"))
        assertTrue(OpenRouterCategory.IMAGE_GEN in openRouterCategories(merged[0]))
        assertEquals(1, merged[0].getJSONObject("generationCapabilities").getJSONObject("images")
            .getJSONObject("supported_parameters").getJSONObject("n").getInt("max"))
        assertEquals(0, merged[1].getInt("context_length"))
        assertTrue(OpenRouterCategory.VIDEO_GEN in openRouterCategories(merged[1]))
        assertEquals(before, chat.toString())
    }
    @Test fun paidGenerationDoesNotInheritFreeTextTokenPricing() {
        val result = mergeOpenRouterCatalogs(JSONArray("""[{"id":"shared","pricing":{"prompt":"0","completion":"0"}}]"""),
            JSONArray("""[{"id":"shared"}]"""), JSONArray()).single()
        assertFalse(OpenRouterCategory.FREE in openRouterCategories(result))
    }
}
