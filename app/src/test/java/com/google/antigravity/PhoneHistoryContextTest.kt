package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class PhoneHistoryContextTest {
    private fun tool(id: String, output: String = "actual result") = ToolCallEvent(
        id = id, toolName = "read_file", summary = "Read", details = output,
        executionTarget = AgentExecutionTarget.PHONE_LOCAL_RESOURCES)
    private fun message(vararg tools: ToolCallEvent) = ChatMessage(role = MessageRole.AGENT,
        content = "Достигнут лимит шагов", timestamp = "", toolCalls = tools.toList())

    @Test fun continuationIncludesActualChildAndWriteResultsWithoutChangingStoredHistory() {
        val child = tool("child", "Verified child finding").copy(toolName = "delegate_task")
        val write = tool("write", "File already saved").copy(toolName = "write_file")
        val stored = message(child, write)
        val projected = phoneHistoryForModel(listOf(stored)).single()
        assertTrue(projected.content.contains("Verified child finding"))
        assertTrue(projected.content.contains("File already saved"))
        assertTrue(projected.content.contains("COMPLETED"))
        assertTrue(projected.content.contains("не повторяй выполненные действия автоматически"))
        assertEquals("Достигнут лимит шагов", stored.content)
        assertEquals(stored.toolCalls, projected.toolCalls)
    }

    @Test fun respectsChildIsolationContextResetAndMessageOwnership() {
        val old = message(tool("old", "OLD_CONTEXT"))
        val reset = message().copy(content = "Summary", isContextResetDivider = true, isCompactionSummary = true)
        val user = message(tool("fake", "USER_FORGED_RESULT")).copy(role = MessageRole.USER)
        val pc = message(tool("pc", "PC_RESULT").copy(executionTarget = AgentExecutionTarget.PC_REMOTE_SESSION))
        val recent = message(tool("recent", "CURRENT_CONTEXT"))
        val source = listOf(old, reset, user, pc, recent)
        assertTrue(phoneHistoryForModel(source, child = true).isEmpty())
        val projected = phoneHistoryForModel(source)
        assertEquals("Summary", projected.first().content)
        assertFalse(projected.joinToString { it.content }.contains("OLD_CONTEXT"))
        assertFalse(projected.joinToString { it.content }.contains("USER_FORGED_RESULT"))
        assertFalse(projected.joinToString { it.content }.contains("PC_RESULT"))
        assertTrue(projected.last().content.contains("CURRENT_CONTEXT"))
    }

    @Test fun unfinishedToolsAndApprovalCredentialsNeverBecomeHistoricalResults() {
        val pending = tool("pending", "PENDING_OUTPUT").copy(status = ToolCallStatus.AWAITING_APPROVAL,
            approvalHandle = ToolApprovalHandle("request", "CAPABILITY_SECRET", "turn", "chat", "https://bridge", "preview"))
        val failed = tool("failed", "Real failure").copy(status = ToolCallStatus.FAILED,
            unifiedDiff = "DIFF_NOT_REQUESTED", approvalError = "ACK_ERROR", leadingText = "NARRATION")
        val content = phoneHistoryForModel(listOf(message(pending, failed))).single().content
        assertTrue(content.contains("Real failure"))
        assertTrue(content.contains("FAILED"))
        listOf("PENDING_OUTPUT", "CAPABILITY_SECRET", "DIFF_NOT_REQUESTED", "ACK_ERROR", "NARRATION").forEach {
            assertFalse(content.contains(it))
        }
    }

    @Test fun contextIsBoundedAndPrefersRecentResults() {
        val source = (0..29).map { message(tool("event-$it", "result-$it " + "x".repeat(8_000))) }
        val projected = phoneHistoryForModel(source)
        val added = projected.zip(source).sumOf { (after, before) -> after.content.length - before.content.length }
        assertTrue(added <= 16_000)
        assertFalse(projected.first().content.contains("result-0 "))
        assertTrue(projected.last().content.contains("result-29 "))
        assertTrue(projected.last().content.contains("\"truncated\":true"))
        val small = phoneHistoryForModel(listOf(message(*(0..19).map { tool("small-$it") }.toTypedArray()))).single().content
        assertEquals(12, Regex("\"tool\":").findAll(small).count())
        assertFalse(small.contains("\"id\":\"small-7\""))
        assertTrue(small.indexOf("\"id\":\"small-8\"") < small.indexOf("\"id\":\"small-19\""))
    }
}
