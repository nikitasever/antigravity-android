package com.google.antigravity.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.model.ProjectWorkspace
import com.google.antigravity.ui.design.DesignColors
import com.google.antigravity.model.ProjectWorkspaces
import com.google.antigravity.model.ScratchProjectItem

/**
 * Раздел «Проекты»: рабочие области с инструкциями проекта, документами
 * контекста и связанными чатами (по образцу Projects ChatGPT/Claude).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkspaceScreen(
    projects: List<ScratchProjectItem>,
    workspaces: Map<String, ProjectWorkspace>,
    activeChatId: String,
    linkedChatTitles: Map<String, String>,
    onOpenDrawer: () -> Unit,
    onOpenProjectChat: (ScratchProjectItem) -> Unit,
    onSetInstructions: (projectId: String, text: String) -> Unit,
    onPickDocument: (projectId: String) -> Unit,
    onRemoveDocument: (projectId: String, documentId: String) -> Unit,
    onLinkActiveChat: (projectId: String) -> Unit,
    onUnlinkChat: (projectId: String, chatId: String) -> Unit,
    onOpenLinkedChat: (chatId: String, title: String) -> Unit
) {
    var selectedProjectName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedProject = projects.find { it.name == selectedProjectName }
    val selectedWorkspace = selectedProject?.let { workspaces[ProjectWorkspaces.idFor(it.name)] }
    BackHandler(enabled = selectedProject != null) { selectedProjectName = null }

    Surface(color = DesignColors.Bg, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
        Column {
            ReferencePageHeader(
                title = if (selectedProject == null) "Проекты" else selectedProject.name,
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedProject != null) selectedProjectName = null else onOpenDrawer()
                    }) {
                        Icon(
                            if (selectedProject == null) Icons.Default.Menu else Icons.AutoMirrored.Filled.ArrowBack,
                            if (selectedProject == null) "Открыть меню" else "Назад к проектам"
                        )
                    }
                }
            )
            if (selectedProject == null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (projects.isEmpty()) item {
                        Text(
                            "Проекты рабочей папки ПК появятся здесь после синхронизации.",
                            color = Color(0xFF9E9E9E), modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                    items(projects, key = { it.name }) { proj ->
                        val workspace = workspaces[ProjectWorkspaces.idFor(proj.name)]
                        Surface(
                            color = DesignColors.Surface, shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().clickable { selectedProjectName = proj.name }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Folder, contentDescription = null,
                                    tint = Color(0xFF60A5FA), modifier = Modifier.size(26.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        proj.name, color = Color.White, fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = buildString {
                                            append("Рабочая область ПК • ${proj.fileCount} объектов")
                                            if (workspace != null) {
                                                if (workspace.instructions.isNotBlank()) append(" • инструкции")
                                                if (workspace.documents.isNotEmpty()) append(" • ${workspace.documents.size} док.")
                                                if (workspace.linkedChatIds.isNotEmpty()) append(" • ${workspace.linkedChatIds.size} чатов")
                                            }
                                        },
                                        color = Color(0xFF8E8E8E), fontSize = 12.sp
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight, contentDescription = "Открыть проект",
                                    tint = Color(0xFF8E8E8E)
                                )
                            }
                        }
                    }
                }
            } else {
                ProjectWorkspaceDetail(
                    project = selectedProject,
                    workspace = selectedWorkspace,
                    activeChatId = activeChatId,
                    linkedChatTitles = linkedChatTitles,
                    onOpenProjectChat = { onOpenProjectChat(selectedProject) },
                    onSetInstructions = { text -> onSetInstructions(selectedProject.name, text) },
                    onPickDocument = { onPickDocument(selectedProject.name) },
                    onRemoveDocument = { docId -> onRemoveDocument(selectedProject.name, docId) },
                    onLinkActiveChat = { onLinkActiveChat(selectedProject.name) },
                    onUnlinkChat = { chatId -> onUnlinkChat(selectedProject.name, chatId) },
                    onOpenLinkedChat = onOpenLinkedChat
                )
            }
        }
    }
}

@Composable
private fun ProjectWorkspaceDetail(
    project: ScratchProjectItem,
    workspace: ProjectWorkspace?,
    activeChatId: String,
    linkedChatTitles: Map<String, String>,
    onOpenProjectChat: () -> Unit,
    onSetInstructions: (String) -> Unit,
    onPickDocument: () -> Unit,
    onRemoveDocument: (String) -> Unit,
    onLinkActiveChat: () -> Unit,
    onUnlinkChat: (String) -> Unit,
    onOpenLinkedChat: (String, String) -> Unit
) {
    var instructions by rememberSaveable(workspace?.id) { mutableStateOf(workspace?.instructions ?: "") }
    var instructionsDirty by remember(workspace?.id) { mutableStateOf(false) }
    var expandedDocId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRemoveDocId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                color = DesignColors.Surface, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Чат проекта", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Открывает чат рабочей области ПК с контекстом проекта.",
                        color = Color(0xFF9E9E9E), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                    Button(
                        onClick = onOpenProjectChat,
                        modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) { Text("Открыть чат проекта") }
                }
            }
        }

        item {
            Surface(
                color = DesignColors.Surface, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Инструкции проекта", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Инструкции входят в контекст всех чатов, связанных с проектом.",
                        color = Color(0xFF9E9E9E), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                    OutlinedTextField(
                        value = instructions, onValueChange = { instructions = it; instructionsDirty = true },
                        placeholder = { Text("Например: отвечай кратко, используй Kotlin…") },
                        minLines = 3, maxLines = 8,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    )
                    Button(
                        onClick = { onSetInstructions(instructions); instructionsDirty = false },
                        enabled = instructionsDirty,
                        modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
                    ) { Text(if (instructionsDirty) "Сохранить инструкции" else "Инструкции сохранены") }
                }
            }
        }

        item {
            Surface(
                color = DesignColors.Surface, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Документы контекста", color = Color.White, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(onClick = onPickDocument) {
                            Icon(Icons.Default.Add, "Добавить документ", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Добавить")
                        }
                    }
                    Text(
                        "Содержимое документов передаётся модели вместе с запросами проекта.",
                        color = Color(0xFF9E9E9E), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                    if (workspace == null || workspace.documents.isEmpty()) {
                        Text(
                            "Документов пока нет.", color = Color(0xFF9E9E9E), fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    workspace?.documents?.forEach { doc ->
                        Column(Modifier.padding(top = 10.dp)) {
                            Surface(
                                color = Color(0xFF202020), shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    expandedDocId = if (expandedDocId == doc.id) null else doc.id
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Description, "Документ проекта",
                                        tint = Color(0xFF60A5FA), modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            doc.name, color = Color.White, fontSize = 14.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "${doc.content.length} симв.", color = Color(0xFF8E8E8E), fontSize = 11.sp
                                        )
                                    }
                                    IconButton(onClick = { confirmRemoveDocId = doc.id }) {
                                        Icon(Icons.Default.Delete, "Удалить документ проекта", tint = Color(0xFFEF9A9A))
                                    }
                                }
                            }
                            if (expandedDocId == doc.id) {
                                Surface(
                                    color = Color(0xFF0D0D0D), shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                ) {
                                    Text(
                                        doc.content, color = Color(0xFFCCCCCC), fontSize = 12.sp,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Surface(
                color = DesignColors.Surface, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Связанные чаты", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Чаты проекта получают его инструкции и документы в контекст.",
                        color = Color(0xFF9E9E9E), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                    if (activeChatId.isNotBlank() && workspace?.linkedChatIds?.contains(activeChatId) != true) {
                        OutlinedButton(
                            onClick = onLinkActiveChat,
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
                        ) { Text("Связать текущий чат с проектом") }
                    }
                    if (workspace == null || workspace.linkedChatIds.isEmpty()) {
                        Text(
                            "Связанных чатов пока нет.", color = Color(0xFF9E9E9E), fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    workspace?.linkedChatIds?.forEach { chatId ->
                        val title = linkedChatTitles[chatId]?.takeIf { it.isNotBlank() } ?: chatId
                        Surface(
                            color = Color(0xFF202020), shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable {
                                onOpenLinkedChat(chatId, title)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.ChatBubbleOutline, "Чат проекта",
                                    tint = Color(0xFF60A5FA), modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    title, color = Color.White, fontSize = 14.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onUnlinkChat(chatId) }) {
                                    Icon(Icons.Default.LinkOff, "Отвязать чат от проекта", tint = Color(0xFFEF9A9A))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirmRemoveDocId?.let { docId ->
        AlertDialog(
            onDismissRequest = { confirmRemoveDocId = null },
            title = { Text("Удалить документ?") },
            text = { Text("Документ будет убран из контекста проекта. Исходный файл на телефоне не удаляется.") },
            confirmButton = {
                TextButton(onClick = { onRemoveDocument(docId); confirmRemoveDocId = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveDocId = null }) { Text("Отмена") } }
        )
    }
}
