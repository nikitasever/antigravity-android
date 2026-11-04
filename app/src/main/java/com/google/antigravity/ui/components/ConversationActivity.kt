package com.google.antigravity.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.model.*
import com.google.antigravity.ui.design.DesignColors

/** Claude recording f_0007: compact activity row → summary sheet.
 * ChatGPT recording f_0033: tool details are read separately from the answer.
 * Every item here comes from the provider/executor; no fabricated timeline steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationActivity(
    message: ChatMessage,
    responseFont: ResponseFont,
    readingTextSize: ChatTextSize,
    onToggleThinking: () -> Unit,
    onDecideToolCall: (String, ApprovalDecision) -> Unit,
    onSaveCode: (String, String) -> Unit
) {
    var toolsOpen by remember(message.id) { mutableStateOf(false) }
    var expandedTool by remember(message.id) { mutableStateOf<String?>(null) }
    val thinking = message.thinking.orEmpty().trim()
    val tools = message.toolCalls
    tools.map { it.leadingText }.filter { it.isNotBlank() }.distinct().forEach { narration ->
        ChatGptMarkdownText(narration, textSize = readingTextSize, responseFont = responseFont,
            onSaveCodeToPhone = onSaveCode)
    }
    if (thinking.isNotEmpty()) {
        ActivityRow("Ход рассуждений", null, onToggleThinking, null, reasoning = true)
    }
    if (tools.isNotEmpty()) {
        val active = tools.lastOrNull { it.status == ToolCallStatus.RUNNING || it.status == ToolCallStatus.AWAITING_APPROVAL }
        val failed = tools.count { it.status == ToolCallStatus.FAILED || it.status == ToolCallStatus.DENIED }
        val cancelled = tools.count { it.status == ToolCallStatus.CANCELLED }
        val label = when {
            active != null -> active.summary.ifBlank { active.toolTitleRu }
            failed > 0 -> "Действия: ${tools.size} · с ошибкой: $failed"
            cancelled > 0 -> "Действия: ${tools.size} · остановлено: $cancelled"
            tools.size == 1 -> tools.first().toolTitleRu
            else -> "Выполнено действий: ${tools.size}"
        }
        ActivityRow(label, if (failed > 0) Icons.Default.ErrorOutline else null,
            { toolsOpen = true }, null)
        // Approval must remain visible even while the summary is collapsed.
        tools.filter { it.requiresApproval && it.approvalDecision == ApprovalDecision.PENDING }.forEach { tool ->
            Text(tool.summary, color = DesignColors.TextPrimary, fontSize = 15.sp)
            ToolApprovalActions(tool, onDecideToolCall)
        }
    }
    if (thinking.isEmpty() && tools.isEmpty() && message.isStreaming && message.content.isBlank()) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), color = DesignColors.TextSecondary, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(10.dp))
            Text("Ожидание ответа…", color = DesignColors.TextSecondary, fontSize = 15.sp)
        }
    }
    if (message.isThinkingExpanded && thinking.isNotEmpty() || toolsOpen) {
        ModalBottomSheet(onDismissRequest = {
            toolsOpen = false
            if (message.isThinkingExpanded) onToggleThinking()
        }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = DesignColors.Surface, contentColor = DesignColors.TextPrimary) {
            SheetHeader("Сводка", {
                toolsOpen = false
                if (message.isThinkingExpanded) onToggleThinking()
            })
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (thinking.isNotEmpty()) item(key = "reasoning") {
                    Column {
                        Text("Рассуждения модели", color = DesignColors.TextSecondary, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(thinking, fontFamily = if (responseFont == ResponseFont.SERIF) FontFamily.Serif else FontFamily.Default,
                                fontSize = (18 * readingTextSize.scale).sp,
                                lineHeight = (27 * readingTextSize.scale).sp, color = DesignColors.TextPrimary)
                        }
                    }
                }
                items(tools, key = { it.id }) { tool ->
                    Column {
                        Row(Modifier.fillMaxWidth().clickable {
                            expandedTool = if (expandedTool == tool.id) null else tool.id
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (tool.status == ToolCallStatus.FAILED || tool.status == ToolCallStatus.DENIED)
                                Icons.Default.ErrorOutline else Icons.Default.Terminal, null,
                                tint = DesignColors.TextSecondary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tool.summary.ifBlank { tool.toolTitleRu }, fontSize = 16.sp)
                                Text(tool.statusLabel(), color = DesignColors.TextSecondary, fontSize = 13.sp)
                            }
                            Icon(if (expandedTool == tool.id) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                "Подробности действия", tint = DesignColors.TextSecondary)
                        }
                        if (tool.requiresApproval && tool.approvalDecision == ApprovalDecision.PENDING)
                            ToolApprovalActions(tool, onDecideToolCall)
                        if (expandedTool == tool.id) {
                            if (tool.leadingText.isNotBlank()) Text(tool.leadingText, fontSize = 16.sp)
                            if (tool.details.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(tool.details, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                    lineHeight = 20.sp, modifier = Modifier.padding(vertical = 12.dp))
                            }
                            if (!tool.unifiedDiff.isNullOrBlank()) ChatGptMarkdownText(
                                markdown = "```diff\n${tool.unifiedDiff}\n```", onSaveCodeToPhone = onSaveCode)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit, trailing: String?, reasoning: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (reasoning) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) { Box(Modifier.size(4.dp).background(DesignColors.ClaudeCoral, CircleShape)) }
            }
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = DesignColors.TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = DesignColors.TextSecondary, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (trailing != null) Text(trailing, color = DesignColors.TextSecondary, fontSize = 13.sp)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Открыть подробности",
            tint = DesignColors.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ToolApprovalActions(tool: ToolCallEvent, decide: (String, ApprovalDecision) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { decide(tool.id, ApprovalDecision.APPROVED) },
            colors = ButtonDefaults.buttonColors(containerColor = DesignColors.AccentBlue)) { Text("Разрешить") }
        OutlinedButton(onClick = { decide(tool.id, ApprovalDecision.DENIED) }) { Text("Отклонить") }
    }
}

private fun ToolCallEvent.statusLabel(): String = when (status) {
    ToolCallStatus.RUNNING -> "Выполняется"
    ToolCallStatus.COMPLETED -> "Завершено"
    ToolCallStatus.FAILED -> "Ошибка"
    ToolCallStatus.CANCELLED -> "Остановлено"
    ToolCallStatus.DENIED -> "Отклонено"
    ToolCallStatus.AWAITING_APPROVAL -> "Ожидает разрешения"
    ToolCallStatus.APPROVED -> "Разрешено"
}
