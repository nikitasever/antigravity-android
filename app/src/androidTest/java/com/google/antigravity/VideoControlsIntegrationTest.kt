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
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real ViewModel + controlled bridge --recovery; never calls a commercial provider. */
@RunWith(AndroidJUnit4::class)
class VideoControlsIntegrationTest {
    @get:org.junit.Rule val bridgeFixture = LocalBridgeFixtureRule(8814, 8815)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun state(): JSONObject = http.newCall(Request.Builder().url("http://127.0.0.1:8814/qa/state").build())
        .execute().use { JSONObject(it.body!!.string()) }
    private fun fixture(action: String) {
        http.newCall(Request.Builder().url("http://127.0.0.1:8814/qa/$action").post("{}".toRequestBody()).build())
            .execute().use { assertTrue(it.isSuccessful) }
    }
    private fun await(label: String, timeout: Long = 20000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; SystemClock.sleep(60) }
        fail(label)
    }
    private fun removeQaMedia(media: GeneratedMediaAttachment) {
        val file = File(media.localPath).canonicalFile
        assertTrue(file.name.startsWith("openrouter_video_media_gen_"))
        val roots = listOf(context.filesDir.canonicalFile, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile)
        assertTrue(roots.any { file.path.startsWith(it.path + File.separator) })
        assertEquals(24L, file.length())
        assertTrue(file.delete())
    }

    @Test(timeout = 60000) fun stopThenDoubleResumeUsesOnePaidJobAndOriginalChat(): Unit = exercise(false)
    @Test(timeout = 60000) fun temporaryStopAndResumeKeepsPromptOutOfJournal(): Unit = exercise(true)

    /** Explicit UI setup only: host opens the activity, presses Stop, then resumes in its menu. */
    @Test(timeout = 60000) fun prepareVideoForUi() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("class") ==
            "com.google.antigravity.VideoControlsIntegrationTest#prepareVideoForUi")
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        fixture("pending")
        val before = state().getInt("posts")
        val checkpoint = VideoRunStore(File(context.filesDir, VideoRunStore.FILE_NAME))
        assertTrue(checkpoint.load().isEmpty())
        val store = ViewModelStore()
        lateinit var vm: AntigravityViewModel
        lateinit var original: AntigravitySettings
        main {
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))[AntigravityViewModel::class.java]
            original = vm.settings.value
            assertTrue(vm.openRouterApiKey.value.isBlank())
            vm.startNewChat()
            vm.updateSettings { it.copy(pcBridgeUrl = "http://127.0.0.1:8814", forcePublicTunnelOnly = false) }
            vm.generateOpenRouterMediaInChat("QA video stop resume UI", "qa/video-model", true)
        }
        await("Provider accepted controlled UI job") { state().getInt("posts") == before + 1 }
        val run = checkpoint.load().single()
        File(context.filesDir, "qa_video_controls_ui.json").writeText(JSONObject()
            .put("requestId", run.requestId).put("conversationId", run.conversationId)
            .put("expectedPosts", before + 1).put("originalBridge", original.pcBridgeUrl).toString())
        main {
            vm.updateSettings { it.copy(pcBridgeUrl = original.pcBridgeUrl, forcePublicTunnelOnly = original.forcePublicTunnelOnly) }
            store.clear()
        }
        val preferences = File(context.applicationInfo.dataDir, "shared_prefs/antigravity_prefs_v4.xml")
        await("Flush activity startup state") {
            runCatching { preferences.readText().contains(original.pcBridgeUrl) && preferences.readText().contains(run.conversationId) }.getOrDefault(false)
        }
    }

    private fun exercise(temporary: Boolean) {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        fixture("pending")
        val before = state()
        val checkpoint = VideoRunStore(File(context.filesDir, VideoRunStore.FILE_NAME))
        assertTrue("Finish previous controlled runs first", checkpoint.load().isEmpty())
        val store = ViewModelStore()
        lateinit var vm: AntigravityViewModel
        lateinit var original: AntigravitySettings
        lateinit var sourceId: String
        try {
            main {
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))[AntigravityViewModel::class.java]
                original = vm.settings.value
                assertTrue("QA must not copy a real key", vm.openRouterApiKey.value.isBlank())
                vm.updateSettings { it.copy(pcBridgeUrl = "http://127.0.0.1:8814", forcePublicTunnelOnly = false) }
                if (temporary) vm.startTemporaryChat() else vm.startNewChat()
                vm.generateOpenRouterMediaInChat("QA video controls only", "qa/video-model", true)
                sourceId = vm.settings.value.activeConversationId
            }
            await("Initial paid submit") { state().getInt("posts") == before.getInt("posts") + 1 }
            val id = vm.messages.value.last().id
            main { vm.stopVisibleGeneration(); vm.stopVisibleGeneration() }
            await("Stopped observation and persisted pause") {
                !vm.isGeneratingMedia.value && vm.messages.value.any { it.id == id && it.content.startsWith("Ожидание видео остановлено") } &&
                    (temporary || checkpoint.load().singleOrNull()?.paused == true)
            }
            assertTrue(id in vm.pendingVideoTaskIds.value)
            assertFalse(vm.messages.value.any { it.isStreaming })
            if (temporary) assertTrue(checkpoint.load().isEmpty())
            val otherId = "qa_controls_other_" + UUID.randomUUID()
            if (!temporary) main {
                vm.startNewChat()
                vm.connectToExistingRemoteSession(RemoteSessionItem(id = otherId, title = "QA other controls", hostType = "PHONE"))
            }
            main { vm.updateSettings { it.copy(pcBridgeUrl = "http://127.0.0.1:8815") } }
            if (temporary) fixture("complete")
            main { vm.regenerateResponseForMessage(id); vm.regenerateResponseForMessage(id) }
            if (!temporary) {
                await("Background source task resumed once") { vm.isGeneratingMedia.value && state().getInt("polls") == before.getInt("polls") + 1 }
                main { vm.stopVisibleGeneration() } // B must not stop the observer belonging to A.
                assertTrue(vm.isGeneratingMedia.value)
                assertFalse(checkpoint.load().single().paused)
                fixture("complete")
            }
            val histories = ChatSessionStore(File(context.filesDir, "chat_history"))
            fun sourceMessages(): List<ChatMessage> = if (temporary) vm.messages.value else histories.load().firstOrNull { it.id == sourceId }?.messages.orEmpty()
            await("Status-only resume delivered to original chat", 45000) {
                sourceMessages().any { it.id == id && it.generatedMedia.any { media -> media.localPath.isNotBlank() } } &&
                    id !in vm.pendingVideoTaskIds.value && !vm.isGeneratingMedia.value
            }
            val messages = sourceMessages()
            assertEquals(1, messages.count { it.id == id })
            assertEquals(1, messages.count { it.role == MessageRole.USER })
            assertTrue(checkpoint.load().isEmpty())
            if (temporary) {
                assertTrue(histories.load().none { it.id == sourceId })
                assertTrue(vm.remoteSessions.value.none { it.id == sourceId })
            } else {
                assertEquals(otherId, vm.settings.value.activeConversationId)
                assertTrue(vm.messages.value.none { it.id == id })
            }
            val media = messages.first { it.id == id }.generatedMedia.single()
            assertTrue(media.mediaUrl.startsWith("http://127.0.0.1:8814/") || media.mediaUrl.startsWith("http://10.0.2.2:8814/"))
            removeQaMedia(media)
            val after = state()
            assertEquals(before.getInt("posts") + 1, after.getInt("posts"))
            assertEquals(before.getInt("polls") + if (temporary) 1 else 2, after.getInt("polls"))
            assertEquals(before.getInt("downloads") + 1, after.getInt("downloads"))
            assertEquals(before.getInt("wrongBridgeCalls"), after.getInt("wrongBridgeCalls"))
            assertEquals(before.getInt("textRequests"), after.getInt("textRequests"))
            val visible = vm.messages.value
            main { vm.regenerateResponseForMessage(id) } // Completed media must never enter the text turn path.
            assertEquals(visible, vm.messages.value)
            assertEquals(after.getInt("posts"), state().getInt("posts"))
        } finally {
            main {
                if (temporary) vm.startNewChat()
                vm.updateSettings { it.copy(pcBridgeUrl = original.pcBridgeUrl, forcePublicTunnelOnly = original.forcePublicTunnelOnly,
                    executionTarget = original.executionTarget) }
                store.clear()
            }
            val preferences = File(context.applicationInfo.dataDir, "shared_prefs/antigravity_prefs_v4.xml")
            await("Flush restored fixture URL before instrumentation exits") {
                runCatching { preferences.readText().contains(original.pcBridgeUrl) }.getOrDefault(false)
            }
        }
    }
}
