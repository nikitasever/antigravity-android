package com.google.antigravity.network

import java.net.URI
import java.net.URLDecoder

internal data class McpOAuthCallback(val state: String, val code: String, val error: String)

/** Accept only the pending native authorization response; never accept an arbitrary redirect. */
internal fun parseMcpOAuthCallback(raw: String, expectedState: String, expires: Long, now: Long): McpOAuthCallback? =
    runCatching {
        val uri = URI(raw)
        require(uri.scheme == "com.google.antigravity" && uri.rawAuthority == null &&
            uri.path == "/mcp-oauth/callback" && uri.fragment == null)
        require(expectedState.isNotBlank() && expires > now)
        val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        fun single(key: String): String {
            val values = params.filter { it.first == key }.map { it.second }
            require(values.size <= 1)
            return values.singleOrNull().orEmpty()
        }
        val state = single("state")
        require(state == expectedState)
        val code = single("code")
        val error = single("error")
        require((code.isNotBlank() && error.isBlank()) || (error.isNotBlank() && code.isBlank()))
        require(code.length <= 4096 && error.length <= 256)
        McpOAuthCallback(state, code, error)
    }.getOrNull()
