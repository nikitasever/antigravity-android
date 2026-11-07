package com.google.antigravity.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun isLocalBridgeUrl(url: String): Boolean {
    val host = url.toHttpUrlOrNull()?.host ?: return false
    val parts = host.split('.').map { it.toIntOrNull() }
    return host == "localhost" || host == "::1" ||
        (parts.size == 4 && parts.all { it != null && it in 0..255 } &&
            (parts[0] == 127 || parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) ||
                (parts[0] == 172 && parts[1]!! in 16..31)))
}

/** Explicit LAN configuration works on phones too; emulator defaults never leak onto phones. */
internal fun bridgeCandidatesForDevice(configured: String, emulator: Boolean, publicOnly: Boolean,
    cached: String, discovered: List<String>, publicDefaults: List<String>): List<String> {
    fun allowed(url: String) = !publicOnly || (url.toHttpUrlOrNull()?.isHttps == true && !isLocalBridgeUrl(url))
    return prioritizedBridgeUrls(configured.takeIf(::allowed).orEmpty(),
        if (emulator && !publicOnly) listOf("http://10.0.2.2:8787", "http://127.0.0.1:8787") else emptyList(),
        cached.takeIf(::allowed).orEmpty(), discovered.filter(::allowed), publicDefaults.filter(::allowed))
}

/** An explicitly selected bridge must precede emulator defaults and cached discoveries. */
internal fun prioritizedBridgeUrls(configured: String, localDefaults: List<String>, cached: String,
    discovered: List<String>, publicDefaults: List<String>): List<String> =
    (listOf(configured) + localDefaults + cached + discovered + publicDefaults)
        .map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
