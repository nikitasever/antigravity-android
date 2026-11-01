package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class ChatSyncMergeTest {
    private fun pref(title: String, pinned: Boolean = false, archived: Boolean = false,
                     lastOpenedAt: Long = 0L, updatedAtMs: Long = 0L) =
        ChatLibraryPreferences(title, pinned, archived, lastOpenedAt, updatedAtMs)

    @Test fun preferencesMergeKeepsNewerContentAndMaxLastOpened() {
        val local = mapOf(
            "chat1" to pref("локальное имя", lastOpenedAt = 500, updatedAtMs = 100),
            "chat2" to pref("только телефон", updatedAtMs = 50)
        )
        val remote = mapOf(
            "chat1" to pref("имя с ПК", pinned = true, lastOpenedAt = 300, updatedAtMs = 200),
            "chat3" to pref("только ПК", updatedAtMs = 10)
        )
        val merged = ChatLibrary.mergeSynced(local, remote)
        assertEquals("имя с ПК", merged.getValue("chat1").title)
        assertTrue(merged.getValue("chat1").pinned)
        assertEquals(500L, merged.getValue("chat1").lastOpenedAt)
        assertEquals("только телефон", merged.getValue("chat2").title)
        assertEquals("только ПК", merged.getValue("chat3").title)
    }

    @Test fun preferencesMergeDoesNotLetOpeningChatClobberRemoteRename() {
        // Простое открытие чата не меняет updatedAtMs, поэтому более свежее
        // переименование с ПК сохраняется, а lastOpenedAt суммируется максимумом.
        val local = mapOf("chat1" to pref("локальное", lastOpenedAt = 900, updatedAtMs = 100))
        val remote = mapOf("chat1" to pref("переименовано на ПК", lastOpenedAt = 100, updatedAtMs = 150))
        val merged = ChatLibrary.mergeSynced(local, remote)
        assertEquals("переименовано на ПК", merged.getValue("chat1").title)
        assertEquals(900L, merged.getValue("chat1").lastOpenedAt)
    }

    @Test fun preferencesEncodeDecodeRoundTripKeepsSyncTimestamp() {
        val original = mapOf("chat1" to pref("Имя", pinned = true, updatedAtMs = 12345L))
        val decoded = ChatLibrary.decode(ChatLibrary.encode(original))
        assertEquals(original, decoded)
    }

    @Test fun artifactsMergePrefersNewerAndPreservesLocalOrder() {
        fun doc(id: String, content: String, updatedAtMs: Long) =
            ArtifactDocument(id = id, title = "t", summary = "s", content = content, updatedAtMs = updatedAtMs)
        val local = listOf(doc("saved_message_a", "старый", 100), doc("saved_message_b", "локальный", 50))
        val remote = listOf(doc("saved_message_c", "с ПК", 10), doc("saved_message_a", "новый", 200))
        val merged = ArtifactLibrary.mergeSynced(local, remote)
        assertEquals(listOf("saved_message_a", "saved_message_b", "saved_message_c"), merged.map { it.id })
        assertEquals("новый", merged.first().content)
        assertEquals("локальный", merged[1].content)
    }

    @Test fun artifactsEncodeDecodeRoundTripKeepsSyncTimestamp() {
        val original = ArtifactDocument(id = "saved_message_x", title = "T", summary = "S",
            content = "C", updatedAtMs = 777L)
        val decoded = ArtifactLibrary.decode(ArtifactLibrary.encode(listOf(original))).single()
        assertEquals(777L, decoded.updatedAtMs)
    }
}
