package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class ConversationTasksTest {
    @Test fun onlyActualDelegationsBecomeTaskRows() {
        val parent = ToolCallEvent(id = "task", toolName = "delegate_task", summary = "Inspect · Телефон",
            details = "", status = ToolCallStatus.RUNNING)
        val read = ToolCallEvent(id = "read", toolName = "read_file", summary = "Read",
            details = "", parentTaskId = parent.id, status = ToolCallStatus.RUNNING)
        val deniedNested = parent.copy(id = "nested", parentTaskId = parent.id, status = ToolCallStatus.FAILED)
        val message = ChatMessage(role = MessageRole.AGENT, content = "", timestamp = "", modelBadge = "Actual model",
            toolCalls = listOf(parent, read, deniedNested))
        val rows = conversationSubagents(listOf(message))
        assertEquals(listOf("task"), rows.map { it.id })
        assertEquals("Actual model", rows.single().model)
        assertEquals(1, conversationTaskPanel(rows).effectiveRunningTasks)
        assertTrue(conversationSubagents(listOf(message.copy(toolCalls = listOf(read)))).isEmpty())
    }

    @Test fun mergedCountsComeFromCurrentTasksAndSettlingRemainsActive() {
        val running = SwarmAgentNode(id = "phone", name = "Phone", status = "running")
        val settling = SwarmAgentNode(id = "pc", name = "PC", status = "cancelled", isSettling = true)
        val old = SubagentPanelState(runningTasks = 99, counts = SwarmCounts(running = 99))
        assertEquals(2, conversationTaskPanel(listOf(running, settling), old).effectiveRunningTasks)
        assertEquals(0, conversationTaskPanel(listOf(running.copy(status = "completed"))).effectiveRunningTasks)
        assertEquals(0, conversationTaskPanel(emptyList(), old).effectiveRunningTasks)
    }
}
