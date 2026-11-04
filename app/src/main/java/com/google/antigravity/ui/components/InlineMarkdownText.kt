package com.google.antigravity.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.google.antigravity.ui.design.DesignColors

/** A short code token is one layout item; oversized tokens wrap inside their own item.
 * The alternate text is the original token, so selection/accessibility keep exact paths.
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
        val ranges = text.getStringAnnotations("CODE", 0, text.length)
        val content = remember(text, codeStyle, widthPx, density, measurer) {
            ranges.associate { range ->
                val value = text.text.substring(range.start, range.end)
                val innerWidth = (widthPx - paddingPx).coerceAtLeast(1)
                val lines = inlineCodeLines(value, innerWidth) { candidate ->
                    measurer.measure(candidate, style = codeStyle, softWrap = false).size.width
                }
                val measured = measurer.measure(lines.joinToString("\n"), style = codeStyle, softWrap = false,
                    constraints = Constraints(maxWidth = innerWidth))
                val width = if (lines.size > 1) widthPx else (measured.size.width + paddingPx).coerceAtMost(widthPx)
                range.start.toString() to InlineTextContent(
                    Placeholder(with(density) { width.toSp() },
                        with(density) { (measured.size.height + verticalPx).toSp() },
                        PlaceholderVerticalAlign.TextCenter)
                ) {
                    // Drawing visual lines leaves selection/accessibility with the original alternate text.
                    Canvas(Modifier.fillMaxSize().background(DesignColors.Surface, RoundedCornerShape(4.dp))) {
                        drawText(measured, topLeft = Offset(paddingPx / 2f, verticalPx / 2f))
                    }
                }
            }
        }
        val rendered = buildAnnotatedString {
            var cursor = 0
            ranges.forEach { range ->
                append(text.subSequence(cursor, range.start))
                appendInlineContent(range.start.toString(), text.text.substring(range.start, range.end))
                cursor = range.end
            }
            append(text.subSequence(cursor, text.length))
            // Native Text links provide activation semantics for accessibility and keyboard.
            text.getStringAnnotations("URL", 0, text.length).forEach { range ->
                addLink(LinkAnnotation.Clickable(range.item,
                    linkInteractionListener = object : LinkInteractionListener {
                        override fun onClick(link: LinkAnnotation) = onUrlClick(range.item)
                    }), range.start, range.end)
            }
        }
        Text(rendered, style = style.copy(hyphens = Hyphens.None), inlineContent = content)
    }
}
