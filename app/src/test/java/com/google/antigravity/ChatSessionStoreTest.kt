package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ChatSessionStoreTest {
    @Test fun approvalCapabilityAndTransientSendingStateNeverEnterHistory() {
        val tool=ToolCallEvent(toolName="write_file",summary="Write",details="",status=ToolCallStatus.AWAITING_APPROVAL,
            requiresApproval=true,approvalHandle=ToolApprovalHandle("r","secret-capability","t","c","http://bridge","path: file"),
            approvalSending=true,approvalError="transient transport failure")
        val encoded=ChatSessionStore.encode(SavedChat("c","C",AgentExecutionTarget.PC_REMOTE_SESSION,
            listOf(ChatMessage(role=MessageRole.AGENT,content="",timestamp="",toolCalls=listOf(tool)))))
        assertFalse(encoded.contains("secret-capability"))
        assertFalse(encoded.contains("transient transport failure"))
        val restored=ChatSessionStore.decode(encoded).messages.single().toolCalls.single()
        assertNull(restored.approvalHandle)
        assertFalse(restored.approvalSending)
        assertEquals(ToolCallStatus.FAILED,restored.status)
    }

    private fun restoreTools(tools: List<ToolCallEvent>): List<ToolCallEvent> = ChatSessionStore.decode(
        ChatSessionStore.encode(SavedChat("tools", "Tools", AgentExecutionTarget.PHONE_LOCAL_RESOURCES,
            listOf(ChatMessage(role = MessageRole.AGENT, content = "", timestamp = "", toolCalls = tools)))))
        .messages.single().toolCalls

    @Test fun ordinaryOutputNeverBecomesADiffAfterRestart() {
        val tool = ToolCallEvent(toolName = "list_dir", summary = "Files", details = "notes.txt\nphoto.jpg",
            unifiedDiff = null, toolTitleRu = "Файлы устройства", parentTaskId = "child",
            executionTarget = AgentExecutionTarget.PHONE_LOCAL_RESOURCES)
        assertEquals(tool, restoreTools(listOf(tool)).single())
    }

    @Test fun actualDiffAndApprovalDecisionSurviveRestart() {
        val tool = ToolCallEvent(toolName = "write_file", summary = "Change", details = "Written",
            unifiedDiff = "--- old\n+++ new\n+real change", requiresApproval = true,
            approvalDecision = ApprovalDecision.APPROVED, status = ToolCallStatus.COMPLETED)
        assertEquals(tool, restoreTools(listOf(tool)).single())
    }

    @Test fun restartExpiresRunningAndApprovalStatesWithAnExplanation() {
        val tools = listOf(ToolCallStatus.RUNNING, ToolCallStatus.AWAITING_APPROVAL, ToolCallStatus.APPROVED).map {
            ToolCallEvent(toolName = "read_file", summary = "Read", details = "partial", unifiedDiff = null,
                requiresApproval = true, status = it)
        }
        val restored = restoreTools(tools)
        assertTrue(restored.all { it.status == ToolCallStatus.FAILED && !it.status.isUnfinished })
        assertTrue(restored.all { it.details.startsWith("partial") && it.details.contains("прервано") })
        assertTrue(restored.all { it.unifiedDiff == null })
    }

    @Test fun olderHistoryWithoutDiffOrApprovalMetadataStillLoadsWithoutInventingChanges() {
        val root = org.json.JSONObject(ChatSessionStore.encode(SavedChat("old", "Old",
            AgentExecutionTarget.PC_REMOTE_SESSION, listOf(ChatMessage(role = MessageRole.AGENT,
                content = "done", timestamp = "", toolCalls = listOf(ToolCallEvent(toolName = "read_file",
                    summary = "Read", details = "file output")))))))
        val tool = root.getJSONArray("messages").getJSONObject(0).getJSONArray("tools").getJSONObject(0)
        listOf("unifiedDiff", "toolTitleRu", "requiresApproval", "approvalDecision").forEach(tool::remove)
        val restored = ChatSessionStore.decode(root.toString()).messages.single().toolCalls.single()
        assertEquals("file output", restored.details)
        assertNull(restored.unifiedDiff)
        assertFalse(restored.requiresApproval)
        assertEquals(ApprovalDecision.PENDING, restored.approvalDecision)
    }

    @Test fun restoresConversationAttachmentsAndToolResultsAfterRestart() {
        val directory = Files.createTempDirectory("antigravity-history").toFile()
        try {
            val user = ChatMessage(role = MessageRole.USER, content = "Проверь файл", timestamp = "10:00",
                attachments = listOf(AttachedFileItem(name = "данные.txt", base64Data = "YWJj", textPreview = "abc")))
            val answer = ChatMessage(role = MessageRole.AGENT, content = "Готово", timestamp = "10:01",
                thinking = "Check the actual file first.", thinkingDurationSec = 7,
                isStreaming = true, toolCalls = listOf(ToolCallEvent(toolName = "read_file", summary = "Файл прочитан",
                    status = ToolCallStatus.COMPLETED, details = "abc")),
                createdDiffs = listOf(FileDiffItem("notes.txt", 1, 0, "+abc")))
            val chat = SavedChat("../unsafe-name", "История", AgentExecutionTarget.PHONE_LOCAL_RESOURCES, listOf(user, answer))
            ChatSessionStore(directory).save(chat)
            val restored = ChatSessionStore(directory).load().single()
            assertEquals(chat.id, restored.id)
            assertEquals(chat.target, restored.target)
            assertEquals(user, restored.messages[0])
            assertEquals(answer.content, restored.messages[1].content)
            assertFalse(restored.messages[1].isStreaming)
            assertEquals(answer.thinking, restored.messages[1].thinking)
            assertEquals(7, restored.messages[1].thinkingDurationSec)
            assertFalse(restored.messages[1].isThinkingExpanded)
            assertEquals(answer.toolCalls, restored.messages[1].toolCalls)
            assertEquals(answer.createdDiffs, restored.messages[1].createdDiffs)
            assertTrue(directory.listFiles()!!.all { it.parentFile == directory })
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptedFileDoesNotDiscardOtherConversations() {
        val directory = Files.createTempDirectory("antigravity-history").toFile()
        try {
            val store = ChatSessionStore(directory)
            store.save(SavedChat("valid", "Test", AgentExecutionTarget.PC_REMOTE_SESSION,
                listOf(ChatMessage(role = MessageRole.USER, content = "Hello", timestamp = ""))))
            java.io.File(directory, "broken.json").writeText("{")
            assertEquals(listOf("valid"), store.load().map { it.id })
        } finally { directory.deleteRecursively() }
    }
}
