package com.google.antigravity

import com.google.antigravity.model.resolveSavedBridgeAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeAddressSettingsTest {
    private val defaultUrl = "https://current.example"
    private val retired = "https://cdd0a117b3ea350f-79-137-206-221.serveousercontent.com"
    @Test fun missingAddressUsesCurrentDefault() {
        listOf(null, "", " ").forEach { assertEquals(defaultUrl, resolveSavedBridgeAddress(it, 0, 2, defaultUrl)) }
    }
    @Test fun knownRetiredDefaultMigratesOnlyFromOlderVersion() {
        assertEquals(defaultUrl, resolveSavedBridgeAddress(retired, 1, 2, defaultUrl))
        assertEquals(retired, resolveSavedBridgeAddress(retired, 2, 2, defaultUrl))
    }
    @Test fun manualOfflineOrInvalidAddressSurvivesRestartAndVersionChange() {
        listOf("https://bridge-address-qa.invalid/abcdef8798", "http://offline-server.local:8798",
            "https://invalidation.example/mcp").forEach {
            assertEquals(it, resolveSavedBridgeAddress(it, 0, 2, defaultUrl))
        }
    }
    @Test fun manualAddressIsNotReplacedByNewDefault() {
        val custom = "http://127.0.0.1:8798"
        assertEquals(custom, resolveSavedBridgeAddress(custom, 0, 2, defaultUrl))
    }
}
