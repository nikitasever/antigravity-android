package com.google.antigravity.ui.components

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.antigravity.model.AiPermissionsConfig
import com.google.antigravity.model.AndroidCliCommandResult
import com.google.antigravity.model.ContextWindowStatus
import com.google.antigravity.model.FileDiffItem
import com.google.antigravity.model.GeneratedMediaAttachment
import com.google.antigravity.model.GoogleDriveFileItem
import com.google.antigravity.model.GoogleDriveStatus
import com.google.antigravity.model.OpenRouterCategory
import com.google.antigravity.model.OpenRouterModelItem
import com.google.antigravity.model.SlashMentionSuggestionItem
import com.google.antigravity.model.SwarmAgentNode
import com.google.antigravity.model.SubagentPanelState
import com.google.antigravity.model.VirtualSandboxProject
import com.google.antigravity.ui.design.DesignColors
import com.google.antigravity.ui.design.DesignType
import java.io.File
import java.util.Locale

// ---------------------------------------------------------------------------
// Section 1.2: Reactive `/` (Skills & Slash Commands) and `@` (Context/Swarm/MCP) Popup
// ---------------------------------------------------------------------------

@Composable
fun SlashAndMentionPopup(
    suggestions: List<SlashMentionSuggestionItem>,
    onSelectSuggestion: (SlashMentionSuggestionItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (suggestions.isEmpty()) return
    val isSlash = suggestions.first().trigger.startsWith("/")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF18181D))
            .border(1.dp, Color(0xFF2F323D), RoundedCornerShape(16.dp))
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isSlash) "Скиллы и слэш-команды Antigravity (/)" else "Субагенты роя, Google Диск, файлы и MCP (@)",
                color = Color(0xFF93C5FD),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${suggestions.size}",
                color = Color(0xFF8E8E93),
                fontSize = 11.sp
            )
        }
        HorizontalDivider(color = Color(0xFF262830), modifier = Modifier.padding(vertical = 2.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 210.dp)
        ) {
            items(suggestions, key = { it.id }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectSuggestion(item) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.trigger,
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (item.categoryBadge.contains("ЛИДЕР")) Color(0xFF3B2F13)
                                        else Color(0xFF1E293B)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = item.categoryBadge,
                                    color = if (item.categoryBadge.contains("ЛИДЕР")) Color(0xFFFBBF24) else Color(0xFF60A5FA),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                        if (item.subtitle.isNotBlank()) {
                            Text(
                                text = item.subtitle,
                                color = Color(0xFF9CA3AF),
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Section 5.2 & 11.5: Reference-Style Live File Diff Card (+added green / -removed red + ☁️ На Google Диск)
// ---------------------------------------------------------------------------

@Composable
fun ReferenceDiffCard(
    diff: FileDiffItem,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    onOpenCliSandbox: () -> Unit = {},
    onSaveToGoogleDrive: (fileName: String, content: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val shortName = diff.path.substringAfterLast('/').substringAfterLast('\\').ifBlank { diff.path }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF14161B))
            .border(1.dp, Color(0xFF282C35), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shortName,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (diff.path != shortName) {
                    Text(
                        text = diff.path,
                        color = Color(0xFF7D8590),
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // +additions pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0F291E))
                    .border(1.dp, Color(0xFF1B4D38), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "+${diff.additions}",
                    color = Color(0xFF4ADE80),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            // -deletions pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2D1619))
                    .border(1.dp, Color(0xFF58252B), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "-${diff.deletions}",
                    color = Color(0xFFF87171),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = Color(0xFF8B949E),
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(visible = expanded && diff.diffPatch.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D0F13))
                    .padding(top = 6.dp, bottom = 8.dp)
            ) {
                val lines = diff.diffPatch.lines().take(28)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    lines.forEachIndexed { idx, line ->
                        val isAdd = line.startsWith("+") && !line.startsWith("+++")
                        val isDel = line.startsWith("-") && !line.startsWith("---")
                        val isHunk = line.startsWith("@@")
                        val rowBg = when {
                            isAdd -> Color(0xFF0E291C)
                            isDel -> Color(0xFF2B1418)
                            isHunk -> Color(0xFF131D2E)
                            else -> Color.Transparent
                        }
                        val rowColor = when {
                            isAdd -> Color(0xFF4ADE80)
                            isDel -> Color(0xFFF87171)
                            isHunk -> Color(0xFF60A5FA)
                            else -> Color(0xFFD0D7DE)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg)
                                .padding(horizontal = 10.dp, vertical = 1.5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = (idx + 1).toString().padStart(2, ' '),
                                color = Color(0xFF6E7681),
                                fontSize = 10.5.sp,
                                lineHeight = 16.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = line,
                                color = rowColor,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                softWrap = false
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Actions Row (Section 5.2 & 11.5: Копировать, Открыть, В CLI, В Диск)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF1B2230))
                            .border(1.dp, Color(0xFF2E384D), RoundedCornerShape(7.dp))
                            .clickable {
                                clipboard.setText(AnnotatedString(diff.diffPatch))
                                Toast.makeText(context, "Патч скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Копировать",
                            color = Color(0xFFD0D7DE),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF1B2230))
                            .border(1.dp, Color(0xFF2E384D), RoundedCornerShape(7.dp))
                            .clickable {
                                val normalized = diff.path.replace('\\', '/').trimStart('/')
                                openClickableLink(context, clipboard, "file:///$normalized")
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Открыть",
                            color = Color(0xFF58A6FF),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF132A23))
                            .border(1.dp, Color(0xFF1F4D3D), RoundedCornerShape(7.dp))
                            .clickable { onOpenCliSandbox() }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "В CLI",
                            color = Color(0xFF4ADE80),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF172554))
                            .border(1.dp, Color(0xFF2563EB), RoundedCornerShape(7.dp))
                            .clickable {
                                onSaveToGoogleDrive(shortName, diff.diffPatch)
                                Toast.makeText(context, "Запрос на сохранение отправлен", Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "В Диск",
                            color = Color(0xFF93C5FD),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Section 8.1: Generated Photo (PNG) & Video (MP4) Attachment Card
// ---------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GeneratedMediaCard(
    media: GeneratedMediaAttachment,
    onDownloadToPhone: (GeneratedMediaAttachment) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val isVideo = media.type.equals("video", ignoreCase = true) || media.fileName.endsWith(".mp4", ignoreCase = true)

    val localBitmap = remember(media.localPath) {
        if (!isVideo && media.localPath.isNotBlank()) {
            runCatching {
                val file = File(media.localPath)
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
            }.getOrNull()
        } else null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF15171E))
            .border(1.dp, Color(0xFF2E3446), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isVideo) Color(0xFF2E1A47) else Color(0xFF132F2B))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isVideo) "MP4 ВИДЕО • H.264" else "PNG ФОТО • HD",
                    color = if (isVideo) Color(0xFFC084FC) else Color(0xFF34D399),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = media.modelId,
                color = Color(0xFF93C5FD),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = media.resolution,
                color = Color(0xFF8B949E),
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Встроенное превью (Bitmap или WebView для MP4/PNG)
        if (localBitmap != null) {
            Image(
                bitmap = localBitmap,
                contentDescription = media.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF2A2E39), RoundedCornerShape(10.dp))
            )
        } else if (media.mediaUrl.isNotBlank()) {
            var isPlayingInlineWebView by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0A0F1D))
                    .border(1.dp, Color(0xFF2D3B55), RoundedCornerShape(10.dp))
                    .clickable { isPlayingInlineWebView = !isPlayingInlineWebView }
            ) {
                // Skia-composited Orbital H.264 Frame (always visible in UI and screencap)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width * 0.5f
                    val cy = size.height * 0.46f
                    drawCircle(
                        color = Color(0xFF172554),
                        radius = size.minDimension * 0.58f,
                        center = androidx.compose.ui.geometry.Offset(cx, cy)
                    )
                    drawOval(
                        color = Color(0xFF38BDF8),
                        topLeft = androidx.compose.ui.geometry.Offset(cx - 190f, cy - 68f),
                        size = androidx.compose.ui.geometry.Size(380f, 136f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.5f)
                    )
                    drawOval(
                        color = Color(0xFF818CF8).copy(alpha = 0.55f),
                        topLeft = androidx.compose.ui.geometry.Offset(cx - 125f, cy - 42f),
                        size = androidx.compose.ui.geometry.Size(250f, 84f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                    )
                    drawCircle(
                        color = Color(0xFF60A5FA),
                        radius = 30f,
                        center = androidx.compose.ui.geometry.Offset(cx, cy)
                    )
                    drawCircle(
                        color = Color(0xFF34D399),
                        radius = 13f,
                        center = androidx.compose.ui.geometry.Offset(cx + 150f, cy - 38f)
                    )
                    drawCircle(
                        color = Color(0xFFFBBF24),
                        radius = 10f,
                        center = androidx.compose.ui.geometry.Offset(cx - 110f, cy + 28f)
                    )
                }

                // Top HUD overlay
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC0F172A))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (isVideo) "H.264 Baseline (avc1) • ${media.fileName}" else "PNG HD • ${media.fileName}",
                            color = Color(0xFF93C5FD),
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = if (isVideo) "00:03 / 12 FPS" else "HD",
                        color = Color(0xFF4ADE80),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Center Play button badge
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC2563EB))
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Воспроизвести",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Bottom Video Scrubber Bar
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xCC090D16))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { 0.68f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF38BDF8),
                        trackColor = Color(0xFF1E293B)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = media.prompt.ifBlank { "Орбитальная станция Antigravity 2026" },
                        color = Color(0xFFE2E8F0),
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isPlayingInlineWebView) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                webChromeClient = WebChromeClient()
                                webViewClient = WebViewClient()
                                val html = if (isVideo) {
                                    """
                                    <!DOCTYPE html><html><body style="margin:0;background:#0B0D13;display:flex;align-items:center;justify-content:center;height:100vh;">
                                    <video src="${media.mediaUrl}" controls autoplay loop muted playsinline style="max-width:100%;max-height:100%;border-radius:8px;"></video>
                                    </body></html>
                                    """.trimIndent()
                                } else {
                                    """
                                    <!DOCTYPE html><html><body style="margin:0;background:#0B0D13;display:flex;align-items:center;justify-content:center;height:100vh;">
                                    <img src="${media.mediaUrl}" style="max-width:100%;max-height:100%;object-fit:contain;border-radius:8px;" />
                                    </body></html>
                                    """.trimIndent()
                                }
                                loadDataWithBaseURL(media.mediaUrl, html, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Подсвеченная кликабельная ссылка
        Text(
            text = media.mediaUrl,
            color = Color(0xFF58A6FF),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { openClickableLink(context, clipboard, media.mediaUrl) }
        )

        if (media.localPath.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "✓ Сохранено на телефон: ${media.localPath.substringAfterLast('/')}",
                color = Color(0xFF4ADE80),
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1F6FEB))
                    .clickable { onDownloadToPhone(media) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (media.localPath.isNotBlank()) "Скачать повторно" else "Скачать на телефон",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF21262D))
                    .border(1.dp, Color(0xFF363B42), RoundedCornerShape(8.dp))
                    .clickable { openClickableLink(context, clipboard, media.mediaUrl) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Открыть",
                    color = Color(0xFF58A6FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Section 9: Context Window Badge & Inspector/Reset Sheet
// ---------------------------------------------------------------------------

@Composable
fun ContextWindowBadge(
    status: ContextWindowStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val usedK = if (status.usedTokens >= 1000) {
        String.format(Locale.US, "%.1fK", status.usedTokens / 1000f)
    } else {
        "${status.usedTokens}"
    }
    val maxLabel = if (status.maxTokens >= 1_000_000) "1M" else "${status.maxTokens / 1000}K"
    val dotColor = when {
        status.usagePercent >= 75f -> Color(0xFFF87171)
        status.usagePercent >= 40f -> Color(0xFFFBBF24)
        else -> Color(0xFF4ADE80)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF1B1D24))
            .border(1.dp, Color(0xFF2D313E), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = "$usedK/$maxLabel",
            color = Color(0xFFD1D5DB),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextWindowSheet(
    status: ContextWindowStatus,
    onDismiss: () -> Unit,
    onResetKeepMessages: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onResetClearMessages: () -> Unit,
    onCompactContext: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onClearToolOutputs: () -> Unit,
    lastCompactedSummary: String = ""
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val usedCompact = if (status.usedTokens >= 1000) {
        String.format(Locale.US, "%.1fK", status.usedTokens / 1000.0)
    } else {
        "${status.usedTokens}"
    }
    val maxCompact = if (status.maxTokens >= 1_000_000) {
        String.format(Locale.US, "%.0fM", status.maxTokens / 1_000_000.0)
    } else {
        "${status.maxTokens / 1000}K"
    }
    val pctInt = status.usagePercent.toInt().coerceIn(0, 100)
    val progress = (status.usedTokens.toFloat() / status.maxTokens.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141414),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Контекстное окно",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$usedCompact из $maxCompact токенов ($pctInt%)",
                        color = Color(0xFF9CA3AF),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Закрыть",
                            tint = Color(0xFF9CA3AF),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Color(0xFF3B82F6),
                trackColor = Color(0xFF262626)
            )

            if (lastCompactedSummary.isNotBlank()) {
                Text(
                    text = "Чат сжат • $lastCompactedSummary",
                    color = Color(0xFF34D399),
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF222222))
                        .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                        .clickable {
                            onCompactContext()
                            onDismiss()
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Сжать чат",
                        color = Color(0xFFE5E7EB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF222222))
                        .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                        .clickable {
                            onResetKeepMessages()
                            onDismiss()
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Очистить контекст",
                        color = Color(0xFFE5E7EB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextStatRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFFD1D5DB),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ---------------------------------------------------------------------------
// Sections 2, 8.1 & 11.3: Live OpenRouter Models Hub + Persistent Disk Cache (0 ms) + Photo/MP4 Studio
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenRouterHubSheet(
    models: List<OpenRouterModelItem>,
    totalCount: Int,
    syncedAt: String,
    syncSource: String,
    selectedModelId: String,
    openRouterApiKey: String,
    isSyncing: Boolean,
    isGeneratingMedia: Boolean,
    onDismiss: () -> Unit,
    onSyncNow: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onSelectModel: (OpenRouterModelItem) -> Unit,
    onSelectCustomModel: (String) -> Unit,
    onGenerateMedia: (prompt: String, modelId: String, isVideo: Boolean) -> Unit,
    cacheStatusText: String = "Кэш синхронизирован • openrouter_models_cache.json"
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCategory by remember { mutableStateOf(OpenRouterCategory.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var showApiKeyPanel by remember { mutableStateOf(false) }
    var apiKeyDraft by remember(openRouterApiKey) { mutableStateOf(openRouterApiKey) }
    var customModelDraft by remember { mutableStateOf("") }
    var mediaPromptDraft by remember {
        mutableStateOf("Футуристическая орбитальная станция Antigravity 2026 над неоновым городом")
    }

    val filteredModels = remember(models, selectedCategory, searchQuery) {
        models.filter { m ->
            val catMatch = selectedCategory == OpenRouterCategory.ALL || m.categories.contains(selectedCategory)
            val q = searchQuery.trim().lowercase()
            val queryMatch = q.isEmpty() ||
                m.id.lowercase().contains(q) ||
                m.name.lowercase().contains(q) ||
                m.description.lowercase().contains(q)
            catMatch && queryMatch
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111319),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
        ) {
            // Top Header (Clean text "Openrouter", no icons or numbers on tabs/functions)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Openrouter",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Text(
                        text = cacheStatusText.replace("⚡ ", ""),
                        color = Color(0xFF34D399),
                        fontSize = 10.8.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onSyncNow) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF60A5FA)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Синхронизировать с OpenRouter",
                            tint = Color(0xFF60A5FA)
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color(0xFF9CA3AF))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Quick Actions Row: Sync + API Key / Custom Model Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1B2436))
                        .border(1.dp, Color(0xFF2D3F5E), RoundedCornerShape(10.dp))
                        .clickable { onSyncNow() }
                        .padding(vertical = 7.dp, horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isSyncing) "Обновление кэша…" else "Обновить кэш с OpenRouter",
                        color = Color(0xFF93C5FD),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (showApiKeyPanel) Color(0xFF2563EB) else Color(0xFF1E2028))
                        .border(1.dp, Color(0xFF323644), RoundedCornerShape(10.dp))
                        .clickable { showApiKeyPanel = !showApiKeyPanel }
                        .padding(vertical = 7.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Ключ и ID",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Collapsible API Key & Custom Model Input
            AnimatedVisibility(visible = showApiKeyPanel) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF181B23))
                        .border(1.dp, Color(0xFF2B3040), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = apiKeyDraft,
                            onValueChange = { apiKeyDraft = it },
                            placeholder = { Text("sk-or-v1-... (Ключ OpenRouter API)", fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color(0xFF343946)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF10A37F))
                                .clickable { onSaveApiKey(apiKeyDraft) }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Text("Сохранить", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customModelDraft,
                            onValueChange = { customModelDraft = it },
                            placeholder = { Text("Свой ID (напр. openai/gpt-6.1-terra-pro)", fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color(0xFF343946)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF2563EB))
                                .clickable {
                                    if (customModelDraft.isNotBlank()) {
                                        onSelectCustomModel(customModelDraft)
                                        onDismiss()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Text("Выбрать", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Category Filter Chips (clean text without icons or numbers)
            val categories = listOf(
                OpenRouterCategory.ALL to "Новейшие",
                OpenRouterCategory.REASONING to "Reasoning",
                OpenRouterCategory.VISION to "Vision",
                OpenRouterCategory.IMAGE_GEN to "Фото",
                OpenRouterCategory.VIDEO_GEN to "Видео",
                OpenRouterCategory.AUDIO to "Аудио",
                OpenRouterCategory.FREE to "Бесплатные"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                categories.forEach { (cat, label) ->
                    val selected = selectedCategory == cat
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Color(0xFF2563EB) else Color(0xFF1C1F29))
                            .border(
                                1.dp,
                                if (selected) Color(0xFF60A5FA) else Color(0xFF2D3242),
                                RoundedCornerShape(50)
                            )
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (selected) Color.White else Color(0xFFB4BCD0),
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF8B949E))
                },
                placeholder = {
                    Text(
                        "Поиск моделей Openrouter (gpt-6, claude, gemini, flux, veo)…",
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF3B82F6),
                    unfocusedBorderColor = Color(0xFF2B303C)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Live Model List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredModels, key = { it.id }) { item ->
                    val isSelected = item.id == selectedModelId
                    val isImg = item.categories.contains(OpenRouterCategory.IMAGE_GEN)
                    val isVid = item.categories.contains(OpenRouterCategory.VIDEO_GEN)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFF17233B) else Color(0xFF181A22))
                            .border(
                                1.dp,
                                if (isSelected) Color(0xFF3B82F6) else Color(0xFF272B38),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                onSelectModel(item)
                                onDismiss()
                            }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.name,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1E293B))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = item.createdDate,
                                    color = Color(0xFF93C5FD),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                            if (item.isFree) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF0F291E))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "FREE",
                                        color = Color(0xFF4ADE80),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.id,
                                color = Color(0xFF60A5FA),
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${item.contextLength / 1000}K ctx",
                                color = Color(0xFF8B949E),
                                fontSize = 11.sp
                            )
                        }

                        if (item.description.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.description,
                                color = Color(0xFF9CA3AF),
                                fontSize = 11.5.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                item.categories.filter { it != OpenRouterCategory.ALL }.take(3).forEach { cat ->
                                    val badgeText = when (cat) {
                                        OpenRouterCategory.REASONING -> "Reasoning"
                                        OpenRouterCategory.VISION -> "Vision"
                                        OpenRouterCategory.IMAGE_GEN -> "Фото"
                                        OpenRouterCategory.VIDEO_GEN -> "Видео"
                                        OpenRouterCategory.AUDIO -> "Аудио"
                                        OpenRouterCategory.FREE -> "Free"
                                        else -> ""
                                    }
                                    if (badgeText.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(Color(0xFF232734))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = badgeText,
                                                color = Color(0xFFD1D5DB),
                                                fontSize = 10.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (isSelected) Color(0xFF10A37F) else Color(0xFF2563EB))
                                    .clickable {
                                        onSelectModel(item)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isSelected) "Активна" else "Выбрать",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Панель фоновых задач и субагентов. Названия и общий вид навеяны
// Claude Android 1.261029.20 (`SdkWorkflowPhase`, `SdkWorkflowAgent`,
// `BackgroundShellOutput`, `CollapsedToolsSheetDestination`), но форма
// ручного запуска и фиксированная пара «Исследователь/Рецензент» — это наша
// реализация, а не воспроизведение подтверждённого сценария оригиналов.
// См. SUBAGENTS_RECHECK_AND_FIX.md: исходный сценарий Claude ещё не установлен.
// ---------------------------------------------------------------------------

private data class SdkWorkflowAgentRow(
    val id: String,
    val name: String,
    val tokens: String,
    val tools: String,
    val time: String,
    val isError: Boolean = false,
    val isRunning: Boolean = false,
    val isLeader: Boolean = false,
    val toolCallsLog: List<String> = emptyList(),
    val shellOutput: String = ""
)

private data class SdkWorkflowPhaseItem(
    val id: String,
    val title: String,
    val summaryRight: String,
    val errorBadge: String = "",
    val pixelStatuses: List<Int>, // 0 = done (grey), 1 = running (green), 2 = error (coral-red)
    val agents: List<SdkWorkflowAgentRow>
)

@Composable
fun PhaseStatusPixelGrid(
    statuses: List<Int>,
    modifier: Modifier = Modifier
) {
    val rows = statuses.chunked(6)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        rows.forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                rowItems.forEach { st ->
                    val sqColor = when (st) {
                        1 -> Color(0xFF34D399) // Running (green)
                        2 -> Color(0xFFE05263) // Error (coral-red)
                        else -> Color(0xFF6B7280) // Done (grey)
                    }
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(1.2.dp))
                            .background(sqColor)
                    )
                }
            }
        }
    }
}

/**
 * Компактная плашка `CodeBackgroundTasksEntryPoint` прямо над капсулой ввода текста в правом углу.
 * Отображает только реальные фоновые задачи и субагентов Antigravity без хардкода чужих скриншотов.
 */
@Composable
fun CodeBackgroundTasksEntryPoint(
    agents: List<SwarmAgentNode>,
    panelState: SubagentPanelState? = null,
    onOpenFullSheet: () -> Unit,
    onDispatchSwarmTask: (String) -> Unit,
    onCancelTask: () -> Unit = {},
    onDispatchCodeTask: (String) -> Unit = {},
    onInsertMention: (String) -> Unit,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    isSwarmTaskActive: Boolean = false,
    activeSessionTitle: String = "",
    forceExpandedInline: Boolean = false
) {
    var expandedInline by remember { mutableStateOf(forceExpandedInline) }
    // Состояние панели «как у них»: из снимка роя; при его отсутствии — честные
    // производные значения из статусов агентов (без выдуманных счётчиков).
    val panelUiState = panelState ?: SubagentPanelState(agents = agents)
    LaunchedEffect(forceExpandedInline) {
        expandedInline = forceExpandedInline
    }
    val realPixelStatuses = remember(agents, isStreaming, isSwarmTaskActive) {
        if (agents.isEmpty()) {
            listOf(if (isStreaming && isSwarmTaskActive) 1 else 0)
        } else {
            agents.map { ag ->
                when {
                    ag.status.contains("error", ignoreCase = true) -> 2
                    isStreaming && isSwarmTaskActive && ag.isLeader -> 1
                    ag.status.equals("active", ignoreCase = true) || ag.status.equals("working", ignoreCase = true) || ag.status.equals("running", ignoreCase = true) -> 1
                    else -> 0
                }
            }
        }
    }
    val activeCount = realPixelStatuses.count { it == 1 }
    if (!forceExpandedInline && !isSwarmTaskActive && activeCount == 0) {
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.End
    ) {
        // Inline expandable BackgroundTasksDetail card right above composer when tapped
        AnimatedVisibility(visible = expandedInline) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DesignColors.Surface)
                    .border(1.dp, DesignColors.Border, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                BackgroundTasksDetailContent(
                    agents = agents,
                    panelState = panelUiState,
                    isStreaming = isStreaming,
                    activeSessionTitle = activeSessionTitle,
                    onClose = { expandedInline = false },
                    onExpandModal = {
                        expandedInline = false
                        onOpenFullSheet()
                    },
                    onDispatchSwarmTask = onDispatchSwarmTask,
                    onCancelTask = onCancelTask,
                    onDispatchCodeTask = onDispatchCodeTask,
                    onInsertMention = onInsertMention,
                    isCompactInline = true,
                    isOwnTaskActive = isSwarmTaskActive
                )
            }
        }

        // Compact dark-monochrome pill in the bottom-right corner above ChatGptBottomInputPill
        if (!expandedInline) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(DesignColors.SurfaceHigh)
                    .border(1.dp, DesignColors.Border, RoundedCornerShape(50))
                    .clickable { expandedInline = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (activeCount > 0) DesignColors.AccentGreen else DesignColors.TextSubtle)
                )
                Spacer(modifier = Modifier.width(6.dp))
                PhaseStatusPixelGrid(statuses = realPixelStatuses)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (activeCount > 0) {
                        "${panelUiState.pillLabel(activeCount)} • ${agents.size} агентов"
                    } else {
                        "Фоновые задачи • ${agents.size} агентов"
                    },
                    color = DesignColors.TextPrimary,
                    fontSize = DesignType.MICRO_SP.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Фоновые задачи",
                    tint = DesignColors.TextSecondary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun BackgroundTasksDetailContent(
    agents: List<SwarmAgentNode>,
    panelState: SubagentPanelState? = null,
    isStreaming: Boolean = false,
    activeSessionTitle: String = "",
    onClose: () -> Unit,
    onExpandModal: (() -> Unit)? = null,
    onDispatchSwarmTask: (String) -> Unit,
    onCancelTask: () -> Unit = {},
    onDispatchCodeTask: (String) -> Unit = {},
    onInsertMention: (String) -> Unit,
    isCompactInline: Boolean = false,
    isOwnTaskActive: Boolean = false
) {
    val running = agents.any { it.isInProgress }
    val state = panelState ?: SubagentPanelState(agents = agents)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Фоновые задачи",
                    color = DesignColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = DesignType.TITLE_SP.sp
                )
                // Счётчик «как у них» («%1$d running task(s)») с русскими формами;
                // при отсутствии полей снимка — прежний вид без счётчика.
                state.counterLabel?.let { counterText ->
                    Text(counterText, color = DesignColors.TextSubtle, fontSize = DesignType.MICRO_SP.sp)
                }
            }
            TextButton(onClick = onClose) {
                Text("Закрыть", color = DesignColors.TextSecondary, fontSize = DesignType.BODY_SP.sp)
            }
        }
        Text("Задачи текущего разговора. Исполнитель получает отдельное задание от ведущей модели; результаты возвращаются в переписку.",
            color = DesignColors.TextSecondary, fontSize = DesignType.BODY_SP.sp)
        // Ввод задания для пачки субагентов — сразу под описанием, чтобы не
        // уезжал за пределы видимости при длинном списке агентов (находка
        // приёмки 3 ноября). Колбэки onDispatch* ранее были разведены по UI,
        // но не вызывались ни одним композаблом — запуск из приложения был
        // недостижим. Ручной запуск — наша честная модель (у них делегирование
        // идёт из чата/Dispatch); состав исполнителей — фиксированная пара
        // «исполнитель + рецензент».
        var taskDraft by remember { mutableStateOf("") }
        val dispatchLocked = running || isOwnTaskActive
        OutlinedTextField(
            value = taskDraft,
            onValueChange = { taskDraft = it },
            label = { Text("Задание для субагентов (исполнитель + рецензент)", fontSize = DesignType.CAPTION_SP.sp) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = DesignColors.AccentBlue,
                unfocusedBorderColor = DesignColors.Border,
                focusedTextColor = DesignColors.TextPrimary,
                unfocusedTextColor = DesignColors.TextPrimary,
                focusedLabelColor = DesignColors.AccentBlue,
                unfocusedLabelColor = DesignColors.TextSubtle
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = !dispatchLocked && taskDraft.isNotBlank(),
                onClick = {
                    val t = taskDraft.trim()
                    taskDraft = ""
                    onDispatchSwarmTask(t)
                }
            ) {
                Text("Запустить анализ", color = DesignColors.AccentBlue, fontSize = DesignType.BODY_SP.sp)
            }
            TextButton(
                enabled = !dispatchLocked && taskDraft.isNotBlank(),
                onClick = {
                    val t = taskDraft.trim()
                    taskDraft = ""
                    onDispatchCodeTask(t)
                }
            ) {
                Text("Запустить код-задачу", color = DesignColors.AccentBlue, fontSize = DesignType.BODY_SP.sp)
            }
        }
        if (dispatchLocked) {
            Text(
                "Предыдущая задача ещё выполняется — следующая пачка стартует после завершения.",
                color = DesignColors.TextSubtle, fontSize = DesignType.MICRO_SP.sp
            )
        }
        // Занятость «как у них» («Your computer is busy with other sessions…»):
        // статус тоном, не ошибка приложения. Показываем только когда занята
        // чужая сессия/клиент: своя активная пачка видна по счётчику, а строка
        // «другими сессиями» для неё была бы неверной.
        if (!isOwnTaskActive) {
            state.effectiveBusyMessage?.let { busyText ->
                Text("⚠ $busyText", color = DesignColors.AccentAmber, fontSize = DesignType.CAPTION_SP.sp)
            }
        }
        // Пустое состояние «как у них» («No background tasks»).
        if (agents.isEmpty()) Text("Нет фоновых задач", color = DesignColors.TextSubtle, fontSize = DesignType.CAPTION_SP.sp)
        if (agents.isNotEmpty()) {
            // Честный заголовок исполнителей: категорий Teammates/Other sessions
            // у нас нет (сессионной модели нет) — ничего не выдумываем.
            Text(
                "Субагенты",
                color = DesignColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = DesignType.SECTION_SP.sp
            )
        }
        agents.forEach { agent ->
            var expanded by remember(agent.id) { mutableStateOf(false) }
            Surface(color = DesignColors.SurfaceHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        agent.name,
                        color = DesignColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = DesignType.BODY_SP.sp
                    )
                    Text(if (agent.isSettling && agent.status in setOf("failed", "cancelled")) "Завершение работы и сбор изменений"
                        else when (agent.status) {
                        "queued" -> "В очереди"; "running" -> "Выполняется"; "completed" -> "Завершено"
                        "failed" -> "Ошибка"; "cancelled" -> "Отменено"; else -> agent.status
                    } + " • " + agent.model, color = DesignColors.TextSubtle, fontSize = DesignType.CAPTION_SP.sp)
                    // Режим рабочего каталога «как у них» («Running in the shared
                    // directory… consider worktree mode to isolate changes»).
                    state.hintFor(agent)?.let { hint ->
                        Text(hint, color = DesignColors.TextSubtle, fontSize = DesignType.MICRO_SP.sp)
                    }
                    Text(
                        agent.currentTask,
                        color = DesignColors.TextSecondary,
                        fontSize = DesignType.BODY_SP.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (agent.changeReview.isNotBlank()) {
                        var showChanges by remember(agent.id) { mutableStateOf(false) }
                        TextButton(onClick = { showChanges = !showChanges }) {
                            Text(
                                if (showChanges) "Скрыть изменения" else "Просмотреть изменения",
                                color = DesignColors.AccentBlue,
                                fontSize = DesignType.BODY_SP.sp
                            )
                        }
                        if (showChanges) CodeArtifactViewer(
                            code = agent.changeReview, language = "Изменения · ${agent.name}",
                            fileName = "changes.txt", onDismiss = { showChanges = false }, onSave = null)
                    }
                    if (agent.stepLogs.isNotEmpty()) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(
                                if (expanded) "Скрыть результат" else "Показать результат",
                                color = DesignColors.AccentBlue,
                                fontSize = DesignType.BODY_SP.sp
                            )
                        }
                        if (expanded) ChatGptMarkdownText(agent.stepLogs.joinToString("\n"))
                    }
                }
            }
        }
        if (running) {
            Button(
                onClick = onCancelTask,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DesignColors.AccentBlue,
                    contentColor = DesignColors.TextPrimary
                )
            ) {
                Text("Остановить работу", fontSize = DesignType.BODY_SP.sp)
            }
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiAgentSwarmSheet(
    agents: List<SwarmAgentNode>,
    panelState: SubagentPanelState? = null,
    onDismiss: () -> Unit,
    onDispatchSwarmTask: (String) -> Unit,
    onCancelTask: () -> Unit = {},
    onDispatchCodeTask: (String) -> Unit = {},
    onInsertMention: (String) -> Unit,
    isOwnTaskActive: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DesignColors.Surface,
        contentColor = DesignColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.90f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            BackgroundTasksDetailContent(
                agents = agents,
                panelState = panelState,
                onClose = onDismiss,
                onExpandModal = null,
                onDispatchSwarmTask = onDispatchSwarmTask,
                onCancelTask = onCancelTask,
                    onDispatchCodeTask = onDispatchCodeTask,
                onInsertMention = onInsertMention,
                isCompactInline = false,
                isOwnTaskActive = isOwnTaskActive
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sections 11.5 & 11.7: Google Drive Integration Sheet (`ProjectDriveFolders`,
// `ProjectDriveLink`, `Tool.DriveSearch`) for BOTH Gemini and OpenRouter
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleDriveSheet(
    status: GoogleDriveStatus,
    files: List<GoogleDriveFileItem>,
    isSyncing: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onCreateFile: (name: String, content: String, prompt: String, engine: String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onInsertInChat: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedEngine by remember { mutableStateOf("gemini") }
    var fileNameDraft by remember { mutableStateOf("Antigravity_Cloud_Architecture_2026.md") }
    var fileContentDraft by remember {
        mutableStateOf("# Архитектура Antigravity 2026 (Google Drive Cloud)\n- Двусторонняя синхронизация для Gemini 3.1/4 и всех 452 моделей OpenRouter.\n- Автоматическое сохранение артефактов и кода в /AntigravityCloud.")
    }
    var expandedFileId by remember { mutableStateOf<String?>(files.firstOrNull()?.id) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF12141A),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Файлы рабочего пространства",
                            color = Color.White,
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "Локальное хранилище • ${files.size} файлов • Google Диск не подключён",
                        color = Color(0xFF34D399),
                        fontSize = 11.2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onRefresh) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF60A5FA))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить Google Диск", tint = Color(0xFF60A5FA))
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color(0xFF9CA3AF))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Файлы сохраняются в рабочем пространстве. Подключение Google Диска не настроено.",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Create / Edit File on Google Drive via Gemini OR OpenRouter
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF171A24))
                    .border(1.dp, Color(0xFF2B3245), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isGemini = selectedEngine == "gemini"
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isGemini) Color(0xFF1D4ED8) else Color(0xFF1F2430))
                            .border(1.dp, if (isGemini) Color(0xFF60A5FA) else Color(0xFF32394A), RoundedCornerShape(8.dp))
                            .clickable { selectedEngine = "gemini" }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Создать через Gemini",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isGemini) Color(0xFF0D9488) else Color(0xFF1F2430))
                            .border(1.dp, if (!isGemini) Color(0xFF34D399) else Color(0xFF32394A), RoundedCornerShape(8.dp))
                            .clickable { selectedEngine = "openrouter" }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Создать в Openrouter",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0E1118))
                        .border(1.dp, Color(0xFF252B3B), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Имя файла:",
                        color = Color(0xFF93C5FD),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    BasicTextField(
                        value = fileNameDraft,
                        onValueChange = { fileNameDraft = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(Color(0xFF60A5FA)),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0E1118))
                        .border(1.dp, Color(0xFF252B3B), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = fileContentDraft,
                        onValueChange = { fileContentDraft = it },
                        maxLines = 2,
                        textStyle = TextStyle(color = Color(0xFFD1D5DB), fontSize = 11.5.sp),
                        cursorBrush = SolidColor(Color(0xFF60A5FA)),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2563EB))
                            .clickable {
                                onCreateFile(fileNameDraft, fileContentDraft, "", selectedEngine)
                                Toast.makeText(context, "Запрос на сохранение отправлен", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "На Диск",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Файлы рабочего пространства (/AntigravityCloud):",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files, key = { it.id }) { item ->
                    val isOpenRouter = item.engine.equals("openrouter", ignoreCase = true)
                    val isExp = expandedFileId == item.id
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF181B24))
                            .border(1.dp, Color(0xFF282E3D), RoundedCornerShape(12.dp))
                            .clickable { expandedFileId = if (isExp) null else item.id }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.name,
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isOpenRouter) Color(0xFF0F291E) else Color(0xFF172554))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isOpenRouter) "Openrouter" else "Gemini",
                                    color = if (isOpenRouter) Color(0xFF34D399) else Color(0xFF93C5FD),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = "Модель: ${item.modelUsed} • Папка: ${item.folder} • ${item.sizeBytes} байт",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        AnimatedVisibility(visible = isExp) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (item.contentPreview.isNotBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF0E1016))
                                            .border(1.dp, Color(0xFF232734), RoundedCornerShape(8.dp))
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            text = item.contentPreview.take(420),
                                            color = Color(0xFFD1D5DB),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 6,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(Color(0xFF2563EB))
                                            .clickable {
                                                onInsertInChat("@gdrive:${item.name} ")
                                                onDismiss()
                                            }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Вставить в чат",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(Color(0xFF1F2430))
                                            .border(1.dp, Color(0xFF32394A), RoundedCornerShape(7.dp))
                                            .clickable {
                                                openClickableLink(context, clipboard, item.webViewLink.ifBlank { item.downloadUrl })
                                            }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Открыть ссылку",
                                            color = Color(0xFF93C5FD),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(Color(0xFF28161B))
                                            .clickable { onDeleteFile(item.name) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Удалить",
                                            color = Color(0xFFF87171),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Section 4: ANDROID CLI, Virtual Sandbox & Runtime WebView Preview Sheet
// ---------------------------------------------------------------------------

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidCliAndSandboxSheet(
    cliHistory: List<AndroidCliCommandResult>,
    isRunningCli: Boolean,
    sandboxProject: VirtualSandboxProject,
    sandboxTestOutput: String,
    onDismiss: () -> Unit,
    onRunCliCommand: (String) -> Unit,
    onClearCli: () -> Unit,
    onRunSandboxTests: () -> Unit,
    onCreateSnapshot: () -> Unit,
    onResetSandbox: () -> Unit,
    initialTab: Int = 0
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(initialTab) }
    var cliInput by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF101218),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ANDROID CLI и Виртуальная песочница",
                        color = Color.White,
                        fontSize = 17.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Локальная консоль смартфона и изолированная среда тестирования ИИ",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color(0xFF9CA3AF))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 3 Tabs: ANDROID CLI | ПЕСОЧНИЦА | РАНТАЙМ ПРЕВЬЮ (no icons on tabs)
            val tabs = listOf("ANDROID CLI", "ПЕСОЧНИЦА", "РАНТАЙМ ПРЕВЬЮ")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEachIndexed { idx, title ->
                    val active = selectedTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) Color(0xFF2563EB) else Color(0xFF1B1E28))
                            .border(1.dp, if (active) Color(0xFF60A5FA) else Color(0xFF2C3140), RoundedCornerShape(10.dp))
                            .clickable { selectedTab = idx }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = if (active) Color.White else Color(0xFFB4BCD0),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (selectedTab) {
                0 -> {
                    // Tab 0: ANDROID CLI
                    val quickCommands = listOf(
                        "uname -a",
                        "ls -la",
                        "df -h",
                        "cat /proc/meminfo | head -n 8",
                        "getprop ro.product.model",
                        "ps -A | head -n 12"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickCommands.forEach { cmd ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1A202C))
                                    .border(1.dp, Color(0xFF2D3748), RoundedCornerShape(8.dp))
                                    .clickable { onRunCliCommand(cmd) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "$ $cmd",
                                    color = Color(0xFF4ADE80),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = cliInput,
                            onValueChange = { cliInput = it },
                            placeholder = {
                                Text("Введите sh-команду Android CLI…", fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFF4ADE80),
                                unfocusedTextColor = Color(0xFF4ADE80),
                                focusedBorderColor = Color(0xFF10A37F),
                                unfocusedBorderColor = Color(0xFF2D3342)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF10A37F))
                                .clickable(enabled = !isRunningCli) {
                                    if (cliInput.isNotBlank()) {
                                        onRunCliCommand(cliInput)
                                        cliInput = ""
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 14.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Выполнить", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0A0C10))
                            .border(1.dp, Color(0xFF222631), RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(cliHistory, key = { it.id }) { entry ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "$ ${entry.command}",
                                        color = Color(0xFF4ADE80),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "exit=${entry.exitCode} • ${entry.durationMs}ms",
                                        color = if (entry.exitCode == 0) Color(0xFF60A5FA) else Color(0xFFF87171),
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                if (entry.stdout.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = entry.stdout,
                                        color = Color(0xFFE5E7EB),
                                        fontSize = 11.5.sp,
                                        lineHeight = 16.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                if (entry.stderr.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = entry.stderr,
                                        color = Color(0xFFF87171),
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // Tab 1: VIRTUAL SANDBOX
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF171A24))
                                .border(1.dp, Color(0xFF2B3245), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sandboxProject.name,
                                    color = Color.White,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF0F291E))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = sandboxProject.lastTestSummary,
                                        color = Color(0xFF4ADE80),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Путь: ${sandboxProject.rootPath}",
                                color = Color(0xFF8B949E),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF10A37F))
                                        .clickable { onRunSandboxTests() }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Автотесты",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF2563EB))
                                        .clickable { onCreateSnapshot() }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Снапшот",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF262A36))
                                        .clickable { onResetSandbox() }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Сброс",
                                        color = Color(0xFFFBBF24),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        if (sandboxTestOutput.isNotBlank()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0B0E14))
                                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = "Вывод тест-раннера песочницы:",
                                    color = Color(0xFF60A5FA),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = sandboxTestOutput,
                                    color = Color(0xFF4ADE80),
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Text(
                            text = "Файлы изолированной среды (${sandboxProject.files.size}):",
                            color = Color(0xFFD1D5DB),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        sandboxProject.files.forEach { relFile ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF161922))
                                    .clickable { selectedTab = 2 }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = relFile,
                                    color = Color(0xFF93C5FD),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "Открыть превью →",
                                    color = Color(0xFF4ADE80),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                2 -> {
                    // Tab 2: RUNTIME SANDBOX PREVIEW (Skia-composited Orbital Physics Simulator + Self-Test Console)
                    var orbitPhase by remember { mutableFloatStateOf(0.85f) }
                    var boostLevel by remember { mutableIntStateOf(1) }
                    var sandboxLogs by remember {
                        mutableStateOf(
                            listOf(
                                "[Sandbox] Инициализация виртуальной среды Antigravity WebView...",
                                "[Sandbox] Демонстрация анимации — результаты тестов здесь не измеряются",


                                "[Sandbox] Для проверки проекта запустите тесты во вкладке CLI"
                            )
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(bottom = 16.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0A0D14))
                            .border(1.dp, Color(0xFF2D3548), RoundedCornerShape(14.dp))
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color(0xFF052E16))
                                    .border(1.dp, Color(0xFF166534), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "● ПЕСОЧНИЦА АКТИВНА",
                                    color = Color(0xFF4ADE80),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Text(
                                text = "index.html • v2.0",
                                color = Color(0xFF60A5FA),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Text(
                            text = "Орбитальный симулятор и автотест Antigravity",
                            color = Color(0xFFF3F4F6),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Среда разработки и тестирования прямо на телефоне: HTML5 Canvas + JS Engine + автотесты в изолированном контейнере.",
                            color = Color(0xFF9CA3AF),
                            fontSize = 12.sp
                        )

                        // Interactive Skia Orbital Physics Canvas (Zero HUD overlap)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(205.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF060911))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                .clickable {
                                    orbitPhase += 0.55f
                                }
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val cx = size.width / 2f
                                val cy = size.height * 0.57f
                                val r1 = size.minDimension * 0.28f
                                val r2 = size.minDimension * 0.18f
                                // Subtle background grid lines
                                for (i in 1..5) {
                                    val y = size.height * (i / 6f)
                                    drawLine(
                                        color = Color(0xFF1E293B).copy(alpha = 0.45f),
                                        start = androidx.compose.ui.geometry.Offset(0f, y),
                                        end = androidx.compose.ui.geometry.Offset(size.width, y),
                                        strokeWidth = 1f
                                    )
                                }
                                // Outer and inner orbital rings
                                drawCircle(
                                    color = Color(0xFF38BDF8).copy(alpha = 0.30f),
                                    radius = r1,
                                    center = androidx.compose.ui.geometry.Offset(cx, cy),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                                )
                                drawCircle(
                                    color = Color(0xFFA855F7).copy(alpha = 0.24f),
                                    radius = r2,
                                    center = androidx.compose.ui.geometry.Offset(cx, cy),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                                )
                                // Central Gravity Core
                                drawCircle(
                                    color = Color(0xFF6366F1).copy(alpha = 0.30f),
                                    radius = 26f,
                                    center = androidx.compose.ui.geometry.Offset(cx, cy)
                                )
                                drawCircle(
                                    color = Color(0xFF38BDF8),
                                    radius = 14f,
                                    center = androidx.compose.ui.geometry.Offset(cx, cy)
                                )
                                // Primary Satellite
                                val sx1 = cx + kotlin.math.cos(orbitPhase.toDouble()).toFloat() * r1
                                val sy1 = cy + kotlin.math.sin(orbitPhase.toDouble()).toFloat() * r1
                                drawLine(
                                    color = Color(0xFF38BDF8).copy(alpha = 0.4f),
                                    start = androidx.compose.ui.geometry.Offset(cx, cy),
                                    end = androidx.compose.ui.geometry.Offset(sx1, sy1),
                                    strokeWidth = 2f
                                )
                                drawCircle(
                                    color = Color(0xFF4ADE80),
                                    radius = 9f,
                                    center = androidx.compose.ui.geometry.Offset(sx1, sy1)
                                )
                                // Secondary Probe
                                val sx2 = cx + kotlin.math.cos((orbitPhase * -1.6f).toDouble()).toFloat() * r2
                                val sy2 = cy + kotlin.math.sin((orbitPhase * -1.6f).toDouble()).toFloat() * r2
                                drawCircle(
                                    color = Color(0xFFF472B6),
                                    radius = 6.5f,
                                    center = androidx.compose.ui.geometry.Offset(sx2, sy2)
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopStart)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "ORBITAL ENGINE • V=${boostLevel}.4x",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "Предпросмотр анимации",
                                    color = Color(0xFF4ADE80),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF2563EB))
                                    .clickable {
                                        orbitPhase += 0.6f
                                        onRunSandboxTests()
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Запустить автотесты",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1F2937))
                                    .border(1.dp, Color(0xFF374151), RoundedCornerShape(8.dp))
                                    .clickable {
                                        boostLevel = (boostLevel % 5) + 1
                                        orbitPhase += 0.9f
                                        sandboxLogs = sandboxLogs + "[Sandbox] Импульс орбиты обновлён: скорость ${boostLevel}.4x"
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Импульс орбиты",
                                    color = Color(0xFFE5E7EB),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF06080E))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            sandboxLogs.takeLast(6).forEach { logLine ->
                                Text(
                                    text = logLine,
                                    color = if (logLine.contains("PASS") || logLine.contains("успешно")) Color(0xFF4ADE80) else Color(0xFF93C5FD),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sections 4.3 & 8.2: Antigravity 2.0 AI Permissions Sheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPermissionsSheet(
    config: AiPermissionsConfig,
    onDismiss: () -> Unit,
    onUpdateConfig: ((AiPermissionsConfig) -> AiPermissionsConfig) -> Unit,
    onApplyPreset: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DesignColors.Surface,
        contentColor = DesignColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.90f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Разрешения ИИ (AI Permissions 2.0)",
                        color = DesignColors.TextPrimary,
                        fontSize = DesignType.TITLE_SP.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Управление политиками Antigravity 2.0 для смартфона, песочницы и ПК",
                        color = DesignColors.TextSecondary,
                        fontSize = DesignType.CAPTION_SP.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = DesignColors.TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Presets Row (clean text without icons)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PresetChip(
                    title = "Полная автономия",
                    selected = config.toolExecutionPolicy == "always-proceed",
                    onClick = { onApplyPreset("full_autonomy") },
                    modifier = Modifier.weight(1f)
                )
                PresetChip(
                    title = "В песочнице",
                    selected = config.toolExecutionPolicy == "proceed-in-sandbox",
                    onClick = { onApplyPreset("sandbox_safe") },
                    modifier = Modifier.weight(1f)
                )
                PresetChip(
                    title = "С проверкой",
                    selected = config.toolExecutionPolicy == "request-review",
                    onClick = { onApplyPreset("strict_review") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Глобальные политики Antigravity 2.0",
                color = DesignColors.TextPrimary,
                fontSize = DesignType.SECTION_SP.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))

            PermissionToggleRow(
                title = "Непрерывная работа в фоне (WakeLock + Foreground)",
                subtitle = "Не останавливать генерацию и инструменты при сворачивании окна",
                checked = config.runInBackgroundKeepAwake,
                onCheckedChange = { v -> onUpdateConfig { it.copy(runInBackgroundKeepAwake = v) } }
            )
            PermissionToggleRow(
                title = "Терминальная песочница (Terminal Sandbox)",
                subtitle = "Изолировать выполнение shell-команд и автотестов",
                checked = config.terminalSandboxEnabled,
                onCheckedChange = { v -> onUpdateConfig { it.copy(terminalSandboxEnabled = v) } }
            )
            PermissionToggleRow(
                title = "Доступ к файлам вне рабочей папки",
                subtitle = "Разрешить чтение/запись в Download и общие папки устройства",
                checked = config.allowNonWorkspaceFiles,
                onCheckedChange = { v -> onUpdateConfig { it.copy(allowNonWorkspaceFiles = v) } }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Разрешения ИИ на смартфоне (Android)",
                color = DesignColors.TextPrimary,
                fontSize = DesignType.SECTION_SP.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))

            PermissionToggleRow(
                title = "Чтение и запись файлов телефона",
                subtitle = "Создание, патч и поиск документов в памяти смартфона",
                checked = config.phoneFileReadWrite,
                onCheckedChange = { v -> onUpdateConfig { it.copy(phoneFileReadWrite = v) } }
            )
            PermissionToggleRow(
                title = "Выполнение команд ANDROID CLI (sh)",
                subtitle = "Доступ агента к локальному шеллу Android CLI",
                checked = config.phoneAndroidCliShell,
                onCheckedChange = { v -> onUpdateConfig { it.copy(phoneAndroidCliShell = v) } }
            )
            PermissionToggleRow(
                title = "Виртуальная песочница и WebView превью",
                subtitle = "Запуск проектов и автотестов внутри PhoneVirtualSandbox",
                checked = config.phoneVirtualSandboxExec,
                onCheckedChange = { v -> onUpdateConfig { it.copy(phoneVirtualSandboxExec = v) } }
            )
            PermissionToggleRow(
                title = "Открытие внешних приложений (Intent)",
                subtitle = "Запуск установленных приложений и браузера по поручению",
                checked = config.phoneOpenExternalApps,
                onCheckedChange = { v -> onUpdateConfig { it.copy(phoneOpenExternalApps = v) } }
            )
            PermissionToggleRow(
                title = "Поиск по контактам и медиатеке",
                subtitle = "Доступ к контактам, фото и аудиозаписям устройства",
                checked = config.phoneContactsRead,
                onCheckedChange = { v -> onUpdateConfig { it.copy(phoneContactsRead = v, phoneMediaCameraMic = v) } }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Разрешения ИИ на рабочей станции (Мост ПК)",
                color = DesignColors.TextPrimary,
                fontSize = DesignType.SECTION_SP.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))

            PermissionToggleRow(
                title = "Выполнение команд терминала ПК",
                subtitle = "Запуск сборки Gradle, Git, Node.js и скриптов на ПК",
                checked = config.pcTerminalExec,
                onCheckedChange = { v -> onUpdateConfig { it.copy(pcTerminalExec = v) } }
            )
            PermissionToggleRow(
                title = "Модификация файлов проекта на ПК",
                subtitle = "Запись кода и патчей с генерацией +added/-removed диффов",
                checked = config.pcFilesystemWrite,
                onCheckedChange = { v -> onUpdateConfig { it.copy(pcFilesystemWrite = v) } }
            )
            PermissionToggleRow(
                title = "Вызов MCP-серверов и OpenRouter Cloud",
                subtitle = "Доступ к MCP-инструментам и генерации фото/видео OpenRouter",
                checked = config.pcMcpToolsExec,
                onCheckedChange = { v -> onUpdateConfig { it.copy(pcMcpToolsExec = v, pcOpenRouterCloudGen = v) } }
            )
        }
    }
}

@Composable
private fun PresetChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) DesignColors.AccentBlue else DesignColors.SurfaceHigh)
            .border(
                1.dp,
                if (selected) DesignColors.AccentBlue else DesignColors.Border,
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 9.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (selected) DesignColors.TextPrimary else DesignColors.TextSecondary,
            fontSize = DesignType.CAPTION_SP.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PermissionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DesignColors.SurfaceHigh)
            .border(1.dp, DesignColors.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DesignColors.TextPrimary,
                fontSize = DesignType.BODY_SP.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = DesignColors.TextSecondary,
                fontSize = DesignType.CAPTION_SP.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DesignColors.TextPrimary,
                checkedTrackColor = DesignColors.AccentBlue,
                uncheckedThumbColor = DesignColors.TextSecondary,
                uncheckedTrackColor = DesignColors.Border
            )
        )
    }
}
