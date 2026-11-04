package com.google.antigravity.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.antigravity.model.ComponentAdapterRecord
import com.google.antigravity.model.DenyCommentDialogState
import com.google.antigravity.model.ExtensionCatalogItem
import com.google.antigravity.model.InstalledPluginItem
import com.google.antigravity.model.McpPermissionMode
import com.google.antigravity.model.McpPermissionModeState
import com.google.antigravity.model.McpToolPermissionScope
import com.google.antigravity.model.McpToolPermissionsState
import com.google.antigravity.model.PcMcpServerItem
import com.google.antigravity.model.PcSkillItem
import com.google.antigravity.model.SkillDetailState
import com.google.antigravity.model.SkillEditState
import com.google.antigravity.model.describeMcpAuthStatus
import com.google.antigravity.model.describeMcpPermissionSource
import com.google.antigravity.model.describeMcpStatus
import com.google.antigravity.model.describeToolPermission
import com.google.antigravity.ui.TwoLineMenuCircleButton
import com.google.antigravity.ui.design.DesignColors
import com.google.antigravity.ui.design.DesignType

// ---------------------------------------------------------------------------
// Этап 6 (EXTENSIONS_INTEGRATION_PLAN.md): единый интерфейс расширений в
// эталонном угольно-чёрном дизайне Claude Web (Connectors / Skills / Plugins).
// 1) Тема: #0E0E10 фон, #18181B карточки/шиты, #27272A рамки 1.dp.
// 2) Вкладки без рамок у неактивных табов, Segmented-капсула Мои / Каталог,
//    контрастная белая кнопка "+ Установить" (#FFFFFF фон, #09090B текст).
// 3) Компактный табличный вид (Connector | Type | Status) с иконками 32.dp
//    для вкладки «Подключения»; честные индикаторы: "✓" только для активных,
//    "⚠" для блокеров/ошибок.
// 4) Hero-баннер (#0D254C для Плагинов, #13331C для Навыков) и карточки-витрины
//    с иконками 42.dp и описанием maxLines = 2.
// 5) Все длинные технические простыни (блокеры, лицензия, состав адаптеров,
//    команды MCP и полные инструкции) вынесены в ModalBottomSheet по нажатию.
// ---------------------------------------------------------------------------

// Фоновые токены «как у них» — значения DesignColors (доказательства
// референсов — см. DesignSystem.kt): экран → Bg (#0D0D0D), листы/диалоги →
// Surface (#212121), карточки секций и повышенные элементы → SurfaceHigh
// (#303030), рамки → Border (#414141). Имена сохранены как алиасы токенов,
// чтобы не разъезжались места использования.
private val BgCharcoal = DesignColors.Bg
private val CardBg = DesignColors.Surface
private val BorderSubtle = DesignColors.Border
private val ActivePillBg = DesignColors.SurfaceHigh
private val SearchFieldBg = DesignColors.SurfaceHigh
private val IconBoxBg = DesignColors.SurfaceHigh

private val WhiteBtnBg = Color(0xFFFFFFFF)
private val WhiteBtnText = Color(0xFF09090B)

private val HeroSkillsBg = Color(0xFF13331C)
private val HeroSkillsBorder = Color(0xFF1F4D2B)
private val HeroPluginsBg = Color(0xFF0D254C)
private val HeroPluginsBorder = Color(0xFF1B3F7A)

// Текст и акценты — напрямую токенами DesignColors (DesignColors.TextPrimary #F3F3F3,
// DesignColors.TextSecondary #C4C3CB, TextSubtle #89888D; DesignColors.AccentGreen #53B559,
// DesignColors.AccentBlue #3566F0, AccentAmber #FFCC38, AccentCoral #FF8066 — их error-red
// не декодирован, честно используем их коралл).

/** Приглушённый фон кнопки «Удалить» (тонированный AccentCoral). */
private val DangerBtnBg = Color(0x26FF8066)

private enum class ExtensionsTab(val title: String) {
    CONNECTIONS("Подключения"),
    SKILLS("Навыки"),
    PLUGINS("Плагины")
}

private enum class ExtensionsScope(val title: String) {
    MINE("Мои"),
    CATALOG("Каталог")
}

private sealed interface ExtensionDetailTarget {
    data class Mcp(val serverId: String) : ExtensionDetailTarget
    data class Skill(val skillId: String) : ExtensionDetailTarget
    /** Редактор навыка («как у них»: SkillEdit) — имя и полный SKILL.md. */
    data class SkillEdit(val skillId: String) : ExtensionDetailTarget
    data class Plugin(val pluginId: String) : ExtensionDetailTarget
    data class Catalog(val itemId: String) : ExtensionDetailTarget
}

/** Цель выбора права инструмента: одна строка или «все инструменты» сервера. */
private sealed interface PermissionPickerTarget {
    data class Tool(val tool: String) : PermissionPickerTarget
    object AllTools : PermissionPickerTarget
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    plugins: List<InstalledPluginItem>,
    skills: List<PcSkillItem>,
    mcpServers: List<PcMcpServerItem>,
    catalog: List<ExtensionCatalogItem>,
    errorText: String?,
    isLoading: Boolean,
    onOpenDrawer: () -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    onTogglePlugin: (String) -> Unit,
    onUpdatePlugin: (String) -> Unit,
    onAddPlugin: (String, String) -> Unit,
    onDeletePlugin: (String) -> Unit,
    onToggleSkill: (String) -> Unit,
    onAddSkill: (String, String, String) -> Unit,
    onDeleteSkill: (String) -> Unit,
    onInsertSkillIntoChat: (String) -> Unit,
    onToggleMcp: (String) -> Unit,
    onAddMcp: (String, String, String) -> Unit,
    onDeleteMcp: (String) -> Unit,
    onCheckMcp: (String) -> Unit,
    onLoginMcp: (String) -> Unit,
    onCallMcpTool: (String, String) -> Unit,
    mcpToolResult: String?,
    isMcpToolCallRunning: Boolean,
    onDismissToolResult: () -> Unit,
    onInstallCatalogItem: (ExtensionCatalogItem) -> Unit,
    mcpToolPermissions: Map<String, McpToolPermissionsState>,
    onLoadMcpToolPermissions: (String) -> Unit,
    /** tool, permission, scope, comment («Deny with comment»; "" — без комментария). */
    onSetMcpToolPermission: (String, String, String, McpToolPermissionScope, String) -> Unit,
    /** permission, scope, comment («Deny with comment»; "" — без комментария). */
    onSetAllMcpToolPermissions: (String, String, McpToolPermissionScope, String) -> Unit,
    mcpScopeSessionId: String,
    mcpScopeTaskId: String,
    permissionModeState: McpPermissionModeState,
    onLoadPermissionMode: () -> Unit,
    onSetPermissionMode: (McpPermissionMode) -> Unit,
    skillDetailStates: Map<String, SkillDetailState>,
    onLoadSkillDetail: (String) -> Unit,
    skillEditState: SkillEditState,
    onStartSkillEdit: (String) -> Unit,
    onSkillEditNameChange: (String) -> Unit,
    onSkillEditContentChange: (String) -> Unit,
    onSaveSkillEdit: (String, String, String) -> Unit
) {
    var activeTab by remember { mutableIntStateOf(0) } // 0=Подключения, 1=Навыки, 2=Плагины
    var scopeMine by remember { mutableStateOf(true) } // true=Мои, false=Каталог
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var mcpToolCallServer by remember { mutableStateOf<PcMcpServerItem?>(null) }
    var detailTarget by remember { mutableStateOf<ExtensionDetailTarget?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgCharcoal)
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Шапка: меню, заголовок, обновление и контрастная белая кнопка "+ Установить".
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TwoLineMenuCircleButton(onClick = onOpenDrawer)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Расширения",
                    color = DesignColors.TextPrimary,
                    fontSize = DesignType.TITLE_SP.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isLoading) "Синхронизация с рабочей станцией ПК…" else "Подключения, навыки и плагины",
                    color = if (isLoading) DesignColors.AccentGreen else DesignColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = WhiteBtnBg,
                    contentColor = WhiteBtnText
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = WhiteBtnText,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Установить",
                    color = WhiteBtnText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Плашка ошибки (если есть).
        if (!errorText.isNullOrBlank()) {
            Surface(
                color = Color(0xFF2D1618),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF5C2327), RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠ $errorText",
                        color = Color(0xFFFCA5A5),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissError, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть",
                            tint = Color(0xFFFCA5A5),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // 2. Двухрядная компактная панель управления (помещается в мобильную
        //    ширину без горизонтального скролла и обрезки):
        //    верхний ряд — вкладки слева + компактная иконка обновления справа;
        //    нижний ряд — цельный Segmented «Мои | Каталог» слева + поиск справа.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ExtensionsTab.entries.forEachIndexed { index, tab ->
                    val isSelected = activeTab == index
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) ActivePillBg else Color.Transparent)
                            .clickable { activeTab = index }
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.title,
                            color = if (isSelected) DesignColors.TextPrimary else DesignColors.TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Обновить",
                    tint = DesignColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Цельный Segmented-переключатель Мои / Каталог: обе надписи
            // читаются полностью, без обрезки за краем экрана.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SegmentedScopeItem(
                    title = ExtensionsScope.MINE.title,
                    selected = scopeMine,
                    onClick = { scopeMine = true }
                )
                SegmentedScopeItem(
                    title = ExtensionsScope.CATALOG.title,
                    selected = !scopeMine,
                    onClick = { scopeMine = false }
                )
            }

            // Поиск на оставшуюся ширину.
            Box(modifier = Modifier.weight(1f)) {
                ClaudeSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = when (activeTab) {
                        0 -> "Поиск подключений…"
                        1 -> "Поиск навыков…"
                        else -> "Поиск плагинов…"
                    }
                )
            }
        }

        val query = searchQuery.trim().lowercase()
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (scopeMine) {
                when (activeTab) {
                    0 -> {
                        val filtered = mcpServers.filter {
                            matchesQuery(query, it.name, it.commandOrUrl, describeMcpStatus(it.status), it.transport)
                        }
                        if (filtered.isEmpty()) {
                            item {
                                EmptyExtensionsCard(
                                    title = "Подключения не добавлены",
                                    subtitle = "Нажмите «+ Установить», чтобы зарегистрировать MCP-сервер (agy mcp add). Инструменты появятся после живого рукопожатия «Проверить»."
                                )
                            }
                        } else {
                            // 3. Табличный компактный вид (Connector | Type | Status).
                            item(key = "mcp_table_header") {
                                McpTableHeaderRow()
                            }
                            items(filtered, key = { "mcp_${it.id}" }) { server ->
                                McpConnectionTableRow(
                                    server = server,
                                    onRowClick = { detailTarget = ExtensionDetailTarget.Mcp(server.id) },
                                    onCheck = { onCheckMcp(server.id) },
                                    onLogin = { onLoginMcp(server.id) },
                                    onCallTool = { mcpToolCallServer = server }
                                )
                            }
                        }
                    }

                    1 -> {
                        // Hero-баннер ТОЛЬКО в режиме «Каталог» (Discover);
                        // в режиме «Мои» сразу список установленных навыков.
                        val filtered = skills.filter { matchesQuery(query, it.name, it.description, it.path) }
                        if (filtered.isEmpty()) {
                            item {
                                EmptyExtensionsCard(
                                    title = "Навыки не найдены",
                                    subtitle = "Навыки — SKILL.md в каталогах рабочей станции. Нажмите «+ Установить» для создания навыка или вызовите его в чате через /имя."
                                )
                            }
                        } else {
                            item(key = "skills_section_header") {
                                SectionHeaderRow(title = "Установленные навыки (${filtered.size})")
                            }
                            items(filtered, key = { "skill_${it.id}" }) { skill ->
                                SkillCard(
                                    skill = skill,
                                    onCardClick = { detailTarget = ExtensionDetailTarget.Skill(skill.id) },
                                    onInsertIntoChat = { onInsertSkillIntoChat(skill.id.removePrefix("/")) },
                                    onToggle = { onToggleSkill(skill.id) }
                                )
                            }
                        }
                    }

                    2 -> {
                        // Hero-баннер ТОЛЬКО в режиме «Каталог» (Discover);
                        // в режиме «Мои» сразу список установленных плагинов.
                        val filtered = plugins.filter {
                            matchesQuery(query, it.name, it.description, it.source, it.category)
                        }
                        if (filtered.isEmpty()) {
                            item {
                                EmptyExtensionsCard(
                                    title = "Плагины не установлены",
                                    subtitle = "«+ Установить» принимает URL или локальный путь (реальный импорт); состав и право распространения проверяются для каждого пакета."
                                )
                            }
                        } else {
                            item(key = "plugins_section_header") {
                                SectionHeaderRow(title = "Установленные плагины (${filtered.size})")
                            }
                            items(filtered, key = { "plugin_${it.id}" }) { plugin ->
                                PluginCard(
                                    plugin = plugin,
                                    onCardClick = { detailTarget = ExtensionDetailTarget.Plugin(plugin.id) },
                                    onToggle = { onTogglePlugin(plugin.id) },
                                    onUpdate = { onUpdatePlugin(plugin.id) }
                                )
                            }
                        }
                    }
                }
            } else {
                // 4. Каталог (Discover). Вкладка «Подключения» — тот же
                //    компактный табличный вид, что и в «Мои» (без Hero-баннера
                //    и без карточек с «Детали»: детали — по тапу на строку).
                //    «Навыки»/«Плагины» — Hero-баннер + карточки-витрины.
                if (query.isEmpty() && activeTab != 0) {
                    item(key = "hero_catalog_$activeTab") {
                        ClaudeHeroBanner(
                            activeTab = activeTab,
                            onActionClick = { showAddDialog = true }
                        )
                    }
                }

                val tabCategories = when (activeTab) {
                    0 -> setOf("mcp-server", "connector", "app")
                    1 -> setOf("skill", "skill-assets", "command", "agent", "output-style")
                    else -> null // «Плагины» — все прочие категории
                }
                val filtered = catalog.filter { item ->
                    val catOk = tabCategories == null || tabCategories.contains(item.category) ||
                        (activeTab == 2 && !setOf(
                            "mcp-server",
                            "connector",
                            "app",
                            "skill",
                            "skill-assets",
                            "command",
                            "agent",
                            "output-style"
                        ).contains(item.category))
                    catOk && matchesQuery(query, item.name, item.sourceProduct, item.category, item.compatibility)
                }

                if (filtered.isEmpty()) {
                    item {
                        EmptyExtensionsCard(
                            title = "В каталоге ничего не найдено",
                            subtitle = "Каталог строится по инвентарю источников (Claude / ChatGPT / Codex / Antigravity). Уточните поиск или обновите данные."
                        )
                    }
                } else if (activeTab == 0) {
                    // Пункт 5: «Подключения» в каталоге — компактная таблица
                    // строк (Иконка + Название | Тип | Статус), детали — по тапу.
                    item(key = "catalog_mcp_table_header") {
                        McpTableHeaderRow()
                    }
                    items(filtered, key = { "cat_${it.id}" }) { item ->
                        McpCatalogTableRow(
                            item = item,
                            onRowClick = { detailTarget = ExtensionDetailTarget.Catalog(item.id) }
                        )
                    }
                } else {
                    val sectionTitle = when (activeTab) {
                        1 -> "Рекомендуемые навыки (${filtered.size})"
                        else -> "Каталог плагинов (${filtered.size})"
                    }
                    item(key = "catalog_section_header_$activeTab") {
                        SectionHeaderRow(title = sectionTitle)
                    }
                    items(filtered, key = { "cat_${it.id}" }) { item ->
                        CatalogCard(
                            item = item,
                            onCardClick = { detailTarget = ExtensionDetailTarget.Catalog(item.id) },
                            onInstall = { onInstallCatalogItem(item) }
                        )
                    }
                }
            }
        }
    }

    // 5. ModalBottomSheet для выноса всех длинных технических простыней.
    detailTarget?.let { target ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { detailTarget = null },
            sheetState = sheetState,
            containerColor = CardBg,
            contentColor = DesignColors.TextPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            when (target) {
                is ExtensionDetailTarget.Mcp -> {
                    val server = mcpServers.find { it.id == target.serverId }
                    if (server != null) {
                        McpDetailSheetContent(
                            server = server,
                            permissions = mcpToolPermissions[server.id],
                            onLoadPermissions = { onLoadMcpToolPermissions(server.id) },
                            onSetToolPermission = { tool, permission, scope, comment ->
                                onSetMcpToolPermission(server.id, tool, permission, scope, comment)
                            },
                            onSetAllToolPermissions = { permission, scope, comment ->
                                onSetAllMcpToolPermissions(server.id, permission, scope, comment)
                            },
                            scopeSessionId = mcpScopeSessionId,
                            scopeTaskId = mcpScopeTaskId,
                            permissionModeState = permissionModeState,
                            onLoadPermissionMode = onLoadPermissionMode,
                            onSetPermissionMode = onSetPermissionMode,
                            onCheck = { onCheckMcp(server.id) },
                            onLogin = { onLoginMcp(server.id) },
                            onOpenToolsDialog = {
                                detailTarget = null
                                mcpToolCallServer = server
                            },
                            onToggle = { onToggleMcp(server.id) },
                            onDelete = {
                                onDeleteMcp(server.id)
                                detailTarget = null
                            },
                            onClose = { detailTarget = null }
                        )
                    } else {
                        detailTarget = null
                    }
                }

                is ExtensionDetailTarget.Skill -> {
                    val skill = skills.find { it.id == target.skillId }
                    if (skill != null) {
                        SkillDetailSheetContent(
                            skill = skill,
                            detailState = skillDetailStates[skill.id],
                            onLoadDetail = { onLoadSkillDetail(skill.id) },
                            onInsertIntoChat = {
                                detailTarget = null
                                onInsertSkillIntoChat(skill.id.removePrefix("/"))
                            },
                            onToggle = { onToggleSkill(skill.id) },
                            onEdit = {
                                detailTarget = ExtensionDetailTarget.SkillEdit(skill.id)
                            },
                            onDelete = {
                                onDeleteSkill(skill.id)
                                detailTarget = null
                            },
                            onClose = { detailTarget = null }
                        )
                    } else {
                        detailTarget = null
                    }
                }

                is ExtensionDetailTarget.SkillEdit -> {
                    SkillEditSheetContent(
                        skillId = target.skillId,
                        state = skillEditState,
                        onStartLoad = { onStartSkillEdit(target.skillId) },
                        onNameChange = onSkillEditNameChange,
                        onContentChange = onSkillEditContentChange,
                        onSave = { newName, content ->
                            onSaveSkillEdit(target.skillId, newName, content)
                        },
                        onClose = { detailTarget = null }
                    )
                }

                is ExtensionDetailTarget.Plugin -> {
                    val plugin = plugins.find { it.id == target.pluginId }
                    if (plugin != null) {
                        PluginDetailSheetContent(
                            plugin = plugin,
                            onToggle = { onTogglePlugin(plugin.id) },
                            onUpdate = { onUpdatePlugin(plugin.id) },
                            onDelete = {
                                onDeletePlugin(plugin.id)
                                detailTarget = null
                            },
                            onClose = { detailTarget = null }
                        )
                    } else {
                        detailTarget = null
                    }
                }

                is ExtensionDetailTarget.Catalog -> {
                    val item = catalog.find { it.id == target.itemId }
                    if (item != null) {
                        CatalogDetailSheetContent(
                            item = item,
                            onInstall = {
                                onInstallCatalogItem(item)
                                detailTarget = null
                            },
                            onClose = { detailTarget = null }
                        )
                    } else {
                        detailTarget = null
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddExtensionDialog(
            initialTab = activeTab,
            onDismiss = { showAddDialog = false },
            onAddPlugin = { src, desc -> onAddPlugin(src, desc); showAddDialog = false },
            onAddSkill = { name, desc, instr -> onAddSkill(name, desc, instr); showAddDialog = false },
            onAddMcp = { name, cmd, args -> onAddMcp(name, cmd, args); showAddDialog = false }
        )
    }

    mcpToolCallServer?.let { server ->
        val liveServer = mcpServers.find { it.id == server.id } ?: server
        Dialog(onDismissRequest = { mcpToolCallServer = null; onDismissToolResult() }) {
            Surface(
                color = CardBg,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .heightIn(max = 520.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Инструменты ${liveServer.name}",
                            color = DesignColors.TextPrimary,
                            fontSize = DesignType.TITLE_SP.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { mcpToolCallServer = null; onDismissToolResult() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = DesignColors.TextSecondary
                            )
                        }
                    }
                    if (liveServer.tools.isEmpty()) {
                        Text(
                            text = "⚠ Список инструментов не получен: выполните «Проверить» (живое MCP-рукопожатие). Инструменты берутся только из живого tools/list.",
                            color = DesignColors.AccentAmber,
                            fontSize = 12.sp
                        )
                    } else {
                        Text(
                            text = "✓ Инструменты из живого tools/list (${liveServer.tools.size}):",
                            color = DesignColors.AccentGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        liveServer.tools.forEach { tool ->
                            Button(
                                onClick = { onCallMcpTool(liveServer.id, tool) },
                                enabled = !isMcpToolCallRunning,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ActivePillBg,
                                    contentColor = DesignColors.TextPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = DesignColors.AccentGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = tool,
                                    color = DesignColors.TextPrimary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    mcpToolResult?.let { result ->
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(thickness = 1.dp, color = BorderSubtle)
                        Text(
                            text = "Результат вызова:",
                            color = DesignColors.AccentBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = result,
                            color = Color(0xFFE4E4E7),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

private fun matchesQuery(query: String, vararg fields: String): Boolean {
    if (query.isEmpty()) return true
    return fields.any { it.lowercase().contains(query) }
}

// QA-дефект Medium #2: залипший статус "connected" при ошибке связи с мостом
// или err != null (error/errorCode заполнены) сбрасывается в "configured"
// (требует проверки) — без свежего рукопожатия «подключён» не показывается.
// internal — тестируется ExtensionsSecurityQaTest.
internal fun effectiveMcpStatus(status: String, error: String, errorCode: String?): String {
    val s = status.trim()
    if (s.equals("connected", ignoreCase = true) && (error.isNotBlank() || !errorCode.isNullOrBlank())) {
        return "configured"
    }
    return s
}

// ---------------------------------------------------------------------------
// Человекочитаемый контент карточек (пункт 2): на витрине — нормальное
// описание назначения инструмента по имени/категории. Сырой текст блокера,
// лицензии и требований Этапов 3–5 остаётся ТОЛЬКО в нижней шторке
// («Детали» / тап по карточке).
// ---------------------------------------------------------------------------

private fun humanDescriptionFor(item: ExtensionCatalogItem): String {
    val n = item.name.lowercase()
    val id = item.id.lowercase()
    fun has(vararg keys: String) = keys.any { n.contains(it) || id.contains(it) }
    return when {
        has("figma") -> "Дизайн-макеты и прототипы Figma в ответах ассистента."
        has("desktop-commander", "desktop commander") -> "Управление файлами и командами на рабочем ПК."
        has("searchfit", "seo") -> "SEO-анализ и оптимизация сайтов."
        has("bright-data", "bright data") -> "Веб-данные и парсинг страниц."
        has("zoom") -> "Встречи и видеосвязь Zoom."
        has("product-tracking", "product tracking") -> "Отслеживание товаров и цен."
        has("doc-coauthoring", "coauthor") -> "Совместная работа над документами."
        has("web-artifacts", "artifacts") -> "Сборка веб-артефактов и страниц."
        has("learn") -> "Обучающие материалы и курсы."
        has("productivity") -> "Инструменты продуктивности и планирования."
        has("marketing") -> "Маркетинговые инструменты."
        has("finance", "финанс") -> "Финансовые инструменты и учёт."
        has("github") -> "Репозитории, ветки и pull request GitHub."
        has("gmail") -> "Почта Gmail: чтение и отправка писем."
        has("calendar", "календар") -> "События календаря и расписание."
        has("outlook") -> "Почта и календарь Outlook."
        has("google-drive", "drive", "dropbox", "sharepoint", "file-sources", "file sources") ->
            "Файлы облака: поиск и открытие документов."
        has("1password") -> "Менеджер паролей 1Password."
        has("web-search", "web search") -> "Поиск в интернете из чата."
        has("memory") -> "Память ассистента о контексте разговоров."
        has("codex") -> "Инструменты Codex для разработки."
        has("skills", "навык", "skill") -> "Навыки: инструкции и рабочие сценарии."
        has("gpt") -> "Пользовательские ассистенты GPT."
        has("custom-connector", "custom connector", "add-connector", "коннектор") ->
            "Подключение внешних сервисов к ассистенту."
        has("mcp") -> "MCP-серверы: внешние инструменты для ассистента."
        has("plugins", "плагин", "plugin") -> "Плагины: пакеты инструментов и правил."
        has("workflow", "automation", "автомат") -> "Автоматизация рабочих процессов."
        has("permission", "разрешен") -> "Настройки разрешений плагинов."
        has("catalog", "каталог") -> "Раздел каталога расширений источника."
        else -> when (item.category) {
            "mcp-server" -> "MCP-сервер: внешние инструменты для ассистента."
            "connector" -> "Подключение внешнего сервиса."
            "skill", "skill-assets" -> "Навык: инструкции и сценарии работы."
            "plugin" -> "Пакет расширений: инструменты и правила."
            "catalog-tab", "catalog-page", "catalog-category", "catalog-view", "catalog-detail" ->
                "Раздел каталога расширений источника."
            else -> "Инструмент из каталога расширений."
        }
    }
}

// Короткое имя источника для нижней строки карточки (пункт 3):
// «Claude for Android 1.261029.20» → «Claude», «ChatGPT …» → «OpenAI»,
// «Antigravity / Gemini CLI …» → «Antigravity».
private fun shortSourceName(source: String): String {
    val s = source.lowercase()
    return when {
        s.contains("claude") -> "Claude"
        s.contains("chatgpt") || s.contains("codex") || s.contains("openai") -> "OpenAI"
        s.contains("antigravity") || s.contains("gemini") -> "Antigravity"
        else -> source.trim().split(" ").firstOrNull()?.take(14)?.ifBlank { "Источник" } ?: "Источник"
    }
}

// Компактный бейдж статуса для карточек: короткая метка без обрубков
// («⚠ ч…»), с приоритетом по ширине над строкой источника.
@Composable
private fun CompactStatusBadge(
    supportStatus: String,
    fullSupportAllowed: Boolean,
    oauthRequired: Boolean = false
) {
    val normalized = supportStatus.trim().lowercase()
    val (symbol, label, color) = when (normalized) {
        "fully-supported", "verified", "connected" -> Triple("✓", "Готов", DesignColors.AccentGreen)
        "partial" -> Triple("⚠", "Частично", DesignColors.AccentAmber)
        "blocked", "failed" -> Triple("⚠", "Заблокирован", DesignColors.AccentCoral)
        "observation", "not-applicable" -> Triple("•", "Наблюдение", DesignColors.TextSubtle)
        else -> if (oauthRequired) Triple("⚠", "Требует OAuth", DesignColors.AccentAmber)
        else Triple("⚠", "Требует проверки", DesignColors.AccentAmber)
    }
    Text(
        text = "$symbol $label",
        color = color,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

// ---------------------------------------------------------------------------
// Элементы навигации и поиска в стиле Claude Web
// ---------------------------------------------------------------------------

@Composable
private fun SegmentedScopeItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ActivePillBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (selected) DesignColors.TextPrimary else DesignColors.TextSecondary,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun ClaudeSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SearchFieldBg)
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Поиск",
            tint = DesignColors.TextSubtle,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = DesignColors.TextSubtle,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = DesignColors.TextPrimary, fontSize = 13.sp),
                cursorBrush = SolidColor(DesignColors.TextPrimary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            IconButton(
                onClick = { onValueChange("") },
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Очистить поиск",
                    tint = DesignColors.TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionHeaderRow(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = DesignColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
    }
}

// ---------------------------------------------------------------------------
// Hero-баннер Claude Web (#13331C для Навыков, #0D254C для Плагинов)
// ---------------------------------------------------------------------------

@Composable
private fun ClaudeHeroBanner(
    activeTab: Int,
    onActionClick: () -> Unit
) {
    val bgColor = when (activeTab) {
        1 -> HeroSkillsBg
        2 -> HeroPluginsBg
        else -> CardBg
    }
    val borderColor = when (activeTab) {
        1 -> HeroSkillsBorder
        2 -> HeroPluginsBorder
        else -> BorderSubtle
    }
    val overline = when (activeTab) {
        1 -> "From Antigravity • SKILL.md"
        2 -> "From Antigravity • Plugins"
        else -> "From Antigravity • MCP"
    }
    val headline = when (activeTab) {
        1 -> "Data & Skills"
        2 -> "Data & Plugins"
        else -> "MCP Connectors"
    }
    val description = when (activeTab) {
        1 -> "Анализ данных, SQL, таблицы и проверенные навыки SKILL.md в едином рабочем пространстве."
        2 -> "Составные пакеты инструментов, правил и адаптеров с проверкой совместимости и лицензии."
        else -> "Живые MCP-подключения stdio и Streamable HTTP с проверкой рукопожатия и вызовом tools/list."
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = overline,
                    color = Color(0xFFD4D4D8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = headline,
                    color = DesignColors.TextPrimary,
                    fontSize = DesignType.TITLE_SP.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    color = Color(0xFFE4E4E7),
                    fontSize = 12.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(WhiteBtnBg)
                        .clickable(onClick = onActionClick)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Добавить",
                        color = WhiteBtnText,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            HeroDataIllustration()
        }
    }
}

@Composable
private fun HeroDataIllustration() {
    Canvas(modifier = Modifier.size(width = 96.dp, height = 76.dp)) {
        val strokeWidth = 2.dp.toPx()
        val tableWidth = size.width * 0.76f
        val tableHeight = size.height * 0.72f
        val topLeft = Offset(2.dp.toPx(), 4.dp.toPx())

        // Контур таблицы (как на скриншотах Claude Web Data).
        drawRoundRect(
            color = Color.White.copy(alpha = 0.9f),
            topLeft = topLeft,
            size = Size(tableWidth, tableHeight),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = strokeWidth)
        )
        // Горизонтальные линии сетки.
        for (i in 1..3) {
            val y = topLeft.y + (tableHeight / 4f) * i
            drawLine(
                color = Color.White.copy(alpha = 0.85f),
                start = Offset(topLeft.x, y),
                end = Offset(topLeft.x + tableWidth, y),
                strokeWidth = strokeWidth
            )
        }
        // Вертикальные линии сетки.
        for (i in 1..2) {
            val x = topLeft.x + (tableWidth / 3f) * i
            drawLine(
                color = Color.White.copy(alpha = 0.85f),
                start = Offset(x, topLeft.y),
                end = Offset(x, topLeft.y + tableHeight),
                strokeWidth = strokeWidth
            )
        }

        // Круг с графиком тренда в правом нижнем углу иллюстрации.
        val circleRadius = 21.dp.toPx()
        val circleCenter = Offset(size.width - circleRadius - 2.dp.toPx(), size.height - circleRadius - 2.dp.toPx())
        drawCircle(
            color = Color(0xFF6EA88E),
            radius = circleRadius,
            center = circleCenter
        )
        drawCircle(
            color = Color.White,
            radius = circleRadius,
            center = circleCenter,
            style = Stroke(width = strokeWidth)
        )

        val trendPath = Path().apply {
            moveTo(circleCenter.x - circleRadius * 0.55f, circleCenter.y + circleRadius * 0.25f)
            lineTo(circleCenter.x - circleRadius * 0.15f, circleCenter.y - circleRadius * 0.10f)
            lineTo(circleCenter.x + circleRadius * 0.10f, circleCenter.y + circleRadius * 0.12f)
            lineTo(circleCenter.x + circleRadius * 0.48f, circleCenter.y - circleRadius * 0.28f)
        }
        drawPath(
            path = trendPath,
            color = Color.White,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

// ---------------------------------------------------------------------------
// Вкладка «Подключения» (MCP): Табличный компактный вид (Connector | Type | Status)
// ---------------------------------------------------------------------------

@Composable
private fun McpTableHeaderRow() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Подключение",
                color = DesignColors.TextSubtle,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Тип",
                color = DesignColors.TextSubtle,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(52.dp)
            )
            Text(
                text = "Статус",
                color = DesignColors.TextSubtle,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                modifier = Modifier.width(132.dp)
            )
        }
        HorizontalDivider(thickness = 1.dp, color = BorderSubtle)
    }
}

@Composable
private fun McpConnectionTableRow(
    server: PcMcpServerItem,
    onRowClick: () -> Unit,
    onCheck: () -> Unit,
    onLogin: () -> Unit,
    onCallTool: () -> Unit
) {
    // Medium #2: залипший connected при ошибке сбрасывается в configured.
    val effStatus = effectiveMcpStatus(server.status, server.error, server.errorCode)
    val isConnected = effStatus.equals("connected", ignoreCase = true)
    val isFailed = effStatus.equals("failed", ignoreCase = true) || server.error.isNotBlank()
    val isDisabled = !server.enabled || effStatus.equals("disabled", ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Колонка 1: Иконка 32.dp + имя коннектора (статус перенесён в
            // правую колонку — имя не сжимается и не обрезается).
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectorIcon32(name = server.name, isConnected = isConnected, isFailed = isFailed)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = server.name,
                    color = if (isDisabled) DesignColors.TextSecondary else DesignColors.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Колонка 2: Тип Web/stdio одним токеном (по центру).
            Text(
                text = if (server.transport.equals("stdio", ignoreCase = true)) "stdio" else "Web",
                color = DesignColors.TextSecondary,
                fontSize = 11.5.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(52.dp)
            )

            // Колонка 3: Статус — текст под заголовком «Статус» + кнопка справа.
            Column(
                modifier = Modifier.width(132.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val subLabel = when {
                    isConnected && server.tools.isNotEmpty() -> "${server.tools.size} инстр. • рукопожатие ОК"
                    isFailed && server.error.isNotBlank() -> server.error
                    else -> describeMcpStatus(effStatus)
                }
                Text(
                    text = subLabel,
                    color = when {
                        isFailed -> DesignColors.AccentAmber
                        isConnected -> DesignColors.AccentGreen
                        else -> DesignColors.TextSubtle
                    },
                    fontSize = 10.5.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    when {
                    isFailed -> {
                        Text(
                            text = "⚠",
                            color = DesignColors.AccentAmber,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        // Белая контрастная кнопка "Проверить" (аналог Reconnect в Claude Web).
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(WhiteBtnBg)
                                .clickable(onClick = onCheck)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "Проверить",
                                color = WhiteBtnText,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }

                    isConnected -> {
                        Text(
                            text = "✓",
                            color = DesignColors.AccentGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (server.tools.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CardBg)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                                    .clickable(onClick = onCallTool)
                                    .padding(horizontal = 9.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "Вызвать",
                                    color = DesignColors.TextPrimary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CardBg)
                                    .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                                    .clickable(onClick = onCheck)
                                    .padding(horizontal = 9.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "Проверить",
                                    color = DesignColors.TextSecondary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    else -> {
                        // Не проверен или отключён: тёмная кнопка "Подключить" / "Проверить" (как Connect в Claude Web).
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(ActivePillBg)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                                .clickable(onClick = if (server.enabled) onCheck else onLogin)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "Проверить",
                                color = DesignColors.TextPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Color(0xFF1F1F23))
    }
}

// Пункт 5: строка таблицы каталога «Подключения» — та же сетка
// (Иконка + Название | Тип | Статус), детали открываются тапом по строке.
@Composable
private fun McpCatalogTableRow(
    item: ExtensionCatalogItem,
    onRowClick: () -> Unit
) {
    val oauthRequired = item.blocker.contains("oauth", ignoreCase = true) ||
        item.licenseNote.contains("oauth", ignoreCase = true)
    val isLocal = item.sourceProduct.startsWith("Antigravity")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Колонка 1: Иконка 32.dp + название.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectorIcon32(name = item.name, isConnected = false, isFailed = false)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = item.name,
                    color = DesignColors.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            // Колонка 2: Тип (Web / ПК).
            Text(
                text = if (isLocal) "ПК" else "Web",
                color = DesignColors.TextSecondary,
                fontSize = 11.5.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(52.dp)
            )
            // Колонка 3: Статус без обрубков.
            Box(
                modifier = Modifier.width(132.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                CompactStatusBadge(
                    supportStatus = item.compatibility,
                    fullSupportAllowed = item.compatibility == "fully-supported",
                    oauthRequired = oauthRequired
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Color(0xFF1F1F23))
    }
}

@Composable
private fun ConnectorIcon32(
    name: String,
    isConnected: Boolean,
    isFailed: Boolean
) {
    val badgeColor = when {
        isFailed -> DesignColors.AccentAmber
        isConnected -> DesignColors.AccentGreen
        else -> DesignColors.AccentBlue
    }
    val glyph = remember(name) {
        val clean = name.trim()
        when {
            clean.contains("workstation", ignoreCase = true) || clean.contains("sysinfo", ignoreCase = true) -> "⚙"
            clean.contains("rea", ignoreCase = true) -> "◈"
            clean.contains("git", ignoreCase = true) -> "⌥"
            clean.contains("google", ignoreCase = true) || clean.contains("drive", ignoreCase = true) -> "G"
            clean.isNotEmpty() -> clean.take(2).uppercase()
            else -> "⌘"
        }
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg)
            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            color = badgeColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ---------------------------------------------------------------------------
// Карточки-витрины (42.dp иконка, maxLines = 2, #18181B фон, 1.dp #27272A рамка)
// ---------------------------------------------------------------------------

@Composable
private fun ShowcaseIcon42(
    categoryOrName: String,
    accentColor: Color = DesignColors.TextPrimary
) {
    val symbol = remember(categoryOrName) {
        val lower = categoryOrName.lowercase()
        when {
            lower.contains("mcp") || lower.contains("command") || lower.contains("cli") || lower.contains("code") -> "</>"
            lower.contains("skill") || lower.contains("agent") || lower.contains("prompt") -> "{ }"
            lower.contains("connector") || lower.contains("app") || lower.contains("web") -> "🌐"
            lower.contains("plugin") -> "⌘"
            else -> "◈"
        }
    }
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(IconBoxBg)
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = accentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun SkillCard(
    skill: PcSkillItem,
    onCardClick: () -> Unit,
    onInsertIntoChat: () -> Unit,
    onToggle: () -> Unit
) {
    Surface(
        color = CardBg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
            .clickable(onClick = onCardClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            ShowcaseIcon42(
                categoryOrName = "skill ${skill.name}",
                accentColor = if (skill.enabled) DesignColors.AccentGreen else DesignColors.TextSubtle
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = skill.name,
                    color = DesignColors.TextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                Text(
                    text = skill.description.ifBlank { skill.instructionsPreview }.ifBlank { "Навык SKILL.md рабочей станции" },
                    color = DesignColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (skill.enabled) "✓ Включён" else "• Отключён",
                        color = if (skill.enabled) DesignColors.AccentGreen else DesignColors.TextSubtle,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "· ПК (SKILL.md)",
                        color = DesignColors.TextSubtle,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            // Кнопки справа в стиле Claude Web: [💬 В чат] + компактный тоггл.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ActivePillBg)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .clickable(onClick = onInsertIntoChat)
                        .padding(horizontal = 7.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "/ В чат",
                        color = DesignColors.TextPrimary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (skill.enabled) ActivePillBg else IconBoxBg)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (skill.enabled) "✓" else "○",
                        color = if (skill.enabled) DesignColors.AccentGreen else DesignColors.TextSubtle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: InstalledPluginItem,
    onCardClick: () -> Unit,
    onToggle: () -> Unit,
    onUpdate: () -> Unit
) {
    val composition = plugin.composition
    Surface(
        color = CardBg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
            .clickable(onClick = onCardClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            ShowcaseIcon42(
                categoryOrName = "${plugin.category} ${plugin.name}",
                accentColor = if (plugin.enabled) DesignColors.AccentBlue else DesignColors.TextSubtle
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = plugin.name,
                        color = DesignColors.TextPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = "v${plugin.version}",
                        color = DesignColors.TextSubtle,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
                Text(
                    text = plugin.description.ifBlank { "Пакет плагина (${plugin.category})" },
                    color = DesignColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "by ${shortSourceName(plugin.source)}",
                        color = DesignColors.TextSubtle,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    CompactStatusBadge(
                        supportStatus = composition.supportStatus,
                        fullSupportAllowed = composition.fullSupportAllowed
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ActivePillBg)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .clickable(onClick = onUpdate)
                        .padding(horizontal = 7.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Обновить",
                        color = DesignColors.TextPrimary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (plugin.enabled) ActivePillBg else IconBoxBg)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center
                ) {
                    // Пункт 4: зелёная «✓» только когда статус не противоречит
                    // ей; у заблокированного плагина «✓» не показывается.
                    val blocked = composition.supportStatus.equals("blocked", ignoreCase = true) ||
                        composition.supportStatus.equals("failed", ignoreCase = true)
                    Text(
                        text = when {
                            blocked -> "⚠"
                            plugin.enabled -> "✓"
                            else -> "○"
                        },
                        color = when {
                            blocked -> DesignColors.AccentCoral
                            plugin.enabled -> DesignColors.AccentGreen
                            else -> DesignColors.TextSubtle
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(
    item: ExtensionCatalogItem,
    onCardClick: () -> Unit,
    onInstall: () -> Unit
) {
    // Пункт 2: на витрине — человеческое описание назначения; сырой текст
    // блокера/лицензии — только в нижней шторке по тапу на карточку.
    val humanDescription = remember(item) { humanDescriptionFor(item) }
    val oauthRequired = remember(item) {
        item.blocker.contains("oauth", ignoreCase = true) ||
            item.licenseNote.contains("oauth", ignoreCase = true)
    }
    Surface(
        color = CardBg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
            .clickable(onClick = onCardClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            ShowcaseIcon42(
                categoryOrName = "${item.category} ${item.name}",
                accentColor = if (item.installable) DesignColors.AccentGreen else DesignColors.TextSecondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.name,
                    color = DesignColors.TextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                Text(
                    text = humanDescription,
                    color = DesignColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                // Пункт 3: короткое имя источника + бейдж без обрубков; бейдж
                // имеет приоритет по ширине, источник сокращается первым.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "by ${shortSourceName(item.sourceProduct)}",
                        color = DesignColors.TextSubtle,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    CompactStatusBadge(
                        supportStatus = item.compatibility,
                        fullSupportAllowed = item.compatibility == "fully-supported",
                        oauthRequired = oauthRequired
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ActivePillBg)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .clickable(onClick = onCardClick)
                        .padding(horizontal = 7.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Детали",
                        color = DesignColors.TextPrimary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
                if (item.installable) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(WhiteBtnBg)
                            .clickable(onClick = onInstall),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Установить",
                            tint = WhiteBtnText,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Исправленный бейдж совместимости: "✓" ТОЛЬКО для активных/поддержанных,
// "⚠" для блокеров/ошибок/предупреждений, "•" для нейтрального наблюдения.
// ---------------------------------------------------------------------------

@Composable
private fun CompositionBadge(supportStatus: String, fullSupportAllowed: Boolean) {
    val normalized = supportStatus.trim().lowercase()
    val (prefixSymbol, label, color) = when (normalized) {
        "fully-supported", "verified", "connected" -> Triple("✓", "полностью поддержан", DesignColors.AccentGreen)
        "partial" -> Triple("⚠", "частично поддержан", DesignColors.AccentAmber)
        "blocked", "failed" -> Triple("⚠", "заблокирован", DesignColors.AccentCoral)
        "observation", "not-applicable" -> Triple("•", "наблюдение", DesignColors.TextSubtle)
        else -> Triple("⚠", "требует проверки", DesignColors.AccentAmber)
    }
    val displayLabel = if (fullSupportAllowed && prefixSymbol == "✓") {
        "✓ $label"
    } else {
        "$prefixSymbol $label"
    }
    Text(
        text = displayLabel,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ComponentRecordLine(record: ComponentAdapterRecord) {
    val normalized = record.status.trim().lowercase()
    val (symbol, statusColor) = when (normalized) {
        "verified" -> "✓" to DesignColors.AccentGreen
        "blocked" -> "⚠" to DesignColors.AccentCoral
        "not-applicable" -> "•" to DesignColors.TextSubtle
        else -> "⚠" to DesignColors.AccentAmber
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(IconBoxBg)
            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = "$symbol ${record.componentType} — стратегия: ${record.strategy} (${record.status})",
            color = statusColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (record.blocker.isNotBlank()) {
            Text(
                text = record.blocker,
                color = DesignColors.TextSecondary,
                fontSize = 11.sp
            )
        }
        if (record.verification.isNotBlank()) {
            Text(
                text = "Проверка: ${record.verification}",
                color = DesignColors.TextSubtle,
                fontSize = 10.5.sp
            )
        }
    }
}

@Composable
private fun EmptyExtensionsCard(title: String, subtitle: String) {
    Surface(
        color = CardBg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                color = DesignColors.TextPrimary,
                fontSize = DesignType.TITLE_SP.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = DesignColors.TextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 17.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------
// ModalBottomSheet: Детальная техническая информация по нажатию на карточку
// ---------------------------------------------------------------------------

@Composable
private fun McpDetailSheetContent(
    server: PcMcpServerItem,
    permissions: McpToolPermissionsState?,
    onLoadPermissions: () -> Unit,
    /** tool, permission, scope, comment («Deny with comment»; "" — без комментария). */
    onSetToolPermission: (String, String, McpToolPermissionScope, String) -> Unit,
    /** permission, scope, comment («Deny with comment»; "" — без комментария). */
    onSetAllToolPermissions: (String, McpToolPermissionScope, String) -> Unit,
    /** Id текущего разговора для скоупа «Для этой сессии» (пусто — нет разговора). */
    scopeSessionId: String,
    /** Id пачки субагентов для скоупа «Для этой задачи» (пусто — «нет активной задачи»). */
    scopeTaskId: String,
    permissionModeState: McpPermissionModeState?,
    onLoadPermissionMode: () -> Unit,
    onSetPermissionMode: (McpPermissionMode) -> Unit,
    onCheck: () -> Unit,
    onLogin: () -> Unit,
    onOpenToolsDialog: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    val isConnected = effectiveMcpStatus(server.status, server.error, server.errorCode).equals("connected", ignoreCase = true)
    val isFailed = server.status.equals("failed", ignoreCase = true) || server.error.isNotBlank()

    // Права инструментов («как у них») и режим разрешений загружаются при
    // открытии деталей.
    LaunchedEffect(server.id) {
        onLoadPermissions()
        onLoadPermissionMode()
    }
    var permissionPicker by remember { mutableStateOf<PermissionPickerTarget?>(null) }
    // Мини-пикер скоупа для «Всегда разрешать» (project/session/task/website).
    var scopePicker by remember { mutableStateOf<PermissionPickerTarget?>(null) }
    // Диалог «Запретить с комментарием» («Deny with comment»): пояснение
    // пользователя попадает в текст отказа модели; пустой комментарий
    // допустим — это «просто Запретить».
    var denyCommentDialog by remember { mutableStateOf<DenyCommentDialogState?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SheetHeader(title = server.name, onClose = onClose)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectorIcon32(name = server.name, isConnected = isConnected, isFailed = isFailed)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${if (isConnected) "✓" else if (isFailed) "⚠" else "•"} ${describeMcpStatus(effectiveMcpStatus(server.status, server.error, server.errorCode))}",
                    color = when {
                        isConnected -> DesignColors.AccentGreen
                        isFailed -> DesignColors.AccentCoral
                        else -> DesignColors.AccentAmber
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

        }

        HorizontalDivider(thickness = 1.dp, color = BorderSubtle)

        // Переключатель активности сервера.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (server.enabled) "Подключение включено" else "Подключение отключено",
                    color = DesignColors.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Исполнитель: ПК (pc-bridge) • ${server.transport}",
                    color = DesignColors.TextSecondary,
                    fontSize = 11.5.sp
                )
            }
            Switch(
                checked = server.enabled,
                onCheckedChange = { onToggle() },
                colors = com.google.antigravity.ui.design.designSwitchColors()
            )
        }

        // Технические MCP-параметры.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Технические параметры MCP",
                color = DesignColors.TextPrimary,
                fontSize = DesignType.SECTION_SP.sp,
                fontWeight = FontWeight.SemiBold
            )
            DetailRowMonospace(label = "ID", value = server.id)
            DetailRowMonospace(label = "Транспорт", value = server.transport)
            DetailRowMonospace(label = "Область", value = server.scope)
            val fullCmd = "${server.command} ${server.args.joinToString(" ")}".trim().ifBlank { server.commandOrUrl }
            if (fullCmd.isNotBlank()) {
                DetailRowMonospace(label = "Команда / URL", value = fullCmd)
            }
            if (server.probedAt.isNotBlank()) {
                DetailRowMonospace(label = "Последняя проверка", value = server.probedAt)
            }
            Text(
                text = "Права: инструменты ПК по списку разрешений (аннотации MCP сами по себе прав не расширяют).",
                color = DesignColors.TextSubtle,
                fontSize = 11.sp
            )
        }

        // Статус авторизации OAuth 2.1 (вход через системный браузер / Custom Tabs).
        if (server.commandOrUrl.startsWith("http")) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(IconBoxBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Авторизация (OAuth 2.1)",
                    color = DesignColors.TextPrimary,
                    fontSize = DesignType.SECTION_SP.sp,
                    fontWeight = FontWeight.SemiBold
                )
                // Требуется вход / Авторизован / Ошибка токена (+ уточнения).
                Text(
                    text = describeMcpAuthStatus(server.authStatus),
                    color = when {
                        server.oauthAuthorized -> DesignColors.AccentGreen
                        server.authStatus == "failed" || server.authStatus == "expired" -> DesignColors.AccentCoral
                        else -> DesignColors.AccentAmber
                    },
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when {
                        server.oauthAuthorized -> "Выход отзывает выданные токены доступа на ПК (pc-bridge)."
                        server.authStatus == "pending" -> "Вход открыт в браузере. После подтверждения вернитесь в приложение — статус обновится автоматически (onResume)."
                        server.authStatus == "failed" -> "Ошибка токена: повторите вход."
                        else -> "Вход выполняется через системный браузер; статус обновляется при возврате в приложение или по кнопке «Проверить»."
                    },
                    color = DesignColors.TextSecondary,
                    fontSize = 11.5.sp
                )
                // Действие входа/выхода (revoke) прямо в деталях подключения.
                Button(
                    onClick = { if (server.authStatus == "pending") onCheck() else onLogin() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (server.oauthAuthorized) DesignColors.AccentCoral else WhiteBtnBg,
                        contentColor = if (server.oauthAuthorized) Color.White else WhiteBtnText
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when {
                            server.oauthAuthorized -> "Выйти (отозвать доступ)"
                            server.authStatus == "pending" -> "Обновить статус входа"
                            else -> "Войти"
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Полный текст ошибки / блокера (если есть).
        if (server.error.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2D1618))
                    .border(1.dp, Color(0xFF5C2327), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "⚠ Диагностика ошибки подключения",
                    color = DesignColors.AccentCoral,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = server.error,
                    color = Color(0xFFFCA5A5),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Список живых инструментов.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (server.tools.isNotEmpty()) {
                    "✓ Доступные инструменты (${server.tools.size})"
                } else {
                    "⚠ Инструменты не получены"
                },
                color = if (server.tools.isNotEmpty()) DesignColors.AccentGreen else DesignColors.AccentAmber,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (server.tools.isNotEmpty()) {
                Text(
                    text = server.tools.joinToString(", "),
                    color = DesignColors.TextSecondary,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    text = "Выполните живое рукопожатие «Проверить» (initialize → notifications/initialized → tools/list).",
                    color = DesignColors.TextSecondary,
                    fontSize = 11.5.sp
                )
            }
        }

        // Режим разрешений («как у них»: default/plan/auto/acceptEdits/dontAsk/
        // bypassPermissions) — рядом с правами инструментов, выбор radio-списком.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Режим разрешений",
                color = DesignColors.TextPrimary,
                fontSize = DesignType.SECTION_SP.sp,
                fontWeight = FontWeight.SemiBold
            )
            when {
                permissionModeState == null ||
                    (permissionModeState.loading && !permissionModeState.loaded) -> {
                    Text(
                        text = "Загрузка режима разрешений…",
                        color = DesignColors.TextSecondary,
                        fontSize = 11.5.sp
                    )
                }
                permissionModeState.error.isNotBlank() -> {
                    // Отказ моста (включая неизвестный режим) — видимый текст.
                    Text(
                        text = "⚠ ${permissionModeState.error}",
                        color = DesignColors.AccentCoral,
                        fontSize = 11.5.sp
                    )
                }
                else -> {
                    if (permissionModeState.mode == null) {
                        Text(
                            text = "Режим не записан мостом.",
                            color = DesignColors.TextSubtle,
                            fontSize = 11.sp
                        )
                    }
                    McpPermissionMode.entries.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSetPermissionMode(mode) }
                        ) {
                            RadioButton(
                                selected = permissionModeState.mode == mode,
                                onClick = { onSetPermissionMode(mode) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = DesignColors.AccentGreen,
                                    unselectedColor = DesignColors.TextSubtle
                                )
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.label,
                                    color = if (permissionModeState.mode == mode) {
                                        DesignColors.TextPrimary
                                    } else {
                                        DesignColors.TextSecondary
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                if (mode == McpPermissionMode.AUTO) {
                                    // Честная граница нашего «Автоматически».
                                    Text(
                                        text = "Оценка риска — эвристика по именам инструментов и аргументам",
                                        color = DesignColors.AccentAmber,
                                        fontSize = 10.5.sp,
                                        lineHeight = 14.sp
                                    )
                                } else {
                                    Text(
                                        text = mode.hint,
                                        color = DesignColors.TextSubtle,
                                        fontSize = 10.5.sp,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }
                    }
                    if (permissionModeState.loading) {
                        Text(
                            text = "Сохранение режима…",
                            color = DesignColors.TextSubtle,
                            fontSize = 11.sp
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = BorderSubtle)
                    Text(
                        text = "Состав режимов — как в Claude (default/plan/auto/acceptEdits/dontAsk/bypassPermissions); " +
                            "точная семантика их Auto-режима (модельная оценка рисков и prompt injection) " +
                            "не воспроизводится — «требует проверки».",
                        color = DesignColors.TextSubtle,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }

        // Права инструментов («как у них»): allow/ask/deny на каждый инструмент;
        // приоритет per-tool > «все инструменты сервера» > глобальная политика.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Права инструментов",
                color = DesignColors.TextPrimary,
                fontSize = DesignType.SECTION_SP.sp,
                fontWeight = FontWeight.SemiBold
            )
            when {
                permissions == null || (permissions.loading && !permissions.loaded) -> {
                    Text(
                        text = "Загрузка прав инструментов…",
                        color = DesignColors.TextSecondary,
                        fontSize = DesignType.CAPTION_SP.sp
                    )
                }
                permissions.error.isNotBlank() -> {
                    // Отказ сервера/связи — видимый текст, успех не выдумывается.
                    Text(
                        text = "⚠ ${permissions.error}",
                        color = DesignColors.AccentCoral,
                        fontSize = DesignType.CAPTION_SP.sp
                    )
                }
                permissions.tools.isEmpty() -> {
                    // Инструменты появляются только после живого рукопожатия.
                    Text(
                        text = "Список инструментов доступен после «Проверить».",
                        color = DesignColors.TextSecondary,
                        fontSize = DesignType.CAPTION_SP.sp
                    )
                }
                else -> {
                    if (permissions.defaultPermission != null) {
                        Text(
                            text = "Право сервера по умолчанию: ${describeToolPermission(permissions.defaultPermission)}",
                            color = DesignColors.TextSubtle,
                            fontSize = DesignType.MICRO_SP.sp
                        )
                    }
                    permissions.tools.forEach { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    permissionPicker = PermissionPickerTarget.Tool(entry.tool)
                                }
                                .padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = entry.tool.ifBlank { "не записано" },
                                    color = DesignColors.TextSecondary,
                                    fontSize = DesignType.CAPTION_SP.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = describeToolPermission(entry.permission, entry.comment) + when (entry.source) {
                                        "policy" -> " • по политике"
                                        "session", "task", "website" ->
                                            " • ${describeMcpPermissionSource(entry.source)}"
                                        else -> ""
                                    },
                                    color = when (entry.permission) {
                                        "allow" -> DesignColors.AccentGreen
                                        "deny" -> DesignColors.AccentCoral
                                        else -> DesignColors.AccentAmber
                                    },
                                    fontSize = DesignType.CAPTION_SP.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            // «Deny with comment»: записанный комментарий
                            // пользователя — второй строкой мелким шрифтом.
                            if (entry.permission.trim().equals("deny", ignoreCase = true) &&
                                entry.comment.isNotBlank()
                            ) {
                                Text(
                                    text = "💬 ${entry.comment}",
                                    color = DesignColors.TextSubtle,
                                    fontSize = DesignType.MICRO_SP.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                permissionPicker = PermissionPickerTarget.AllTools
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Изменить права всех инструментов",
                            color = DesignColors.AccentBlue,
                            fontSize = DesignType.CAPTION_SP.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (permissions.loading) {
                        Text(
                            text = "Обновление прав…",
                            color = DesignColors.TextSubtle,
                            fontSize = DesignType.MICRO_SP.sp
                        )
                    }
                }
            }
        }

        // Кнопки управления подключением.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onCheck,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WhiteBtnBg,
                    contentColor = WhiteBtnText
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Проверить", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
            // «Войти» / «Выйти (отозвать доступ)» — в карточке авторизации выше.
            Button(
                onClick = onOpenToolsDialog,
                enabled = server.tools.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActivePillBg,
                    contentColor = DesignColors.TextPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Вызвать", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить подключение",
                    tint = DesignColors.AccentCoral
                )
            }
        }
    }

    // Селектор права («как у них»): Всегда разрешать / Спрашивать / Запретить.
    permissionPicker?.let { target ->
        val currentPermission = when (target) {
            is PermissionPickerTarget.Tool ->
                permissions?.tools?.firstOrNull { it.tool == target.tool }?.permission ?: ""
            PermissionPickerTarget.AllTools -> permissions?.defaultPermission ?: ""
        }
        AlertDialog(
            onDismissRequest = { permissionPicker = null },
            containerColor = CardBg,
            title = {
                Text(
                    text = when (target) {
                        is PermissionPickerTarget.Tool -> "Право инструмента ${target.tool}"
                        PermissionPickerTarget.AllTools -> "Права всех инструментов"
                    },
                    color = DesignColors.TextPrimary,
                    fontSize = DesignType.TITLE_SP.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column {
                    listOf(
                        "allow" to "Всегда разрешать",
                        "ask" to "Спрашивать",
                        "deny" to "Запретить"
                    ).forEach { (value, label) ->
                        TextButton(
                            onClick = {
                                when {
                                    value == "allow" -> {
                                        // «Всегда разрешать» — сначала мини-пикер скоупа
                                        // («как у них»: Always allow for this project/…).
                                        scopePicker = target
                                    }
                                    value == "deny" -> {
                                        // «Запретить» — диалог «Deny with comment»:
                                        // комментарий необязателен (пустой = «просто
                                        // Запретить»), но пояснение попадёт в текст
                                        // отказа модели.
                                        denyCommentDialog = DenyCommentDialogState(
                                            tool = (target as? PermissionPickerTarget.Tool)?.tool.orEmpty(),
                                            isAllTools = target is PermissionPickerTarget.AllTools
                                        )
                                    }
                                    else -> {
                                        when (target) {
                                            is PermissionPickerTarget.Tool ->
                                                onSetToolPermission(
                                                    target.tool, value,
                                                    McpToolPermissionScope.PROJECT, ""
                                                )
                                            PermissionPickerTarget.AllTools ->
                                                onSetAllToolPermissions(
                                                    value, McpToolPermissionScope.PROJECT, ""
                                                )
                                        }
                                    }
                                }
                                permissionPicker = null
                            }
                        ) {
                            Text(
                                text = if (currentPermission == value) "$label ✓" else label,
                                color = if (currentPermission == value) DesignColors.AccentGreen else DesignColors.TextPrimary,
                                fontSize = DesignType.BODY_SP.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { permissionPicker = null }) {
                    Text("Отмена", color = DesignColors.TextSecondary)
                }
            }
        )
    }

    // Диалог «Запретить с комментарием» («Deny with comment»): пояснение
    // пользователя необязательно и попадёт в текст отказа модели; пустой
    // комментарий допустим — это «просто Запретить». Отправляется только с
    // deny (контракт моста), повторный deny без комментария очищает прежний.
    denyCommentDialog?.let { state ->
        AlertDialog(
            onDismissRequest = { denyCommentDialog = null },
            containerColor = CardBg,
            title = {
                Text(
                    text = if (state.isAllTools) {
                        "Запретить все инструменты"
                    } else {
                        "Запретить ${state.tool.ifBlank { "не записано" }}"
                    },
                    color = DesignColors.TextPrimary,
                    fontSize = DesignType.TITLE_SP.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = state.comment,
                        onValueChange = { denyCommentDialog = state.copy(comment = it) },
                        label = { Text("Комментарий к запрету (необязательно)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = DesignColors.TextPrimary,
                            unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = DesignColors.TextPrimary,
                            unfocusedTextColor = DesignColors.TextPrimary,
                            focusedLabelColor = DesignColors.TextSecondary,
                            unfocusedLabelColor = DesignColors.TextSubtle
                        )
                    )
                    Text(
                        text = "Пояснение попадёт в текст отказа модели («Deny with comment»).",
                        color = DesignColors.TextSubtle,
                        fontSize = DesignType.MICRO_SP.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (state.isAllTools) {
                        onSetAllToolPermissions(
                            "deny", McpToolPermissionScope.PROJECT, state.comment
                        )
                    } else {
                        onSetToolPermission(
                            state.tool, "deny", McpToolPermissionScope.PROJECT, state.comment
                        )
                    }
                    denyCommentDialog = null
                }) {
                    Text(state.confirmLabel, color = DesignColors.AccentCoral, fontSize = DesignType.BODY_SP.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { denyCommentDialog = null }) {
                    Text("Отмена", color = DesignColors.TextSecondary, fontSize = DesignType.BODY_SP.sp)
                }
            }
        )
    }

    // Мини-пикер скоупа для «Всегда разрешать» («как у них»: «Always allow for
    // this project/session/task/website»). Скоупы — наша модель по мотивам
    // Claude; точная семантика референса не подтверждена.
    scopePicker?.let { target ->
        AlertDialog(
            onDismissRequest = { scopePicker = null },
            containerColor = CardBg,
            title = {
                Text(
                    text = "Скоуп правила «Всегда разрешать»",
                    color = DesignColors.TextPrimary,
                    fontSize = DesignType.TITLE_SP.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    McpToolPermissionScope.entries.forEach { scope ->
                        val unavailableReason = when {
                            scope == McpToolPermissionScope.SESSION &&
                                scopeSessionId.isBlank() -> "нет активного разговора"
                            scope == McpToolPermissionScope.TASK &&
                                scopeTaskId.isBlank() -> "нет активной задачи"
                            else -> ""
                        }
                        Column {
                            TextButton(
                                onClick = {
                                    when (target) {
                                        is PermissionPickerTarget.Tool ->
                                            onSetToolPermission(
                                                target.tool, "allow", scope, ""
                                            )
                                        PermissionPickerTarget.AllTools ->
                                            onSetAllToolPermissions("allow", scope, "")
                                    }
                                    scopePicker = null
                                },
                                enabled = unavailableReason.isBlank()
                            ) {
                                Text(
                                    text = scope.label,
                                    color = if (unavailableReason.isBlank()) DesignColors.TextPrimary else DesignColors.TextSubtle,
                                    fontSize = DesignType.BODY_SP.sp
                                )
                            }
                            // Честная подсказка: границы скоупа и недоступность.
                            Text(
                                text = unavailableReason.ifBlank { scope.hint },
                                color = if (unavailableReason.isBlank()) DesignColors.TextSubtle else DesignColors.AccentAmber,
                                fontSize = DesignType.MICRO_SP.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = BorderSubtle)
                    Text(
                        text = "Скоупы — наша модель по мотивам Claude; точная семантика референса не подтверждена.",
                        color = DesignColors.TextSubtle,
                        fontSize = DesignType.MICRO_SP.sp,
                        lineHeight = 14.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { scopePicker = null }) {
                    Text("Отмена", color = DesignColors.TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun SkillDetailSheetContent(
    skill: PcSkillItem,
    detailState: SkillDetailState?,
    onLoadDetail: () -> Unit,
    onInsertIntoChat: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    // Полный текст SKILL.md (content из skillDetail) загружается при открытии.
    LaunchedEffect(skill.id) { onLoadDetail() }
    val detail = detailState?.detail
    // Встроенный навык (source = builtin) — только чтение: «Удалить» скрыт,
    // «Редактировать» заблокирован, показана честная пометка. Мост отклоняет
    // правку и удаление builtin кодом skill_read_only.
    val isBuiltin = detail?.source == "builtin"
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SheetHeader(title = skill.name, onClose = onClose)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShowcaseIcon42(
                categoryOrName = "skill ${skill.name}",
                accentColor = if (skill.enabled) DesignColors.AccentGreen else DesignColors.TextSubtle
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Вызов в разговоре: /${skill.id.removePrefix("/")}",
                    color = DesignColors.AccentGreen,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

        }

        HorizontalDivider(thickness = 1.dp, color = BorderSubtle)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (skill.enabled) "Навык активен" else "Навык отключён",
                    color = DesignColors.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Исполнитель: ПК • SKILL.md",
                    color = DesignColors.TextSecondary,
                    fontSize = 11.5.sp
                )
            }
            Switch(
                checked = skill.enabled,
                onCheckedChange = { onToggle() },
                colors = com.google.antigravity.ui.design.designSwitchColors()
            )
        }

        if (skill.description.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(IconBoxBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Описание", color = DesignColors.TextPrimary, fontSize = DesignType.SECTION_SP.sp, fontWeight = FontWeight.SemiBold)
                Text(skill.description, color = DesignColors.TextSecondary, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }

        if (detailState?.error?.isNotBlank() == true) {
            // Ошибка загрузки SKILL.md показывается текстом, без маскировки.
            Text(
                text = "⚠ ${detailState.error}",
                color = DesignColors.AccentCoral,
                fontSize = 11.5.sp
            )
        }

        // Полный текст SKILL.md (content); до загрузки — честное превью.
        val fullContent = detail?.content.orEmpty()
        val blockTitle: String
        val blockText: String
        when {
            fullContent.isNotBlank() -> {
                blockTitle = "SKILL.md — полный текст"
                blockText = fullContent
            }
            detailState?.loading == true -> {
                blockTitle = "SKILL.md"
                blockText = "Загрузка полного текста SKILL.md…"
            }
            else -> {
                blockTitle = "SKILL.md — превью (полный текст не загружен)"
                blockText = skill.instruction.ifBlank { skill.instructionsPreview }
            }
        }
        if (blockText.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(IconBoxBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(blockTitle, color = DesignColors.TextPrimary, fontSize = DesignType.SECTION_SP.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = blockText,
                    color = DesignColors.TextSecondary,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            DetailRowMonospace(
                label = "Источник",
                value = detail?.source?.ifBlank { "не записано" } ?: "не записано"
            )
            if (skill.path.isNotBlank()) {
                DetailRowMonospace(label = "Путь пакета", value = skill.path)
            }
        }

        Button(
            onClick = onInsertIntoChat,
            colors = ButtonDefaults.buttonColors(
                containerColor = WhiteBtnBg,
                contentColor = WhiteBtnText
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Вставить в чат: /${skill.id.removePrefix("/")}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onEdit,
                enabled = !isBuiltin,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActivePillBg,
                    contentColor = DesignColors.TextPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Редактировать", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (isBuiltin) {
                // Честная пометка вместо «Удалить»: встроенный навык только читается.
                Text(
                    text = "встроенный навык — только чтение",
                    color = DesignColors.TextSubtle,
                    fontSize = 11.5.sp,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Button(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DangerBtnBg,
                        contentColor = DesignColors.AccentCoral
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Удалить", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    // Подтверждение удаления («как у них»: «Delete skill?»): действие необратимо,
    // навык и его история версий удаляются навсегда.
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = CardBg,
            title = {
                Text(
                    text = "Удалить навык?",
                    color = DesignColors.TextPrimary,
                    fontSize = DesignType.TITLE_SP.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "Навык и его история версий будут удалены навсегда.",
                    color = DesignColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text("Удалить", color = DesignColors.AccentCoral, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Отмена", color = DesignColors.TextSecondary)
                }
            }
        )
    }
}

/**
 * Редактор навыка («как у них»: SkillEdit — «Edit skill», «Skill name»):
 * имя навыка и полный текст SKILL.md. Состояния: загрузка черновика, ошибка
 * сохранения текстом моста, блокировка кнопок на время сохранения. Редактор
 * закрывается только по «Отмена» или после успешного сохранения; отказ
 * (включая skill_name_taken) оставляет его открытым с текстом ошибки.
 */
@Composable
private fun SkillEditSheetContent(
    skillId: String,
    state: SkillEditState,
    onStartLoad: () -> Unit,
    onNameChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onSave: (String, String) -> Unit,
    onClose: () -> Unit
) {
    // Черновик имени и SKILL.md загружается при открытии редактора.
    LaunchedEffect(skillId) { onStartLoad() }

    // Закрытие шторки только после реального закрытия редактора состоянием
    // (onSaved сбрасывает editorOpen); иначе отказ сохранения закрыл бы его.
    var editorWasOpen by remember(skillId) { mutableStateOf(false) }
    LaunchedEffect(state.editorOpen) {
        if (state.editorOpen) editorWasOpen = true
        else if (editorWasOpen) onClose()
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = DesignColors.TextPrimary,
        unfocusedBorderColor = BorderSubtle,
        focusedTextColor = DesignColors.TextPrimary,
        unfocusedTextColor = DesignColors.TextPrimary,
        cursorColor = DesignColors.TextPrimary,
        focusedLabelColor = DesignColors.TextSecondary,
        unfocusedLabelColor = DesignColors.TextSubtle
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SheetHeader(title = "Редактирование навыка", onClose = onClose)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "/${skillId.removePrefix("/")}",
                    color = DesignColors.AccentGreen,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

        }

        HorizontalDivider(thickness = 1.dp, color = BorderSubtle)

        when {
            // Черновик ещё не загружен (или это состояние другого навыка).
            state.skillId != skillId || state.loading -> {
                Text(
                    text = "Загрузка SKILL.md…",
                    color = DesignColors.TextSecondary,
                    fontSize = 12.5.sp
                )
            }

            // Загрузка не удалась: ошибка видима текстом, редактор не открыт.
            !state.editorOpen -> {
                Text(
                    text = "⚠ ${state.error.ifBlank { "SKILL.md не получен" }}",
                    color = DesignColors.AccentCoral,
                    fontSize = 12.sp
                )
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WhiteBtnBg,
                        contentColor = WhiteBtnText
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Закрыть", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            else -> {
                OutlinedTextField(
                    value = state.nameDraft,
                    onValueChange = onNameChange,
                    label = { Text("Имя навыка") },
                    singleLine = true,
                    enabled = !state.saving && !state.readOnly,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.contentDraft,
                    onValueChange = onContentChange,
                    label = { Text("SKILL.md") },
                    enabled = !state.saving && !state.readOnly,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = DesignColors.TextPrimary
                    ),
                    colors = textFieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 380.dp)
                )
                if (state.readOnly) {
                    Text(
                        text = "встроенный навык — только чтение",
                        color = DesignColors.TextSubtle,
                        fontSize = 11.5.sp
                    )
                }
                // Ошибка сохранения (включая skill_name_taken) — текстом моста,
                // без маскировки; редактор остаётся открытым.
                if (state.error.isNotBlank()) {
                    Text(
                        text = "⚠ ${state.error}",
                        color = DesignColors.AccentCoral,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                } else if (!state.canSave && state.saveBlockReason.isNotBlank()) {
                    // Честная причина, почему сохранение недоступно.
                    Text(
                        text = state.saveBlockReason,
                        color = DesignColors.AccentAmber,
                        fontSize = 11.5.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { onSave(state.nameDraft, state.contentDraft) },
                        enabled = state.canSave,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WhiteBtnBg,
                            contentColor = WhiteBtnText
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Сохранить", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(
                        onClick = onClose,
                        enabled = !state.saving,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Отмена", color = DesignColors.TextSecondary, fontSize = 13.sp)
                    }
                }
                if (state.saving) {
                    Text(
                        text = "Сохранение…",
                        color = DesignColors.TextSecondary,
                        fontSize = 11.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginDetailSheetContent(
    plugin: InstalledPluginItem,
    onToggle: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    val composition = plugin.composition
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SheetHeader(title = "${plugin.name} (v${plugin.version})", onClose = onClose)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShowcaseIcon42(
                categoryOrName = "${plugin.category} ${plugin.name}",
                accentColor = if (plugin.enabled) DesignColors.AccentBlue else DesignColors.TextSubtle
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                CompositionBadge(
                    supportStatus = composition.supportStatus,
                    fullSupportAllowed = composition.fullSupportAllowed
                )
            }

        }

        HorizontalDivider(thickness = 1.dp, color = BorderSubtle)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (plugin.enabled) "Плагин включён" else "Плагин отключён",
                    color = DesignColors.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${plugin.category} • Источник: ${plugin.source}",
                    color = DesignColors.TextSecondary,
                    fontSize = 11.5.sp
                )
            }
            Switch(
                checked = plugin.enabled,
                onCheckedChange = { onToggle() },
                colors = com.google.antigravity.ui.design.designSwitchColors()
            )
        }

        if (plugin.description.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(IconBoxBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Описание пакета", color = DesignColors.TextPrimary, fontSize = DesignType.SECTION_SP.sp, fontWeight = FontWeight.SemiBold)
                Text(plugin.description, color = DesignColors.TextSecondary, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }

        // Право распространения и лицензия.
        if (composition.licenseNote.isNotBlank() || composition.licenseRight.isNotBlank()) {
            val isGranted = composition.licenseRight == "granted"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(IconBoxBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${if (isGranted) "✓" else "⚠"} Право распространения и лицензия",
                    color = if (isGranted) DesignColors.AccentGreen else DesignColors.AccentAmber,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = composition.licenseNote.ifBlank { "Статус права: ${composition.licenseRight}" },
                    color = DesignColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // Полный список блокеров.
        if (composition.blockers.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2D1618))
                    .border(1.dp, Color(0xFF5C2327), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "⚠ Блокеры совместимости (${composition.blockers.size})",
                    color = DesignColors.AccentCoral,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                composition.blockers.forEach { blocker ->
                    Text(
                        text = "• $blocker",
                        color = Color(0xFFFCA5A5),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Полный состав адаптеров компонентов.
        if (composition.records.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Состав адаптеров компонентов (${composition.records.size})",
                    color = DesignColors.TextPrimary,
                    fontSize = DesignType.SECTION_SP.sp,
                    fontWeight = FontWeight.SemiBold
                )
                composition.records.forEach { record ->
                    ComponentRecordLine(record = record)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onUpdate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WhiteBtnBg,
                    contentColor = WhiteBtnText
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = WhiteBtnText,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Обновить пакет", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить плагин",
                    tint = DesignColors.AccentCoral
                )
            }
        }
    }
}

@Composable
private fun CatalogDetailSheetContent(
    item: ExtensionCatalogItem,
    onInstall: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SheetHeader(title = item.name, onClose = onClose)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShowcaseIcon42(
                categoryOrName = "${item.category} ${item.name}",
                accentColor = if (item.installable) DesignColors.AccentGreen else DesignColors.TextSecondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                CompositionBadge(
                    supportStatus = item.compatibility,
                    fullSupportAllowed = item.compatibility == "fully-supported"
                )
            }

        }

        HorizontalDivider(thickness = 1.dp, color = BorderSubtle)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Паспорт позиции каталога",
                color = DesignColors.TextPrimary,
                fontSize = DesignType.SECTION_SP.sp,
                fontWeight = FontWeight.SemiBold
            )
            DetailRowMonospace(label = "ID", value = item.id)
            DetailRowMonospace(label = "Продукт-источник", value = item.sourceProduct)
            DetailRowMonospace(label = "Категория", value = item.category)
            DetailRowMonospace(label = "Издатель", value = item.publisher.ifBlank { "не записано" })
            DetailRowMonospace(label = "Источник (URL)", value = item.sourceUrl.ifBlank { "не записано" })
            DetailRowMonospace(label = "Доступность", value = item.availability)
            if (item.installPath.isNotBlank()) {
                DetailRowMonospace(label = "Путь источника", value = item.installPath)
            }
        }

        if (item.blocker.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2B2111))
                    .border(1.dp, Color(0xFF57411D), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "⚠ Статус совместимости и ограничения",
                    color = DesignColors.AccentAmber,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = item.blocker,
                    color = Color(0xFFFDE68A),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        if (item.licenseNote.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(IconBoxBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Право распространения / Лицензия",
                    color = DesignColors.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = item.licenseNote,
                    color = DesignColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }

        // Сценарий проверки при переносе (testScenario); пусто — «не записано».
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Сценарий проверки",
                color = DesignColors.TextPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = item.testScenario.ifBlank { "не записано" },
                color = DesignColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        if (item.installable) {
            Button(
                onClick = onInstall,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WhiteBtnBg,
                    contentColor = WhiteBtnText
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = WhiteBtnText,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Установить из проверенного источника", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text(
                text = "Установка недоступна: позиция каталога является наблюдением источника без проверенного переносимого пакета.",
                color = DesignColors.TextSubtle,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun DetailRowMonospace(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            color = DesignColors.TextSubtle,
            fontSize = 11.5.sp,
            modifier = Modifier.width(132.dp)
        )
        Text(
            text = value,
            color = DesignColors.TextSecondary,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

// ---------------------------------------------------------------------------
// Диалог установки: URL/путь (реальный импорт) или создание локального пакета
// ---------------------------------------------------------------------------

@Composable
private fun AddExtensionDialog(
    initialTab: Int,
    onDismiss: () -> Unit,
    onAddPlugin: (String, String) -> Unit,
    onAddSkill: (String, String, String) -> Unit,
    onAddMcp: (String, String, String) -> Unit
) {
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var pluginSource by remember { mutableStateOf("") }
    var pluginDesc by remember { mutableStateOf("") }
    var skillName by remember { mutableStateOf("") }
    var skillDesc by remember { mutableStateOf("") }
    var skillInstr by remember { mutableStateOf("") }
    var mcpName by remember { mutableStateOf("") }
    var mcpCmd by remember { mutableStateOf("") }
    var mcpArgs by remember { mutableStateOf("") }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = DesignColors.TextPrimary,
        unfocusedBorderColor = BorderSubtle,
        focusedTextColor = DesignColors.TextPrimary,
        unfocusedTextColor = DesignColors.TextPrimary,
        cursorColor = DesignColors.TextPrimary,
        focusedLabelColor = DesignColors.TextSecondary,
        unfocusedLabelColor = DesignColors.TextSubtle
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = CardBg,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .heightIn(max = 560.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Установить расширение",
                        color = DesignColors.TextPrimary,
                        fontSize = DesignType.TITLE_SP.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = DesignColors.TextSecondary)
                    }
                }

                // Segmented-переключатель типа расширения в диалоге.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgCharcoal)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf("Подключение", "Навык", "Плагин").forEachIndexed { idx, label ->
                        val selected = tab == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) ActivePillBg else Color.Transparent)
                                .clickable { tab = idx }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (selected) DesignColors.TextPrimary else DesignColors.TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    }
                }

                when (tab) {
                    0 -> {
                        OutlinedTextField(
                            value = mcpName,
                            onValueChange = { mcpName = it },
                            label = { Text("Имя MCP-сервера") },
                            singleLine = true,
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = mcpCmd,
                            onValueChange = { mcpCmd = it },
                            label = { Text("Команда запуска (node, python…)") },
                            singleLine = true,
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = mcpArgs,
                            onValueChange = { mcpArgs = it },
                            label = { Text("Аргументы (через пробел)") },
                            singleLine = true,
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Инструменты появятся только после живого рукопожатия («Проверить»).",
                            color = DesignColors.TextSubtle,
                            fontSize = 11.sp
                        )
                        Button(
                            onClick = { onAddMcp(mcpName.trim(), mcpCmd.trim(), mcpArgs.trim()) },
                            enabled = mcpName.isNotBlank() && mcpCmd.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WhiteBtnBg,
                                contentColor = WhiteBtnText
                            )
                        ) {
                            Text("Добавить подключение", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    1 -> {
                        OutlinedTextField(
                            value = skillName,
                            onValueChange = { skillName = it },
                            label = { Text("Имя навыка (lowercase-kebab)") },
                            singleLine = true,
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = skillDesc,
                            onValueChange = { skillDesc = it },
                            label = { Text("Описание") },
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = skillInstr,
                            onValueChange = { skillInstr = it },
                            label = { Text("Инструкции SKILL.md") },
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { onAddSkill(skillName.trim(), skillDesc.trim(), skillInstr.trim()) },
                            enabled = skillName.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WhiteBtnBg,
                                contentColor = WhiteBtnText
                            )
                        ) {
                            Text("Создать навык", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    else -> {
                        OutlinedTextField(
                            value = pluginSource,
                            onValueChange = { pluginSource = it },
                            label = { Text("URL или локальный путь пакета") },
                            singleLine = true,
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pluginDesc,
                            onValueChange = { pluginDesc = it },
                            label = { Text("Описание (для создания локального пакета)") },
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "URL — установка с источника; локальное имя — создание нового пакета (без фиктивной установки).",
                            color = DesignColors.TextSubtle,
                            fontSize = 11.sp
                        )
                        Button(
                            onClick = { onAddPlugin(pluginSource.trim(), pluginDesc.trim()) },
                            enabled = pluginSource.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WhiteBtnBg,
                                contentColor = WhiteBtnText
                            )
                        ) {
                            Text("Продолжить", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
