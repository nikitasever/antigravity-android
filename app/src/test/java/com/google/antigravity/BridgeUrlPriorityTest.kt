package com.google.antigravity

import com.google.antigravity.network.prioritizedBridgeUrls
import com.google.antigravity.network.bridgeCandidatesForDevice
import com.google.antigravity.network.isLocalBridgeUrl
import org.junit.Assert.*
import org.junit.Test

class BridgeUrlPriorityTest {
    @Test fun physicalPhoneRetainsExplicitLanWithoutEmulatorDefaults() {
        val urls = bridgeCandidatesForDevice("http://192.168.1.25:8787/", false, false,
            "https://previous.example", emptyList(), emptyList())
        assertEquals(listOf("http://192.168.1.25:8787", "https://previous.example"), urls)
    }
    @Test fun publicOnlyRejectsLanAndPlaintextButDoesNotMatchHostSubstrings() {
        assertEquals(listOf("https://192.168.example.com"), bridgeCandidatesForDevice(
            "http://192.168.1.25:8787", false, true, "http://public.example", listOf("https://172.20.1.1"),
            listOf("https://192.168.example.com")))
        assertTrue(isLocalBridgeUrl("http://10.20.0.1"))
        assertFalse(isLocalBridgeUrl("https://localhost.attacker.example"))
    }
    @Test fun explicitBridgeWinsOverEmulatorAndPreviouslyConnectedPc() {
        val urls = prioritizedBridgeUrls("http://10.0.2.2:8789/", listOf("http://10.0.2.2:8787"),
            "https://old-pc.example", listOf("https://discovered.example"), listOf("https://default.example"))
        assertEquals("http://10.0.2.2:8789", urls.first())
        assertEquals(5, urls.size)
    }
    @Test fun emptyConfigAndRepeatedDiscoveriesKeepUsefulFallbacksOnce() {
        assertEquals(listOf("http://10.0.2.2:8787", "https://pc.example"),
            prioritizedBridgeUrls("", listOf("http://10.0.2.2:8787"), "https://pc.example/",
                listOf("https://pc.example"), emptyList()))
    }
}
