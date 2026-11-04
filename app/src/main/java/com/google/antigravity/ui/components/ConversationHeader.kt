package com.google.antigravity.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.ui.TwoLineMenuCircleButton
import com.google.antigravity.ui.design.DesignColors

/** Three equally aligned controls, sized from the supplied ChatGPT Android reference.
 * Conversation-specific utilities stay in the menu instead of a second header row.
 */
@Composable
internal fun ConversationHeader(
    workSelected: Boolean,
    modelName: String,
    contextLabel: String,
    onDrawer: () -> Unit,
    onSelectWork: (Boolean) -> Unit,
    onNewChat: () -> Unit,
    onModel: () -> Unit,
    onContext: () -> Unit,
    onSearch: () -> Unit,
    onOutline: () -> Unit,
    hasConversation: Boolean = false,
    title: String = "",
    executionLabel: String = ""
) {
    var menuOpen by remember { mutableStateOf(false) }
    val actions: @Composable () -> Unit = {
        Box {
            FilledIconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = DesignColors.SurfaceHigh)) {
                Icon(Icons.Default.MoreHoriz, "Меню чата", tint = Color.White, modifier = Modifier.size(26.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                containerColor = DesignColors.SurfaceHigh) {
                DropdownMenuItem(text = { Text("Новый чат") }, onClick = { menuOpen = false; onNewChat() })
                if (hasConversation) DropdownMenuItem(text = { Text("Чат и рабочие сессии") },
                    onClick = { menuOpen = false; onSelectWork(!workSelected) })
                DropdownMenuItem(text = { Column { Text("Модель"); Text(modelName, fontSize = 12.sp, color = DesignColors.TextSecondary) } },
                    onClick = { menuOpen = false; onModel() })
                DropdownMenuItem(text = { Column { Text("Контекст"); Text(contextLabel, fontSize = 12.sp, color = DesignColors.TextSecondary) } },
                    onClick = { menuOpen = false; onContext() })
                HorizontalDivider(color = DesignColors.Border)
                DropdownMenuItem(text = { Text("Поиск в чате") }, onClick = { menuOpen = false; onSearch() })
                DropdownMenuItem(text = { Text("Содержание разговора") }, onClick = { menuOpen = false; onOutline() })
            }
        }
    }
    if (hasConversation) {
        // Both populated reference conversations use a title and session controls.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDrawer) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Открыть список чатов", tint = DesignColors.TextPrimary)
            }
            Column(Modifier.weight(1f).clickable(onClick = onModel).padding(horizontal = 4.dp)) {
                Text(title.ifBlank { "Новый чат" }, fontSize = 18.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = DesignColors.TextPrimary)
                Text("$executionLabel · $modelName", fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = DesignColors.TextSecondary)
            }
            IconButton(onClick = onNewChat) {
                Icon(Icons.Default.Edit, "Новый чат", tint = DesignColors.TextPrimary)
            }
            actions()
        }
    } else ChatModeHeader(workSelected, onDrawer, onSelectWork, actions)
}

/** The same segment geometry is used when switching between Chat and Work. */
@Composable
internal fun ChatModeHeader(
    workSelected: Boolean,
    onDrawer: () -> Unit,
    onSelectWork: (Boolean) -> Unit,
    trailing: @Composable () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TwoLineMenuCircleButton(onDrawer)
        Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Surface(color = DesignColors.SurfaceHigh, shape = RoundedCornerShape(28.dp),
                modifier = Modifier.widthIn(max = 190.dp).fillMaxWidth().height(48.dp)) {
                Row(Modifier.padding(3.dp)) {
                    listOf("Чат", "Работа").forEachIndexed { index, label ->
                        val selected = workSelected == (index == 1)
                        Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                            .background(if (selected) DesignColors.Surface else Color.Transparent)
                            .selectable(selected, role = Role.Tab, onClick = { onSelectWork(index == 1) }),
                            contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 16.sp, color = DesignColors.TextPrimary)
                        }
                    }
                }
            }
        }
        trailing()
    }
}
