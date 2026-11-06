package com.google.antigravity.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.model.*
import com.google.antigravity.ui.design.DesignColors

/** Claude Remote control reference: a quiet task-count link above the composer. */
@Composable
fun CodeBackgroundTasksEntryPoint(
    agents: List<SwarmAgentNode>,
    onOpenFullSheet: () -> Unit,
    modifier: Modifier = Modifier,
    showWhenIdle: Boolean = false,
    isOwnTaskActive: Boolean = false
) {
    val active = agents.count { it.isInProgress }
    if (!showWhenIdle && active == 0 && !isOwnTaskActive) return
    Row(modifier.fillMaxWidth().clickable(onClick = onOpenFullSheet).heightIn(min = 48.dp)
        .padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (active > 0 || isOwnTaskActive) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = DesignColors.ClaudeCoral)
            Spacer(Modifier.width(10.dp))
        }
        Text(when {
            active > 0 -> SubagentPlurals.runningLabel(active)
            isOwnTaskActive -> "Запуск задачи…"
            else -> "Фоновые задачи"
        }, color = DesignColors.TextSecondary, fontSize = 14.sp,
            modifier = Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = DesignColors.TextSecondary, modifier = Modifier.size(18.dp))
    }
}

/** Task inspection only. Delegation starts in the conversation, without a second composer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiAgentSwarmSheet(
    agents: List<SwarmAgentNode>,
    panelState: SubagentPanelState? = null,
    onDismiss: () -> Unit,
    onCancelTask: () -> Unit = {},
    isOwnTaskActive: Boolean = false
) {
    val state = conversationTaskPanel(agents, panelState)
    val running = agents.any { it.isInProgress } || isOwnTaskActive
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DesignColors.Surface, contentColor = DesignColors.TextPrimary) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            SheetHeader("Фоновые задачи", onDismiss)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(if (running && agents.isEmpty()) "Запуск задачи…" else if (state.effectiveRunningTasks > 0)
                        SubagentPlurals.runningLabel(state.effectiveRunningTasks) else "Нет выполняющихся задач",
                        color = DesignColors.TextSecondary, fontSize = 14.sp)
                }
                if (agents.isEmpty() && !running) item {
                    Text("Поставьте задачу в чате. Здесь появятся подзадачи, которые ведущая модель передаст отдельным исполнителям.",
                        color = DesignColors.TextSecondary, fontSize = 16.sp, lineHeight = 24.sp)
                }
                if (!isOwnTaskActive) state.effectiveBusyMessage?.let { text ->
                    item { Text(text, color = DesignColors.TextSecondary, fontSize = 14.sp) }
                }
                items(agents, key = { it.id }) { agent ->
                    var expanded by rememberSaveable(agent.id) { mutableStateOf(false) }
                    var changesOpen by rememberSaveable(agent.id) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 64.dp)
                            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (agent.status == "failed") Icons.Default.ErrorOutline else Icons.Default.AccountTree,
                                null, tint = DesignColors.TextSecondary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(agent.name, fontSize = 16.sp, color = DesignColors.TextPrimary,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(taskStatus(agent), agent.model.takeIf { it.isNotBlank() }).joinToString(" · "),
                                    color = DesignColors.TextSecondary, fontSize = 13.sp)
                            }
                            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                if (expanded) "Скрыть результат" else "Показать результат",
                                tint = DesignColors.TextSecondary, modifier = Modifier.size(24.dp))
                        }
                        if (expanded) {
                            if (agent.currentTask.isNotBlank() && agent.currentTask != agent.name)
                                Text(agent.currentTask, color = DesignColors.TextSecondary, fontSize = 15.sp)
                            state.hintFor(agent)?.let { Text(it, color = DesignColors.TextSecondary, fontSize = 13.sp) }
                            if (agent.stepLogs.isNotEmpty()) ChatGptMarkdownText(agent.stepLogs.joinToString("\n\n"))
                            else Text(if (agent.isInProgress) "Результат появится после завершения." else "Результат не записан.",
                                color = DesignColors.TextSecondary, fontSize = 14.sp)
                            if (agent.changeReview.isNotBlank()) {
                                TextButton(onClick = { changesOpen = true }) { Text("Просмотреть изменения") }
                                if (changesOpen) CodeArtifactViewer(code = agent.changeReview,
                                    language = "Изменения · ${agent.name}", fileName = "changes.txt",
                                    onDismiss = { changesOpen = false }, onSave = null)
                            }
                        }
                    }
                }
            }
            if (running) OutlinedButton(onClick = onCancelTask,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 48.dp)) {
                Text("Остановить работу", color = DesignColors.TextPrimary)
            }
        }
    }
}

private fun taskStatus(agent: SwarmAgentNode): String = when {
    agent.isSettling -> "Завершение работы и сбор изменений"
    else -> when (agent.status) {
        "queued" -> "В очереди"
        "running", "active", "working" -> "Выполняется"
        "completed" -> "Завершено"
        "failed" -> "Ошибка"
        "cancelled" -> "Остановлено"
        "awaiting_approval" -> "Ожидает разрешения"
        "approved" -> "Разрешено"
        "denied" -> "Отклонено"
        else -> agent.status
    }
}
