package com.google.antigravity

import com.google.antigravity.network.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class BridgeCredentialsTest {
    @Test fun normalizesExactOriginsAndRejectsPublicPlaintextAndUserinfo() {
        assertEquals("https://example.com", bridgeOrigin("https://EXAMPLE.com:443/path?q=1#fragment"))
        assertEquals("http://127.0.0.1:8819", bridgeOrigin("http://127.0.0.1:8819/api/status"))
        assertNull(bridgeOrigin("http://public.example/path"))
        assertNull(bridgeOrigin("https://owner:secret@example.com/"))
        assertNull(bridgeOrigin("file:///a"))
        assertNotEquals(bridgeOrigin("https://example.com"), bridgeOrigin("https://example.com:8443"))
    }
    @Test fun onlyPairedExactOriginReceivesCredentialAndStaleHeadersAreRemoved() {
        val token = "a".repeat(64)
        val seen = mutableListOf<Request>()
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor(BridgeAuthInterceptor {
            if (it == "https://paired.example") token else null
        }).addInterceptor { chain ->
            seen.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("QA").body("ok".toResponseBody()).build()
        }.build()
        for (url in listOf("https://paired.example/api/status",
            "https://openrouter.ai/api/v1/chat/completions")) {
            client.newCall(Request.Builder().url(url).header(BRIDGE_TOKEN_HEADER, "stale").build()).execute().close()
        }
        assertEquals(token, seen[0].header(BRIDGE_TOKEN_HEADER))
        assertTrue(seen.drop(1).all { it.header(BRIDGE_TOKEN_HEADER) == null })
        for (url in listOf("https://other.example/api/chat", "https://paired.example:8443/api/tts?text=private")) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().close()
                fail("Unpaired bridge must fail before the network")
            } catch (expected: java.io.IOException) {
                assertTrue(expected.message!!.contains("сопряжение"))
            }
        }
        assertEquals("Only paired bridge and direct provider reach the transport", 2, seen.size)
    }
}
