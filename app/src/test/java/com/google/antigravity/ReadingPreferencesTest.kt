package com.google.antigravity

import com.google.antigravity.model.*
import com.google.antigravity.ui.components.parseInlineMarkdown
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ReadingPreferencesTest {
    @Test fun oldAndUnknownPreferencesPreserveExistingAppearance() {
        assertEquals(ChatTextSize.NORMAL, ChatTextSize.restore(null))
        assertEquals(ChatTextSize.NORMAL, ChatTextSize.restore("obsolete-size"))
        assertEquals(ResponseFont.SYSTEM, ResponseFont.restore(null))
        assertEquals(ResponseFont.SYSTEM, ResponseFont.restore("obsolete-font"))
        assertEquals(ChatTextSize.LARGE, ChatTextSize.restore(ChatTextSize.LARGE.name))
        assertEquals(ResponseFont.SERIF, ResponseFont.restore(ResponseFont.SERIF.name))
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
