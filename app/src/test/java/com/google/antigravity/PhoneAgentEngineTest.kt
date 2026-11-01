package com.google.antigravity

import com.google.antigravity.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PhoneAgentEngineTest {
    @Test fun childUsesItsOwnTaskAndReturnsRealFindingsToLeader() = runBlocking {
        var rootCalls = 0
        var childCalls = 0
        val snapshots = mutableListOf<List<ToolCallEvent>>()
        val engine = PhoneAgentEngine(model = { prompt, child ->
            if (child) {
                assertTrue(prompt.contains("Inspect notes"))
                assertFalse(prompt.contains("PRIVATE_PARENT_CONTEXT"))
                if (childCalls++ == 0) "```phone_action {\"action\":\"read_file\",\"path\":\"notes.txt\"} ```"
                else { assertTrue(prompt.contains("actual file contents")); "Found actual facts" }
            } else {
                if (rootCalls++ == 0) "```phone_action {\"action\":\"delegate_task\",\"name\":\"Check notes\",\"task\":\"Inspect notes\"} ```"
                else { assertTrue(prompt.contains("Found actual facts")); "Final answer using verified facts" }
            }
        }, execute = { obj ->
            assertEquals("read_file", obj.getString("action"))
            PhoneToolOutput(true, "Read", "actual file contents")
        }, publish = { snapshots.add(it) })
        val reply = engine.run("PRIVATE_PARENT_CONTEXT")
        assertEquals("Final answer using verified facts", reply.text)
        assertEquals(2, reply.tools.size)
        assertTrue(snapshots.first().single().status == ToolCallStatus.RUNNING)
        val task = reply.tools.first { it.toolName == "delegate_task" }
        val read = reply.tools.first { it.toolName == "read_file" }
        assertEquals(task.id, read.parentTaskId)
        assertTrue(reply.tools.all { it.status == ToolCallStatus.COMPLETED && it.executionTarget == AgentExecutionTarget.PHONE_LOCAL_RESOURCES })
    }

    @Test fun childrenCannotWriteOrDelegateAgain() = runBlocking {
        var roots = 0
        var children = 0
        var executed = 0
        val engine = PhoneAgentEngine(model = { _, child ->
            if (!child) if (roots++ == 0) "```phone_action {\"action\":\"delegate_task\",\"task\":\"review\"} ```" else "done"
            else when (children++) {
                0 -> "```phone_action {\"action\":\"write_file\",\"path\":\"x\",\"content\":\"x\"} ```"
                1 -> "```phone_action {\"action\":\"delegate_task\",\"task\":\"nested\"} ```"
                else -> "read-only review"
            }
        }, execute = { executed++; PhoneToolOutput(true, "bad", "bad") }, publish = {})
        val result = engine.run("review")
        assertEquals(0, executed)
        assertEquals(2, result.tools.count { it.status == ToolCallStatus.FAILED })
    }

    @Test fun plainJsonDoesNotExecuteAndRepeatedDelegationIsBounded() = runBlocking {
        var executed = 0
        val plain = PhoneAgentEngine(model = { _, _ -> "```json {\"action\":\"write_file\"} ```" },
            execute = { executed++; PhoneToolOutput(true, "", "") }, publish = {})
        assertTrue(plain.run("explain JSON").tools.isEmpty())
        assertEquals(0, executed)
        var children = 0
        val bounded = PhoneAgentEngine(model = { _, child ->
            if (child) { children++; "child result" }
            else "```phone_action {\"action\":\"delegate_task\",\"task\":\"review\"} ```"
        }, execute = { error("must not call") }, publish = {})
        val result = bounded.run("delegate")
        assertEquals(2, children)
        assertTrue(result.tools.any { it.status == ToolCallStatus.FAILED && it.details.contains("Лимит") })
        assertTrue(result.text.contains("лимит"))
    }

    @Test fun cancellationStopsTheWholeDelegationWithoutASecondModelCall() = runBlocking {
        var calls = 0
        val engine = PhoneAgentEngine(model = { _, _ -> calls++; throw CancellationException("Stop") },
            execute = { error("must not execute") }, publish = {})
        try { engine.run("cancel"); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }
}
