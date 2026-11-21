package com.google.antigravity.googlepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsWireTest {

    @Test
    fun `query encodes header, qname and question`() {
        val packet = DnsWire.query(0x1234, "daily-cloudcode-pa.googleapis.com")
        assertEquals(0x12, packet[0].toInt() and 0xFF)
        assertEquals(0x34, packet[1].toInt() and 0xFF)
        assertEquals(0x01, packet[2].toInt() and 0xFF) // RD
        assertEquals(QueryShape.of(packet), QueryShape.expected("daily-cloudcode-pa.googleapis.com"))
    }

    @Test
    fun `answer addresses are read from A records`() {
        val packet = answer(0x0102, listOf(byteArrayOf(31, 25, -17, -124)))
        assertEquals(listOf("31.25.239.132"), DnsWire.answerAddresses(packet, 0x0102))
    }

    @Test
    fun `foreign transaction id and non-answer are rejected`() {
        val packet = answer(0x0102, listOf(byteArrayOf(1, 2, 3, 4)))
        assertEquals(emptyList<String>(), DnsWire.answerAddresses(packet, 0x0103))
        val queryFlags = packet.copyOf().also { it[2] = 0; it[3] = 0 }
        assertEquals(emptyList<String>(), DnsWire.answerAddresses(queryFlags, 0x0102))
    }

    @Test
    fun `truncated answer does not invent an address`() {
        val packet = answer(7, listOf(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertTrue(DnsWire.answerAddresses(packet, 7).isEmpty())
    }

    private fun answer(id: Int, rdata: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(0x81); out.write(0x80)
        out.write(0); out.write(0) // QDCOUNT
        out.write(0); out.write(rdata.size) // ANCOUNT
        out.write(0); out.write(0); out.write(0); out.write(0)
        rdata.forEach {
            out.write(0xC0); out.write(0x0C) // сжатое имя
            out.write(0); out.write(1) // A
            out.write(0); out.write(1) // IN
            out.write(0); out.write(0); out.write(0); out.write(60)
            out.write(0); out.write(it.size)
            out.write(it)
        }
        return out.toByteArray()
    }

    /** Ожидаемая форма вопроса: сравниваем без длины имени в заголовке. */
    private object QueryShape {
        fun of(packet: ByteArray): List<Int> = packet.drop(12).map { it.toInt() and 0xFF }

        fun expected(host: String): List<Int> = buildList {
            host.split('.').forEach { label ->
                add(label.length)
                addAll(label.toByteArray().map { it.toInt() and 0xFF })
            }
            add(0)
            add(0); add(1); add(0); add(1)
        }
    }
}
