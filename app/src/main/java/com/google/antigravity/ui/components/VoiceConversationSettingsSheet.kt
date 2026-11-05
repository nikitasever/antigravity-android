package com.google.antigravity.ui.components

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.ui.design.DesignColors
import com.google.antigravity.ui.design.designSwitchColors

/** ChatGPT 091540: grouped voice, language and audio rows in a modal sheet.
 * Voice opens the existing settings owner; audio opens actual Android controls.
 * Recognition is currently Russian-only, so the language row is read-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceConversationSettingsSheet(
    voiceLabel: String,
    onOpenVoiceSettings: () -> Unit,
    showCaptions: Boolean,
    onCaptionsChange: (Boolean) -> Unit,
    onCycleVisualizerStyle: () -> Unit,
    onDismiss: () -> Unit,
    onSheetTopChanged: (Float) -> Unit = {}
) {
    val context = LocalContext.current
    var extrasVisible by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        // Measure a child after the sheet's anchored offset, not the outer modifier
        // (which covers the unshifted full dialog on Material3).
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(48.dp)
                .onGloballyPositioned {
                    val top = it.positionOnScreen().y
                    if (top.isFinite()) onSheetTopChanged(top)
                },
                contentAlignment = Alignment.Center) {
                BottomSheetDefaults.DragHandle(color = DesignColors.TextSubtle)
            }
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DesignColors.SurfaceHigh, contentColor = DesignColors.TextPrimary) {
        SheetHeader("Настройки", onDismiss, trailingContent = {
            IconButton(onClick = { extrasVisible = !extrasVisible }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreHoriz, if (extrasVisible) "Скрыть дополнительные настройки" else "Дополнительные настройки")
            }
        })
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(color = DesignColors.SurfaceHigh, shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, DesignColors.Border)) {
                Column {
                    VoiceSettingRow(Icons.Default.GraphicEq, "Голос", voiceLabel, onClick = onOpenVoiceSettings)
                    HorizontalDivider(color = DesignColors.Border)
                    VoiceSettingRow(Icons.Default.Language, "Язык", "Русский")
                    HorizontalDivider(color = DesignColors.Border)
                    VoiceSettingRow(Icons.AutoMirrored.Filled.VolumeUp, "Вывод звука", "Настройки Android", stackValue = true) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
                            .onFailure { Toast.makeText(context, "Не удалось открыть настройки звука", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
            if (extrasVisible) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Субтитры", fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(checked = showCaptions, onCheckedChange = onCaptionsChange, colors = designSwitchColors())
            }
            TextButton(onClick = onCycleVisualizerStyle, modifier = Modifier.fillMaxWidth()) {
                Text("Изменить визуализацию", color = DesignColors.TextPrimary)
            }
            }
        }
    }
}

@Composable
private fun VoiceSettingRow(icon: ImageVector, title: String, value: String,
    stackValue: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        if (stackValue) Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, lineHeight = 22.sp)
            Text(value, fontSize = 15.sp, lineHeight = 21.sp, color = DesignColors.TextSecondary)
        } else {
            Text(title, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
            Text(value, fontSize = 15.sp, lineHeight = 21.sp, color = DesignColors.TextSecondary,
                modifier = Modifier.widthIn(max = 130.dp))
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = DesignColors.TextSecondary, modifier = Modifier.size(24.dp))
    }
}
