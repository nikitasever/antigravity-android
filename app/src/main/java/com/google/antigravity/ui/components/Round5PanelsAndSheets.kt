package com.google.antigravity.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.imePadding
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
                                    color = if (item.categoryBadge.contains("ЛИДЕР")) Color(0xFFFBBF24) else DesignColors.AccentBlue,
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
                            .border(1.dp, DesignColors.AccentBlue, RoundedCornerShape(7.dp))
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
            SheetHeader(title = "Контекст беседы", onClose = onDismiss)
            Text("$usedCompact из $maxCompact токенов ($pctInt%)",
                color = DesignColors.TextSecondary, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace)

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = DesignColors.AccentBlue,
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
    var category by remember { mutableStateOf(OpenRouterCategory.ALL) }
    var query by remember { mutableStateOf("") }
    var credentials by remember { mutableStateOf(false) }
    var keyDraft by remember(openRouterApiKey) { mutableStateOf(openRouterApiKey) }
    var customId by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    val filtered = remember(models, category, query) {
        val search = query.trim()
        models.filter { (category == OpenRouterCategory.ALL || category in it.categories) &&
            (search.isBlank() || it.name.contains(search, true) || it.id.contains(search, true)) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = DesignColors.Bg, contentColor = DesignColors.TextPrimary) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().padding(horizontal = 16.dp)) {
            SheetHeader(title = "Модели OpenRouter", onClose = onDismiss)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isSyncing) "Обновление списка…" else "Моделей: ${models.size}",
                    color = DesignColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onSyncNow, enabled = !isSyncing) {
                    if (isSyncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, "Обновить модели", tint = DesignColors.TextSecondary)
                }
                TextButton(onClick = { credentials = !credentials }) { Text("Ключ и модель", color = DesignColors.TextPrimary) }
            }
            if (cacheStatusText.contains("ошиб", true) || cacheStatusText.contains("частич", true)) {
                Text("Список обновлён не полностью. Можно выбрать сохранённую модель или повторить обновление.",
                    color = DesignColors.TextSecondary, fontSize = 13.sp)
            }
            AnimatedVisibility(credentials) {
                Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = keyDraft, onValueChange = { keyDraft = it }, singleLine = true,
                        label = { Text("Ключ OpenRouter") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Скрыть" else "Показать") } },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = DesignColors.TextPrimary,
                            unfocusedTextColor = DesignColors.TextPrimary, focusedBorderColor = DesignColors.AccentBlue,
                            unfocusedBorderColor = DesignColors.Border))
                    TextButton(onClick = { onSaveApiKey(keyDraft.trim()); credentials = false }) { Text("Сохранить ключ") }
                    OutlinedTextField(value = customId, onValueChange = { customId = it }, singleLine = true,
                        label = { Text("ID модели") }, placeholder = { Text("провайдер/модель") },
                        modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = DesignColors.TextPrimary, unfocusedTextColor = DesignColors.TextPrimary,
                            focusedBorderColor = DesignColors.AccentBlue, unfocusedBorderColor = DesignColors.Border))
                    TextButton(enabled = customId.trim().isNotEmpty(), onClick = { onSelectCustomModel(customId.trim()); onDismiss() }) {
                        Text("Выбрать по ID")
                    }
                }
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Поиск моделей", fontSize = 15.sp) }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(androidx.compose.material.icons.Icons.Default.Close, "Очистить поиск")
                } }, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = DesignColors.TextPrimary,
                    unfocusedTextColor = DesignColors.TextPrimary, focusedContainerColor = DesignColors.Surface,
                    unfocusedContainerColor = DesignColors.Surface, focusedBorderColor = DesignColors.Border,
                    unfocusedBorderColor = Color.Transparent))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(OpenRouterCategory.ALL to "Все", OpenRouterCategory.REASONING to "Рассуждение",
                    OpenRouterCategory.VISION to "Зрение", OpenRouterCategory.IMAGE_GEN to "Фото",
                    OpenRouterCategory.VIDEO_GEN to "Видео", OpenRouterCategory.AUDIO to "Аудио",
                    OpenRouterCategory.FREE to "Бесплатные").forEach { (value, label) ->
                    androidx.compose.material3.FilterChip(selected = category == value,
                        onClick = { category = value }, label = { Text(label) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = DesignColors.Bg, labelColor = DesignColors.TextSecondary,
                            selectedContainerColor = DesignColors.SurfaceHigh, selectedLabelColor = DesignColors.TextPrimary))
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (filtered.isEmpty()) item {
                    Text(if (models.isEmpty()) "Список моделей пока пуст. Обновите его или укажите ID модели."
                        else "Модели не найдены. Измените поиск или фильтр.", color = DesignColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 24.dp))
                }
                items(filtered, key = { it.id }) { model ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (model.id == selectedModelId) DesignColors.Surface else Color.Transparent)
                        .clickable { onSelectModel(model); onDismiss() }.padding(horizontal = 12.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(model.name, color = DesignColors.TextPrimary, fontSize = 16.sp,
                                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(model.id, color = DesignColors.TextSubtle, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val details = buildList {
                                if (model.isFree) add("Бесплатная")
                                if (model.isVideoGen) add("Видео") else if (model.isImageGen) add("Изображения")
                                if (model.isReasoning) add("Рассуждение")
                                if (model.isVision) add("Зрение")
                                if (model.contextLength > 0) add("${model.contextLength / 1000} тыс. контекст")
                            }
                            if (details.isNotEmpty()) Text(details.joinToString(" · "), color = DesignColors.TextSecondary,
                                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (model.id == selectedModelId) Icon(androidx.compose.material.icons.Icons.Default.Check,
                            "Выбрана", tint = DesignColors.TextPrimary, modifier = Modifier.padding(start = 12.dp).size(22.dp))
                    }
                    HorizontalDivider(color = DesignColors.Border.copy(alpha = 0.35f))
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
            SheetHeader(title = "Файлы рабочего пространства", onClose = onDismiss)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = DesignColors.AccentBlue)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить Google Диск", tint = DesignColors.AccentBlue)
                    }
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
                            .border(1.dp, if (isGemini) DesignColors.AccentBlue else Color(0xFF32394A), RoundedCornerShape(8.dp))
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
                            .border(1.dp, if (!isGemini) DesignColors.AccentBlue else Color(0xFF32394A), RoundedCornerShape(8.dp))
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
                        cursorBrush = SolidColor(DesignColors.AccentBlue),
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
                        cursorBrush = SolidColor(DesignColors.AccentBlue),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(DesignColors.AccentBlue)
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
                                            .background(DesignColors.AccentBlue)
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
            SheetHeader(title = "Терминал телефона", onClose = onDismiss)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Локальная консоль смартфона и изолированная среда тестирования ИИ",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                            .background(if (active) DesignColors.AccentBlue else Color(0xFF1B1E28))
                            .border(1.dp, if (active) DesignColors.AccentBlue else Color(0xFF2C3140), RoundedCornerShape(10.dp))
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
                                focusedBorderColor = DesignColors.AccentBlue,
                                unfocusedBorderColor = Color(0xFF2D3342)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(DesignColors.AccentBlue)
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
                                        color = if (entry.exitCode == 0) DesignColors.AccentBlue else Color(0xFFF87171),
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
                                        .background(DesignColors.AccentBlue)
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
                                        .background(DesignColors.AccentBlue)
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
                                    color = DesignColors.AccentBlue,
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
                                color = DesignColors.AccentBlue,
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
                                    .background(DesignColors.AccentBlue)
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
            SheetHeader(title = "Доступ помощника", onClose = onDismiss)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Доступ к телефону, песочнице и компьютеру",
                        color = DesignColors.TextSecondary,
                        fontSize = DesignType.CAPTION_SP.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                text = "Режим выполнения",
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
            .background(DesignColors.Surface)
            .border(1.dp, DesignColors.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DesignColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = DesignColors.TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = com.google.antigravity.ui.design.designSwitchColors()
        )
    }
}
