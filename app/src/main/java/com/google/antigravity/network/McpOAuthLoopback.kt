package com.google.antigravity.network

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.SocketTimeoutException

/** RFC 8252 native redirect. Bind only to this phone's IPv4 loopback, never LAN. */
internal class McpOAuthLoopback : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    val redirectUri: String = "http://127.0.0.1:${server.localPort}/mcp-oauth/callback"

    fun awaitBrowserReturn(expectedState: String) {
        val deadline = System.currentTimeMillis() + 600_000
        while (!server.isClosed && System.currentTimeMillis() < deadline) {
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                socket.soTimeout = 3000
                try {
                    val reader = socket.getInputStream()
                    val line = StringBuilder()
                    while (line.length < 8192) {
                        val next = reader.read()
                        if (next < 0 || next == 10) break
                        line.append(next.toChar())
                    }
                    val parts = line.toString().trim().split(' ')
                    val request = URI(parts.getOrElse(1) { "" })
                    val callback = "com.google.antigravity:/mcp-oauth/callback?${request.rawQuery.orEmpty()}"
                    val valid = parts.firstOrNull() == "GET" && request.rawAuthority == null &&
                        request.path == "/mcp-oauth/callback" &&
                        parseMcpOAuthCallback(callback, expectedState, deadline, System.currentTimeMillis()) != null
                    val body = if (valid) {
                        val href = callback.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
                        "<!doctype html><html lang=\"ru\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>Antigravity</title><p>Ответ авторизации получен.</p><a href=\"$href\">Вернуться в Antigravity</a></html>"
                    } else "Неверный ответ авторизации"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val status = if (valid) "200 OK" else "400 Bad Request"
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes); flush()
                    }
                    if (valid) return
                } catch (_: Exception) { /* unrelated local request cannot terminate the pending flow */ }
            }
        }
    }
    override fun close() { server.close() }
}
