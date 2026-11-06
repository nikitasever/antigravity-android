package com.google.antigravity.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import com.google.antigravity.ui.design.DesignColors
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryDrawer(
    sessions: List<RemoteSessionItem>,
    preferences: Map<String, ChatLibraryPreferences>,
    activeSessionId: String,
    searchContents: (String) -> Set<String>,
    onUpdate: (String, ChatLibraryPreferences) -> Unit,
    onSelect: (RemoteSessionItem) -> Unit,
    onNewChat: () -> Unit,
    onRefresh: () -> Unit,
    onSection: (DrawerSection) -> Unit,
    onModels: () -> Unit,
    onFiles: () -> Unit,
    onTerminal: () -> Unit,
    onPermissions: () -> Unit,
    onContext: () -> Unit,
    onProfile: () -> Unit,
    projects: List<ScratchProjectItem> = emptyList(),
    onSelectProject: (ScratchProjectItem) -> Unit = {},
    onTemporaryChat: () -> Unit = {}
) {
    var query by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var toolsExpanded by rememberSaveable { mutableStateOf(false) }
    var renameSession by remember { mutableStateOf<RemoteSessionItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    val matches = remember(query, sessions, preferences) { searchContents(query) }
    val visible = remember(sessions, preferences, query, archived, matches) {
        ChatLibrary.visible(sessions, preferences, query, archived, matches)
    }
    // Поиск по проектам («Поиск чатов, файлов и проектов»).
    val matchingProjects = remember(query, projects) {
        val q = query.trim()
        if (q.isEmpty()) emptyList() else projects.filter { it.name.contains(q, true) }
    }
    val muted = Color(0xFF9E9E9E)
    ModalDrawerSheet(
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().imePadding(),
        drawerContainerColor = Color(0xFF111111), drawerContentColor = Color.White
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Antigravity", fontSize = 32.sp, fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Обновить историю") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Поиск чатов и проектов", fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                Icon(Icons.Default.Close, "Очистить поиск")
            } },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = DesignColors.SurfaceHigh, unfocusedContainerColor = DesignColors.SurfaceHigh,
                focusedBorderColor = DesignColors.Border, unfocusedBorderColor = Color.Transparent),
            shape = RoundedCornerShape(32.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (query.isBlank()) {
                item {
                    HistoryDestination(Icons.Default.VisibilityOff, "Временный чат", onTemporaryChat)
                    HistoryDestination(Icons.Default.Folder, "Проекты") { onSection(DrawerSection.PROJECTS) }
                    HistoryDestination(Icons.Default.Description, "Библиотека") { onSection(DrawerSection.LIBRARY) }
                    HistoryDestination(Icons.Default.Computer, "Рабочие сессии") { onSection(DrawerSection.REMOTE_SESSIONS) }
                    HistoryDestination(if (toolsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        "Инструменты", { toolsExpanded = !toolsExpanded })
                    if (toolsExpanded) {
                        HistoryDestination(Icons.Default.PhoneAndroid, "Файлы телефона") { onSection(DrawerSection.PHONE_EXPLORER) }
                        HistoryDestination(Icons.Default.Cloud, "Модели OpenRouter", onModels)
                        HistoryDestination(Icons.Default.FolderOpen, "Файлы рабочего пространства", onFiles)
                        HistoryDestination(Icons.Default.Image, "Изображения") { onSection(DrawerSection.IMAGES) }
                        HistoryDestination(Icons.Default.Extension, "Плагины и MCP") { onSection(DrawerSection.PLUGINS) }
                        HistoryDestination(Icons.Default.Terminal, "Терминал и песочница", onTerminal)
                        HistoryDestination(Icons.Default.Security, "Доступ ИИ", onPermissions)
                        HistoryDestination(Icons.Default.DataUsage, "Контекст чата", onContext)
                        HistoryDestination(Icons.Default.Language, "Веб-синхронизация") { onSection(DrawerSection.GOOGLE_WEB) }
                        HistoryDestination(Icons.Default.Schedule, "Запланировано") { onSection(DrawerSection.SCHEDULED) }
                    }
                    HorizontalDivider(Modifier.padding(16.dp), color = Color(0xFF303030))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (archived) "Архив" else if (query.isBlank()) "Недавние" else "Результаты поиска",
                        color = muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { archived = !archived }) { Text(if (archived) "Все чаты" else "Архив", color = muted) }
                }
            }
            if (matchingProjects.isNotEmpty()) {
                item {
                    Text("Проекты", color = muted, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                items(matchingProjects.distinctBy { it.host to it.path }, key = { listOf("project", it.host, it.path).toString() }) { proj ->
                    Surface(color = Color.Transparent, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { onSelectProject(proj) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = DesignColors.TextSecondary,
                                modifier = Modifier.padding(start = 12.dp).size(20.dp))
                            Column(Modifier.weight(1f).padding(start = 14.dp, top = 13.dp, bottom = 13.dp)) {
                                Text(proj.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontSize = 15.sp, color = Color.White)
                                Text("Проект • ${proj.fileCount} объектов", color = muted, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (visible.isEmpty() && matchingProjects.isEmpty()) item {
                Text(when { query.isNotBlank() -> "Ничего не найдено. Попробуйте другое слово."
                    archived -> "В архиве пока нет чатов"
                    else -> "Здесь появятся ваши разговоры" }, color = muted, fontSize = 14.sp,
                    modifier = Modifier.padding(20.dp))
            }
            items(visible, key = { "chat_${it.id}" }) { session ->
                var menuOpen by remember(session.id) { mutableStateOf(false) }
                val p = preferences[session.id] ?: ChatLibraryPreferences()
                Surface(color = if (session.id == activeSessionId) Color(0xFF2F2F2F) else Color.Transparent,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clickable { onSelect(session) }.heightIn(min = 52.dp)
                            .padding(start = 16.dp, top = 13.dp, bottom = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ChatBubbleOutline, null, tint = DesignColors.TextSecondary,
                                modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text((if (p.pinned) "• " else "") + session.title, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, fontSize = 16.sp, color = Color.White)
                            if (query.isNotBlank() && session.id in matches && !session.title.contains(query.trim(), true)) {
                                Text("Совпадение в сообщении или вложении", color = muted, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreHoriz, "Действия с чатом «${session.title}»", tint = muted) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(if (p.pinned) "Открепить" else "Закрепить") }, onClick = {
                                    onUpdate(session.id, p.copy(pinned = !p.pinned)); menuOpen = false
                                })
                                DropdownMenuItem(text = { Text("Переименовать") }, onClick = {
                                    renameSession = session; renameText = session.title; menuOpen = false
                                })
                                DropdownMenuItem(text = { Text(if (p.archived) "Вернуть из архива" else "В архив") }, onClick = {
                                    onUpdate(session.id, p.copy(archived = !p.archived)); menuOpen = false
                                })
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = onProfile, modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = DesignColors.SurfaceHigh)) {
                Icon(Icons.Default.PersonOutline, "Настройки", tint = DesignColors.TextPrimary)
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onNewChat, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF9F9F7), contentColor = Color.Black)) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(22.dp))
                Text("Новый чат", fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    renameSession?.let { session ->
        AlertDialog(onDismissRequest = { renameSession = null }, containerColor = Color(0xFF242424),
            titleContentColor = Color.White, textContentColor = Color.White,
            title = { Text("Название чата") }, text = {
                Column {
                    OutlinedTextField(value = renameText, onValueChange = { renameText = it.take(160) },
                        singleLine = true, label = { Text("Название") }, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                    Text("Названия, закрепления и архив синхронизируются с ПК и сохраняются на этом устройстве.", color = muted,
                        fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }, confirmButton = { TextButton(enabled = renameText.isNotBlank(), onClick = {
                onUpdate(session.id, (preferences[session.id] ?: ChatLibraryPreferences()).copy(title = renameText.trim()))
                renameSession = null
            }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { renameSession = null }) { Text("Отмена") } })
    }
}

@Composable
private fun HistoryDestination(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color(0xFFC4C3BB), modifier = Modifier.size(24.dp))
        Text(label, color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(start = 16.dp))
    }
}
