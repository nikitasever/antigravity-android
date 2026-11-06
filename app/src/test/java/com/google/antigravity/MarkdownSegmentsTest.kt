package com.google.antigravity

import com.google.antigravity.ui.components.splitCodeAndProse
import org.junit.Assert.*
import org.junit.Test

class MarkdownSegmentsTest {
    @Test fun streamingFenceRendersCodeBeforeTheClosingFenceArrives() {
        val parts = splitCodeAndProse("Ответ\n```kotlin\nval x = 1")
        assertEquals(2, parts.size)
        assertFalse(parts[0].isCodeBlock)
        assertTrue(parts[1].isCodeBlock)
        assertEquals("kotlin", parts[1].language)
        assertEquals("val x = 1", parts[1].text)
    }
    @Test fun backticksInsideStringsDoNotCloseTheBlock() {
        val parts = splitCodeAndProse("```js\nconst text = \"```\";\n```\nГотово")
        assertEquals(2, parts.size)
        assertEquals("const text = \"```\";\n", parts[0].text)
        assertEquals("Готово", parts[1].text)
    }
    @Test fun longerFencesCanContainMarkdownFences() {
        val parts = splitCodeAndProse("````markdown\n```js\n1\n```\n````")
        assertEquals(1, parts.size)
        assertEquals("```js\n1\n```\n", parts[0].text)
    }
    @Test fun supportsTildesWindowsLineEndingsAndMultipleBlocks() {
        val parts = splitCodeAndProse("~~~sh\r\necho ok\r\n~~~\r\nТекст\r\n```json\r\n{}\r\n```\r\n")
        assertEquals(3, parts.size)
        assertEquals("echo ok\r\n", parts[0].text)
        assertEquals("Текст", parts[1].text)
        assertEquals("{}\r\n", parts[2].text)
    }
    @Test fun inlineTripleBackticksAreNotABlock() {
        val text = "Пример ``` внутри строки"
        assertEquals(text, splitCodeAndProse(text).single().text)
        assertFalse(splitCodeAndProse(text).single().isCodeBlock)
    }
}
