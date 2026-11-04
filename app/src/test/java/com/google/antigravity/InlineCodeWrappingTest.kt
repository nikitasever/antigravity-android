package com.google.antigravity

import com.google.antigravity.ui.components.inlineCodeLines
import com.google.antigravity.ui.components.inlineCodeItemEnd
import org.junit.Assert.*
import org.junit.Test

class InlineCodeWrappingTest {
    @Test fun closingPunctuationStaysWithCodeWithoutChangingOriginalText() {
        val text = "/data/user/0/com.google.antigravity/files.) Next"
        val pathEnd = text.indexOf(".) Next")
        assertEquals(pathEnd + 2, inlineCodeItemEnd(text, pathEnd))
        assertEquals(pathEnd, inlineCodeItemEnd(text, pathEnd, pathEnd))
        assertEquals(6, inlineCodeItemEnd("v1.2.0 Next", 6))
        val item = text.substring(0, inlineCodeItemEnd(text, pathEnd))
        val lines = wrap(item, item.codePointCount(0, item.length) - 2)
        assertEquals(item, lines.joinToString(""))
        assertFalse(lines.last().all { it in ".)" })
    }
    @Test fun hugeTokensNeverRequireMeasuringHugeSubstrings() {
        val value = "x".repeat(10_000)
        var longestProbe = 0
        val lines = inlineCodeLines(value, 20) {
            longestProbe = maxOf(longestProbe, it.length)
            it.length
        }
        assertEquals(value, lines.joinToString(""))
        assertTrue(lines.all { it.length <= 20 })
        assertTrue("Measurement must stay near the available line width", longestProbe <= 40)
    }

    private fun wrap(value: String, width: Int) = inlineCodeLines(value, width) {
        it.codePointCount(0, it.length)
    }

    @Test fun shortTokensRemainWhole() {
        for (token in listOf("mcp_system_server.js", "Local", "v1.2.0")) {
            assertEquals(listOf(token), wrap(token, 30))
        }
    }

    @Test fun oversizedPathUsesFullLinesWithoutIsolatingDriveOrChangingCharacters() {
        val path = "C:\\very_long_directory_without_spaces\\project\\mcp_system_server.js"
        val lines = wrap(path, 14)
        assertEquals(path, lines.joinToString(""))
        assertEquals(14, lines.first().length)
        assertTrue(lines.all { it.length <= 14 })
        assertNotEquals("C:", lines.first())
        assertNotEquals("C:\\", lines.first())
    }

    @Test fun wrappingRespectsUnicodeCharacterBoundaries() {
        val value = "C:\\🧠🧠\\a\u0301a\u0301\\файл"
        val lines = wrap(value, 5)
        assertEquals(value, lines.joinToString(""))
        assertTrue(lines.none { it.lastOrNull()?.isHighSurrogate() == true })
        assertTrue(lines.none { it.startsWith('\u0301') })
    }
}
