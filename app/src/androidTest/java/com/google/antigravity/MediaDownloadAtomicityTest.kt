package com.google.antigravity

import android.os.Build
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.device.PhoneResourceController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaDownloadAtomicityTest {
    @Test(timeout = 60000) fun incompleteOrWrongSizeDownloadPreservesExistingPhoneFile(): Unit = runBlocking {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = PhoneResourceController(context)
        val name = "qa_atomic_" + UUID.randomUUID().toString() + ".mp4"
        val (saved, localPath) = controller.downloadMediaToPhone("http://127.0.0.1:8814/qa/media-good", name, 24)
        assertTrue(localPath, saved)
        val file = File(localPath).canonicalFile
        assertEquals(name, file.name)
        val roots = listOf(context.filesDir.canonicalFile, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile)
        assertTrue(roots.any { file.path.startsWith(it.path + File.separator) })
        val original = file.readBytes()
        val before = file.parentFile!!.listFiles().orEmpty().filter { it.name.startsWith("media-download-") }.map { it.name }.toSet()
        try {
            assertFalse(controller.downloadMediaToPhone("http://127.0.0.1:8814/qa/media-good", name, 25).first)
            assertArrayEquals(original, file.readBytes())
            assertFalse(controller.downloadMediaToPhone("http://127.0.0.1:8814/qa/media-truncated", name, 100).first)
            assertArrayEquals(original, file.readBytes())
            val after = file.parentFile!!.listFiles().orEmpty().filter { it.name.startsWith("media-download-") }.map { it.name }.toSet()
            assertEquals(before, after)
        } finally { assertTrue(file.delete()) }
    }
}
