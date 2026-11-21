package com.google.antigravity.googlepro

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCodeParsingTest {

    @Test
    fun `ineligible tiers are not a verdict - the reason is kept, the tier too`() {
        val state = parseCodeAssist(JSONObject("""
            {"currentTier":{"id":"standard","name":"Standard"},
             "ineligibleTiers":[{"tierId":"free-tier","tierName":"Free","reasonMessage":"User location is not supported"}]}
        """.trimIndent()))
        assertTrue(state.passedGate)
        assertTrue(state.usable)
        assertEquals("standard", state.tierId)
        assertEquals(1, state.ineligibleReasons.size)
    }

    @Test
    fun `a response without a tier is still passed`() {
        val state = parseCodeAssist(JSONObject("""{"cloudaicompanionProject":"p-1"}"""))
        assertTrue(state.passedGate)
        assertEquals("p-1", state.project)
    }

    @Test
    fun `server model configs become the catalog`() {
        val state = parseCodeAssist(JSONObject("""
            {"currentTier":{"id":"standard","modelConfigs":[{"modelId":"gemini-3-pro-high"},{"modelId":"gemini-3-flash"}]}}
        """.trimIndent()))
        assertEquals(listOf("gemini-3-pro-high", "gemini-3-flash"), state.models)
    }

    @Test
    fun `sse lines carry json and skip noise`() {
        assertNull(parseSseLine("event: ping"))
        assertNull(parseSseLine("data: [DONE]"))
        assertNotNull(parseSseLine("""data: {"candidates":[]}"""))
    }

    @Test
    fun `wrapped sse chunks are unwrapped before reading`() {
        val chunk = JSONObject("""{"response":{"candidates":[{"content":{"parts":[{"text":"ответ"}]}}]}}""")
        assertEquals("ответ", collectChunkText(chunk).text)
    }

    @Test
    fun `thought parts stay separate from the answer`() {
        val chunk = JSONObject("""
            {"candidates":[{"content":{"parts":[{"text":"думаю","thought":true},{"text":"ответ"}]}}],
             "usageMetadata":{"candidatesTokenCount":12}}
        """.trimIndent())
        val reply = collectChunkText(chunk)
        assertEquals("ответ", reply.text)
        assertEquals("думаю", reply.thinking)
        assertEquals(12, outputTokensOf(chunk))
    }

    @Test
    fun `user defined project is requested from the client`() {
        val state = parseCodeAssist(JSONObject("""
            {"allowedTiers":[{"id":"standard-tier","isDefault":true,"userDefinedCloudaicompanionProject":true}]}
        """.trimIndent()))
        assertTrue(state.needsProject)
        assertEquals("standard-tier", state.defaultTierId)
        assertTrue(!state.unlicensed)
    }

    @Test
    fun `a granted tier no longer asks for a project`() {
        val state = parseCodeAssist(JSONObject("""
            {"currentTier":{"id":"standard-tier"},"cloudaicompanionProject":"p-1",
             "allowedTiers":[{"id":"standard-tier","userDefinedCloudaicompanionProject":true}]}
        """.trimIndent()))
        assertTrue(!state.needsProject)
        assertEquals("p-1", state.project)
    }

    @Test
    fun `no tiers at all means the account is unlicensed`() {
        assertTrue(parseCodeAssist(JSONObject("{}")).unlicensed)
    }

    @Test
    fun `default gemini project is preferred and dead ones skipped`() {
        val json = JSONObject("""
            {"projects":[{"projectId":"zzz","lifecycleState":"ACTIVE"},
                         {"projectId":"dead","lifecycleState":"DELETE_REQUESTED"},
                         {"projectId":"gen-lang-client-1","lifecycleState":"ACTIVE"}]}
        """.trimIndent())
        assertEquals("gen-lang-client-1", rankProjects(json).first())
        assertEquals(2, rankProjects(json).size)
    }

    @Test
    fun `models are prefixed locally and stripped for the server`() {
        val options = googleProModelOptions(listOf("gemini-9-pro"))
        assertEquals("google-pro/gemini-9-pro", options.first().id)
        assertEquals("gemini-9-pro", remoteProModel(options.first().id))
        assertTrue(isProModel(options.first().id))
        assertTrue(options.none { it.id == it.id.removePrefix("google-pro/") })
    }
}
