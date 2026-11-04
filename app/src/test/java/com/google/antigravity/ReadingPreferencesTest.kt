package com.google.antigravity

import com.google.antigravity.model.*
import com.google.antigravity.ui.components.parseInlineMarkdown
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ReadingPreferencesTest {
    @Test fun inlineCodeRetainsExactTokensAndLinkOffsets() {
        val tokens = listOf("mcp_system_server.js", "Local", "v1.2.0", "C:\\Users\\name\\file")
        val markdown = tokens.joinToString(" и ") { "`$it`" } + " [документ](https://example.com)"
        val parsed = parseInlineMarkdown(markdown, Color.White)
        val code = parsed.getStringAnnotations("CODE", 0, parsed.length)
        assertEquals(tokens, code.map { parsed.text.substring(it.start, it.end) })
        assertEquals(tokens.joinToString(" и ") + " документ", parsed.text)
        val link = parsed.getStringAnnotations("URL", 0, parsed.length).single()
        assertEquals("документ", parsed.text.substring(link.start, link.end))
        assertEquals("https://example.com", link.item)
    }
    @Test fun newPreferencesUseSerifAndExplicitChoicesArePreserved() {
        assertEquals(ChatTextSize.NORMAL, ChatTextSize.restore(null))
        assertEquals(ChatTextSize.NORMAL, ChatTextSize.restore("obsolete-size"))
        assertEquals(ResponseFont.SERIF, ResponseFont.restore(null))
        assertEquals(ResponseFont.SERIF, ResponseFont.restore("obsolete-font"))
        assertEquals(ChatTextSize.LARGE, ChatTextSize.restore(ChatTextSize.LARGE.name))
        assertEquals(ResponseFont.SERIF, ResponseFont.restore(ResponseFont.SERIF.name))
        assertEquals(ResponseFont.SYSTEM, ResponseFont.restore(ResponseFont.SYSTEM.name))
    }
    @Test fun enlargedHeadingsRetainTextAndClickableLinkRanges() {
        val text = "## Заголовок\nОткрой [документ](https://example.com/doc) и прочитай **ответ**."
        val normal = parseInlineMarkdown(text, Color.White)
        val large = parseInlineMarkdown(text, Color.White, ChatTextSize.LARGE.scale)
        assertEquals(normal.text, large.text)
        assertEquals(normal.getStringAnnotations("URL", 0, normal.length),
            large.getStringAnnotations("URL", 0, large.length))
        assertTrue(large.getStringAnnotations("URL", 0, large.length).isNotEmpty())
        assertTrue(large.spanStyles.first().item.fontSize.value > normal.spanStyles.first().item.fontSize.value)
    }
}
