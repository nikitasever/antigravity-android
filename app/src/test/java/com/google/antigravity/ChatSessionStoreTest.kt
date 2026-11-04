package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ChatSessionStoreTest {
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
