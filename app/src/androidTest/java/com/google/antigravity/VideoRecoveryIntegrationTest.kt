package com.google.antigravity

import android.app.Application
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.model.*
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

/** Run phase 1, host force-stop, POST fixture /qa/complete, then phase 2 in a new process. */
@RunWith(AndroidJUnit4::class)
class VideoRecoveryIntegrationTest {
    @get:org.junit.Rule val bridgeFixture = LocalBridgeFixtureRule(8814, 8815)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
    private val metadata get() = File(context.filesDir, "qa_video_recovery.json")
    private fun requireHostPhase(method: String) {
        org.junit.Assume.assumeTrue("This phase requires host-controlled process termination",
            InstrumentationRegistry.getArguments().getString("class") == "com.google.antigravity.VideoRecoveryIntegrationTest#$method")
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun state(): JSONObject = http.newCall(Request.Builder().url("http://127.0.0.1:8814/qa/state").build())
        .execute().use { JSONObject(it.body!!.string()) }
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; SystemClock.sleep(80) }
        fail(label)
    }
    private fun viewModel(store: ViewModelStore): AntigravityViewModel = ViewModelProvider(store,
        ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))[AntigravityViewModel::class.java]

    @Test(timeout = 60000) fun phase1SubmitAndLeaveAnotherChatBeforeProcessDeath() {
        requireHostPhase("phase1SubmitAndLeaveAnotherChatBeforeProcessDeath")
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertTrue("Finish any previous QA recovery first", VideoRunStore(File(context.filesDir, VideoRunStore.FILE_NAME)).load().isEmpty())
        val store = ViewModelStore()
        lateinit var vm: AntigravityViewModel
        lateinit var original: AntigravitySettings
        main {
            vm = viewModel(store)
            assertTrue("No real API key in QA VM", vm.openRouterApiKey.value.isBlank())
            original = vm.settings.value
            vm.startNewChat()
            vm.updateSettings { it.copy(pcBridgeUrl = "http://127.0.0.1:8814", forcePublicTunnelOnly = false) }
            vm.generateOpenRouterMediaInChat("QA process recovery only", "qa/video-model", true)
        }
        await("One provider submit must start") { state().getInt("posts") == 1 }
        val run = VideoRunStore(File(context.filesDir, VideoRunStore.FILE_NAME)).load().single()
        assertTrue(run.bridgeUrl in setOf("http://127.0.0.1:8814", "http://10.0.2.2:8814"))
        assertTrue(vm.isGeneratingMedia.value)
        val otherId = "qa_other_" + UUID.randomUUID().toString()
        metadata.writeText(JSONObject().put("requestId", run.requestId).put("conversationId", run.conversationId)
            .put("userMessageId", run.userMessageId).put("otherId", otherId).put("bridge", run.bridgeUrl)
            .put("pid", android.os.Process.myPid())
            .put("originalBridge", original.pcBridgeUrl).put("originalPublicOnly", original.forcePublicTunnelOnly)
            .put("originalTarget", original.executionTarget.name).toString())
        main {
            vm.startNewChat()
            vm.updateSettings { it.copy(pcBridgeUrl = "http://127.0.0.1:8815") }
            vm.connectToExistingRemoteSession(RemoteSessionItem(id = otherId, title = "QA other chat", hostType = "PHONE"))
            vm.stopActiveGeneration() // Persists the other chat; media observation belongs to A.
        }
        assertEquals(otherId, vm.settings.value.activeConversationId)
        assertEquals(1, state().getInt("posts"))
        // Instrumentation terminates its process on return, without Activity's normal
        // queued-work drain. Observe apply() on disk before intentionally killing it.
        val preferencesFile = File(context.applicationInfo.dataDir, "shared_prefs/antigravity_prefs_v4.xml")
        await("Active chat selection must reach disk before host termination") {
            runCatching { preferencesFile.readText().contains("<string name=\"active_conversation_id\">$otherId</string>") }.getOrDefault(false)
        }
        // Intentionally no store.clear(): the host terminates the actual app process next.
    }

    @Test(timeout = 60000) fun phase2RestoreStatusOnlyAndKeepResultInOriginalChat() {
        requireHostPhase("phase2RestoreStatusOnlyAndKeepResultInOriginalChat")
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val saved = JSONObject(metadata.readText())
        val requestId = saved.getString("requestId")
        val conversationId = saved.getString("conversationId")
        assertTrue("Must run in a new Android process", saved.getInt("pid") != android.os.Process.myPid())
        // Simulate an uncommitted/lost source history snapshot using strictly this QA chat.
        val name = java.security.MessageDigest.getInstance("SHA-256").digest(conversationId.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".json"
        val historyDirectory = File(context.filesDir, "chat_history").canonicalFile
        val sourceFile = File(historyDirectory, name).canonicalFile
        assertEquals(historyDirectory, sourceFile.parentFile)
        if (sourceFile.exists()) {
            val source = JSONObject(sourceFile.readText())
            assertEquals(conversationId, source.getString("id"))
            assertEquals("QA process recovery only", source.getString("title"))
            assertTrue(sourceFile.delete())
        }
        val store = ViewModelStore()
        lateinit var vm: AntigravityViewModel
        try {
            main { vm = viewModel(store) }
            val histories = ChatSessionStore(File(context.filesDir, "chat_history"))
            await("Recovered job must deliver to its source history and clear checkpoint") {
                val message = histories.load().firstOrNull { it.id == conversationId }?.messages?.firstOrNull { it.id == requestId }
                message?.generatedMedia?.any { it.localPath.isNotBlank() && File(it.localPath).isFile } == true &&
                    VideoRunStore(File(context.filesDir, VideoRunStore.FILE_NAME)).load().none { it.requestId == requestId }
            }
            val messages = histories.load().first { it.id == conversationId }.messages
            assertEquals(1, messages.count { it.id == requestId })
            assertEquals(1, messages.count { it.id == saved.getString("userMessageId") })
            assertEquals(saved.getString("otherId"), vm.settings.value.activeConversationId)
            assertTrue(vm.messages.value.none { it.id == requestId })
            assertTrue(vm.remoteSessions.value.any { it.id == conversationId })
            assertEquals("http://127.0.0.1:8815", vm.settings.value.pcBridgeUrl)
            assertEquals(1, state().getInt("posts"))
            assertEquals(0, state().getInt("wrongBridgeCalls"))
            val media = messages.first { it.id == requestId }.generatedMedia.single()
            assertTrue(media.mediaUrl.startsWith(saved.getString("bridge") + "/"))
            val file = File(media.localPath).canonicalFile
            assertEquals("openrouter_video_$requestId.mp4", file.name)
            val roots = listOf(context.filesDir.canonicalFile, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile)
            assertTrue(roots.any { file.path.startsWith(it.path + File.separator) })
            assertEquals(24L, file.length())
            assertTrue(file.delete())
        } finally {
            main {
                vm.updateSettings { it.copy(pcBridgeUrl = saved.getString("originalBridge"),
                    forcePublicTunnelOnly = saved.getBoolean("originalPublicOnly"),
                    executionTarget = AgentExecutionTarget.valueOf(saved.getString("originalTarget"))) }
                store.clear()
            }
            val preferencesFile = File(context.applicationInfo.dataDir, "shared_prefs/antigravity_prefs_v4.xml")
            await("Restore original bridge before instrumentation exits") {
                runCatching { preferencesFile.readText().contains(saved.getString("originalBridge")) }.getOrDefault(false)
            }
        }
    }
}
