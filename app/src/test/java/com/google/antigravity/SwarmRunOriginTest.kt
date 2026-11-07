package com.google.antigravity

import com.google.antigravity.model.*
import com.google.antigravity.network.scopedSwarmCall
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class SwarmRunOriginTest {
    private val origin = SwarmRunOrigin("run-A", "chat-A", "https://bridge-A.example")

    @Test fun pendingCancellationStaysWithItsOriginalLaunchAfterANewerOneIsCreated() {
        val next = origin.copy(requestId = "run-B", conversationId = "chat-B", bridgeUrl = "https://bridge-B.example")
        origin.requestCancellation()
        origin.requestCancellation()
        assertTrue(origin.cancellationRequested)
        assertFalse(next.cancellationRequested)
        assertFalse(origin.isCurrent(next))
        assertEquals("run-A", origin.requestId)
        assertEquals("https://bridge-A.example", origin.bridgeUrl)
    }

    @Test fun notificationIdIsRequiredAndOldActionCannotCancelNewBatch() {
        assertTrue(origin.matchesCancellation("run-A"))
        assertFalse(origin.matchesCancellation(""))
        assertFalse(origin.matchesCancellation("run-B"))
        assertFalse(origin.copy(requestId = "run-B").matchesCancellation("run-A"))
    }
    @Test fun oldResponsesCannotOwnANewerRunEvenWithTheSameRoutingValues() {
        assertTrue(origin.isCurrent(origin))
        assertFalse(origin.isCurrent(null))
        assertFalse(origin.isCurrent(origin.copy()))
        assertFalse(origin.isCurrent(origin.copy(requestId = "run-B", conversationId = "chat-B")))
    }
    @Test fun snapshotBatchMustMatchButLegacyMissingIdIsCompatible() {
        assertTrue(origin.accepts(SwarmSnapshot(batchId = "run-A")))
        assertTrue(origin.accepts(SwarmSnapshot()))
        assertFalse(origin.accepts(SwarmSnapshot(batchId = "run-B")))
    }
    @Test fun incompleteOriginsCannotProduceUnscopedCancellation() {
        listOf(origin.copyValues("", "chat", "https://a.example"),
            origin.copyValues("run", "", "https://a.example"),
            origin.copyValues("run", "chat", " ")).forEach { make ->
            assertThrows(IllegalArgumentException::class.java) { make() }
        }
    }
    private fun SwarmRunOrigin.copyValues(id: String, chat: String, url: String): () -> SwarmRunOrigin =
        { SwarmRunOrigin(id, chat, url) }

    @Test fun pollingDoesNotFollowRedirectToAnotherBridge() = verifyRedirect(false, 302)
    @Test fun cancellationPostIsNotReplayedAtAnotherBridge() = verifyRedirect(true, 307)

    private fun verifyRedirect(post: Boolean, code: Int) {
        val firstHits = AtomicInteger()
        val secondHits = AtomicInteger()
        val seenRequest = AtomicReference("")
        val seenBody = AtomicReference("")
        val failure = AtomicReference<Throwable?>()
        val first = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val second = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        fun serve(socket: ServerSocket, isFirst: Boolean) = thread(isDaemon = true) {
            try {
                socket.soTimeout = 4000
                socket.accept().use { connection ->
                    connection.soTimeout = 3000
                    val reader = connection.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = reader.readLine()
                    var length = 0
                    while (true) {
                        val header = reader.readLine() ?: error("Incomplete HTTP headers")
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val count = reader.read(body, read, length - read)
                        require(count > 0)
                        read += count
                    }
                    if (isFirst) {
                        firstHits.incrementAndGet(); seenRequest.set(requestLine); seenBody.set(String(body))
                    } else secondHits.incrementAndGet()
                    val location = if (isFirst) "Location: http://127.0.0.1:${second.localPort}/api/agents/swarm\r\n" else ""
                    connection.getOutputStream().write(("HTTP/1.1 ${if (isFirst) code else 200} QA\r\n" +
                        location + "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
                }
            } catch (error: Throwable) { if (!socket.isClosed) failure.set(error) }
        }
        val firstThread = serve(first, true)
        val secondThread = serve(second, false)
        try {
            val payload = "{\"action\":\"cancel_task\",\"requestId\":\"run-A\"}"
            val builder = Request.Builder().url("http://127.0.0.1:${first.localPort}/api/agents/swarm?requestId=run-A")
            if (post) builder.post(payload.toRequestBody())
            scopedSwarmCall(OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(3)).build(), builder.build())
                .execute().use { assertEquals(code, it.code) }
            assertEquals(1, firstHits.get())
            assertEquals(0, secondHits.get())
            assertEquals("${if (post) "POST" else "GET"} /api/agents/swarm?requestId=run-A HTTP/1.1", seenRequest.get())
            assertEquals(if (post) payload else "", seenBody.get())
        } finally {
            first.close(); second.close()
            firstThread.join(3500); secondThread.join(3500)
        }
        failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
        assertFalse(firstThread.isAlive || secondThread.isAlive)
    }
}
