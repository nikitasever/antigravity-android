package com.google.antigravity

import com.google.antigravity.network.useCancellable
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HttpCancellationTest {
    @Test fun stoppingTurnInterruptsBlockedResponseBody() = runBlocking {
        ServerSocket(0).use { server ->
            val headersSent = CountDownLatch(1)
            val releaseServer = CountDownLatch(1)
            val sender = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\npartial".toByteArray())
                        flush()
                    }
                    headersSent.countDown()
                    releaseServer.await(5, TimeUnit.SECONDS)
                }
            }
            val client = OkHttpClient()
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
            val turn = launch(Dispatchers.IO) { call.useCancellable { it.body!!.string() } }
            try {
                assertTrue(headersSent.await(3, TimeUnit.SECONDS))
                withTimeout(2000) { turn.cancelAndJoin() }
                assertTrue("Stopping must close the underlying socket", call.isCanceled())
            } finally {
                releaseServer.countDown()
                turn.cancelAndJoin()
                sender.join(1000)
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }
}
