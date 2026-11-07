package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class VideoRunStoreTest {
    private fun sample(id: String = "request_A") = VideoRun(id, "user_$id", "conversation_A", "QA",
        AgentExecutionTarget.PHONE_LOCAL_RESOURCES, "https://bridge-A.example", "qa/video", "QA prompt", "12:00")
    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("video-runs-").toFile()
        try { block(File(dir, VideoRunStore.FILE_NAME)) } finally {
            check(dir.canonicalPath.startsWith(File(System.getProperty("java.io.tmpdir")).canonicalPath + File.separator))
            check(dir.name.startsWith("video-runs-"))
            dir.deleteRecursively()
        }
    }
    @Test fun originalRoutingAndKeySourceSurviveNewStoreInstances() = fixture { file ->
        VideoRunStore(file).save(sample().copy(keyHash = "a".repeat(64)))
        val restored = VideoRunStore(file).load().single()
        assertEquals("https://bridge-A.example", restored.bridgeUrl)
        assertEquals("conversation_A", restored.conversationId)
        assertEquals("a".repeat(64), restored.keyHash)
        assertFalse(file.readText().contains("apiKey"))
    }
    @Test fun mutableAttentionStateCannotRebindAnExistingRequest() = fixture { file ->
        val store = VideoRunStore(file)
        store.save(sample())
        store.save(sample().copy(paused = true, lastError = "Network paused"))
        assertTrue(store.load().single().paused)
        assertThrows(IllegalArgumentException::class.java) { store.save(sample().copy(bridgeUrl = "https://bridge-B.example")) }
        assertThrows(IllegalArgumentException::class.java) { store.save(sample().copy(model = "other")) }
        assertEquals("qa/video", store.load().single().model)
    }
    @Test fun removingOneDeliveredRunLeavesOtherPendingWork() = fixture { file ->
        val store = VideoRunStore(file); store.save(sample()); store.save(sample("request_B"))
        store.remove("request_A")
        assertEquals("request_B", store.load().single().requestId)
    }
    @Test fun corruptedOrUnknownSchemaCannotBeOverwrittenByANewPaidIntent() = fixture { file ->
        for (data in listOf("{broken", """{"version":99,"runs":[]}""")) {
            file.writeText(data)
            assertThrows(IOException::class.java) { VideoRunStore(file).load() }
            assertThrows(IOException::class.java) { VideoRunStore(file).save(sample()) }
            assertEquals(data, file.readText())
        }
    }
    @Test fun incompleteTemporaryWriteDoesNotReplaceCommittedOrigin() = fixture { file ->
        VideoRunStore(file).save(sample())
        File(file.parentFile, file.name + ".tmp").writeText("{broken")
        assertEquals("request_A", VideoRunStore(file).load().single().requestId)
    }
    @Test fun boundedJournalNeverEvictsUnresolvedRuns() = fixture { file ->
        val store = VideoRunStore(file)
        repeat(32) { store.save(sample("run_$it")) }
        assertThrows(IOException::class.java) { store.save(sample("overflow")) }
        assertEquals(32, store.load().size)
    }
    @Test fun credentialsOrInvalidRoutingCannotEnterCheckpoint() {
        for (url in listOf("https://user:password@bridge.example", "https://bridge.example?token=secret", "file:///tmp/video"))
            assertThrows(IllegalArgumentException::class.java) { sample().copy(bridgeUrl = url) }
        assertThrows(IllegalArgumentException::class.java) { sample().copy(keyHash = "plaintext-key") }
    }
    @Test fun directProviderIdentityIsMonotonicAcrossRestartAndCannotSwitchTransport() = fixture { file ->
        val store = VideoRunStore(file)
        val initial = sample().copy(transport = VideoTransport.PHONE_API, bridgeUrl = VideoRun.DIRECT_API_BASE, keyHash = "a".repeat(64))
        store.save(initial)
        store.save(initial.copy(submissionAttempted = true))
        val accepted = initial.copy(submissionAttempted = true, providerJobId = "provider_A")
        store.save(accepted)
        assertEquals(accepted, VideoRunStore(file).load().single())
        assertThrows(IllegalArgumentException::class.java) { store.save(initial) }
        assertThrows(IllegalArgumentException::class.java) { store.save(accepted.copy(providerJobId = "provider_B")) }
        assertThrows(IllegalArgumentException::class.java) { store.save(sample()) }
        assertThrows(IllegalArgumentException::class.java) { initial.copy(bridgeUrl = "https://other.example") }
    }
    @Test fun v1BridgeCheckpointMigratesWithoutChangingItsSource() = fixture { file ->
        VideoRunStore(file).save(sample())
        val legacy = org.json.JSONObject(file.readText()).put("version", 1)
        legacy.getJSONArray("runs").getJSONObject(0).apply {
            remove("transport"); remove("providerJobId"); remove("submissionAttempted")
        }
        file.writeText(legacy.toString())
        val store = VideoRunStore(file)
        assertEquals(sample(), store.load().single())
        store.save(sample().copy(paused = true))
        assertEquals(2, org.json.JSONObject(file.readText()).getInt("version"))
    }
}
