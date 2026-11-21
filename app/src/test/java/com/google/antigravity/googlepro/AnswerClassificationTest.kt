package com.google.antigravity.googlepro

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerClassificationTest {

    private val reference = listOf("172.217.20.10", "172.217.20.11")

    @Test
    fun `a different address is a substitution`() {
        assertEquals(AnswerKind.SUBSTITUTED, classifyAnswer(listOf("31.25.239.132"), reference))
    }

    @Test
    fun `the same address set is a passthrough`() {
        assertEquals(AnswerKind.PASSTHROUGH, classifyAnswer(reference.reversed(), reference))
    }

    @Test
    fun `empty provider answer is nothing`() {
        assertEquals(AnswerKind.NONE, classifyAnswer(emptyList(), reference))
    }

    @Test
    fun `a poisoned reference of only stubs cannot disprove a provider`() {
        val stubs = listOf("8.6.112.0", "8.47.69.0")
        assertEquals(AnswerKind.SUBSTITUTED, classifyAnswer(listOf("31.25.239.132"), stubs))
    }

    @Test
    fun `stub entries are dropped from both sides before comparison`() {
        // DPI-заглушка есть и у провайдера, и у эталона: после отбрасывания наборы равны.
        assertEquals(
            AnswerKind.PASSTHROUGH,
            classifyAnswer(listOf("172.217.20.10", "8.6.112.0"), listOf("172.217.20.10", "8.6.112.0"))
        )
    }

    @Test
    fun `gate hosts are recognised, other hosts are not`() {
        val resolver = RegionGateResolver()
        assertEquals(true, resolver.isGateHost("daily-cloudcode-pa.googleapis.com"))
        assertEquals(true, resolver.isGateHost("CLOUDCODE-PA.googleapis.com"))
        assertEquals(false, resolver.isGateHost("generativelanguage.googleapis.com"))
    }
}
