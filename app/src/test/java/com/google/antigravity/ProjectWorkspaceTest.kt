package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class ProjectWorkspaceTest {
    private fun sample() = ProjectWorkspace(
        id = ProjectWorkspaces.idFor("antigravity-android"),
        name = "antigravity-android",
        instructions = "Отвечай кратко, используй Kotlin.",
        documents = listOf(ProjectDocument(id = "doc_1", name = "архитектура.md", content = "# Архитектура\nМодуль :app.")),
        linkedChatIds = listOf("chat-1", "chat-2")
    )

    @Test fun encodeDecodeRoundTripKeepsInstructionsDocumentsAndChats() {
        val workspace = sample()
        val decoded = ProjectWorkspaces.decode(ProjectWorkspaces.encode(mapOf(workspace.id to workspace)))
        assertEquals(mapOf(workspace.id to workspace), decoded)
    }

    @Test fun decodeToleratesBrokenJsonAndMalformedEntries() {
        assertTrue(ProjectWorkspaces.decode("broken").isEmpty())
        assertTrue(ProjectWorkspaces.decode("").isEmpty())
        val mixed = """[{"id":"","name":"пустой"},{"id":"project_ok","name":"OK"}]"""
        val decoded = ProjectWorkspaces.decode(mixed)
        assertEquals(setOf("project_ok"), decoded.keys)
        assertEquals("OK", decoded.getValue("project_ok").name)
    }

    @Test fun linkedChatManagementIsIdempotentAndExclusiveToOneProject() {
        val base = sample()
        assertEquals(base, base.withLinkedChat("chat-1"))
        val extended = base.withLinkedChat("chat-3")
        assertEquals(listOf("chat-1", "chat-2", "chat-3"), extended.linkedChatIds)
        assertEquals(listOf("chat-2", "chat-3"), extended.withoutLinkedChat("chat-1").linkedChatIds)
        assertEquals(base.withoutLinkedChat("missing"), base)
    }

    @Test fun documentsAreReplacedByIdAndRemovable() {
        val base = sample()
        val updated = base.withDocument(ProjectDocument(id = "doc_1", name = "новое.md", content = "новое"))
        assertEquals(1, updated.documents.size)
        assertEquals("новое.md", updated.documents.single().name)
        assertEquals(2, base.withDocument(ProjectDocument(id = "doc_2", name = "второй", content = "x")).documents.size)
        assertTrue(base.withoutDocument("doc_1").documents.isEmpty())
        assertEquals(base, base.withoutDocument("missing"))
    }

    @Test fun noOpEditsKeepSyncTimestampAndActualChangesAdvanceIt() {
        val base = sample().copy(updatedAtMs = 100L)
        assertEquals(base, base.withInstructions(base.instructions, now = 200L))
        assertEquals(base, base.withDocument(base.documents.single(), now = 200L))
        assertEquals(base, base.withoutDocument("missing", now = 200L))
        assertEquals(200L, base.withoutDocument("doc_1", now = 200L).updatedAtMs)
    }

    @Test fun contextMarkdownContainsInstructionsAndDocumentsOnlyForProjectChats() {
        assertEquals("", ProjectWorkspaces.contextMarkdown(null))
        assertEquals("", ProjectWorkspaces.contextMarkdown(sample().copy(instructions = " ", documents = emptyList())))
        val context = ProjectWorkspaces.contextMarkdown(sample())
        assertTrue(context.contains("Отвечай кратко, используй Kotlin."))
        assertTrue(context.contains("архитектура.md"))
        assertTrue(context.contains("Модуль :app."))
    }

    @Test fun workspaceIdIsStableAndDistinctFromChatSessionIds() {
        assertEquals("project_antigravity-android", ProjectWorkspaces.idFor("antigravity-android"))
        assertEquals(ProjectWorkspaces.idFor("my project"), ProjectWorkspaces.idFor("my   project"))
        assertNotEquals(ProjectWorkspaces.idFor("x"), "proj_x")
    }

    @Test fun workspaceSyncMergeFollowsFreshnessAndKeepsOneSidedEntries() {
        val edited = sample().withInstructions("Новые инструкции", now = 100L)
        // Round-trip сохраняет метку слияния.
        val decoded = ProjectWorkspaces.decode(ProjectWorkspaces.encode(mapOf(edited.id to edited)))
        assertEquals(mapOf(edited.id to edited), decoded)
        // Слияние: более новая рабочая область побеждает, односторонние сохраняются.
        val local = mapOf(
            edited.id to edited,
            "project_local" to ProjectWorkspace(id = "project_local", name = "только телефон", updatedAtMs = 5L)
        )
        val mergedStale = ProjectWorkspaces.mergeSynced(local,
            mapOf(edited.id to edited.copy(instructions = "устаревшие", updatedAtMs = 50L)))
        assertEquals("Новые инструкции", mergedStale.getValue(edited.id).instructions)
        val mergedFresh = ProjectWorkspaces.mergeSynced(local,
            mapOf(edited.id to edited.copy(instructions = "с ПК", updatedAtMs = 200L)))
        assertEquals("с ПК", mergedFresh.getValue(edited.id).instructions)
        assertEquals(2, mergedFresh.size) // обе односторонние записи сохранены
        assertTrue("project_local" in mergedFresh.keys)
    }
}
