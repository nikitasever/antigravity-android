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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run after the host has set the controlled fixture /qa/complete flag. */
@RunWith(AndroidJUnit4::class)
class TemporaryVideoPrivacyTest {
    @get:org.junit.Rule val bridgeFixture = LocalBridgeFixtureRule(8814)
    @Test(timeout = 60000) fun temporaryVideoDoesNotCreateChatOrCheckpoint(): Unit {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = ViewModelStore()
        lateinit var vm: AntigravityViewModel
        lateinit var original: AntigravitySettings
        lateinit var transientId: String
        try {
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application))[AntigravityViewModel::class.java]
                original = vm.settings.value
                assertTrue(vm.openRouterApiKey.value.isBlank())
                vm.updateSettings { it.copy(pcBridgeUrl = "http://127.0.0.1:8814", forcePublicTunnelOnly = false) }
                vm.startTemporaryChat()
                transientId = vm.settings.value.activeConversationId
                vm.generateOpenRouterMediaInChat("QA private temporary video", "qa/video-model", true)
            }
            val until = SystemClock.elapsedRealtime() + 20000
            while (SystemClock.elapsedRealtime() < until && vm.messages.value.none { it.generatedMedia.isNotEmpty() }) SystemClock.sleep(80)
            val media = vm.messages.value.flatMap { it.generatedMedia }.single()
            assertTrue(VideoRunStore(File(context.filesDir, VideoRunStore.FILE_NAME)).load().none { it.conversationId == transientId })
            assertTrue(ChatSessionStore(File(context.filesDir, "chat_history")).load().none { it.id == transientId })
            assertTrue(vm.remoteSessions.value.none { it.id == transientId })
            val file = File(media.localPath).canonicalFile
            assertTrue(file.name.startsWith("openrouter_video_media_gen_"))
            val roots = listOf(context.filesDir.canonicalFile, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile)
            assertTrue(roots.any { file.path.startsWith(it.path + File.separator) })
            assertTrue(file.delete())
        } finally {
            instrumentation.runOnMainSync {
                vm.startNewChat()
                vm.updateSettings { it.copy(pcBridgeUrl = original.pcBridgeUrl, forcePublicTunnelOnly = original.forcePublicTunnelOnly) }
                store.clear()
            }
        }
        assertTrue(ChatSessionStore(File(context.filesDir, "chat_history")).load().none { it.id == transientId })
    }
}
