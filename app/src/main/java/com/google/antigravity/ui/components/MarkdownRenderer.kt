package com.google.antigravity.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.R

private val ChatGptMenloFontFamily = FontFamily(
    Font(R.font.menlo_regular, FontWeight.Normal),
    Font(R.font.menlo_bold, FontWeight.Bold)
)

fun openClickableLink(
    context: Context,
    clipboard: ClipboardManager,
    rawUrl: String
) {
    val cleanUrl = rawUrl.trim().trimEnd('.', ',', ';', ')')
    if (cleanUrl.isBlank()) return
    clipboard.setText(AnnotatedString(cleanUrl))
    try {
        if (cleanUrl.startsWith("http://", ignoreCase = true) || cleanUrl.startsWith("https://", ignoreCase = true)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } else if (cleanUrl.startsWith("file://", ignoreCase = true)) {
            Toast.makeText(
                context,
                "Локальная ссылка скопирована: ${cleanUrl.substringAfterLast('/')}",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(context, "Ссылка скопирована: $cleanUrl", Toast.LENGTH_SHORT).show()
        }
    } catch (_: Throwable) {
        Toast.makeText(context, "Ссылка скопирована в буфер: $cleanUrl", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Полноценный рендерер Markdown 1:1 в стиле ChatGPT Android 1.2026.293 и Claude Code:
 * - Все ссылки `[label](url)` и прямые URL `https://...`, `http://...`, `file:///...`
 *   подсвечиваются синим цветом (`#58A6FF`), подчёркиваются и открываются по нажатию
 * - Блоки кода со шрифтом Menlo, шапкой языка, кнопками «Копировать код» и «Скачать»
 * - Подсветка diff-строк (`+` зелёный, `-` красный)
 * - Таблицы Markdown (`| col1 | col2 |`)
 */
@Composable
fun ChatGptMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xFFECECEC),
    onSaveCodeToPhone: ((suggestedFileName: String, code: String) -> Unit)? = null,
    onUrlClick: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val segments = remember(markdown) { splitCodeAndProse(markdown) }

    Column(modifier = modifier) {
        segments.forEachIndexed { idx, seg ->
            if (seg.isCodeBlock) {
                var copied by remember { mutableStateOf(false) }
                var saved by remember { mutableStateOf(false) }
                val langLabel = seg.language.ifBlank { "code" }
                val ext = when (langLabel.lowercase()) {
                    "kotlin", "kt" -> "kt"
                    "python", "py" -> "py"
                    "javascript", "js" -> "js"
                    "typescript", "ts" -> "ts"
                    "json" -> "json"
                    "markdown", "md" -> "md"
                    "html" -> "html"
                    "sh", "bash", "shell" -> "sh"
                    "diff", "patch" -> "diff"
                    else -> "txt"
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0D0D0D))
                        .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A1A))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = langLabel,
                            color = Color(0xFF9E9E9E),
                            fontSize = 11.sp,
                            fontFamily = ChatGptMenloFontFamily
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (copied) "✓ Скопировано" else "Копировать код",
                                color = if (copied) Color(0xFF10A37F) else Color(0xFFB4B4B4),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        clipboard.setText(AnnotatedString(seg.text.trimEnd()))
                                        copied = true
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            if (onSaveCodeToPhone != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (saved) "✓ Сохранено" else "Скачать",
                                    color = if (saved) Color(0xFF10A37F) else Color(0xFFB4B4B4),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            onSaveCodeToPhone("snippet_${System.currentTimeMillis() / 1000}.$ext", seg.text.trimEnd())
                                            saved = true
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    val codeAnnotated = remember(seg.text, seg.language) {
                        buildCodeBlockAnnotatedString(seg.text.trimEnd(), seg.language)
                    }

                    Text(
                        text = codeAnnotated,
                        color = Color(0xFFE0E0E0),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = ChatGptMenloFontFamily,
                        softWrap = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
            } else {
                val annotated = remember(seg.text, textColor) {
                    parseInlineMarkdown(seg.text, textColor)
                }
                @Suppress("DEPRECATION")
                ClickableText(
                    text = annotated,
                    style = TextStyle(
                        color = textColor,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    onClick = { offset ->
                        annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                            .firstOrNull()
                            ?.let { ann ->
                                if (onUrlClick != null) {
                                    onUrlClick(ann.item)
                                } else {
                                    openClickableLink(context, clipboard, ann.item)
                                }
                            }
                    }
                )
            }
            if (idx < segments.lastIndex) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

private fun buildCodeBlockAnnotatedString(code: String, lang: String): AnnotatedString {
    val isDiff = lang.equals("diff", ignoreCase = true) || code.startsWith("--- ")
    if (!isDiff) return AnnotatedString(code)
    return buildAnnotatedString {
        val lines = code.lines()
        lines.forEachIndexed { idx, line ->
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF4ADE80)
                line.startsWith("-") && !line.startsWith("---") -> Color(0xFFF87171)
                line.startsWith("@@") -> Color(0xFF60A5FA)
                else -> Color(0xFFE0E0E0)
            }
            withStyle(SpanStyle(color = color)) {
                append(line)
            }
            if (idx < lines.lastIndex) append("\n")
        }
    }
}

private data class MarkdownSegment(
    val isCodeBlock: Boolean,
    val language: String,
    val text: String
)

private fun splitCodeAndProse(raw: String): List<MarkdownSegment> {
    val result = mutableListOf<MarkdownSegment>()
    val regex = Regex("```([a-zA-Z0-9_-]*)\\n?([\\s\\S]*?)```")
    var lastIndex = 0
    for (match in regex.findAll(raw)) {
        if (match.range.first > lastIndex) {
            val prose = raw.substring(lastIndex, match.range.first).trim('\n')
            if (prose.isNotBlank()) {
                result.add(MarkdownSegment(false, "", prose))
            }
        }
        result.add(
            MarkdownSegment(
                isCodeBlock = true,
                language = match.groupValues[1].trim(),
                text = match.groupValues[2]
            )
        )
        lastIndex = match.range.last + 1
    }
    if (lastIndex < raw.length) {
        val tail = raw.substring(lastIndex).trim('\n')
        if (tail.isNotBlank()) {
            result.add(MarkdownSegment(false, "", tail))
        }
    }
    if (result.isEmpty()) {
        result.add(MarkdownSegment(false, "", raw))
    }
    return result
}

fun parseInlineMarkdown(text: String, baseColor: Color): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.lines()
        lines.forEachIndexed { lineIdx, rawLine ->
            val trimmed = rawLine.trimStart()
            val lineToParse = when {
                trimmed.startsWith("##### ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = Color.White)) {
                        appendInlineFormatted(trimmed.removePrefix("##### "))
                    }
                    null
                }
                trimmed.startsWith("#### ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.5.sp, color = Color.White)) {
                        appendInlineFormatted(trimmed.removePrefix("#### "))
                    }
                    null
                }
                trimmed.startsWith("### ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)) {
                        appendInlineFormatted(trimmed.removePrefix("### "))
                    }
                    null
                }
                trimmed.startsWith("## ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Color.White)) {
                        appendInlineFormatted(trimmed.removePrefix("## "))
                    }
                    null
                }
                trimmed.startsWith("# ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Color.White)) {
                        appendInlineFormatted(trimmed.removePrefix("# "))
                    }
                    null
                }
                trimmed == "---" || trimmed == "***" -> {
                    withStyle(SpanStyle(color = Color(0xFF333333), fontFamily = ChatGptMenloFontFamily)) {
                        append("────────────────────────────────")
                    }
                    null
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    append("• ")
                    trimmed.substring(2)
                }
                trimmed.startsWith("|") && trimmed.endsWith("|") -> {
                    val cells = trimmed.trim('|').split("|").map { it.trim() }
                    if (cells.all { it.all { ch -> ch == '-' || ch == ':' || ch == ' ' } }) {
                        withStyle(SpanStyle(color = Color(0xFF383838), fontFamily = ChatGptMenloFontFamily)) {
                            append("────────────────────────────────")
                        }
                    } else {
                        withStyle(SpanStyle(fontSize = 13.5.sp, color = Color(0xFFE5E5E5))) {
                            cells.forEachIndexed { cellIdx, cellText ->
                                appendInlineFormatted(cellText)
                                if (cellIdx < cells.lastIndex) {
                                    withStyle(SpanStyle(color = Color(0xFF555555))) {
                                        append("  •  ")
                                    }
                                }
                            }
                        }
                    }
                    null
                }
                else -> rawLine
            }

            if (lineToParse != null) {
                withStyle(SpanStyle(color = baseColor)) {
                    appendInlineFormatted(lineToParse)
                }
            }

            if (lineIdx < lines.lastIndex) {
                append("\n")
            }
        }
    }
}

private val LinkHighlightStyle = SpanStyle(
    color = Color(0xFF58A6FF),
    fontWeight = FontWeight.SemiBold,
    textDecoration = TextDecoration.Underline
)

private fun AnnotatedString.Builder.appendInlineFormatted(input: String) {
    // 1. [label](url)
    // 2. `(https://...|http://...|file:///...)`
    // 3. Bare https://..., http://..., file:///...
    // 4. **bold**, `code`, *italic*
    val tokenRegex = Regex(
        "(\\[[^\\]]+\\]\\([^)\\s]+\\)|`(?:https?://|file:///)[^`]+`|(?:https?://|file:///)[^\\s)\\]`\"<>]+|\\*\\*[^*]+\\*\\*|`[^`]+`|\\*[^*]+\\*)"
    )
    var cursor = 0
    for (m in tokenRegex.findAll(input)) {
        if (m.range.first > cursor) {
            append(input.substring(cursor, m.range.first))
        }
        val token = m.value
        when {
            token.startsWith("[") && token.contains("](") && token.endsWith(")") -> {
                val label = token.substringAfter("[").substringBefore("](").trim('`').ifBlank { "Ссылка" }
                val rawUrl = token.substringAfter("](").removeSuffix(")").trim()
                pushStringAnnotation(tag = "URL", annotation = rawUrl)
                withStyle(LinkHighlightStyle) {
                    append(label)
                }
                pop()
            }
            token.startsWith("`") && token.endsWith("`") &&
                (token.startsWith("`http://", ignoreCase = true) ||
                    token.startsWith("`https://", ignoreCase = true) ||
                    token.startsWith("`file:///", ignoreCase = true)) -> {
                val rawUrl = token.substring(1, token.length - 1).trim()
                pushStringAnnotation(tag = "URL", annotation = rawUrl)
                withStyle(LinkHighlightStyle.copy(fontFamily = ChatGptMenloFontFamily)) {
                    append(rawUrl)
                }
                pop()
            }
            token.startsWith("http://", ignoreCase = true) ||
                token.startsWith("https://", ignoreCase = true) ||
                token.startsWith("file:///", ignoreCase = true) -> {
                val cleanUrl = token.trimEnd('.', ',', ';')
                val trailingPunct = token.substring(cleanUrl.length)
                pushStringAnnotation(tag = "URL", annotation = cleanUrl)
                withStyle(LinkHighlightStyle) {
                    append(cleanUrl)
                }
                pop()
                if (trailingPunct.isNotEmpty()) {
                    append(trailingPunct)
                }
            }
            token.startsWith("**") && token.endsWith("**") && token.length > 4 -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
                    append(token.substring(2, token.length - 2))
                }
            }
            token.startsWith("`") && token.endsWith("`") && token.length > 2 -> {
                withStyle(
                    SpanStyle(
                        fontFamily = ChatGptMenloFontFamily,
                        background = Color(0xFF262626),
                        color = Color(0xFF93C5FD)
                    )
                ) {
                    append(" ${token.substring(1, token.length - 1)} ")
                }
            }
            token.startsWith("*") && token.endsWith("*") && token.length > 2 -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(token.substring(1, token.length - 1))
                }
            }
            else -> append(token)
        }
        cursor = m.range.last + 1
    }
    if (cursor < input.length) {
        append(input.substring(cursor))
    }
}


