package com.google.antigravity

import android.os.Build
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.device.PhoneResourceController
import com.google.antigravity.network.OpenRouterVideoClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Requires the local protocol fixture at 8814. No paid generation or user credentials. */
@RunWith(AndroidJUnit4::class)
class OpenRouterVideoIntegrationTest {
    @Test(timeout = 60000) fun asyncProtocolDownloadsItsControlledBytesToTheOwnedEmulator(): Unit = runBlocking {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "qa_android_" + UUID.randomUUID().toString()
        val base = "http://127.0.0.1:8814"
        val statuses = mutableListOf<String>()
        val media = OpenRouterVideoClient(OkHttpClient(), 0).execute(base, id, "qa/video-model", "QA protocol only", "qa-video-key",
            onProgress = { statuses.add(it) })
        assertEquals(listOf("pending", "in_progress", "completed"), statuses)
        val fileName = "openrouter_video_$id.mp4"
        assertEquals(fileName, media.getString("fileName"))
        val (saved, localPath) = PhoneResourceController(context).downloadMediaToPhone(base + media.getString("mediaUrl"), fileName)
        assertTrue("Controlled file must save on phone: $localPath", saved)
        val file = File(localPath).canonicalFile
        val allowed = listOf(context.filesDir.canonicalFile, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile)
        assertEquals(fileName, file.name)
        assertTrue("Cleanup limited to the owned QA file", allowed.any { file.path.startsWith(it.path + File.separator) })
        try {
            assertEquals(24L, file.length())
            assertEquals("ftypisom", file.readBytes().copyOfRange(4, 12).toString(Charsets.US_ASCII))
        } finally { assertTrue(file.delete()) }
    }
}
