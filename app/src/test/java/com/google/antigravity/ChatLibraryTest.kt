package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class ChatLibraryTest {
    private fun session(id: String, updated: Long) = RemoteSessionItem(id, "Чат $id", updatedAtMs = updated)

    @Test fun pinnedAndArchivedSurvivePersistenceAndRemoteRefresh() {
        val prefs = mapOf("first" to ChatLibraryPreferences("Новое имя", pinned = true),
            "hidden" to ChatLibraryPreferences(archived = true))
        val restored = ChatLibrary.decode(ChatLibrary.encode(prefs))
        val remote = listOf(session("recent", 20), session("first", 1), session("hidden", 30))
        val visible = ChatLibrary.visible(remote, restored, "", false)
        assertEquals(listOf("first", "recent"), visible.map { it.id })
        assertEquals("Новое имя", visible.first().title)
        assertEquals(listOf("hidden"), ChatLibrary.visible(remote, restored, "", true).map { it.id })
        assertEquals(3, ChatLibrary.visible(remote, restored - "hidden", "", false).size)
    }

    @Test fun searchFindsMessageAndAttachmentWithoutIncludingArchivedChats() {
        val message = ChatMessage(role = MessageRole.USER, content = "Обсудим договор", timestamp = "",
            attachments = listOf(AttachedFileItem(name = "Отчёт.pdf")))
        val history = mapOf("first" to listOf(message), "hidden" to listOf(message))
        assertEquals(setOf("first", "hidden"), ChatLibrary.search(history, "  ДОГОВОР  "))
        assertEquals(setOf("first", "hidden"), ChatLibrary.search(history, "отчёт.pdf"))
        val sessions = listOf(session("first", 1), session("hidden", 2))
        val results = ChatLibrary.visible(sessions, mapOf("hidden" to ChatLibraryPreferences(archived = true)),
            "договор", false, ChatLibrary.search(history, "договор"))
        assertEquals(listOf("first"), results.map { it.id })
        assertEquals(2, history.size)
    }

    @Test fun openedChatOrdersBeforeOlderChatsAndDuplicatesAreRemoved() {
        val sessions = listOf(session("first", 1), session("second", 20), session("first", 1))
        val visible = ChatLibrary.visible(sessions, mapOf("first" to ChatLibraryPreferences(lastOpenedAt = 30)), "", false)
        assertEquals(listOf("first", "second"), visible.map { it.id })
    }

    @Test fun invalidPreferencesDoNotDestroyTheConversationList() {
        assertTrue(ChatLibrary.decode("broken").isEmpty())
        assertEquals(1, ChatLibrary.visible(listOf(session("first", 1)), emptyMap(), "ЧАТ", false).size)
        assertTrue(ChatLibrary.search(emptyMap(), "").isEmpty())
    }
}
