package com.google.antigravity

import com.google.antigravity.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenRouterCapabilitiesTest {
    @Test fun readingVideoDoesNotMeanGeneratingVideo() {
        val categories = openRouterCategories(JSONObject("""{"id":"example/video-reader","architecture":{"input_modalities":["video","text"],"output_modalities":["text"]}}"""))
        assertTrue(OpenRouterCategory.VISION in categories)
        assertFalse(OpenRouterCategory.VIDEO_GEN in categories)
        assertFalse(OpenRouterCategory.FREE in categories)
    }
    @Test fun namesDoNotOverrideExplicitOutputCapabilities() {
        val categories = openRouterCategories(JSONObject("""{"id":"example/flux-video","architecture":{"output_modalities":["text"]}}"""))
        assertFalse(OpenRouterCategory.IMAGE_GEN in categories)
        assertFalse(OpenRouterCategory.VIDEO_GEN in categories)
    }
    @Test fun imagePricePreventsZeroTokenPricesFromBeingLabeledFree() {
        val categories = openRouterCategories(JSONObject("""{"id":"example/image","architecture":{"output_modalities":["image"]},"pricing":{"prompt":"0","completion":"0","image":"0.02"}}"""))
        assertTrue(OpenRouterCategory.IMAGE_GEN in categories)
        assertFalse(OpenRouterCategory.FREE in categories)
    }
    @Test fun modalityStringFallbackAndFreeVariantRemainSupported() {
        val categories = openRouterCategories(JSONObject("""{"id":"example/model:free","architecture":{"modality":"text+image->text+video"}}"""))
        assertTrue(OpenRouterCategory.VISION in categories)
        assertTrue(OpenRouterCategory.VIDEO_GEN in categories)
        assertTrue(OpenRouterCategory.FREE in categories)
    }
}
