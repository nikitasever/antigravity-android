package com.google.antigravity

import com.google.antigravity.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PhoneAgentEngineTest {
    @Test fun growingToolOutputsStayBoundedWithoutLosingCompletedEvidence() = runBlocking {
        var rounds = 0
        var executed = 0
        val observationSizes = mutableListOf<Int>()
        val engine = PhoneAgentEngine(model = { prompt, child ->
            assertFalse(child)
            if (rounds > 0) {
                val context = prompt.substringAfter("Реальные результаты предыдущих шагов:\n")
                observationSizes.add(context.length)
                assertEquals(rounds * 4, Regex("read_file: успешно").findAll(context).count())
                assertTrue(context.contains("RESULT-${rounds * 4} "))
                assertTrue(context.contains("Вывод сокращён"))
            }
            if (rounds++ < 5) (1..4).joinToString("\n") {
                "```phone_action {\"action\":\"read_file\",\"path\":\"notes.txt\"} ```"
            } else "Done using recent evidence"
        }, execute = {
            executed++
            PhoneToolOutput(true, "Read", "RESULT-$executed " + "x".repeat(20_000) + " END-$executed")
        }, publish = {})
        val reply = engine.run("Review files")
        println("OBSERVATION_MAX_CHARS=${observationSizes.maxOrNull()}")
        assertEquals(20, executed)
        assertTrue(reply.completed)
        assertEquals(20, reply.tools.size)
        assertTrue(reply.tools.all { it.status == ToolCallStatus.COMPLETED })
        assertTrue(reply.tools.first().details.endsWith("END-1"))
        assertTrue(reply.tools.last().details.endsWith("END-20"))
        assertTrue("Observation section has ${observationSizes.maxOrNull()} chars", observationSizes.all { it <= 16_000 })
    }

    @Test fun oversizedFailureAndMalformedJsonAreBoundedWithoutFurtherExecution() = runBlocking {
        var rounds = 0
        var executions = 0
        val engine = PhoneAgentEngine(model = { prompt, _ ->
            when (rounds++) {
                0 -> "```phone_action {broken} ```\n```phone_action {\"action\":\"read_file\"} ```"
                else -> {
                    val context = prompt.substringAfter("Реальные результаты предыдущих шагов:\n")
                    assertTrue(context.length <= 16_000)
                    assertTrue(context.contains("некорректный JSON"))
                    assertTrue(context.contains("read_file: ошибка"))
                    assertTrue(context.contains("Вывод сокращён"))
                    "Read failed; no retry"
                }
            }
        }, execute = { executions++; throw IllegalStateException("Failure " + "x".repeat(20_000)) }, publish = {})
        val reply = engine.run("Read")
        assertEquals(1, executions)
        assertEquals(ToolCallStatus.FAILED, reply.tools.single().status)
        assertTrue(reply.tools.single().details.length > 4_000)
    }

    @Test fun childObservationsAreBoundedAndLeaderReceivesOnlyTheChildResult() = runBlocking {
        var roots = 0
        var children = 0
        val engine = PhoneAgentEngine(model = { prompt, child ->
            if (child) {
                if (children++ == 0) (1..4).joinToString("\n") {
                    "```phone_action {\"action\":\"read_file\",\"path\":\"notes.txt\"} ```"
                } else {
                    assertTrue(prompt.substringAfter("Реальные результаты предыдущих шагов:\n").length <= 16_000)
                    "Actual child summary"
                }
            } else if (roots++ == 0) "```phone_action {\"action\":\"delegate_task\",\"task\":\"Review\"} ```"
            else {
                assertTrue(prompt.contains("Actual child summary"))
                assertFalse(prompt.contains("CHILD_RAW_OUTPUT"))
                "Done"
            }
        }, execute = { PhoneToolOutput(true, "Read", "CHILD_RAW_OUTPUT " + "x".repeat(20_000)) }, publish = {})
        val reply = engine.run("Review")
        assertTrue(reply.completed)
        assertEquals(5, reply.tools.size)
        assertTrue(reply.tools.all { it.status == ToolCallStatus.COMPLETED })
    }

    @Test fun exhaustedLeaderIsIncompleteAndPreservesCompletedReads() = runBlocking {
        var modelCalls = 0
        var reads = 0
        val engine = PhoneAgentEngine(model = { _, child ->
            assertFalse(child)
            modelCalls++
            "```phone_action {\"action\":\"read_file\",\"path\":\"notes.txt\"} ```"
        }, execute = {
            reads++
            PhoneToolOutput(true, "Read", "Actual read $reads")
        }, publish = {})
        val reply = engine.run("Review notes")
        assertFalse(reply.completed)
        assertEquals(6, modelCalls)
        assertEquals(6, reads)
        assertTrue(reply.text.contains("Достигнут лимит шагов"))
        assertTrue(reply.tools.all { it.status == ToolCallStatus.COMPLETED })
        assertEquals("Actual read 6", reply.tools.last().details)
    }

    @Test fun exhaustedChildIsFailedAndLeaderReceivesTheFailure() = runBlocking {
        var roots = 0
        val engine = PhoneAgentEngine(model = { prompt, child ->
            if (child) "```phone_action {\"action\":\"list_dir\",\"path\":\".\"} ```"
            else if (roots++ == 0) "```phone_action {\"action\":\"delegate_task\",\"task\":\"Inspect files\"} ```"
            else {
                assertTrue(prompt.contains("delegate_task: ошибка"))
                assertTrue(prompt.contains("Достигнут лимит шагов"))
                "The task did not finish"
            }
        }, execute = { PhoneToolOutput(true, "Read", "files") }, publish = {})
        val reply = engine.run("Inspect")
        assertEquals(ToolCallStatus.FAILED, reply.tools.first().status)
        assertEquals(3, reply.tools.count { it.parentTaskId == reply.tools.first().id })
    }

    @Test fun rootDiscoversExistingPhoneCliAndSandboxAndUsesActualOutput() = runBlocking {
        var calls = 0
        val engine = PhoneAgentEngine(model = { prompt, child ->
            assertFalse(child)
            assertTrue(prompt.contains("android_cli(command)"))
            assertTrue(prompt.contains("sandbox_test(sandboxId)"))
            if (calls++ == 0) "```phone_action {\"action\":\"android_cli\",\"command\":\"pwd\"} ```"
            else { assertTrue(prompt.contains("/real/android/workspace")); "Рабочая папка получена" }
        }, execute = { obj ->
            assertEquals("pwd", obj.getString("command"))
            PhoneToolOutput(true, "Android CLI", "/real/android/workspace")
        }, publish = {})
        val reply = engine.run("Проверь рабочую папку")
        assertEquals("Рабочая папка получена", reply.text)
        assertTrue(reply.completed)
        assertEquals(ToolCallStatus.COMPLETED, reply.tools.single().status)
        assertEquals(AgentExecutionTarget.PHONE_LOCAL_RESOURCES, reply.tools.single().executionTarget)
    }

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
