package com.google.antigravity.model

/** Migrate only the known retired default. A user's unreachable address is still their setting. */
internal fun resolveSavedBridgeAddress(savedUrl: String?, savedVersion: Int, currentVersion: Int,
    defaultUrl: String): String = when {
    savedUrl.isNullOrBlank() -> defaultUrl
    savedVersion < currentVersion && savedUrl ==
        "https://cdd0a117b3ea350f-79-137-206-221.serveousercontent.com" -> defaultUrl
    else -> savedUrl
}
