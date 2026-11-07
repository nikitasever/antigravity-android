package com.google.antigravity.model

import java.util.Locale

fun GeneratedMediaAttachment.isGeneratedVideo(): Boolean = type.equals("video", true) || mimeType.startsWith("video/", true)

fun GeneratedMediaAttachment.mediaFormatLabel(): String {
    val format = when (mimeType.lowercase(Locale.ROOT)) {
        "video/mp4" -> "MP4"
        "video/webm" -> "WebM"
        "image/png" -> "PNG"
        "image/jpeg" -> "JPEG"
        "image/webp" -> "WebP"
        "image/gif" -> "GIF"
        else -> ""
    }
    return listOf(if (isGeneratedVideo()) "Видео" else "Изображение", format).filter { it.isNotBlank() }.joinToString(" • ")
}
