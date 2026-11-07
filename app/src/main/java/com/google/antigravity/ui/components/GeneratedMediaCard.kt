package com.google.antigravity.ui.components

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.antigravity.model.GeneratedMediaAttachment
import com.google.antigravity.model.mediaFormatLabel
import com.google.antigravity.model.isGeneratedVideo
import com.google.antigravity.ui.design.DesignColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Real local image or an honest file tile. No invented frame, codec or playback progress. */
@Composable
fun GeneratedMediaCard(media: GeneratedMediaAttachment, onDownloadToPhone: (GeneratedMediaAttachment) -> Unit,
    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val localFile = media.localPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
    val video = media.isGeneratedVideo()
    val preview by produceState<ImageBitmap?>(null, localFile?.absolutePath, video) {
        value = if (video || localFile == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(localFile.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                    var sample = 1
                    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                    BitmapFactory.decodeFile(localFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    fun open() {
        if (localFile == null) {
            if (media.mediaUrl.isNotBlank()) openClickableLink(context, clipboard, media.mediaUrl)
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                media.mimeType.ifBlank { if (video) "video/*" else "image/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            android.widget.Toast.makeText(context, "Не удалось открыть файл. Установите приложение для этого формата.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    Surface(modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(14.dp),
        color = DesignColors.Surface, border = BorderStroke(1.dp, DesignColors.Border)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(media.mediaFormatLabel(), color = DesignColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
            Text(media.prompt.ifBlank { media.fileName }, color = DesignColors.TextPrimary, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (media.modelId.isNotBlank()) Text(media.modelId, color = DesignColors.TextSubtle,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            preview?.let {
                Image(it, contentDescription = media.fileName, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp))
            }
            val details = listOfNotNull(media.resolution.takeIf { it.isNotBlank() },
                media.durationSec.takeIf { it > 0 }?.let { "$it сек" }).joinToString(" • ")
            if (details.isNotBlank()) Text(details, color = DesignColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Text(if (localFile != null) "Сохранено на телефоне" else if (media.mediaUrl.isNotBlank()) "Файл доступен по ссылке" else "Файл недоступен на устройстве",
                color = DesignColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { open() }, enabled = localFile != null || media.mediaUrl.isNotBlank()) {
                    if (video) { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(if (video) "Открыть видео" else "Открыть")
                }
                if (localFile == null) TextButton(onClick = { onDownloadToPhone(media) }, enabled = media.mediaUrl.isNotBlank()) {
                    Text("Сохранить")
                }
            }
        }
    }
}
