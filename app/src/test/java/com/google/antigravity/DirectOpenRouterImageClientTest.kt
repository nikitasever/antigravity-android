package com.google.antigravity

import com.google.antigravity.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64

class DirectOpenRouterImageClientTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNQi1kFAAHYAS18RkYRAAAAAElFTkSuQmCC")
    private val encoded get() = Base64.getEncoder().encodeToString(png)
    private val catalog = """{"data":[{"id":"qa/image"}]}"""
    private val image get() = """{"data":[{"b64_json":"$encoded","media_type":"image/jpeg"}]}"""
    private fun fixture(reply: (Request, Int) -> Pair<Int, String>, block: suspend (DirectOpenRouterImageClient, File, List<Request>) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("direct-image-qa-").toFile()
        val calls = mutableListOf<Request>()
        val client = DirectOpenRouterImageClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); calls.add(request)
            assertEquals("openrouter.ai", request.url.host); assertTrue(request.url.isHttps)
            assertEquals("Bearer qa-key", request.header("Authorization"))
            val (code, body) = reply(request, calls.size)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("QA")
                .header("Location", "https://other.example/secret").body(body.toResponseBody()).build()
        }.build(), imageBounds = { if (it.contentEquals(png)) 1 to 1 else null })
        try { block(client, dir, calls) } finally {
            check(dir.canonicalFile.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile)
            check(dir.name.startsWith("direct-image-qa-")); dir.deleteRecursively()
        }
    }

    @Test fun dedicatedApiUsesExactModelAndAtomicallySavesActualFormatBeforeCallback() = fixture(
        { _, n -> 200 to if (n == 1) catalog else image }) { client, dir, calls ->
        var saved = false
        val media = client.execute("qa/image", "Draw", "qa-key", dir, onSaved = {
            assertTrue(File(it.localPath).readBytes().contentEquals(png)); saved = true
        })
        assertTrue(saved); assertEquals("image/png", media.mimeType); assertTrue(media.fileName.endsWith(".png"))
        assertEquals(png.size.toLong(), media.sizeBytes); assertEquals("", media.mediaUrl)
        assertEquals(media.localPath, media.localPhonePath); assertEquals(2, calls.size)
        assertEquals("/api/v1/images", calls.last().url.encodedPath)
        val body = okio.Buffer(); calls.last().body!!.writeTo(body)
        assertEquals("qa/image", JSONObject(body.readUtf8()).getString("model"))
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test fun knownChatImageModelUsesOneChatRequestWithoutPaidFallback() = fixture({ _, n ->
        200 to if (n == 1) """{"data":[]}""" else
            """{"choices":[{"message":{"images":[{"image_url":{"url":"data:image/png;base64,$encoded"}}]}}]}"""
    }) { client, dir, calls ->
        client.execute("qa/chat-image", "Draw", "qa-key", dir, knownChatImageModel = true)
        assertEquals(2, calls.size); assertEquals("/api/v1/chat/completions", calls.last().url.encodedPath)
        val body = okio.Buffer(); calls.last().body!!.writeTo(body)
        val json = JSONObject(body.readUtf8()); assertEquals("qa/chat-image", json.getString("model"))
        assertEquals("image", json.getJSONArray("modalities").getString(0))
    }

    @Test fun unverifiedModelCannotStartPaidRequest() = fixture({ _, _ -> 200 to """{"data":[]}""" }) { client, dir, calls ->
        try { client.execute("qa/unknown", "Draw", "qa-key", dir); fail("Expected failure") }
        catch (_: OpenRouterImageFailure) { }
        assertEquals(1, calls.size); assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun failedPaidPostIsNotReplayedAndNeverEchoesProviderSecrets() = fixture({ _, n ->
        if (n == 1) 200 to catalog else 402 to "qa-key private prompt"
    }) { client, dir, calls ->
        try { client.execute("qa/image", "private prompt", "qa-key", dir); fail("Expected failure") }
        catch (failure: OpenRouterImageFailure) {
            assertTrue(failure.message!!.contains("402")); assertFalse(failure.message!!.contains("private prompt"))
            assertFalse(failure.message!!.contains("qa-key"))
        }
        assertEquals(2, calls.size); assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun redirectAndPartialContentAreRejectedWithoutNewRequest() {
        for (status in listOf(307, 206, 429)) fixture({ _, n -> if (n == 1) 200 to catalog else status to image }) { client, dir, calls ->
            try { client.execute("qa/image", "Draw", "qa-key", dir); fail("Expected failure") }
            catch (_: OpenRouterImageFailure) { }
            assertEquals(2, calls.size); assertTrue(dir.listFiles()!!.isEmpty())
        }
    }

    @Test fun errorEnvelopeAndInvalidImageDoNotProduceFakeFiles() {
        for (body in listOf("""{"error":{"code":402,"message":"qa-key"}}""",
            """{"data":[{"b64_json":"YWJj"}]}""", """{"data":[{"b64_json":"@@@"}]}""", "not json")) {
            fixture({ _, n -> 200 to if (n == 1) catalog else body }) { client, dir, _ ->
                try { client.execute("qa/image", "Draw", "qa-key", dir); fail("Expected failure") }
                catch (_: OpenRouterImageFailure) { }
                assertTrue(dir.listFiles()!!.isEmpty())
            }
        }
    }

    @Test fun remoteImageUrlDoesNotReceiveCredentialOrTriggerDownload() = fixture({ _, n ->
        200 to if (n == 1) """{"data":[]}""" else
            """{"choices":[{"message":{"images":[{"image_url":{"url":"https://other.example/image.png"}}]}}]}"""
    }) { client, dir, calls ->
        try { client.execute("qa/image", "Draw", "qa-key", dir, true); fail("Expected failure") }
        catch (_: OpenRouterImageFailure) { }
        assertEquals(2, calls.size); assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun lostPostResponseDoesNotStartAnotherGeneration() = fixture({ _, n ->
        if (n == 1) 200 to catalog else throw IOException("QA disconnected")
    }) { client, dir, calls ->
        try { client.execute("qa/image", "Draw", "qa-key", dir); fail("Expected failure") }
        catch (_: IOException) { }
        assertEquals(2, calls.size); assertTrue(dir.listFiles()!!.isEmpty())
    }
}
