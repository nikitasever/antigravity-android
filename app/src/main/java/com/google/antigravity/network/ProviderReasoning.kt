package com.google.antigravity.network

import org.json.JSONObject

/** Only provider-supplied summaries; never synthesized from elapsed time or tools. */
internal data class ProviderReply(val text: String, val thinking: String = "")

internal fun parseGeminiReply(response: JSONObject): ProviderReply {
    val parts = response.optJSONArray("candidates")?.optJSONObject(0)
        ?.optJSONObject("content")?.optJSONArray("parts")
    val text = StringBuilder()
    val thinking = StringBuilder()
    if (parts != null) for (i in 0 until parts.length()) {
        val part = parts.optJSONObject(i) ?: continue
        // thoughtSignature is an opaque protocol field, never displayable text.
        val value = part.optString("text", "")
        if (part.optBoolean("thought")) thinking.append(value) else text.append(value)
    }
    return ProviderReply(text.toString().trim(), thinking.toString().trim())
}

/** Streaming events are cumulative snapshots. Replace the current step, don't append each delta twice. */
internal class PhoneReasoningAccumulator {
    private val completed = mutableListOf<String>()
    private var current = ""
    fun beginStep() { current = "" }
    fun accept(summary: String) { if (summary.isNotBlank()) current = summary.trim() }
    fun finishStep() { if (current.isNotBlank()) completed.add(current); current = "" }
    val text: String get() = (completed + current.takeIf { it.isNotBlank() }.orEmpty())
        .filter { it.isNotBlank() }.joinToString("\n\n")
}
