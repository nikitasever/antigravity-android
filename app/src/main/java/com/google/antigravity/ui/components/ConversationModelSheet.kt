package com.google.antigravity.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.model.*
import com.google.antigravity.ui.design.DesignColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationModelSheet(
    settings: AntigravitySettings,
    availableModels: List<AntigravityModelOption>,
    openRouterTotalCount: Int,
    onSelectTarget: (AgentExecutionTarget) -> Unit,
    onSelectModel: (String) -> Unit,
    onOpenOpenRouterCatalog: () -> Unit,
    onDismiss: () -> Unit
) {
    val models = modelPickerOptions(availableModels, settings.selectedModelId, settings.selectedModelDisplay)
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DesignColors.Bg, contentColor = DesignColors.TextPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            SheetHeader("Выбрать модель", onDismiss)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)) {
                ModelSectionLabel("Где выполнять задачи")
                Column(Modifier.selectableGroup()) {
                    listOf(
                        AgentExecutionTarget.PC_REMOTE_SESSION to "Сессия ПК",
                        AgentExecutionTarget.PHONE_LOCAL_RESOURCES to "Телефон",
                        AgentExecutionTarget.GOOGLE_WEB_CONSOLE to "Веб-консоль"
                    ).forEach { (target, label) ->
                        ModelChoice(label, "", settings.executionTarget == target) { onSelectTarget(target) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = DesignColors.Border)
                ModelSectionLabel("Модель")
                Column(Modifier.selectableGroup()) {
                    models.forEach { model ->
                        ModelChoice(model.displayName, model.badge, settings.selectedModelId == model.id) {
                            onSelectModel(model.id)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = DesignColors.Border)
                Surface(onClick = onOpenOpenRouterCatalog, color = DesignColors.Surface,
                    shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("OpenRouter", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(if (openRouterTotalCount > 0) "Каталог · $openRouterTotalCount моделей" else "Каталог моделей",
                                color = DesignColors.TextSecondary, fontSize = 13.sp)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSectionLabel(text: String) {
    Text(text, color = DesignColors.TextSecondary, fontSize = 13.sp,
        modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp))
}

@Composable
private fun ModelChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Surface(color = if (selected) DesignColors.Surface else androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                if (detail.isNotBlank()) Text(detail, color = DesignColors.TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(24.dp)) {
                if (selected) Icon(Icons.Default.Check, null, tint = DesignColors.TextPrimary)
            }
        }
    }
}
