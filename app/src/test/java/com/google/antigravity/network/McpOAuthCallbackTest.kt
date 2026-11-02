package com.google.antigravity.network

import org.junit.Assert.*
import org.junit.Test

class McpOAuthCallbackTest {
    private val base = "com.google.antigravity:/mcp-oauth/callback"

    @Test fun `accepts code and cancellation only for pending state`() {
        assertEquals("a+b", parseMcpOAuthCallback("$base?state=valid&code=a%2Bb", "valid", 100, 1)?.code)
        assertEquals("access_denied", parseMcpOAuthCallback("$base?state=valid&error=access_denied", "valid", 100, 1)?.error)
    }

    @Test fun `rejects unsolicited expired or malformed responses`() {
        for (url in listOf("$base?state=wrong&code=x", "$base?state=valid&state=valid&code=x",
            "$base?state=valid&code=x&code=y", "$base?state=valid&code=x&error=no",
            "$base?state=valid", "$base?state=valid&code=x#fragment",
            "com.google.antigravity://evil/mcp-oauth/callback?state=valid&code=x",
            "https://example.com/mcp-oauth/callback?state=valid&code=x")) {
            assertNull(url, parseMcpOAuthCallback(url, "valid", 100, 1))
        }
        assertNull(parseMcpOAuthCallback("$base?state=valid&code=x", "valid", 100, 100))
    }

    @Test fun `catalog hides staging aliases and OAuth does not imply a handshake`() {
        val catalog = parseExtensionCatalog(org.json.JSONObject("""{"matrix":{"positions":[
            {"id":"plugin","canonicalId":"plugin","name":"Plugin","componentTypes":["plugin"]},
            {"id":"staging","canonicalId":"plugin","name":"Copy","componentTypes":["plugin"]}
        ]}}"""))
        assertEquals(listOf("plugin"), catalog.map { it.id })
        val server = parseMcpServerList(org.json.JSONObject("""{"mcpServers":[{"id":"remote","name":"Remote","commandOrUrl":"https://example.com/mcp","status":"configured","oauthAuthorized":true}]}""")).single()
        assertTrue(server.oauthAuthorized)
        assertEquals("configured", server.status)
        assertEquals("https://example.com/mcp", server.commandOrUrl)
    }

    @Test fun `phone loopback accepts only pending callback and offers explicit return to app`() {
        McpOAuthLoopback().use { loopback ->
            val worker = Thread { loopback.awaitBrowserReturn("valid") }.apply { start() }
            try {
                val bad = java.net.URL("${loopback.redirectUri}?state=wrong&code=x").openConnection() as java.net.HttpURLConnection
                bad.connectTimeout = 3000; bad.readTimeout = 3000
                assertEquals(400, bad.responseCode)
                bad.errorStream?.close(); bad.disconnect()
                val good = java.net.URL("${loopback.redirectUri}?state=valid&code=a%2Bb").openConnection() as java.net.HttpURLConnection
                good.connectTimeout = 3000; good.readTimeout = 3000
                assertEquals(200, good.responseCode)
                val html = good.inputStream.bufferedReader().use { it.readText() }
                assertTrue(html.contains("com.google.antigravity:/mcp-oauth/callback?state=valid&amp;code=a%2Bb"))
                assertEquals("no-store", good.getHeaderField("Cache-Control"))
                good.disconnect()
                worker.join(5000)
                assertFalse(worker.isAlive)
            } finally { loopback.close(); worker.join(5000) }
        }
    }
}
