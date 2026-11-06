package com.google.antigravity

import com.google.antigravity.device.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Reader
import java.io.StringReader
import java.nio.file.Files
import com.google.antigravity.model.*
import kotlinx.coroutines.runBlocking

class PhoneTextPageTest {
    @Test fun actualPagesReachAgentAndNextCursorDoesNotRepeatTheFirstPage() = runBlocking {
        val text = "PAGE_A " + "a".repeat(2993) + "PAGE_B " + "b".repeat(2993)
        var rounds = 0
        val offsets = mutableListOf<Long>()
        val engine = PhoneAgentEngine(model = { prompt, _ ->
            when (rounds++) {
                0 -> "```phone_action {\"action\":\"read_file\",\"path\":\"qa.txt\"} ```"
                1 -> {
                    assertTrue(prompt.contains("PAGE_A"))
                    assertTrue(prompt.contains("\"nextOffset\":3000"))
                    "```phone_action {\"action\":\"read_file\",\"path\":\"qa.txt\",\"offset\":3000} ```"
                }
                else -> {
                    assertTrue(prompt.contains("PAGE_B"))
                    assertTrue(prompt.contains("\"hasMore\":false"))
                    "Both pages received"
                }
            }
        }, execute = { obj ->
            val range = PhoneTextReadRange.fromJson(obj)
            offsets.add(range.offset)
            val page = readPhoneTextPage(StringReader(text), range.offset, range.limit)
            PhoneToolOutput(true, "Read page", page.toolOutput())
        }, publish = {})
        val reply = engine.run("Read both pages")
        assertTrue(reply.completed)
        assertEquals(listOf(0L, 3000L), offsets)
        assertEquals(2, reply.tools.size)
    }

    @Test fun consecutivePagesReconstructTextWithoutSkippingCharacters() {
        val text = "А".repeat(3000) + "Б".repeat(3000) + "tail"
        var offset = 0L
        val result = StringBuilder()
        do {
            val page = StringReader(text).use { readPhoneTextPage(it, offset, 3000) }
            result.append(page.text)
            assertTrue(page.toolOutput().length < 4000)
            assertEquals(offset + page.text.length, page.nextOffset)
            offset = page.nextOffset
        } while (page.hasMore)
        assertEquals(text, result.toString())
        val eof = readPhoneTextPage(StringReader(text), offset, 3000)
        assertEquals("", eof.text)
        assertFalse(eof.hasMore)
        assertEquals(offset, eof.nextOffset)
    }

    @Test fun emojiIsNeverSplitAndCursorUsesUtf16Units() {
        val text = "A😀Б"
        val first = readPhoneTextPage(StringReader(text), 0, 2)
        assertEquals("A", first.text)
        assertEquals(1L, first.nextOffset)
        val second = readPhoneTextPage(StringReader(text), first.nextOffset, 2)
        assertEquals("😀", second.text)
        assertEquals(3L, second.nextOffset)
        val last = readPhoneTextPage(StringReader(text), second.nextOffset, 2)
        assertEquals("Б", last.text)
        assertFalse(last.hasMore)
        assertThrows(IllegalArgumentException::class.java) { readPhoneTextPage(StringReader(text), 2, 2) }
    }

    @Test fun rangeRejectsFractionalNullNegativeAndOversizedInputs() {
        assertEquals(PhoneTextReadRange(), PhoneTextReadRange.fromJson(JSONObject()))
        assertEquals(PhoneTextReadRange(3000, 200), PhoneTextReadRange.fromJson(JSONObject("{offset:3000,limit:200}")))
        listOf("{offset:-1}", "{offset:1.5}", "{offset:null}", "{limit:1}", "{limit:3001}", "{limit:true}",
            "{limit:4294967298}").forEach { json ->
            assertThrows(json, IllegalArgumentException::class.java) { PhoneTextReadRange.fromJson(JSONObject(json)) }
        }
        assertThrows(IllegalArgumentException::class.java) { readPhoneTextPage(StringReader("abc"), 4, 2) }
    }

    @Test fun emptyFileAndExactPageBoundaryHaveTruthfulEndState() {
        assertFalse(readPhoneTextPage(StringReader(""), 0, 3000).hasMore)
        val exact = readPhoneTextPage(StringReader("x".repeat(3000)), 0, 3000)
        assertEquals(3000L, exact.nextOffset)
        assertFalse(exact.hasMore)
        assertTrue(readPhoneTextPage(StringReader("x".repeat(3001)), 0, 3000).hasMore)
    }

    @Test fun actualUtf8FileCanBeReadBeyondTheOld15000CharacterLimit() {
        val file = Files.createTempFile("phone-page-", ".txt").toFile()
        try {
            file.writeText("я".repeat(18000) + "ACTUAL_TAIL", Charsets.UTF_8)
            val page = file.bufferedReader().use { readPhoneTextPage(it, 18000, 3000) }
            assertEquals("ACTUAL_TAIL", page.text)
            assertFalse(page.hasMore)
            val header = JSONObject(page.toolOutput().substringBefore('\n'))
            assertEquals(18011L, header.getLong("nextOffset"))
            assertEquals("UTF-16", header.getString("unit"))
        } finally { file.delete() }
    }

    @Test fun shortReaderReadsAndZeroLengthChunksDoNotSkipData() {
        val reader = object : Reader() {
            private var index = 0
            private var zero = true
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                assertTrue(len <= 4096)
                if (zero) { zero = false; return 0 }
                if (index == 10000) return -1
                cbuf[off] = ('a'.code + index++ % 26).toChar()
                return 1
            }
            override fun close() {}
        }
        val page = readPhoneTextPage(reader, 9000, 3000)
        assertEquals(1000, page.text.length)
        assertEquals(10000L, page.nextOffset)
        assertFalse(page.hasMore)
        assertEquals(('a'.code + 9000 % 26).toChar(), page.text.first())
    }
}
