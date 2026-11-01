package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Регрессия для длинных разговоров: хранение, поиск и история для модели. */
class LongConversationTest {
    @Test fun longTranscriptSurvivesSaveAndRestore() {
        val directory = Files.createTempDirectory("antigravity-long").toFile()
        try {
            val messages = (0 until 500).map { i ->
                ChatMessage(
                    id = "msg_$i",
                    role = if (i % 2 == 0) MessageRole.USER else MessageRole.AGENT,
                    content = "Сообщение номер $i с уникальным маркером marker_$i",
                    timestamp = "12:00"
                )
            }
            ChatSessionStore(directory).save(
                SavedChat("long-conv", "Длинный разговор", AgentExecutionTarget.PC_REMOTE_SESSION, messages)
            )
            val restored = ChatSessionStore(directory).load().single()
            assertEquals(500, restored.messages.size)
            assertEquals(messages, restored.messages)
        } finally { directory.deleteRecursively() }
    }

    @Test fun searchFindsNeedleInThousandMessageCorpus() {
        val history = (0 until 1000).associate { i ->
            "chat_$i" to listOf(
                ChatMessage(role = MessageRole.USER, content = "обычное сообщение $i", timestamp = ""),
                ChatMessage(role = MessageRole.AGENT, content = "обычный ответ $i", timestamp = "")
            )
        }.toMutableMap()
        history["chat_777"] = listOf(
            ChatMessage(role = MessageRole.AGENT, content = "Итог: параметр NEEDLE_VALUE сохранён", timestamp = "")
        )
        val found = ChatLibrary.search(history, "NEEDLE_VALUE")
        assertEquals(setOf("chat_777"), found)
        assertTrue(ChatLibrary.search(history, "   ").isEmpty())
    }

    @Test fun modelHistoryHandlesThousandMessagesInOrder() {
        val messages = (0 until 1000).map { i ->
            ChatMessage(
                id = "m$i",
                role = if (i % 2 == 0) MessageRole.USER else MessageRole.AGENT,
                content = "Текст $i", timestamp = ""
            )
        }
        val history = chatHistoryForModel(messages)
        assertEquals(1000, history.size)
        assertEquals("Текст 0", history.first().content)
        assertEquals("Текст 999", history.last().content)
    }
}
