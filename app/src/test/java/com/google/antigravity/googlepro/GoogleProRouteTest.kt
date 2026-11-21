package com.google.antigravity.googlepro

import com.google.antigravity.model.AgentExecutionTarget
import com.google.antigravity.model.PhoneModelRoute
import com.google.antigravity.model.phoneFallbackTarget
import com.google.antigravity.model.phoneModelRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleProRouteTest {

    @Test
    fun `subscription models route to the account, not to an API key`() {
        assertEquals(PhoneModelRoute.GOOGLE_PRO, phoneModelRoute("google-pro/gemini-3-pro-high"))
        assertEquals(PhoneModelRoute.GEMINI_API, phoneModelRoute("gemini-4-hydrogen"))
        assertEquals(PhoneModelRoute.PC_CLI, phoneModelRoute("gemini-4-argon-high"))
        assertEquals(PhoneModelRoute.OPENROUTER_API, phoneModelRoute("openrouter/anthropic/claude"))
    }

    @Test
    fun `the bridge being down only falls back when the account is signed in`() {
        val target = AgentExecutionTarget.PC_REMOTE_SESSION
        assertEquals(
            AgentExecutionTarget.PHONE_LOCAL_RESOURCES,
            phoneFallbackTarget(target, true, "google-pro/gemini-3-pro-high", "", "", googleProReady = true)
        )
        assertEquals(
            target,
            phoneFallbackTarget(target, true, "google-pro/gemini-3-pro-high", "", "", googleProReady = false)
        )
    }

    @Test
    fun `a reachable bridge is never overridden`() {
        assertEquals(
            AgentExecutionTarget.PC_REMOTE_SESSION,
            phoneFallbackTarget(AgentExecutionTarget.PC_REMOTE_SESSION, false,
                "google-pro/gemini-3-pro-high", "", "", googleProReady = true)
        )
    }

    @Test
    fun `the callback parser accepts only our path and state`() {
        val raw = "com.google.antigravity:/google-oauth/callback?state=abc&code=xyz"
        assertEquals("xyz", parseGoogleOAuthCallback(raw, "abc")?.code)
        assertNull(parseGoogleOAuthCallback(raw, "other"))
        assertNull(parseGoogleOAuthCallback("com.google.antigravity:/mcp-oauth/callback?state=abc&code=xyz", "abc"))
        assertNull(parseGoogleOAuthCallback("com.google.antigravity:/google-oauth/callback?state=abc", "abc"))
    }

    @Test
    fun `an oauth error is carried through`() {
        val raw = "com.google.antigravity:/google-oauth/callback?state=abc&error=access_denied"
        assertEquals("access_denied", parseGoogleOAuthCallback(raw, "abc")?.error)
    }
}
