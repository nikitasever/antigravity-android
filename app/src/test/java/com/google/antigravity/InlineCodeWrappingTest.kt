package com.google.antigravity

import com.google.antigravity.ui.components.inlineCodeLines
import org.junit.Assert.*
import org.junit.Test

class InlineCodeWrappingTest {
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
