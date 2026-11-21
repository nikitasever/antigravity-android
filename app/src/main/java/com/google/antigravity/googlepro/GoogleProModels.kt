package com.google.antigravity.googlepro

import com.google.antigravity.model.AntigravityModelOption

/** Подпись раздела: модели идут по подписке, а не по ключу API. */
private const val PRO_BADGE = "Google AI Pro • подписка аккаунта"

/**
 * Состав моделей для подписочного маршрута.
 *
 * Серверный список из `loadCodeAssist` всегда важнее статического: имена на
 * стороне Google меняются чаще, чем приложение. Статический список — только
 * стартовая догадка, пока ответа нет.
 */
internal fun googleProModelOptions(serverModels: List<String> = emptyList()): List<AntigravityModelOption> {
    val merged = (serverModels + GoogleProConfig.FALLBACK_MODELS)
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith(GoogleProConfig.MODEL_PREFIX) }
        .distinct()
    return merged.map { AntigravityModelOption(GoogleProConfig.MODEL_PREFIX + it, prettyProModelName(it), PRO_BADGE) }
}

/** `google-pro/gemini-3-pro-high` → `gemini-3-pro-high` (как ждёт CloudCode). */
internal fun remoteProModel(localId: String): String =
    localId.trim().removePrefix(GoogleProConfig.MODEL_PREFIX)

internal fun isProModel(modelId: String): Boolean =
    modelId.trim().startsWith(GoogleProConfig.MODEL_PREFIX)

/** Человекочитаемое имя из серверного идентификатора. */
internal fun prettyProModelName(remote: String): String = remote.split('-').joinToString(" ") { token ->
    when (token.lowercase()) {
        "gemini" -> "Gemini"
        "pro" -> "Pro"
        "flash" -> "Flash"
        "lite" -> "Lite"
        "high" -> "(High)"
        "medium" -> "(Medium)"
        "low" -> "(Low)"
        "sonar" -> "Sonar"
        "tiered" -> "(Tiered)"
        "fast" -> "(Fast)"
        "preview" -> "preview"
        else -> token.replaceFirstChar { it.uppercase() }
    }
}
