package com.google.antigravity

import com.google.antigravity.network.OpenRouterVideoClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class OpenRouterVideoClientTest {
    private data class Reply(val status: Int, val body: String, val location: String? = null)
    private fun withBridge(reply: (String, JSONObject) -> Reply, block: (String, MutableList<String>) -> Unit) {
        val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                while (!socket.isClosed) socket.accept().use { connection ->
                    connection.soTimeout = 5000
                    val reader = connection.getInputStream().bufferedReader(Charsets.UTF_8)
                    val path = reader.readLine().split(' ')[1]
                    var length = 0
                    while (true) {
                        val header = reader.readLine() ?: error("Incomplete HTTP headers")
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    val chars = CharArray(length)
                    var read = 0
                    while (read < chars.size) { val count = reader.read(chars, read, chars.size - read); check(count > 0); read += count }
                    calls.add(path)
                    val response = reply(path, JSONObject(String(chars)))
                    val body = response.body.toByteArray(Charsets.UTF_8)
                    val headers = "HTTP/1.1 ${response.status} QA\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n" +
                        (response.location?.let { "Location: $it\r\n" } ?: "") + "\r\n"
                    connection.getOutputStream().write(headers.toByteArray(Charsets.UTF_8) + body)
                }
            } catch (error: Throwable) { if (!socket.isClosed) failure.set(error) }
        }
        try { block("http://127.0.0.1:${socket.localPort}", calls) }
        finally { socket.close(); worker.join(3000); assertFalse(worker.isAlive); failure.get()?.let { throw it } }
    }
    private fun job(status: String, id: String = "request_A") = """{"ok":true,"job":{"requestId":"$id","id":"provider_A","status":"$status"}}"""

    @Test fun oneSubmitThenPollingAndDownloadUsesStableRequestIdAndKey() {
        var polls = 0
        withBridge({ path, payload ->
            assertEquals("request_A", payload.getString("requestId"))
            assertEquals("qa-key", payload.getString("apiKey"))
            when (path.substringAfterLast('/')) {
                "generate-video" -> { assertEquals("qa/model", payload.getString("model")); Reply(202, job("pending")) }
                "video-status" -> Reply(200, job(if (polls++ == 0) "in_progress" else "completed"))
                else -> Reply(200, """{"ok":true,"media":{"mediaUrl":"/download/media/result.mp4"}}""")
            }
        }) { url, calls ->
            val result = runBlocking { OpenRouterVideoClient(OkHttpClient(), 0).execute(url, "request_A", "qa/model", "QA", "qa-key") }
            assertEquals("/download/media/result.mp4", result.getString("mediaUrl"))
            assertEquals(1, calls.count { it.endsWith("generate-video") })
            assertEquals(2, calls.count { it.endsWith("video-status") })
            assertEquals(1, calls.count { it.endsWith("video-download") })
        }
    }
    @Test fun resumeOnlyPollsAndNeverSubmits() = withBridge({ path, _ ->
        Reply(200, if (path.endsWith("video-status")) job("completed") else """{"ok":true,"media":{"mediaUrl":"/result.mp4"}}""")
    }) { url, calls ->
        runBlocking { OpenRouterVideoClient(OkHttpClient(), 0).execute(url, "request_A", "qa/model", "QA", "", resume = true) }
        assertFalse(calls.any { it.endsWith("generate-video") })
    }
    @Test fun anotherJobIdCannotTriggerContentDownload() = withBridge({ _, _ -> Reply(202, job("completed", "other")) }) { url, calls ->
        assertThrows(IllegalStateException::class.java) {
            runBlocking { OpenRouterVideoClient(OkHttpClient(), 0).execute(url, "request_A", "qa/model", "QA", "") }
        }
        assertEquals(1, calls.size)
    }
    @Test fun creditErrorsAndWaitLimitsNeverResubmit() {
        withBridge({ _, _ -> Reply(402, """{"ok":false,"error":"No credits qa-key"}""") }) { url, calls ->
            val error = assertThrows(java.io.IOException::class.java) {
                runBlocking { OpenRouterVideoClient(OkHttpClient(), 0).execute(url, "request_A", "qa/model", "QA", "qa-key") }
            }
            assertFalse(error.message!!.contains("qa-key")); assertEquals(1, calls.size)
        }
        withBridge({ _, _ -> Reply(200, job("pending")) }) { url, calls ->
            assertThrows(java.io.IOException::class.java) {
                runBlocking { OpenRouterVideoClient(OkHttpClient(), 0, 1).execute(url, "request_A", "qa/model", "QA", "") }
            }
            assertEquals(1, calls.count { it.endsWith("generate-video") })
            assertEquals(1, calls.count { it.endsWith("video-status") })
        }
    }
    @Test fun redirectsDoNotReplayAJobAtAnotherBridge() = withBridge({ _, _ -> Reply(202, job("completed")) }) { second, secondCalls ->
        withBridge({ _, _ -> Reply(307, "{}", second + "/api/openrouter/generate-video") }) { first, firstCalls ->
            assertThrows(java.io.IOException::class.java) {
                runBlocking { OpenRouterVideoClient(OkHttpClient(), 0).execute(first, "request_A", "qa/model", "QA", "") }
            }
            assertEquals(1, firstCalls.size); assertEquals(0, secondCalls.size)
        }
    }
}
