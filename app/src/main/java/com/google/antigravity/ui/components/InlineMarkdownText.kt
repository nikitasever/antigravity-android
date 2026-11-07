package com.google.antigravity.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.google.antigravity.ui.design.DesignColors

/** Short tokens are atomic inline items. Tall tokens own a measured block so a
 * fixed prose lineHeight cannot hide their lower lines under the next element.
 */
@Composable
internal fun InlineMarkdownText(text: AnnotatedString, style: TextStyle, onUrlClick: (String) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints {
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val paddingPx = with(density) { 6.dp.roundToPx() }
        val verticalPx = with(density) { 2.dp.roundToPx() }
        val codeStyle = style.copy(fontFamily = ChatGptMenloFontFamily,
            fontSize = style.fontSize * 0.84f, lineHeight = style.lineHeight * 0.8f,
            lineBreak = LineBreak.Simple, hyphens = Hyphens.None, color = DesignColors.TextPrimary)
        val items = remember(text, codeStyle, widthPx, density, measurer) {
            val ranges = text.getStringAnnotations("CODE", 0, text.length)
            ranges.mapIndexed { index, range ->
                val end = inlineCodeItemEnd(text.text, range.end, ranges.getOrNull(index + 1)?.start ?: text.length)
                val value = text.text.substring(range.start, end)
                val innerWidth = (widthPx - paddingPx).coerceAtLeast(1)
                val lines = inlineCodeLines(value, innerWidth) { candidate ->
                    measurer.measure(candidate, style = codeStyle, softWrap = false).size.width
                }
                val measured = measurer.measure(lines.joinToString("\n"), style = codeStyle, softWrap = false,
                    constraints = Constraints(maxWidth = innerWidth))
                CodeItem(range.start, end, value, measured, lines.size > 1)
            }
        }
        val content = items.filterNot { it.block }.associate { item ->
                val width = (item.measured.size.width + paddingPx).coerceAtMost(widthPx)
                item.start.toString() to InlineTextContent(
                    Placeholder(with(density) { width.toSp() },
                        with(density) { (item.measured.size.height + verticalPx).toSp() },
                        PlaceholderVerticalAlign.TextCenter)
                ) {
                    // Drawing visual lines leaves selection/accessibility with the original alternate text.
                    Canvas(Modifier.fillMaxSize().background(DesignColors.Surface, RoundedCornerShape(4.dp))) {
                        drawText(item.measured, topLeft = Offset(paddingPx / 2f, verticalPx / 2f))
                    }
                }
        }
        fun prose(start: Int, end: Int): AnnotatedString = buildAnnotatedString {
            var cursor = start
            items.filter { !it.block && it.start >= start && it.end <= end }.forEach { item ->
                append(text.subSequence(cursor, item.start))
                appendInlineContent(item.start.toString(), item.value)
                cursor = item.end
            }
            append(text.subSequence(cursor, end))
            // Native Text links provide activation semantics for accessibility and keyboard.
            text.getStringAnnotations("URL", start, end).forEach { range ->
                val from = maxOf(start, range.start) - start
                val to = minOf(end, range.end) - start
                if (from < to) addLink(LinkAnnotation.Clickable(range.item,
                    linkInteractionListener = object : LinkInteractionListener {
                        override fun onClick(link: LinkAnnotation) = onUrlClick(range.item)
                    }), from, to)
            }
        }
        Column(Modifier.fillMaxWidth()) {
            var cursor = 0
            items.filter { it.block }.forEach { item ->
                if (cursor < item.start) Text(prose(cursor, item.start), style = style.copy(hyphens = Hyphens.None), inlineContent = content)
                val url = text.getStringAnnotations("URL", item.start, item.end).firstOrNull()?.item
                Canvas(Modifier.fillMaxWidth().height(with(density) { (item.measured.size.height + verticalPx).toDp() })
                    .background(DesignColors.Surface, RoundedCornerShape(4.dp))
                    .then(if (url != null) Modifier.clickable { onUrlClick(url) } else Modifier)
                    .semantics { this.text = AnnotatedString(item.value) }) {
                    drawText(item.measured, topLeft = Offset(paddingPx / 2f, verticalPx / 2f))
                }
                cursor = item.end
            }
            if (cursor < text.length) Text(prose(cursor, text.length), style = style.copy(hyphens = Hyphens.None), inlineContent = content)
        }
    }
}

private data class CodeItem(val start: Int, val end: Int, val value: String, val measured: TextLayoutResult, val block: Boolean)
