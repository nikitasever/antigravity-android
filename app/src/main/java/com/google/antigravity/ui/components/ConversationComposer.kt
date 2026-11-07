package com.google.antigravity.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.ui.design.DesignColors

/** One owner for compact home input and Claude-style conversation/queue input.
 * The field gets its own row while writing or generating, so Stop/Queue cannot
 * squeeze it to zero width on a narrow phone. Dispatch remains in the caller.
 */
@Composable
internal fun ConversationComposer(
    text: String,
    placeholder: String,
    expanded: Boolean,
    streaming: Boolean,
    hasAttachments: Boolean,
    dictating: Boolean,
    onTextChange: (String) -> Unit,
    onAttach: () -> Unit,
    onDictate: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    stopDescription: String = "Остановить генерацию",
    modelLabel: String = "",
    onModel: () -> Unit = {},
    voiceAction: @Composable () -> Unit
) {
    val stacked = expanded || streaming || text.isNotEmpty() || hasAttachments
    val canSend = text.isNotBlank() || hasAttachments
    val field: @Composable () -> Unit = {
        BasicTextField(value = text, onValueChange = onTextChange,
            textStyle = TextStyle(color = DesignColors.TextPrimary, fontSize = 16.sp, lineHeight = 24.sp),
            cursorBrush = SolidColor(DesignColors.TextPrimary), maxLines = 5,
            modifier = Modifier.fillMaxWidth(), decorationBox = { input ->
                Box {
                    if (text.isEmpty()) Text(placeholder, fontSize = 16.sp, lineHeight = 24.sp,
                        maxLines = if (stacked) 2 else 1, overflow = TextOverflow.Ellipsis,
                        color = DesignColors.TextSubtle)
                    input()
                }
            })
    }
    val attach: @Composable () -> Unit = {
        IconButton(onClick = onAttach) {
            Icon(Icons.Default.Add, "Загрузить файлы или фото", modifier = Modifier.size(26.dp),
                tint = DesignColors.TextPrimary)
        }
    }
    val dictate: @Composable () -> Unit = {
        FilledIconButton(onClick = onDictate,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (dictating) MaterialTheme.colorScheme.error else Color.Transparent,
                contentColor = DesignColors.TextPrimary)) {
            Icon(Icons.Outlined.Mic, if (dictating) "Завершить диктовку" else "Голосовая диктовка в текст")
        }
    }
    val submit: @Composable () -> Unit = {
        if (canSend) FilledIconButton(onClick = onSend,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (streaming) DesignColors.SurfaceHigh else DesignColors.TextPrimary,
                contentColor = if (streaming) DesignColors.TextPrimary else DesignColors.Bg)) {
            Icon(Icons.Default.ArrowUpward, if (streaming) "Добавить в очередь" else "Отправить")
        }
        if (streaming) FilledIconButton(onClick = onStop,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = DesignColors.TextPrimary, contentColor = DesignColors.Bg)) {
            Icon(Icons.Default.Stop, stopDescription)
        } else if (!canSend) voiceAction()
    }
    Surface(shape = RoundedCornerShape(if (stacked) 24.dp else 32.dp),
        color = if (stacked) DesignColors.Surface else DesignColors.SurfaceHigh,
        border = BorderStroke(1.dp, DesignColors.Border), modifier = Modifier.fillMaxWidth()) {
        // Keep the editing node at the SAME composition position in both modes.
        // Moving a focused editor between parents (even movableContentOf) can
        // detach Android's input connection during the first keystroke.
        Column(Modifier.padding(if (stacked) 8.dp else 4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = if (stacked) 40.dp else 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (!stacked) attach()
                Box(Modifier.weight(1f).padding(
                    horizontal = if (stacked) 8.dp else 4.dp,
                    vertical = if (stacked) 8.dp else 0.dp)) { field() }
                if (!stacked) { dictate(); submit() }
            }
            if (stacked) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                attach()
                if (modelLabel.isNotBlank()) Box(Modifier.weight(1f)) {
                    TextButton(onClick = onModel, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(modelLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = DesignColors.TextPrimary, fontSize = 13.sp)
                    }
                } else Spacer(Modifier.weight(1f))
                dictate()
                submit()
            }
        }
    }
}
