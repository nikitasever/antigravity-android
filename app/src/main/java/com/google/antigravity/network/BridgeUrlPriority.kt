package com.google.antigravity.network

/** An explicitly selected bridge must precede emulator defaults and cached discoveries. */
internal fun prioritizedBridgeUrls(configured: String, localDefaults: List<String>, cached: String,
    discovered: List<String>, publicDefaults: List<String>): List<String> =
    (listOf(configured) + localDefaults + cached + discovered + publicDefaults)
        .map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
