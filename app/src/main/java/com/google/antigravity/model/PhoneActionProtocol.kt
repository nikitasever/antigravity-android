package com.google.antigravity.model

/** Ordinary JSON examples in an answer are content, never executable tools. */
fun processPhoneActionBlocks(text: String, execute: (String) -> Boolean): String =
    Regex("```phone_action\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).replace(text) { match ->
        if (execute(match.groupValues[1].trim())) "" else match.value
    }.trim()
