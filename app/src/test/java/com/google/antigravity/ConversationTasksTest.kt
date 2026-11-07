package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class ConversationTasksTest {
    @Test fun supportedRunningAliasesKeepTaskControlsActiveAndCountsConsistent() {
        val agents = listOf("running", "active", "working").map { SwarmAgentNode(id = it, name = it, status = it) }
        assertTrue(agents.all { it.isInProgress })
        val state = conversationTaskPanel(agents)
        assertEquals(3, state.effectiveRunningTasks)
        assertEquals(3, state.effectiveCounts.running)
        assertTrue(SwarmAgentNode(id = "default", name = "Default").isInProgress)
    }

    @Test fun terminalAndUnknownStatusesDoNotKeepTaskControlsActive() {
        listOf("completed", "failed", "cancelled", "ready", "unknown").forEach { status ->
            val agent = SwarmAgentNode(id = status, name = status, status = status)
            assertFalse(agent.isInProgress)
            assertEquals(0, conversationTaskPanel(listOf(agent)).effectiveRunningTasks)
            assertTrue(agent.copy(isSettling = true).isInProgress)
        }
    }

    @Test fun swarmStatusParsingDoesNotDependOnTurkishDeviceLocale() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            listOf("RUNNING", "ACTIVE", "WORKING", "FAILED").forEach { status ->
                val agent = com.google.antigravity.network.parseSwarmAgentNode(
                    org.json.JSONObject().put("id", status).put("status", status), 0)
                assertEquals(status.lowercase(java.util.Locale.ROOT), agent.status)
            }
        } finally { java.util.Locale.setDefault(original) }
    }

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
