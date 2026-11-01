package com.google.antigravity

import com.google.antigravity.model.AttachedFileItem
import com.google.antigravity.model.ChatMessage
import com.google.antigravity.model.MessageRole
import com.google.antigravity.model.MessageVersionState
import com.google.antigravity.model.forkMessageVersion
import com.google.antigravity.model.selectMessageVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageVersionsTest {

    @Test fun versionsSurviveStorageAndKeepIndependentBackendContexts() {
        val original = listOf(createMessage("u", MessageRole.USER, "original"),
            createMessage("a", MessageRole.AGENT, "original answer"))
        val edited = forkMessageVersion(original, "u", "edited")!! +
            createMessage("b", MessageRole.AGENT, "edited answer")
        val model = com.google.antigravity.model.SavedChat("chat", "Test",
            com.google.antigravity.model.AgentExecutionTarget.PC_REMOTE_SESSION, edited)
        val restored = com.google.antigravity.model.ChatSessionStore.decode(
            com.google.antigravity.model.ChatSessionStore.encode(model)).messages
        assertEquals(edited, restored)
        val back = selectMessageVersion(restored, "u", 0)!!
        assertEquals("original answer", back.last().content)
        assertEquals("chat", com.google.antigravity.model.conversationExecutionId("chat", back))
        val alternativeId = com.google.antigravity.model.conversationExecutionId("chat", restored)
        assertTrue(alternativeId != "chat")
        assertEquals(alternativeId, com.google.antigravity.model.conversationExecutionId("chat",
            selectMessageVersion(back, "u", 1)!!))
        assertEquals(restored, com.google.antigravity.model.mergeRemoteChatHistory(restored, original))
    }

    private fun createMessage(
        id: String,
        role: MessageRole,
        content: String,
        attachments: List<AttachedFileItem> = emptyList(),
        isStreaming: Boolean = false,
        versionState: MessageVersionState? = null
    ): ChatMessage {
        return ChatMessage(
            id = id,
            role = role,
            content = content,
            timestamp = "12:00",
            attachments = attachments,
            isStreaming = isStreaming
        ).copy(versionState = versionState)
    }

    @Test
    fun `edit preserves originals, attachments, and prefix`() {
        val attachment = AttachedFileItem(name = "test.txt", uriString = "content://test")
        val prefixMsg = createMessage("msg0", MessageRole.USER, "prefix text")
        val anchorMsg = createMessage(
            "msg1",
            MessageRole.USER,
            "original text",
            attachments = listOf(attachment)
        )
        val replyMsg = createMessage("msg2", MessageRole.AGENT, "reply to original")

        val messages = listOf(prefixMsg, anchorMsg, replyMsg)
        val updated = forkMessageVersion(messages, "msg1", "edited text")

        assertNotNull(updated)
        assertEquals(2, updated!!.size)
        assertEquals("msg0", updated[0].id)
        assertEquals("prefix text", updated[0].content)

        val editedHead = updated[1]
        assertEquals("msg1", editedHead.id)
        assertEquals("edited text", editedHead.content)
        assertEquals(listOf(attachment), editedHead.attachments)

        val state = editedHead.versionState
        assertNotNull(state)
        assertEquals(1, state!!.selected)
        assertEquals(2, state.branches.size)

        // Branch 0 is original tail
        val branch0 = state.branches[0]
        assertEquals(2, branch0.size)
        assertEquals("original text", branch0[0].content)
        assertNull(branch0[0].versionState)
        assertEquals("reply to original", branch0[1].content)

        // Branch 1 is new version head without visible continuation
        val branch1 = state.branches[1]
        assertEquals(1, branch1.size)
        assertEquals("edited text", branch1[0].content)
        assertNull(branch1[0].versionState)
    }

    @Test
    fun `append reply to version2 then switching 1 to 2 restores corresponding replies`() {
        val anchor = createMessage("u1", MessageRole.USER, "prompt v1")
        val r1 = createMessage("a1", MessageRole.AGENT, "reply v1")

        // Fork to v2
        val v2List = forkMessageVersion(listOf(anchor, r1), "u1", "prompt v2")
        assertNotNull(v2List)
        assertEquals(1, v2List!!.size)

        // Append reply to v2
        val r2 = createMessage("a2", MessageRole.AGENT, "reply v2")
        val withV2Reply = v2List + r2

        // Switch back to v1 (index 0)
        val backToV1 = selectMessageVersion(withV2Reply, "u1", 0)
        assertNotNull(backToV1)
        assertEquals(2, backToV1!!.size)
        assertEquals("prompt v1", backToV1[0].content)
        assertEquals("reply v1", backToV1[1].content)
        assertEquals(0, backToV1[0].versionState?.selected)

        // Switch to v2 (index 1) - must restore v2 reply
        val restoredV2 = selectMessageVersion(backToV1, "u1", 1)
        assertNotNull(restoredV2)
        assertEquals(2, restoredV2!!.size)
        assertEquals("prompt v2", restoredV2[0].content)
        assertEquals("reply v2", restoredV2[1].content)
        assertEquals(1, restoredV2[0].versionState?.selected)
    }

    @Test
    fun `multiple edits retain all versions`() {
        val initial = listOf(createMessage("u1", MessageRole.USER, "version 0"))
        val v1 = forkMessageVersion(initial, "u1", "version 1")
        assertNotNull(v1)
        val v2 = forkMessageVersion(v1!!, "u1", "version 2")
        assertNotNull(v2)

        val head = v2!![0]
        val state = head.versionState
        assertNotNull(state)
        assertEquals(2, state!!.selected)
        assertEquals(3, state.branches.size)
        assertEquals("version 0", state.branches[0][0].content)
        assertEquals("version 1", state.branches[1][0].content)
        assertEquals("version 2", state.branches[2][0].content)
        assertEquals("version 2", head.content)
    }

    @Test
    fun `invalid inputs and streaming are rejected`() {
        val validUser = createMessage("u1", MessageRole.USER, "hello")
        val streamingUser = createMessage("u2", MessageRole.USER, "streaming", isStreaming = true)
        val agentMsg = createMessage("a1", MessageRole.AGENT, "agent")

        // Reject blank edit
        assertNull(forkMessageVersion(listOf(validUser), "u1", "   "))

        // Reject invalid id
        assertNull(forkMessageVersion(listOf(validUser), "non-existent", "test"))

        // Reject streaming in conversation
        assertNull(forkMessageVersion(listOf(validUser, streamingUser), "u1", "test"))
        assertNull(forkMessageVersion(listOf(streamingUser), "u2", "test"))

        // Reject anchor that is agent or system
        assertNull(forkMessageVersion(listOf(agentMsg), "a1", "test"))

        // Selection validations
        val forked = forkMessageVersion(listOf(validUser), "u1", "v2")!!
        // Invalid index
        assertNull(selectMessageVersion(forked, "u1", -1))
        assertNull(selectMessageVersion(forked, "u1", 99))
        // Invalid id
        assertNull(selectMessageVersion(forked, "non-existent", 0))
        // Same selected index returns original messages instance
        val same = selectMessageVersion(forked, "u1", 1)
        assertSame(forked, same)
        // Operation while streaming
        val withStreaming = forked + streamingUser
        assertNull(selectMessageVersion(withStreaming, "u1", 0))
    }

    @Test
    fun `snapshots own head versionState is null`() {
        val anchor = createMessage("u1", MessageRole.USER, "hello")
        val reply = createMessage("a1", MessageRole.AGENT, "world")
        val forked = forkMessageVersion(listOf(anchor, reply), "u1", "hello edited")!!

        val state = forked[0].versionState
        assertNotNull(state)
        for (branch in state!!.branches) {
            assertTrue(branch.isNotEmpty())
            assertNull("Branch head must have null versionState", branch[0].versionState)
        }
    }

    @Test
    fun `later nested version metadata survives outer switch`() {
        // Outer user message u1, nested user message u2
        val u1 = createMessage("u1", MessageRole.USER, "outer v1")
        val a1 = createMessage("a1", MessageRole.AGENT, "outer reply")
        val u2 = createMessage("u2", MessageRole.USER, "nested v1")
        val a2 = createMessage("a2", MessageRole.AGENT, "nested reply")

        val conversation = listOf(u1, a1, u2, a2)

        // Nested edit u2 -> nested v2
        val nestedForked = forkMessageVersion(conversation, "u2", "nested v2")!!
        assertEquals(3, nestedForked.size)
        assertNotNull(nestedForked[2].versionState)
        assertEquals("nested v2", nestedForked[2].content)

        // Now fork outer u1 -> outer v2
        val outerForked = forkMessageVersion(nestedForked, "u1", "outer v2")!!
        assertEquals(1, outerForked.size)
        assertEquals("outer v2", outerForked[0].content)

        // Switch outer back to v1
        val outerRestored = selectMessageVersion(outerForked, "u1", 0)!!
        assertEquals(3, outerRestored.size)
        assertEquals("outer v1", outerRestored[0].content)
        assertEquals("outer reply", outerRestored[1].content)
        assertEquals("nested v2", outerRestored[2].content)

        // Check that nested version metadata on u2 survived
        val nestedState = outerRestored[2].versionState
        assertNotNull(nestedState)
        assertEquals(1, nestedState!!.selected)
        assertEquals(2, nestedState.branches.size)
        assertEquals("nested v1", nestedState.branches[0][0].content)
        assertEquals("nested v2", nestedState.branches[1][0].content)
    }
}
