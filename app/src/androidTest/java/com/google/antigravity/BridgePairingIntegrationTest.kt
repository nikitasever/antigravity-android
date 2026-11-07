package com.google.antigravity

import android.os.Build
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.device.PhoneResourceController
import com.google.antigravity.network.*
import com.google.antigravity.voice.RealtimeVoiceEngine
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Requires owned fixture on 8819/8820 via adb reverse. No real owner credential or provider key. */
@RunWith(AndroidJUnit4::class)
class BridgePairingIntegrationTest {
    @Test(timeout = 60000) fun pairsWithKeystoreThenAuthenticatesBackendVoiceMediaAndBrowserWithoutCrossOriginLeak() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val base = "http://127.0.0.1:8819"
        val credentials = BridgeCredentials(context)
        assertNull("QA origin must not already be paired", credentials.tokenFor(base))
        val pairing = BridgePairingClient(credentials)
        val http = OkHttpClient.Builder().followRedirects(false).build()
        val authenticated = http.newBuilder().addInterceptor(credentials.interceptor()).build()
        val controller = PhoneResourceController(context)
        var downloaded: File? = null
        var voice: RealtimeVoiceEngine? = null
        var web: android.webkit.WebView? = null
        try {
            http.newCall(Request.Builder().url("$base/api/status").build()).execute().use { assertEquals(401, it.code) }
            assertTrue(pairing.pair(base, "123456"))
            val secret = requireNotNull(credentials.tokenFor(base))
            assertEquals(64, secret.length)
            assertNull(credentials.tokenFor("http://127.0.0.1:8820"))
            assertNull(credentials.tokenFor("https://openrouter.ai"))
            val stored = File(context.applicationInfo.dataDir, "shared_prefs/bridge_credentials_v1.xml").readText()
            assertFalse("Plain token must not be in prefs", stored.contains(secret))
            assertTrue(AntigravityBackendClient(controller).checkPcBridgeStatus(base).connected)
            authenticated.newCall(Request.Builder().url("$base/redirect").build()).execute().use { assertEquals(302, it.code) }
            authenticated.newCall(Request.Builder().url("http://127.0.0.1:8820/check").build()).execute().use {
                assertFalse(org.json.JSONObject(it.body!!.string()).getBoolean("credentialReceived"))
            }
            val (saved, path) = controller.downloadMediaToPhone("$base/sample.txt", "paired-qa-${UUID.randomUUID()}.txt", 5)
            assertTrue(saved); downloaded = File(path).canonicalFile; assertEquals("hello", downloaded!!.readText())
            instrumentation.runOnMainSync { voice = RealtimeVoiceEngine(context, {}, { false }) }
            val field = RealtimeVoiceEngine::class.java.getDeclaredField("okHttpClient").apply { isAccessible = true }
            val voiceHttp = field.get(voice) as OkHttpClient
            voiceHttp.newCall(Request.Builder().url("$base/api/tts?text=QA").build()).execute().use {
                assertEquals(200, it.code); assertEquals(512, it.body!!.bytes().size)
            }
            val browser = requireNotNull(pairing.browserUrl(base, "qa-session"))
            assertFalse(browser.contains(secret))
            http.newCall(Request.Builder().url(browser).build()).execute().use { opened ->
                assertEquals(303, opened.code)
                val cookie = requireNotNull(opened.header("Set-Cookie")); assertTrue(cookie.contains("HttpOnly"))
                http.newCall(Request.Builder().url("$base/api/sessions").header("Cookie", cookie.substringBefore(';')).build())
                    .execute().use { assertEquals(200, it.code) }
            }
            http.newCall(Request.Builder().url(browser).build()).execute().use { assertEquals(403, it.code) }
            val title = java.util.concurrent.atomic.AtomicReference("")
            val webAddress = requireNotNull(pairing.browserUrl(base, "qa-session"))
            instrumentation.runOnMainSync {
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                web = android.webkit.WebView(context).apply {
                    settings.javaScriptEnabled = true
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onReceivedTitle(view: android.webkit.WebView?, value: String?) { title.set(value.orEmpty()) }
                    }
                    loadUrl(webAddress)
                }
            }
            val until = android.os.SystemClock.elapsedRealtime() + 20000
            while (title.get() != "Paired QA Web" && android.os.SystemClock.elapsedRealtime() < until) android.os.SystemClock.sleep(100)
            assertEquals("Paired QA Web", title.get())
            authenticated.newCall(Request.Builder().url("$base/api/pairing/open")
                .post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(200, it.code) }
            credentials.forget(base)
            try {
                authenticated.newCall(Request.Builder().url("$base/api/status").build()).execute().close()
                fail("Forgotten bridge must be rejected before network")
            } catch (expected: java.io.IOException) {
                assertTrue(expected.message!!.contains("сопряжение"))
            }
        } finally {
            voice?.let { instrumentation.runOnMainSync { it.release() } }
            instrumentation.runOnMainSync {
                web?.stopLoading(); web?.destroy()
                android.webkit.CookieManager.getInstance().setCookie(base, "agy_owner=; Max-Age=0; Path=/")
                android.webkit.CookieManager.getInstance().flush()
            }
            credentials.forget(base)
            downloaded?.let { file ->
                val roots = listOf(context.filesDir.canonicalFile, controller.appSandboxWorkspace.canonicalFile, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile)
                check(roots.any { file.path.startsWith(it.path + File.separator) }); check(file.name.startsWith("paired-qa-")); check(file.delete())
            }
        }
    }
}
