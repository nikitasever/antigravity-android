package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class ArtifactVersionsTest {
    private fun saved(content: String = "# Ответ v1") = ArtifactLibrary.savedMessage(
        ChatMessage(id = "m1", role = MessageRole.AGENT, timestamp = "", content = content)
    )!!

    @Test fun editSavesPreviousContentAsVersionAndRecomputesSummary() {
        val doc = saved()
        val edited = ArtifactLibrary.edited(doc, "## Отредактировано", now = 100L)
        assertEquals("## Отредактировано", edited.content)
        assertTrue(edited.summary.startsWith("## Отредактировано"))
        assertEquals(1, edited.versions.size)
        assertEquals(doc.content, edited.versions.single().content)
        assertEquals(100L, edited.versions.single().savedAt)
        // Идентичное редактирование ничего не меняет.
        assertEquals(edited, ArtifactLibrary.edited(edited, "## Отредактировано"))
    }

    @Test fun restoreIsNonDestructiveAndKeepsVersion() {
        val doc = saved()
        val v2 = ArtifactLibrary.edited(doc, "вторая версия", now = 1L)
        val versionId = v2.versions.single().id
        val restored = ArtifactLibrary.restored(v2, versionId, now = 2L)!!
        assertEquals(doc.content, restored.content)
        // Обе версии сохранены, текущий текст предварительно ушёл в историю.
        assertEquals(2, restored.versions.size)
        assertTrue(restored.versions.any { it.content == "вторая версия" })
        // Неизвестная версия — null.
        assertNull(ArtifactLibrary.restored(v2, "missing"))
    }

    @Test fun resavedMergesSameMessageWithoutDuplicates() {
        val original = saved("первый ответ")
        val fresh = saved("обновлённый ответ")
        val merged = ArtifactLibrary.resaved(original, fresh)
        assertEquals("обновлённый ответ", merged.content)
        assertEquals(1, merged.versions.size)
        assertEquals("первый ответ", merged.versions.single().content)
        // Повторное сохранение идентичного содержимого — no-op без дублей.
        assertEquals(merged, ArtifactLibrary.resaved(merged, merged.copy(summary = merged.summary)))
    }

    @Test fun versionHistoryIsCappedAtMaxVersions() {
        var doc = saved("v0")
        for (i in 1..ArtifactLibrary.MAX_VERSIONS + 5) {
            doc = ArtifactLibrary.edited(doc, "v$i", now = i.toLong())
        }
        assertEquals(ArtifactLibrary.MAX_VERSIONS, doc.versions.size)
    }

    @Test fun renameChangesOnlyTitle() {
        val doc = saved()
        val renamed = ArtifactLibrary.renamed(doc, "  Новое имя  ")
        assertEquals("Новое имя", renamed.title)
        assertEquals(doc.content, renamed.content)
        assertEquals(doc.versions, renamed.versions)
        assertEquals(doc, ArtifactLibrary.renamed(doc, "   "))
    }

    @Test fun encodeDecodeRoundTripKeepsVersionsAndOldFormatStillLoads() {
        val doc = ArtifactLibrary.edited(saved(), "новый текст", now = 5L)
        val decoded = ArtifactLibrary.decode(ArtifactLibrary.encode(listOf(doc)))
        assertEquals(listOf(doc), decoded)
        // Старый формат без поля versions загружается с пустой историей.
        val legacy = """[{"id":"saved_message_x","title":"T","summary":"S","content":"C","type":"MARKDOWN","updatedAt":"Сегодня"}]"""
        val legacyDoc = ArtifactLibrary.decode(legacy).single()
        assertEquals("C", legacyDoc.content)
        assertTrue(legacyDoc.versions.isEmpty())
        assertFalse(legacyDoc.pinned)
    }

    @Test fun pinningTogglesFlagBumpsTimestampAndSurvivesSync() {
        val doc = saved()
        val pinned = ArtifactLibrary.pinned(doc, true, now = 10L)
        assertTrue(pinned.pinned)
        assertEquals(10L, pinned.updatedAtMs)
        // Идентичное состояние — no-op.
        assertEquals(pinned, ArtifactLibrary.pinned(pinned, true, now = 99L))
        val unpinned = ArtifactLibrary.pinned(pinned, false, now = 20L)
        assertFalse(unpinned.pinned)
        // Round-trip кодирования сохраняет закрепление.
        val decoded = ArtifactLibrary.decode(ArtifactLibrary.encode(listOf(pinned))).single()
        assertTrue(decoded.pinned)
        // Слияние с ПК: более свежее изменение закрепления побеждает.
        val remoteStale = pinned.copy(pinned = false, updatedAtMs = 5L)
        assertEquals(pinned, ArtifactLibrary.mergeSynced(listOf(pinned), listOf(remoteStale)).single())
        val remoteFresh = pinned.copy(pinned = false, updatedAtMs = 50L)
        assertFalse(ArtifactLibrary.mergeSynced(listOf(pinned), listOf(remoteFresh)).single().pinned)
    }
}
