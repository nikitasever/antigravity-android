package com.google.antigravity

import com.google.antigravity.model.mapToDirectGeminiModelId
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiModelIdentityTest {
    @Test fun bridgeModelIsNeverSilentlyRenamedToAnotherGeneration() {
        assertEquals("gemini-4-argon-high", mapToDirectGeminiModelId("gemini-4-argon-high"))
        assertEquals("gemini-4-argon-low", mapToDirectGeminiModelId("gemini-4-argon-low"))
    }

    @Test fun directApiModelKeepsItsExactIdentity() {
        assertEquals("gemini-2.5-pro", mapToDirectGeminiModelId("models/gemini-2.5-pro"))
        assertEquals("gemini-2.5-flash", mapToDirectGeminiModelId("gemini-2.5-flash"))
    }
}
