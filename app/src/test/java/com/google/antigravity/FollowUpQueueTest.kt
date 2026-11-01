package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class FollowUpQueueTest {
    @Test fun queuePreservesVersionAffinityAcrossRestartAndChatRebinding() {
        val item = QueuedFollowUp(conversationId = "chat", target = AgentExecutionTarget.PC_REMOTE_SESSION,
            prompt = "continue", executionContextId = "alternative")
        assertEquals(listOf(item), FollowUpQueue.decode(FollowUpQueue.encode(listOf(item))))
        assertEquals("alternative", FollowUpQueue.rebind(listOf(item), "chat", "remote", item.target)
            .single().executionContextId)
        assertEquals("remote", FollowUpQueue.rebind(listOf(item.copy(executionContextId = "chat")),
            "chat", "remote", item.target).single().executionContextId)
    }
    @Test fun remoteConversationRebindingDoesNotMoveAnotherChatOrDestination() {
        val phone = QueuedFollowUp(conversationId = "old", target = AgentExecutionTarget.PHONE_LOCAL_RESOURCES, prompt = "phone")
        val pc = QueuedFollowUp(conversationId = "old", target = AgentExecutionTarget.PC_REMOTE_SESSION, prompt = "pc")
        val other = pc.copy(id = "other", conversationId = "other")
        val rebound = FollowUpQueue.rebind(listOf(phone, pc, other), "old", "remote-id", pc.target)
        assertEquals(listOf(phone, pc.copy(conversationId = "remote-id"), other), rebound)
    }
    @Test fun queuePersistsOrderConversationTargetAndAttachmentsWithoutPersistingTemporaryItems() {
        val a = QueuedFollowUp(conversationId = "phone", target = AgentExecutionTarget.PHONE_LOCAL_RESOURCES,
            prompt = "first", attachments = listOf(AttachedFileItem(name = "notes.txt", textPreview = "actual notes")))
        val b = QueuedFollowUp(conversationId = "pc", target = AgentExecutionTarget.PC_REMOTE_SESSION, prompt = "second")
        val temporary = QueuedFollowUp(conversationId = "temporary", target = a.target, prompt = "PRIVATE", temporary = true)
        val json = FollowUpQueue.encode(listOf(a, temporary, b))
        assertFalse(json.contains("PRIVATE"))
        assertEquals(listOf(a, b), FollowUpQueue.decode(json))
        assertTrue(FollowUpQueue.decode("broken JSON").isEmpty())
    }

    @Test fun taskOwnershipAndDestinationSurviveChatPersistenceAndRunningTasksRecoverAsInterrupted() {
        val tool = ToolCallEvent(id = "child-read", toolName = "read_file", summary = "Read", details = "",
            status = ToolCallStatus.RUNNING, parentTaskId = "parent", executionTarget = AgentExecutionTarget.PHONE_LOCAL_RESOURCES)
        val chat = SavedChat("chat", "Task", AgentExecutionTarget.PHONE_LOCAL_RESOURCES,
            listOf(ChatMessage(role = MessageRole.AGENT, content = "", timestamp = "", toolCalls = listOf(tool))))
        val restored = ChatSessionStore.decode(ChatSessionStore.encode(chat)).messages.single().toolCalls.single()
        assertEquals("parent", restored.parentTaskId)
        assertEquals(AgentExecutionTarget.PHONE_LOCAL_RESOURCES, restored.executionTarget)
        assertEquals(ToolCallStatus.FAILED, restored.status)
    }
}
