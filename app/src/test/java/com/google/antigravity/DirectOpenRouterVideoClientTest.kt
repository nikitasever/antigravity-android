package com.google.antigravity

import com.google.antigravity.model.*
import com.google.antigravity.network.DirectOpenRouterVideoClient
import com.google.antigravity.network.VideoJobFailure
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DirectOpenRouterVideoClientTest {
    private val key = "qa-secret"
    private fun sample() = VideoRun("request_A", "user_A", "chat_A", "QA", AgentExecutionTarget.PHONE_LOCAL_RESOURCES,
        VideoRun.DIRECT_API_BASE, "qa/video", "QA", "12:00",
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }, transport = VideoTransport.PHONE_API)
    private data class Call(val method: String, val path: String, val host: String, val authorization: String, val body: String)
    private data class Reply(val status: Int = 200, val body: ByteArray = byteArrayOf(), val headers: Map<String, String> = emptyMap(), val drop: Boolean = false)
    private fun json(body: String, status: Int = 200) = Reply(status, body.toByteArray())
    private val models = """{"data":[{"id":"qa/video","supported_durations":[8,4]}]}"""
    private fun mp4() = ByteArray(24).apply { this[3] = 24; "ftypisom".toByteArray().copyInto(this, 4) }

    /** Rewrites canonical HTTPS requests only inside this test transport, never via an app setting. */
    private fun fixture(reply: (Call) -> Reply, block: (DirectOpenRouterVideoClient, File, MutableList<Call>) -> Unit) {
        val directory = Files.createTempDirectory("direct-video-qa-").toFile()
        val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val calls = Collections.synchronizedList(mutableListOf<Call>())
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                while (!socket.isClosed) socket.accept().use { connection ->
                    connection.soTimeout = 5000
                    val reader = connection.getInputStream().bufferedReader()
                    val first = reader.readLine().split(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = reader.readLine() ?: error("Incomplete headers")
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    val chars = CharArray(headers["content-length"]?.toInt() ?: 0)
                    var read = 0
                    while (read < chars.size) { val count = reader.read(chars, read, chars.size - read); check(count > 0); read += count }
                    val call = Call(first[0], first[1], headers["x-qa-original-host"].orEmpty(), headers["authorization"].orEmpty(), String(chars))
                    calls.add(call)
                    val result = reply(call)
                    if (!result.drop) {
                        val outgoing = mapOf("Content-Length" to result.body.size.toString(), "Connection" to "close") + result.headers
                        val head = "HTTP/1.1 ${result.status} QA\r\n" + outgoing.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
                        connection.getOutputStream().write(head.toByteArray() + result.body)
                    }
                }
            } catch (error: Throwable) { if (!socket.isClosed) failure.set(error) }
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.isHttps && request.url.host in setOf("openrouter.ai", "cdn.qa.example"))
            val rewritten = "http://127.0.0.1:${socket.localPort}" + request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
            chain.proceed(request.newBuilder().url(rewritten).header("X-QA-Original-Host", request.url.host).build())
        }.build()
        try { block(DirectOpenRouterVideoClient(http, 0), directory, calls) }
        finally {
            socket.close(); worker.join(3000); assertFalse(worker.isAlive)
            check(directory.canonicalPath.startsWith(File(requireNotNull(System.getProperty("java.io.tmpdir"))).canonicalPath + File.separator))
            check(directory.name.startsWith("direct-video-qa-")); directory.deleteRecursively()
            failure.get()?.let { throw it }
        }
    }

    @Test fun directSubmitPersistsIdBeforePollingAndNeverLeaksKeyToCdn() {
        var polls = 0
        var accepted = false
        fixture({ call ->
            if (call.host == "cdn.qa.example") { assertEquals("", call.authorization); Reply(body = mp4()) }
            else {
                assertEquals("Bearer $key", call.authorization)
                when {
                    call.path.endsWith("/models") -> json(models)
                    call.method == "POST" -> {
                        val body = JSONObject(call.body)
                        assertEquals("qa/video", body.getString("model")); assertEquals("QA", body.getString("prompt"))
                        assertEquals(4, body.getInt("duration"))
                        json("""{"id":"provider_A","status":"pending","polling_url":"https://untrusted.invalid"}""", 202)
                    }
                    call.path.contains("/content") -> Reply(302, headers = mapOf("Location" to "https://cdn.qa.example/signed.mp4?secret=ephemeral"))
                    else -> { assertTrue(accepted); json(JSONObject().put("id", "provider_A").put("status", if (polls++ == 0) "pending" else "completed").toString()) }
                }
            }
        }) { client, directory, calls ->
            val journal = VideoRunStore(File(directory, "runs.json")); var run = sample(); journal.save(run)
            val media = runBlocking { client.execute(run, key, directory,
                onSubmitting = { run = run.copy(submissionAttempted = true); journal.save(run) },
                onAccepted = { id -> run = run.copy(providerJobId = id); journal.save(run); accepted = true }, onProgress = {}) }
            assertEquals("provider_A", VideoRunStore(File(directory, "runs.json")).load().single().providerJobId)
            assertEquals("", media.mediaUrl); assertEquals("video/mp4", media.mimeType)
            assertEquals(24L, File(media.localPath).length())
            assertFalse(File(directory, "runs.json").readText().contains(key))
            assertFalse(File(directory, "runs.json").readText().contains("ephemeral"))
            runBlocking { client.execute(run, key, directory, onSubmitting = { fail("Must not resubmit") }, onAccepted = { fail("ID already known") }, onProgress = {}) }
            assertEquals(1, calls.count { it.method == "POST" })
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
        }
    }

    @Test fun lostSubmitResponseCannotBecomeAnotherPaidRequestAfterRestart() = fixture({ call ->
        if (call.method == "POST") Reply(drop = true) else json(models)
    }) { client, directory, calls ->
        val journal = VideoRunStore(File(directory, "runs.json")); val run = sample(); journal.save(run)
        val error = assertThrows(VideoJobFailure::class.java) { runBlocking {
            client.execute(run, key, directory, onSubmitting = { journal.save(run.copy(submissionAttempted = true)) }, onAccepted = { fail("No provider ID") }, onProgress = {})
        } }
        assertEquals("unknown", error.state)
        val before = calls.size
        assertThrows(VideoJobFailure::class.java) { runBlocking {
            client.execute(journal.load().single(), key, directory, onSubmitting = { fail("Must not resubmit") }, onAccepted = {}, onProgress = {})
        } }
        assertEquals(before, calls.size); assertEquals(1, calls.count { it.method == "POST" })
    }

    @Test fun creditRejectionRedactsSecretsAndDoesNotDownload() = fixture({ call ->
        if (call.method == "POST") json("""{"error":{"message":"No credits qa-secret https://cdn.qa.example/token"}}""", 402) else json(models)
    }) { client, directory, calls ->
        val error = assertThrows(VideoJobFailure::class.java) { runBlocking {
            client.execute(sample(), key, directory, onSubmitting = {}, onAccepted = { fail("Rejected") }, onProgress = {})
        } }
        assertTrue(error.terminal); assertEquals(402, error.httpStatus)
        assertFalse(error.message!!.contains(key)); assertFalse(error.message!!.contains("https://"))
        assertEquals(1, calls.count { it.method == "POST" }); assertEquals(2, calls.size)
    }

    @Test fun invalidModelOrFailedCheckpointNeverSubmits() {
        fixture({ json("""{"data":[]}""") }) { client, directory, calls ->
            assertThrows(VideoJobFailure::class.java) { runBlocking { client.execute(sample(), key, directory, {}, {}, {}) } }
            assertEquals(0, calls.count { it.method == "POST" })
        }
        fixture({ json(models) }) { client, directory, calls ->
            assertThrows(IOException::class.java) { runBlocking { client.execute(sample(), key, directory, { throw IOException("Disk full") }, {}, {}) } }
            assertEquals(0, calls.count { it.method == "POST" })
        }
    }

    @Test fun anotherProviderIdCannotSelectContent() = fixture({ json("""{"id":"provider_B","status":"completed"}""") }) { client, directory, calls ->
        val run = sample().copy(submissionAttempted = true, providerJobId = "provider_A")
        assertThrows(IOException::class.java) { runBlocking { client.execute(run, key, directory, {}, {}, {}) } }
        assertEquals(1, calls.size); assertTrue(calls.none { it.path.contains("content") || it.method == "POST" })
    }

    @Test fun dotSegmentsCannotRebindTheProviderApiPath() {
        for (id in listOf(".", "..")) fixture({ call ->
            if (call.method == "POST") json(JSONObject().put("id", id).put("status", "completed").toString(), 202) else json(models)
        }) { client, directory, calls ->
            val error = assertThrows(VideoJobFailure::class.java) { runBlocking {
                client.execute(sample(), key, directory, {}, { fail("Invalid provider ID must not be bound") }, {})
            } }
            assertEquals("unknown", error.state)
            assertEquals(2, calls.size); assertTrue(calls.none { it.path.endsWith("/api/v1/") || it.path.contains("content") })
            assertThrows(IllegalArgumentException::class.java) { sample().copy(submissionAttempted = true, providerJobId = id) }
        }
    }

    @Test fun failedContentDoesNotReplaceAnExistingFile() {
        for (failure in listOf(Reply(body = "not a video".toByteArray()), Reply(body = mp4(), headers = mapOf("Content-Length" to "100")),
            Reply(206, body = mp4(), headers = mapOf("Content-Range" to "bytes 0-23/100")),
            Reply(302, headers = mapOf("Location" to "http://cdn.qa.example/insecure")))) {
            fixture({ call -> if (call.path.contains("content")) failure else json("""{"id":"provider_A","status":"completed"}""") }) { client, directory, calls ->
                val file = File(directory, "openrouter_video_request_A.mp4"); val original = mp4().apply { this[23] = 7 }; file.writeBytes(original)
                assertThrows(IOException::class.java) { runBlocking {
                    client.execute(sample().copy(submissionAttempted = true, providerJobId = "provider_A"), key, directory, {}, {}, {})
                } }
                assertArrayEquals(original, file.readBytes()); assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
                assertTrue(calls.none { it.method == "POST" || it.host == "cdn.qa.example" })
            }
        }
    }
}
