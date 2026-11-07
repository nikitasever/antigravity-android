package com.google.antigravity

import com.google.antigravity.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DirectOpenRouterChatClientTest {
    private fun client(code: Int, body: String, inspect: (Request) -> Unit = {}) = DirectOpenRouterChatClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("QA")
                .body(body.toResponseBody()).build()
        }.build())
    private val history get() = JSONArray().put(JSONObject().put("role", "user").put("content", "Привет"))

    @Test fun sendsExactSelectedModelAndUsesReportedUsage() = runBlocking {
        var calls = 0
        val result = client(200, """{"choices":[{"message":{"content":"Ответ","reasoning":"Summary"}}],"usage":{"completion_tokens":31}}""") {
            calls++
            assertEquals("https://openrouter.ai/api/v1/chat/completions", it.url.toString())
            assertEquals("Bearer qa-secret", it.header("Authorization"))
            val buffer = okio.Buffer(); it.body!!.writeTo(buffer)
            val payload = JSONObject(buffer.readUtf8())
            assertEquals("vendor/exact", payload.getString("model"))
            assertEquals(4096, payload.getInt("max_tokens"))
            assertFalse(payload.getBoolean("stream"))
            assertEquals("Привет", payload.getJSONArray("messages").getJSONObject(0).getString("content"))
        }.complete("vendor/exact", " qa-secret ", history)
        assertEquals(1, calls); assertEquals("Ответ", result.text); assertEquals("Summary", result.reasoning)
        assertEquals(31, result.outputTokens)
    }

    @Test fun reportsBillingAuthenticationLimitsAndUnavailableModelWithoutEchoingSecrets() = runBlocking {
        for (status in listOf(400, 401, 402, 403, 404, 429, 502, 503)) {
            var calls = 0
            try {
                client(status, """{"error":{"message":"qa-secret private prompt"}}""") { calls++ }
                    .complete("qa/model", "qa-secret", history)
                fail("Expected failure $status")
            } catch (failure: OpenRouterChatFailure) {
                assertEquals(status, failure.status); assertTrue(failure.message!!.contains(status.toString()))
                assertFalse(failure.message!!.contains("qa-secret")); assertFalse(failure.message!!.contains("private prompt"))
            }
            assertEquals(1, calls)
        }
    }

    @Test fun handlesErrorEnvelopeOnHttp200AndEmptyReasoningOnlyReply() = runBlocking {
        for (body in listOf("""{"error":{"code":402,"message":"private"}}""",
            """{"choices":[{"finish_reason":"length","message":{"content":null,"reasoning":"thinking"}}]}""", "not JSON")) {
            try { client(200, body).complete("qa/model", "qa-key", history); fail("Expected failure") }
            catch (failure: OpenRouterChatFailure) { assertFalse(failure.message!!.contains("private")) }
        }
    }

    @Test fun acceptsTextContentPartsWithoutInventingUsage() = runBlocking {
        val result = client(200, """{"choices":[{"message":{"content":[{"type":"text","text":"A"},{"type":"image_url","image_url":{}},{"type":"text","text":"B"}]}}]}""")
            .complete("qa/model", "qa-key", history)
        assertEquals("AB", result.text); assertEquals(0, result.outputTokens)
    }

    @Test fun rejectsRedirectWithoutFollowingOrReplaying() = runBlocking {
        var calls = 0
        try {
            client(307, "") { calls++ }.complete("qa/model", "qa-key", history); fail("Expected failure")
        } catch (failure: OpenRouterChatFailure) { assertEquals(307, failure.status) }
        assertEquals(1, calls)
    }

    @Test fun missingModelAndKeyDoNotSendAnyRequest() = runBlocking {
        var calls = 0
        for ((model, key) in listOf("" to "qa", "qa/model" to "")) {
            try { client(200, "") { calls++ }.complete(model, key, history); fail("Expected failure") }
            catch (_: OpenRouterChatFailure) { }
        }
        assertEquals(0, calls)
    }

    @Test fun openRouterIsAlsoARealProviderPrefixAndMustNotBeStripped() = runBlocking {
        client(200, """{"choices":[{"message":{"content":"OK"}}]}""") {
            val body = okio.Buffer(); it.body!!.writeTo(body)
            assertEquals("openrouter/auto", JSONObject(body.readUtf8()).getString("model"))
        }.complete("openrouter/auto", "qa-key", history)
        Unit
    }
}
