package com.google.antigravity.model

import com.google.antigravity.googlepro.isProModel

enum class PhoneModelRoute {
    PC_CLI,
    GEMINI_API,
    OPENROUTER_API,

    /** Подписка Google AI Pro: запрос идёт в CloudCode от имени аккаунта Google. */
    GOOGLE_PRO
}

private val CLI_GEMINI_MODEL = Regex("gemini-(4-.+|3\\.8-flash)-(xhigh|high|medium|low|fast)")

/** Inference location is independent of the phone tool executor. */
fun phoneModelRoute(modelId: String): PhoneModelRoute = when {
    isProModel(modelId) -> PhoneModelRoute.GOOGLE_PRO
    CLI_GEMINI_MODEL.matches(modelId) || modelId == "gemini-pro-orchestrator" -> PhoneModelRoute.PC_CLI
    modelId.contains("/") && !modelId.startsWith("models/") -> PhoneModelRoute.OPENROUTER_API
    else -> PhoneModelRoute.GEMINI_API
}

internal fun phoneFallbackTarget(
    target: AgentExecutionTarget,
    bridgeUnavailable: Boolean,
    modelId: String,
    geminiApiKey: String,
    openRouterApiKey: String,
    googleProReady: Boolean = false
): AgentExecutionTarget {
    if (target != AgentExecutionTarget.PC_REMOTE_SESSION || !bridgeUnavailable || modelId.isBlank()) return target
    val hasKey = when (phoneModelRoute(modelId)) {
        PhoneModelRoute.OPENROUTER_API -> openRouterApiKey.isNotBlank()
        PhoneModelRoute.GEMINI_API -> geminiApiKey.isNotBlank()
        // Подписка живёт на телефоне: мост ПК для неё не нужен вовсе.
        PhoneModelRoute.GOOGLE_PRO -> googleProReady
        PhoneModelRoute.PC_CLI -> false
    }
    return if (hasKey) AgentExecutionTarget.PHONE_LOCAL_RESOURCES else target
}
