package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class ChatHistoryPolicyTest {
    private fun message(id: String, role: MessageRole, text: String = id) =
        ChatMessage(id = id, role = role, content = text, timestamp = "")

    @Test fun explicitBranchCopiesOnlyHistoryThroughSelectedMessage() {
        val all = listOf(message("u1", MessageRole.USER), message("a1", MessageRole.AGENT), message("u2", MessageRole.USER))
        assertEquals(all.take(2), prepareChatBranch(all, "a1"))
        assertEquals(3, all.size)
        assertNull(prepareChatBranch(all, "missing"))
        assertNull(prepareChatBranch(listOf(all[1].copy(isStreaming = true)), "a1"))
    }

    @Test fun contextResetRetainsVisibleHistoryButExcludesItFromModelInput() {
        val old = message("old", MessageRole.USER)
        val reset = message("reset", MessageRole.AGENT).copy(isContextResetDivider = true)
        val recent = message("recent", MessageRole.USER)
        val streaming = message("stream", MessageRole.AGENT).copy(isStreaming = true)
        assertEquals(listOf(recent), chatHistoryForModel(listOf(old, reset, recent, streaming)))
        assertTrue(chatHistoryForModel(listOf(old, reset)).isEmpty())
    }

    @Test fun compactionSummaryBecomesModelLeadAndKeepsRecentTurns() {
        val old = message("old", MessageRole.USER)
        val summary = message("summary", MessageRole.AGENT).copy(
            content = "Сводка: пользователь делал экран входа",
            isContextResetDivider = true, isCompactionSummary = true
        )
        val recentUser = message("recent-user", MessageRole.USER)
        val recentReply = message("recent-reply", MessageRole.AGENT)
        val history = chatHistoryForModel(listOf(old, summary, recentUser, recentReply))
        assertEquals(listOf(summary, recentUser, recentReply), history)
        assertEquals("Сводка: пользователь делал экран входа", history.first().content)
    }

    @Test fun compactionSummaryWithoutContentIsNotSentToModel() {
        val summary = message("summary", MessageRole.AGENT).copy(
            content = "  ", isContextResetDivider = true, isCompactionSummary = true
        )
        val recent = message("recent", MessageRole.USER)
        assertEquals(listOf(recent), chatHistoryForModel(listOf(summary, recent)))
    }

    @Test fun transcriptRefreshPreservesLocalAttachmentsAndStableIdentity() {
        val local = message("local-id", MessageRole.USER).copy(attachments = listOf(AttachedFileItem(name = "file.pdf")))
        val remote = message("remote-id", MessageRole.USER, local.content)
        val merged = mergeRemoteChatHistory(listOf(local), listOf(remote)).single()
        assertEquals(local.id, merged.id)
        assertEquals(local.attachments, merged.attachments)
        assertEquals(listOf(local), mergeRemoteChatHistory(listOf(local), emptyList()))
    }

    @Test fun retryPreservesEarlierTurnsAndOriginalAttachments() {
        val attachment = AttachedFileItem(name = "photo.png", uriString = "content://photo")
        val first = message("first", MessageRole.USER)
        val firstReply = message("first-reply", MessageRole.AGENT)
        val user = message("user", MessageRole.USER).copy(attachments = listOf(attachment))
        val tool = message("tool", MessageRole.SYSTEM)
        val answer = message("answer", MessageRole.AGENT)
        val retry = prepareChatRetry(listOf(first, firstReply, user, tool, answer), answer.id)!!
        assertEquals(listOf(first, firstReply), retry.history)
        assertEquals(user.content, retry.prompt)
        assertEquals(listOf(attachment), retry.attachments)
    }

    @Test fun retryRejectsUnknownMessagesAndUserMessages() {
        val user = message("user", MessageRole.USER)
        assertNull(prepareChatRetry(listOf(user), "missing"))
        assertNull(prepareChatRetry(listOf(user), user.id))
        assertNull(prepareChatRetry(listOf(message("a", MessageRole.AGENT)), "a"))
    }

    @Test fun editBranchesFromSelectedUserAndKeepsAttachments() {
        val attachment = AttachedFileItem(name = "notes.pdf")
        val user = message("u", MessageRole.USER).copy(attachments = listOf(attachment))
        val edit = prepareChatEdit(listOf(user, message("a", MessageRole.AGENT)), "u", "  fixed  ")!!
        assertTrue(edit.history.isEmpty())
        assertEquals("fixed", edit.prompt)
        assertEquals(listOf(attachment), edit.attachments)
        assertNull(prepareChatEdit(listOf(user), "unknown", "fixed"))
        assertNull(prepareChatEdit(listOf(user), "u", "   "))
    }

    @Test fun permissionAliasesTrackSettingsChangesAfterCopy() {
        val permissions = AiPermissionsConfig().copy(
            phoneFileReadWrite = false, phoneAndroidCliShell = false,
            phoneVirtualSandboxExec = false, phoneContactsRead = false,
            pcTerminalExec = false, pcFilesystemWrite = false
        )
        assertFalse(permissions.phoneFileSystemAccess)
        assertFalse(permissions.phoneAndroidCliAccess)
        assertFalse(permissions.phoneVirtualSandboxAccess)
        assertFalse(permissions.phoneContactsCalendarSmsAccess)
        assertFalse(permissions.pcPowerShellExecution)
        assertFalse(permissions.pcWorkspaceReadWrite)
    }
}
