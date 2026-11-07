package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class GeneratedMediaMetadataTest {
    @Test fun unspecifiedMetadataDoesNotInventDimensionsOrFormat() {
        val media = GeneratedMediaAttachment(type = "image", fileName = "result.png", mediaUrl = "https://example.test/media")
        assertEquals("", media.resolution)
        assertEquals(0, media.width)
        assertEquals(0, media.height)
        assertEquals("Изображение", media.mediaFormatLabel())
    }
    @Test fun webmAndJpegUseActualMimeNotAnMp4OrPngBadge() {
        val media = GeneratedMediaAttachment(type = "video", mimeType = "video/webm", fileName = "result.webm", mediaUrl = "")
        assertEquals("Видео • WebM", media.mediaFormatLabel())
        assertEquals("Изображение • JPEG", media.copy(type = "image", mimeType = "image/jpeg").mediaFormatLabel())
    }
    @Test fun mp4ContainerDoesNotImplyH264DurationFpsOrResolution() {
        val media = GeneratedMediaAttachment(mimeType = "video/mp4", fileName = "result.mp4", mediaUrl = "")
        assertEquals("Видео • MP4", media.mediaFormatLabel())
        assertEquals(0, media.durationSec)
        assertEquals("", media.resolution)
    }
}
