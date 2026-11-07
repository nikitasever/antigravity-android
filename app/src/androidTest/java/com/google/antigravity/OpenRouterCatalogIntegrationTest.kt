package com.google.antigravity

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.device.PhoneResourceController
import com.google.antigravity.network.AntigravityBackendClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the local empty-catalog QA bridge and public internet; no key or paid request. */
@RunWith(AndroidJUnit4::class)
class OpenRouterCatalogIntegrationTest {
    @Test(timeout = 90000) fun directAndroidDiscoveryMergesAllThreePublicCatalogs(): Unit = runBlocking {
        assertTrue("Owned QA emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = AntigravityBackendClient(PhoneResourceController(context))
        val result = client.fetchLiveOpenRouterModels("http://127.0.0.1:8810", true)
        assertTrue("Fixture must have forced the direct Android path", result.source.contains("телефон"))
        assertFalse("All three public discovery requests must succeed", result.partial)
        assertFalse(result.fromCache)
        assertEquals(result.models.size, result.models.map { it.id }.distinct().size)
        val images = result.models.filter { JSONObject(it.generationCapabilitiesJson).has("images") }
        val videos = result.models.filter { JSONObject(it.generationCapabilitiesJson).has("videos") }
        assertTrue(images.isNotEmpty())
        assertTrue(videos.isNotEmpty())
        assertTrue(images.all { it.isImageGen })
        assertTrue(videos.all { it.isVideoGen })
        assertTrue("Dedicated video-only models do not invent a text context window", videos.any { it.contextLength == 0 })
        assertTrue("Supported video durations survive discovery", videos.any {
            (JSONObject(it.generationCapabilitiesJson).getJSONObject("videos").optJSONArray("supported_durations")?.length() ?: 0) > 0
        })
        Log.i("OpenRouterCatalogQA", "models=${result.models.size}, imageApi=${images.size}, videoApi=${videos.size}, partial=${result.partial}")
    }
}
