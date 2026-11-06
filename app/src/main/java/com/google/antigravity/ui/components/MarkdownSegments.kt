package com.google.antigravity.ui.components

internal data class MarkdownSegment(val isCodeBlock: Boolean, val language: String, val text: String)

/** Parse fenced blocks even before the provider streams a closing fence.
 * A closing fence must be on its own line; backticks inside source are data.
 */
internal fun splitCodeAndProse(raw: String): List<MarkdownSegment> {
    val result = mutableListOf<MarkdownSegment>()
    val opening = Regex("(?m)^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)(?:\\r?\\n|$)")
    var cursor = 0
    fun prose(value: String) {
        if (value.isNotBlank()) result += MarkdownSegment(false, "", value.trim('\r', '\n'))
    }
    while (cursor < raw.length) {
        val start = opening.find(raw, cursor) ?: break
        prose(raw.substring(cursor, start.range.first))
        val fence = start.groupValues[1]
        val bodyStart = start.range.last + 1
        val closing = Regex("(?m)^ {0,3}${Regex.escape(fence.first().toString())}{${fence.length},}[ \\t]*(?:\\r?\\n|$)")
            .find(raw, bodyStart)
        val language = start.groupValues[2].trim().substringBefore(' ')
        result += MarkdownSegment(true, language,
            raw.substring(bodyStart, closing?.range?.first ?: raw.length))
        cursor = closing?.range?.last?.plus(1) ?: raw.length
    }
    prose(raw.substring(cursor))
    return result.ifEmpty { listOf(MarkdownSegment(false, "", raw)) }
}
