package com.google.antigravity

import android.app.Application
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.model.*
import com.google.antigravity.network.DirectOpenRouterImageClient
import com.google.antigravity.network.DirectOpenRouterVideoClient
import com.google.antigravity.viewmodel.AntigravityViewModel
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DirectImageIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val encoded = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNQi1kFAAHYAS18RkYRAAAAAElFTkSuQmCC"
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, block: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < until) { if (block()) return; SystemClock.sleep(60) }
        fail(label)
    }
    private fun verify(temporary: Boolean, leaveChat: Boolean = false) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val posts = AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(if (leaveChat) 1 else 0)
        val history = ChatSessionStore(File(context.filesDir, "chat_history"))
        val imageClient = DirectOpenRouterImageClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("openrouter.ai", request.url.host)
            assertEquals("Bearer qa-direct-image-key", request.header("Authorization"))
            val body = if (request.method == "GET") {
                assertEquals("/api/v1/images/models", request.url.encodedPath)
                """{"data":[{"id":"qa/direct-image"}]}"""
            } else {
                assertEquals("/api/v1/images", request.url.encodedPath); posts.incrementAndGet()
                entered.countDown()
                check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
                """{"data":[{"b64_json":"$encoded"}]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("QA")
                .body(body.toResponseBody()).build()
        }.build())
        val store = ViewModelStore()
        lateinit var vm: AntigravityViewModel
        lateinit var original: AntigravitySettings
        var configured = false
        var chatId = ""
        var generated: File? = null
        try {
            main {
                vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AntigravityViewModel(context.applicationContext as Application, DirectOpenRouterVideoClient(), imageClient) as T
                })[AntigravityViewModel::class.java]
                original = vm.settings.value
                check(vm.openRouterApiKey.value.isBlank()) { "Do not overwrite a real key" }
                configured = true
                vm.updateSettings { it.copy(executionTarget = AgentExecutionTarget.PHONE_LOCAL_RESOURCES,
                    pcBridgeUrl = "http://127.0.0.1:1", forcePublicTunnelOnly = false) }
                vm.saveOpenRouterApiKey("qa-direct-image-key")
                if (temporary) vm.startTemporaryChat() else vm.startNewChat()
                vm.generateOpenRouterMediaInChat("QA image on phone", "qa/direct-image", false)
                chatId = vm.settings.value.activeConversationId
            }
            if (leaveChat) {
                check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                main { vm.startNewChat() }
                release.countDown()
            }
            await("Image attached to original chat") {
                !vm.isGeneratingMedia.value && (if (leaveChat) history.load().firstOrNull { it.id == chatId }?.messages.orEmpty()
                    else vm.messages.value).any { it.generatedMedia.isNotEmpty() }
            }
            val media = (if (leaveChat) history.load().first { it.id == chatId }.messages
                else vm.messages.value).flatMap { it.generatedMedia }.single()
            if (leaveChat) assertTrue(vm.messages.value.none { it.generatedMedia.isNotEmpty() })
            val file = File(media.localPath).canonicalFile; generated = file
            assertEquals(File(context.filesDir, "generated_media").canonicalFile, file.parentFile)
            assertTrue(file.name.startsWith("openrouter_") && file.extension == "png")
            assertEquals("", media.mediaUrl); assertEquals(file, File(requireNotNull(media.localPhonePath)).canonicalFile)
            assertEquals(1, media.width); assertEquals(1, media.height)
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            assertNotNull("A real PNG must decode", bitmap); assertEquals(1, bitmap.width); bitmap.recycle()
            assertEquals(1, posts.get())
            val saved = ChatSessionStore(File(context.filesDir, "chat_history")).load().firstOrNull { it.id == chatId }
            if (temporary) assertNull(saved)
            else assertEquals(media.localPath, saved!!.messages.flatMap { it.generatedMedia }.single().localPath)
        } finally {
            release.countDown()
            if (configured) main {
                vm.saveOpenRouterApiKey(""); vm.startNewChat()
                vm.updateSettings { it.copy(pcBridgeUrl = original.pcBridgeUrl, executionTarget = original.executionTarget,
                    forcePublicTunnelOnly = original.forcePublicTunnelOnly, selectedModelId = original.selectedModelId) }
                store.clear()
            }
            main { store.clear() }
            if (configured) await("Clear dummy image credential") {
                val prefs = File(context.applicationInfo.dataDir, "shared_prefs/antigravity_prefs_v4.xml")
                !prefs.exists() || !prefs.readText().contains("qa-direct-image-key")
            }
            generated?.let { check(it.parentFile == File(context.filesDir, "generated_media").canonicalFile); check(it.delete()) }
        }
    }
    @Test(timeout = 60000) fun imageIsDecodedStoredAndSavedInPhoneChatWithoutPc() = verify(false)
    @Test(timeout = 60000) fun temporaryImageIsNotWrittenToChatHistory() = verify(true)
    @Test(timeout = 60000) fun switchingChatsKeepsImageInOriginalHistory() = verify(false, true)
}
