package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneModelRouteTest {
    @Test fun cliModelsUseBridgeInferenceWithPhoneTools() {
        listOf("gemini-4-argon-high", "gemini-4-argon-low", "gemini-3.8-flash-low").forEach {
            assertEquals(PhoneModelRoute.PC_CLI, phoneModelRoute(it))
        }
    }
    @Test fun apiProvidersKeepTheirRoutes() {
        assertEquals(PhoneModelRoute.GEMINI_API, phoneModelRoute("gemini-2.5-pro"))
        assertEquals(PhoneModelRoute.GEMINI_API, phoneModelRoute("models/gemini-2.5-pro"))
        assertEquals(PhoneModelRoute.OPENROUTER_API, phoneModelRoute("google/gemini-2.5-pro"))
    }
}
