package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class ArtifactLibraryTest {
    @Test fun savedAnswerRoundTripKeepsMarkdownAndStableIdentity() {
        val message = ChatMessage(id = "answer", role = MessageRole.AGENT, timestamp = "", content = "# План\n\n```kt\nval x = 1\n```")
        val document = ArtifactLibrary.savedMessage(message)!!
        assertEquals("План", document.title)
        val decoded = ArtifactLibrary.decode(ArtifactLibrary.encode(listOf(document, document)))
        assertEquals(listOf(document), decoded)
        assertEquals(message.content, decoded.single().content)
    }

    @Test fun remoteDocumentsAndUnfinishedAnswersAreNotPersistedAsSavedCopies() {
        val remote = ArtifactDocument(id = "remote", title = "Report", summary = "", content = "server content")
        assertEquals("[]", ArtifactLibrary.encode(listOf(remote)))
        val message = ChatMessage(role = MessageRole.AGENT, timestamp = "", content = "Partial", isStreaming = true)
        assertNull(ArtifactLibrary.savedMessage(message))
        assertTrue(ArtifactLibrary.decode("broken").isEmpty())
    }
}
