package com.google.antigravity.ui.components

import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.antigravity.model.ArtifactDocument
import com.google.antigravity.ui.design.DesignColors
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.google.antigravity.model.ArtifactVersion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun looksLikeHtml(content: String): Boolean {
    val head = content.trimStart().take(400).lowercase()
    return head.startsWith("<!doctype html") || head.startsWith("<html") ||
        head.contains("<body") || head.contains("<div id=")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactLibraryScreen(artifacts: List<ArtifactDocument>, onOpenDrawer: () -> Unit,
    onOpenFiles: () -> Unit, onSaveFile: (String, String) -> Unit, onRemove: (String) -> Unit,
    onRename: (String, String) -> Unit = { _, _ -> },
    onEditContent: (String, String) -> Unit = { _, _ -> },
    onRestoreVersion: (String, String) -> Unit = { _, _ -> },
    onTogglePinned: (String) -> Unit = {}) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var onlySaved by rememberSaveable { mutableStateOf(false) }
    var confirmRemoval by remember { mutableStateOf(false) }
    var documentMenuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var showVersions by remember { mutableStateOf(false) }
    var previewMode by rememberSaveable { mutableStateOf(true) }
    val selected = artifacts.find { it.id == selectedId }
    val context = LocalContext.current
    BackHandler(enabled = selected != null) {
        if (editing) editing = false else selectedId = null
    }
    val filtered = remember(artifacts, query, onlySaved) { artifacts.filter {
        (!onlySaved || it.id.startsWith("saved_message_")) &&
            (query.isBlank() || it.title.contains(query.trim(), true) || it.content.contains(query.trim(), true))
    }.sortedByDescending { it.pinned } }
    LaunchedEffect(selectedId) { editing = false; previewMode = true; documentMenuOpen = false }
    Surface(color = DesignColors.Bg, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
        Column {
            ReferencePageHeader(title = if (selected == null) "Библиотека"
                else parseInlineMarkdown(selected.title, DesignColors.TextPrimary).text,
                navigationIcon = {
                IconButton(onClick = {
                    when {
                        editing -> editing = false
                        selected != null -> selectedId = null
                        else -> onOpenDrawer()
                    }
                }) {
                    Icon(if (selected == null) Icons.Default.Menu else Icons.AutoMirrored.Filled.ArrowBack,
                        if (selected == null) "Открыть меню" else "Назад к библиотеке")
                }
            }, actions = {
                if (selected != null && !editing) {
                    IconButton(onClick = { onSaveFile("document_${selected.id.takeLast(12)}.md", selected.content) }) {
                        Icon(Icons.Default.SaveAlt, "Сохранить документ на телефон")
                    }
                    Box {
                        IconButton(onClick = { documentMenuOpen = true }) { Icon(Icons.Default.MoreVert, "Действия с документом") }
                        DropdownMenu(expanded = documentMenuOpen, onDismissRequest = { documentMenuOpen = false },
                            containerColor = DesignColors.Surface) {
                            if (selected.id.startsWith("saved_message_")) {
                                DropdownMenuItem(text = { Text(if (selected.pinned) "Открепить" else "Закрепить") },
                                    onClick = { documentMenuOpen = false; onTogglePinned(selected.id) })
                                DropdownMenuItem(text = { Text("Редактировать") },
                                    onClick = { documentMenuOpen = false; editing = true })
                                if (selected.versions.isNotEmpty()) DropdownMenuItem(text = { Text("Версии документа") },
                                    onClick = { documentMenuOpen = false; showVersions = true })
                            }
                            DropdownMenuItem(text = { Text("Поделиться") }, onClick = {
                                documentMenuOpen = false
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, selected.content)
                                }, "Поделиться документом"))
                            })
                            if (selected.id.startsWith("saved_message_")) DropdownMenuItem(text = { Text("Удалить сохранённый документ") },
                                onClick = { documentMenuOpen = false; confirmRemoval = true })
                        }
                    }
                } else if (selected == null) IconButton(onClick = onOpenFiles) { Icon(Icons.Default.FolderOpen, "Файлы рабочего пространства") }
            })
            if (selected != null) {
                if (editing) {
                    ArtifactEditForm(
                        document = selected,
                        onSave = { title, content ->
                            onRename(selected.id, title)
                            onEditContent(selected.id, content)
                            editing = false
                        },
                        onCancel = { editing = false }
                    )
                } else {
                    // Переключатель интерактивного предпросмотра и исходника.
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = previewMode, onClick = { previewMode = true },
                            label = { Text("Предпросмотр") })
                        FilterChip(selected = !previewMode, onClick = { previewMode = false },
                            label = { Text("Исходник") })
                    }
                    if (previewMode && looksLikeHtml(selected.content)) {
                        InteractiveHtmlPreview(content = selected.content,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp))
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp)) {
                            item {
                                if (previewMode) {
                                    ChatGptMarkdownText(selected.content, onSaveCodeToPhone = onSaveFile)
                                } else {
                                    Text(selected.content, color = Color(0xFFCCCCCC), fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            } else {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Поиск документов") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить поиск") } },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !onlySaved, onClick = { onlySaved = false }, label = { Text("Все") })
                    FilterChip(selected = onlySaved, onClick = { onlySaved = true }, label = { Text("Сохранённые ответы") })
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (filtered.isEmpty()) item {
                        Text(if (query.isNotBlank()) "Документы не найдены" else "Сохраните ответ из меню сообщения — он появится здесь.",
                            color = Color(0xFF9E9E9E), modifier = Modifier.padding(vertical = 24.dp))
                    }
                    items(filtered, key = { it.id }) { document ->
                        Surface(color = DesignColors.Surface, shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clickable { selectedId = document.id }) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (document.pinned) {
                                        Icon(Icons.Default.PushPin, "Закреплён",
                                            tint = Color(0xFF60A5FA), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(parseInlineMarkdown(document.title, DesignColors.TextPrimary).text,
                                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                        color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Text(parseInlineMarkdown(document.summary, DesignColors.TextSecondary).text,
                                    fontSize = 13.sp, color = Color(0xFF9E9E9E),
                                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                if (document.versions.isNotEmpty()) {
                                    Text("${document.versions.size} версий", fontSize = 11.sp,
                                        color = Color(0xFF60A5FA), modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmRemoval && selected != null) AlertDialog(onDismissRequest = { confirmRemoval = false },
        title = { Text("Удалить из библиотеки?") }, text = { Text("Сообщение останется в чате. Будет удалена только сохранённая копия вместе с версиями.") },
        confirmButton = { TextButton(onClick = { onRemove(selected.id); selectedId = null; confirmRemoval = false }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Отмена") } })
    if (showVersions && selected != null) ArtifactVersionsDialog(
        versions = selected.versions,
        onDismiss = { showVersions = false },
        onRestore = { versionId ->
            onRestoreVersion(selected.id, versionId)
            showVersions = false
        }
    )
}

/** Форма редактирования заголовка и содержимого локальной сохранённой копии. */
@Composable
private fun ArtifactEditForm(
    document: ArtifactDocument,
    onSave: (title: String, content: String) -> Unit,
    onCancel: () -> Unit
) {
    var title by rememberSaveable(document.id) { mutableStateOf(document.title) }
    var content by rememberSaveable(document.id) { mutableStateOf(document.content) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true,
            label = { Text("Название") },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = content, onValueChange = { content = it },
            label = { Text("Содержимое") }, minLines = 10, maxLines = 24,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            modifier = Modifier.fillMaxWidth().weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Отмена") }
            Button(onClick = { onSave(title, content) }, modifier = Modifier.weight(1f)) { Text("Сохранить") }
        }
    }
}

/** Список версий документа с восстановлением (текущий текст уходит в историю). */
@Composable
private fun ArtifactVersionsDialog(
    versions: List<ArtifactVersion>,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Версии документа") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(versions.asReversed(), key = { it.id }) { version ->
                    Surface(color = Color(0xFF202020), shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(dateFormat.format(Date(version.savedAt)), fontSize = 12.sp, color = Color(0xFF8E8E8E))
                            Text(version.content.replace(Regex("\\s+"), " ").take(120), fontSize = 13.sp,
                                color = Color(0xFFCCCCCC), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 6.dp))
                            TextButton(onClick = { onRestore(version.id) }, modifier = Modifier.align(Alignment.End)) {
                                Text("Восстановить")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

/** Интерактивный предпросмотр HTML-артефактов (WebView с включёнными сценариями). */
@Composable
private fun InteractiveHtmlPreview(content: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
            }
        },
        update = { webView -> webView.loadDataWithBaseURL(null, content, "text/html", "utf-8", null) }
    )
}
