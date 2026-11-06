package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class ToolCallLifecycleTest {
    @Test fun interruptionTerminatesAllPendingStatesAndPreservesFinishedEvidence() {
        val tools = ToolCallStatus.entries.map { status -> ToolCallEvent(
            id = status.name, toolName = "read_file", summary = "Read", details = "partial output",
            status = status, unifiedDiff = null, requiresApproval = true,
            approvalDecision = ApprovalDecision.PENDING, parentTaskId = "parent") }
        val interrupted = interruptToolCalls(tools, "Connection lost")
        tools.zip(interrupted).forEach { (before, after) ->
            if (before.status in setOf(ToolCallStatus.RUNNING, ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.APPROVED)) {
                assertEquals(ToolCallStatus.FAILED, after.status)
                assertEquals("partial output\n\nConnection lost", after.details)
                assertNull(after.unifiedDiff)
                assertEquals("parent", after.parentTaskId)
            } else assertEquals(before, after)
        }
        assertFalse(interrupted.any { it.status.isUnfinished })
    }

    @Test fun stopCancelsPendingApprovalsWithoutClaimingThatCompletedWorkWasUndone() {
        val completed = ToolCallEvent(toolName = "write_file", summary = "Saved", details = "Saved file",
            status = ToolCallStatus.COMPLETED, unifiedDiff = "+real change")
        val waiting = completed.copy(id = "waiting", status = ToolCallStatus.AWAITING_APPROVAL)
        val approved = completed.copy(id = "approved", status = ToolCallStatus.APPROVED)
        val stopped = interruptToolCalls(listOf(completed, waiting, approved), "Stopped", cancelled = true)
        assertEquals(completed, stopped.first())
        assertTrue(stopped.drop(1).all { it.status == ToolCallStatus.CANCELLED })
        assertEquals(stopped, interruptToolCalls(stopped, "Stopped again", cancelled = true))
    }
}
