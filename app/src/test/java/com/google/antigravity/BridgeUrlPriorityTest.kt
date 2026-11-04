package com.google.antigravity

import com.google.antigravity.network.prioritizedBridgeUrls
import org.junit.Assert.*
import org.junit.Test

class BridgeUrlPriorityTest {
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
