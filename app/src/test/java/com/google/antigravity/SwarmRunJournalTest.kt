package com.google.antigravity

import com.google.antigravity.model.SwarmRunJournal
import com.google.antigravity.model.SwarmRunOrigin
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SwarmRunJournalTest {
    private fun fixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("swarm-journal").toFile()
        try { block(File(directory, SwarmRunJournal.FILE_NAME)) } finally { directory.deleteRecursively() }
    }
    private fun run(id: String = "A") = SwarmRunOrigin(id, "chat-$id", "https://bridge-$id.example")

    @Test fun routingAndEarlyCancellationSurviveNewStoreInstances() = fixture { file ->
        SwarmRunJournal(file).remember(run())
        assertTrue(SwarmRunJournal(file).requestCancellation("A"))
        val restored = SwarmRunJournal(file).load().single()
        assertEquals("chat-A", restored.conversationId)
        assertEquals("https://bridge-A.example", restored.bridgeUrl)
        assertTrue(restored.cancellationRequested)
    }
    @Test fun unknownNotificationCannotCreateOrCancelAnotherRun() = fixture { file ->
        val store = SwarmRunJournal(file)
        store.remember(run("B"))
        assertFalse(store.requestCancellation("A"))
        assertFalse(store.requestCancellation(""))
        assertFalse(store.load().single().cancellationRequested)
    }
    @Test fun staleWriterCannotClearCancellationOrChangeOwnership() = fixture { file ->
        val store = SwarmRunJournal(file)
        store.remember(run())
        store.requestCancellation("A")
        store.remember(run())
        assertTrue(store.load().single().cancellationRequested)
        assertThrows(IllegalArgumentException::class.java) { store.remember(run().copy(bridgeUrl = "https://other.example")) }
        assertEquals("https://bridge-A.example", store.load().single().bridgeUrl)
    }
    @Test fun expiredFutureCorruptAndUnknownVersionDataCannotResumeTasks() = fixture { file ->
        val timestamp = 1000000000L
        SwarmRunJournal(file) { timestamp }.remember(run())
        assertTrue(SwarmRunJournal(file) { timestamp + 8L * 24 * 60 * 60 * 1000 }.load().isEmpty())
        assertTrue(SwarmRunJournal(file) { timestamp - 600000 }.load().isEmpty())
        file.writeText("{\"version\":99,\"runs\":[]}")
        assertTrue(SwarmRunJournal(file).load().isEmpty())
        file.writeText("{truncated")
        assertTrue(SwarmRunJournal(file).load().isEmpty())
    }
    @Test fun orphanTemporaryFileDoesNotReplaceCommittedCheckpoint() = fixture { file ->
        SwarmRunJournal(file).remember(run())
        File(file.parentFile, file.name + ".tmp").writeText("{broken")
        assertEquals("A", SwarmRunJournal(file).load().single().requestId)
    }
    @Test fun boundedJournalDoesNotEvictPendingCancellations() = fixture { file ->
        val store = SwarmRunJournal(file)
        repeat(32) { index -> store.remember(run("$index")); store.requestCancellation("$index") }
        assertThrows(java.io.IOException::class.java) { store.remember(run("overflow")) }
        assertEquals(32, store.load().size)
    }
}
