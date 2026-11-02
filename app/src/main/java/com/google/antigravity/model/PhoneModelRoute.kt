package com.google.antigravity.model

enum class PhoneModelRoute { PC_CLI, GEMINI_API, OPENROUTER_API }

/** Inference location is independent of the phone tool executor. */
fun phoneModelRoute(modelId: String): PhoneModelRoute = when {
    modelId.startsWith("gemini-4-argon") || modelId.startsWith("gemini-3.8-") -> PhoneModelRoute.PC_CLI
    modelId.contains("/") && !modelId.startsWith("models/") -> PhoneModelRoute.OPENROUTER_API
    else -> PhoneModelRoute.GEMINI_API
}
