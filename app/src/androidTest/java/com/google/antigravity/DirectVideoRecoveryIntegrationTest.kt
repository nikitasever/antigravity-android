package com.google.antigravity

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.model.*
import com.google.antigravity.network.DirectOpenRouterVideoClient
import com.google.antigravity.viewmodel.AntigravityViewModel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class DirectVideoRecoveryIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val metadata get() = File(context.filesDir, "qa_direct_video.json")
    private val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun phase(method: String) {
        org.junit.Assume.assumeTrue("Requires host process restart between phases",
            InstrumentationRegistry.getArguments().getString("class") == "com.google.antigravity.DirectVideoRecoveryIntegrationTest#$method")
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
    }
    private fun state() = http.newCall(Request.Builder().url("http://127.0.0.1:8816/qa/state").build()).execute().use { JSONObject(it.body!!.string()) }
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; SystemClock.sleep(60) }
        fail(label)
    }
    private fun transportClient(): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.isHttps && request.url.host in setOf("openrouter.ai", "cdn.qa.example"))
            val url = "http://127.0.0.1:8816" + request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
            chain.proceed(request.newBuilder().url(url).build())
        }.build()
    private fun vm(store: ViewModelStore): AntigravityViewModel {
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AntigravityViewModel(context.applicationContext as Application, DirectOpenRouterVideoClient(transportClient(), 30000)) as T
        })[AntigravityViewModel::class.java]
    }
    private fun checkpoint() = VideoRunStore(File(context.filesDir, VideoRunStore.FILE_NAME))
    private fun preferences() = File(context.applicationInfo.dataDir, "shared_prefs/antigravity_prefs_v4.xml")

    /** Finish only a failed owned QA setup; never discard an accepted provider job to make a test green. */
    @Test(timeout = 60000) fun cleanupRejectedQaSetup(): Unit = runBlocking {
        phase("cleanupRejectedQaSetup")
        val run = checkpoint().load().single()
        assertEquals("qa/direct-video", run.model)
        assertEquals("QA direct API recovery", run.prompt)
        assertTrue(run.providerJobId.startsWith("qa_provider_"))
        val media = DirectOpenRouterVideoClient(transportClient(), 0).execute(run, "qa-direct-video-key",
            File(context.filesDir, "generated_media"), { fail("Must not submit") }, { fail("ID already known") }, {})
        val history = ChatSessionStore(File(context.filesDir, "chat_history"))
        val chat = history.load().single { it.id == run.conversationId }
        assertTrue(chat.messages.any { it.id == run.requestId })
        history.save(chat.copy(messages = chat.messages.map { if (it.id == run.requestId) it.copy(
            content = "QA setup completed during cleanup", isStreaming = false, generatedMedia = listOf(media)) else it }))
        checkpoint().remove(run.requestId)
        val store = ViewModelStore()
        main {
            val model = vm(store)
            assertEquals("qa-direct-video-key", model.openRouterApiKey.value)
            model.updateSettings { it.copy(executionTarget = AgentExecutionTarget.PHONE_LOCAL_RESOURCES) }
            model.saveOpenRouterApiKey("")
            model.startNewChat()
            store.clear()
        }
        await("Clear controlled credential") { !preferences().readText().contains("qa-direct-video-key") }
        val file = File(media.localPath).canonicalFile
        assertEquals(File(context.filesDir, "generated_media").canonicalFile, file.parentFile)
        assertEquals("openrouter_video_${run.requestId}.mp4", file.name)
        assertEquals(24L, file.length()); assertTrue(file.delete())
    }

    @Test(timeout = 60000) fun temporaryDirectTaskKeepsPromptOutOfHistoryAndJournal() {
        phase("temporaryDirectTaskKeepsPromptOutOfHistoryAndJournal")
        val before = state().getInt("posts")
        val store = ViewModelStore()
        lateinit var model: AntigravityViewModel
        lateinit var original: AntigravitySettings
        lateinit var id: String
        try {
            main {
                model = vm(store); original = model.settings.value
                assertTrue(model.openRouterApiKey.value.isBlank())
                model.updateSettings { it.copy(executionTarget = AgentExecutionTarget.PHONE_LOCAL_RESOURCES,
                    pcBridgeUrl = "http://127.0.0.1:8817", forcePublicTunnelOnly = false) }
                model.saveOpenRouterApiKey("qa-direct-video-key")
                model.startTemporaryChat()
                model.generateOpenRouterMediaInChat("QA direct private video", "qa/direct-video", true)
                id = model.settings.value.activeConversationId
            }
            await("Temporary phone output") { model.messages.value.any { it.generatedMedia.isNotEmpty() } && !model.isGeneratingMedia.value }
            assertTrue(checkpoint().load().none { it.conversationId == id })
            assertTrue(ChatSessionStore(File(context.filesDir, "chat_history")).load().none { it.id == id })
            assertTrue(model.remoteSessions.value.none { it.id == id })
            val media = model.messages.value.flatMap { it.generatedMedia }.single()
            val file = File(media.localPath).canonicalFile
            assertEquals(File(context.filesDir, "generated_media").canonicalFile, file.parentFile)
            assertTrue(file.name.startsWith("openrouter_video_media_gen_"))
            assertEquals(24L, file.length()); assertTrue(file.delete())
            assertEquals(before + 1, state().getInt("posts"))
            assertEquals(0, state().getInt("forbiddenBridgePosts")); assertFalse(state().getBoolean("bridgeReceivedCredential"))
            assertFalse(state().getBoolean("cdnReceivedAuthorization"))
        } finally {
            main {
                model.saveOpenRouterApiKey("")
                model.startNewChat()
                model.updateSettings { it.copy(pcBridgeUrl = original.pcBridgeUrl, forcePublicTunnelOnly = original.forcePublicTunnelOnly,
                    executionTarget = original.executionTarget, selectedModelId = original.selectedModelId) }
                store.clear()
            }
            await("Clear temporary test credential") { !preferences().readText().contains("qa-direct-video-key") }
        }
    }

    @Test(timeout = 60000) fun phase1SubmitPauseResumeThenLeaveChat() {
        phase("phase1SubmitPauseResumeThenLeaveChat")
        assertTrue(checkpoint().load().isEmpty())
        val store = ViewModelStore()
        lateinit var model: AntigravityViewModel
        lateinit var original: AntigravitySettings
        main {
            model = vm(store); original = model.settings.value
            assertTrue("Do not copy a real key into QA", model.openRouterApiKey.value.isBlank())
            metadata.writeText(JSONObject().put("pid", android.os.Process.myPid()).put("originalBridge", original.pcBridgeUrl)
                .put("originalTarget", original.executionTarget.name).put("originalPublicOnly", original.forcePublicTunnelOnly)
                .put("originalModel", original.selectedModelId).toString())
            model.startNewChat()
            model.updateSettings { it.copy(executionTarget = AgentExecutionTarget.PHONE_LOCAL_RESOURCES,
                pcBridgeUrl = "http://127.0.0.1:8817", forcePublicTunnelOnly = false) }
            model.saveOpenRouterApiKey("qa-direct-video-key")
            model.selectCustomOpenRouterModelId("qa/direct-video")
            model.generateOpenRouterMediaInChat("QA direct API recovery", "qa/direct-video", true)
        }
        await("Provider ID must be persisted on phone") { checkpoint().load().singleOrNull()?.providerJobId?.isNotBlank() == true }
        val run = checkpoint().load().single()
        assertEquals(VideoTransport.PHONE_API, run.transport)
        assertEquals(VideoRun.DIRECT_API_BASE, run.bridgeUrl)
        assertTrue(run.submissionAttempted)
        assertFalse(File(context.filesDir, VideoRunStore.FILE_NAME).readText().contains("qa-direct-video-key"))
        main { model.stopVisibleGeneration() }
        await("Direct pause must preserve provider ID") { checkpoint().load().single().paused && !model.isGeneratingMedia.value }
        assertEquals(run.providerJobId, checkpoint().load().single().providerJobId)
        main { model.resumeVideoTask(run.requestId); model.resumeVideoTask(run.requestId) }
        await("Resume must observe the same provider") { model.isGeneratingMedia.value && state().getInt("polls") > 0 }
        assertEquals(1, state().getInt("posts"))
        val otherId = "qa_direct_other_" + UUID.randomUUID()
        metadata.writeText(JSONObject().put("requestId", run.requestId).put("conversationId", run.conversationId)
            .put("providerJobId", run.providerJobId).put("otherId", otherId).put("pid", android.os.Process.myPid())
            .put("originalBridge", original.pcBridgeUrl).put("originalTarget", original.executionTarget.name)
            .put("originalPublicOnly", original.forcePublicTunnelOnly).put("originalModel", original.selectedModelId).toString())
        main {
            model.startNewChat()
            model.connectToExistingRemoteSession(RemoteSessionItem(id = otherId, title = "QA other direct chat", hostType = "PHONE"))
            model.stopActiveGeneration()
        }
        await("Flush selected other chat before instrumentation exits") {
            runCatching { preferences().readText().contains(otherId) }.getOrDefault(false)
        }
        assertEquals(0, state().getInt("forbiddenBridgePosts"))
        assertFalse(state().getBoolean("bridgeReceivedCredential"))
        // Host terminates process, marks fixture complete, then runs phase 2 in a new PID.
    }

    @Test(timeout = 60000) fun phase2RestoreDirectTaskWithoutPcOrNewSubmit() {
        phase("phase2RestoreDirectTaskWithoutPcOrNewSubmit")
        val saved = JSONObject(metadata.readText())
        assertTrue(saved.getInt("pid") != android.os.Process.myPid())
        val store = ViewModelStore()
        lateinit var model: AntigravityViewModel
        try {
            main { model = vm(store) }
            val histories = ChatSessionStore(File(context.filesDir, "chat_history"))
            await("Direct result delivered from restored provider ID") {
                histories.load().firstOrNull { it.id == saved.getString("conversationId") }?.messages
                    ?.firstOrNull { it.id == saved.getString("requestId") }?.generatedMedia?.any { it.localPath.isNotBlank() } == true && checkpoint().load().isEmpty()
            }
            val messages = histories.load().first { it.id == saved.getString("conversationId") }.messages
            val media = messages.single { it.id == saved.getString("requestId") }.generatedMedia.single()
            assertEquals(1, messages.count { it.role == MessageRole.USER })
            assertEquals("", media.mediaUrl)
            val file = File(media.localPath).canonicalFile
            assertEquals(File(context.filesDir, "generated_media").canonicalFile, file.parentFile)
            assertEquals("openrouter_video_${saved.getString("requestId")}.mp4", file.name)
            assertEquals(24L, file.length()); assertTrue(file.delete())
            assertEquals(saved.getString("otherId"), model.settings.value.activeConversationId)
            assertTrue(model.messages.value.none { it.id == saved.getString("requestId") })
            assertEquals(1, state().getInt("posts"))
            assertEquals(1, state().getInt("downloads"))
            assertEquals(0, state().getInt("forbiddenBridgePosts"))
            assertFalse(state().getBoolean("bridgeReceivedCredential"))
            assertFalse(state().getBoolean("cdnReceivedAuthorization"))
        } finally {
            main {
                model.updateSettings { it.copy(executionTarget = AgentExecutionTarget.PHONE_LOCAL_RESOURCES) }
                model.saveOpenRouterApiKey("")
                model.updateSettings { it.copy(pcBridgeUrl = saved.getString("originalBridge"),
                    forcePublicTunnelOnly = saved.getBoolean("originalPublicOnly"),
                    executionTarget = AgentExecutionTarget.valueOf(saved.getString("originalTarget")), selectedModelId = saved.getString("originalModel")) }
                store.clear()
            }
            await("Remove dummy key and restore QA settings before exit") {
                runCatching { !preferences().readText().contains("qa-direct-video-key") && preferences().readText().contains(saved.getString("originalBridge")) }.getOrDefault(false)
            }
        }
    }
}
