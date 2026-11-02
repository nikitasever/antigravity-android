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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.google.antigravity.model.ExtensionCatalogItem
import com.google.antigravity.model.InstalledPluginItem
import com.google.antigravity.model.PcMcpServerItem
import com.google.antigravity.model.PcSkillItem
import com.google.antigravity.model.describeMcpStatus
import com.google.antigravity.ui.TwoLineMenuCircleButton

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

private val BgCharcoal = Color(0xFF0E0E10)
private val CardBg = Color(0xFF18181B)
private val BorderSubtle = Color(0xFF27272A)
private val ActivePillBg = Color(0xFF27272A)
private val SearchFieldBg = Color(0xFF141417)
private val IconBoxBg = Color(0xFF121215)

private val WhiteBtnBg = Color(0xFFFFFFFF)
private val WhiteBtnText = Color(0xFF09090B)

private val HeroSkillsBg = Color(0xFF13331C)
private val HeroSkillsBorder = Color(0xFF1F4D2B)
private val HeroPluginsBg = Color(0xFF0D254C)
private val HeroPluginsBorder = Color(0xFF1B3F7A)

private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFA1A1AA)
private val SubtleText = Color(0xFF71717A)

private val AccentGreen = Color(0xFF10B981)
private val AccentBlue = Color(0xFF60A5FA)
private val WarningAmber = Color(0xFFF59E0B)
private val DangerRed = Color(0xFFF87171)

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
    data class Plugin(val pluginId: String) : ExtensionDetailTarget
    data class Catalog(val itemId: String) : ExtensionDetailTarget
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
    onInstallCatalogItem: (ExtensionCatalogItem) -> Unit
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
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isLoading) "Синхронизация с рабочей станцией ПК…" else "Подключения, навыки и плагины",
                    color = if (isLoading) AccentGreen else TextSecondary,
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
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF5C2327), RoundedCornerShape(10.dp))
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
                            color = if (isSelected) TextPrimary else TextSecondary,
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
                    tint = TextSecondary,
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
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
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
            contentColor = TextPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            when (target) {
                is ExtensionDetailTarget.Mcp -> {
                    val server = mcpServers.find { it.id == target.serverId }
                    if (server != null) {
                        McpDetailSheetContent(
                            server = server,
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
                            onInsertIntoChat = {
                                detailTarget = null
                                onInsertSkillIntoChat(skill.id.removePrefix("/"))
                            },
                            onToggle = { onToggleSkill(skill.id) },
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
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
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
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { mcpToolCallServer = null; onDismissToolResult() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = TextSecondary
                            )
                        }
                    }
                    if (liveServer.tools.isEmpty()) {
                        Text(
                            text = "⚠ Список инструментов не получен: выполните «Проверить» (живое MCP-рукопожатие). Инструменты берутся только из живого tools/list.",
                            color = WarningAmber,
                            fontSize = 12.sp
                        )
                    } else {
                        Text(
                            text = "✓ Инструменты из живого tools/list (${liveServer.tools.size}):",
                            color = AccentGreen,
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
                                    contentColor = TextPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = AccentGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = tool,
                                    color = TextPrimary,
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
                            color = AccentBlue,
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
        "fully-supported", "verified", "connected" -> Triple("✓", "Готов", AccentGreen)
        "partial" -> Triple("⚠", "Частично", WarningAmber)
        "blocked", "failed" -> Triple("⚠", "Заблокирован", DangerRed)
        "observation", "not-applicable" -> Triple("•", "Наблюдение", SubtleText)
        else -> if (oauthRequired) Triple("⚠", "Требует OAuth", WarningAmber)
        else Triple("⚠", "Требует проверки", WarningAmber)
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
            color = if (selected) TextPrimary else TextSecondary,
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
            .clip(RoundedCornerShape(10.dp))
            .background(SearchFieldBg)
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Поиск",
            tint = SubtleText,
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
                    color = SubtleText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                cursorBrush = SolidColor(TextPrimary),
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
                    tint = TextSecondary,
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
            color = TextPrimary,
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
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
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
                    color = TextPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
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
                color = SubtleText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Тип",
                color = SubtleText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(52.dp)
            )
            Text(
                text = "Статус",
                color = SubtleText,
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
                    color = if (isDisabled) TextSecondary else TextPrimary,
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
                color = TextSecondary,
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
                        isFailed -> WarningAmber
                        isConnected -> AccentGreen
                        else -> SubtleText
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
                            color = WarningAmber,
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
                            color = AccentGreen,
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
                                    color = TextPrimary,
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
                                    color = TextSecondary,
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
                                color = TextPrimary,
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
                    color = TextPrimary,
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
                color = TextSecondary,
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
        isFailed -> WarningAmber
        isConnected -> AccentGreen
        else -> AccentBlue
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
    accentColor: Color = TextPrimary
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
            .clip(RoundedCornerShape(10.dp))
            .background(IconBoxBg)
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp)),
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
                accentColor = if (skill.enabled) AccentGreen else SubtleText
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = skill.name,
                    color = TextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                Text(
                    text = skill.description.ifBlank { skill.instructionsPreview }.ifBlank { "Навык SKILL.md рабочей станции" },
                    color = TextSecondary,
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
                        color = if (skill.enabled) AccentGreen else SubtleText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "· ПК (SKILL.md)",
                        color = SubtleText,
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
                        color = TextPrimary,
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
                        color = if (skill.enabled) AccentGreen else SubtleText,
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
                accentColor = if (plugin.enabled) AccentBlue else SubtleText
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
                        color = TextPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = "v${plugin.version}",
                        color = SubtleText,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
                Text(
                    text = plugin.description.ifBlank { "Пакет плагина (${plugin.category})" },
                    color = TextSecondary,
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
                        color = SubtleText,
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
                        color = TextPrimary,
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
                            blocked -> DangerRed
                            plugin.enabled -> AccentGreen
                            else -> SubtleText
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
                accentColor = if (item.installable) AccentGreen else TextSecondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.name,
                    color = TextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                Text(
                    text = humanDescription,
                    color = TextSecondary,
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
                        color = SubtleText,
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
                        color = TextPrimary,
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
        "fully-supported", "verified", "connected" -> Triple("✓", "полностью поддержан", AccentGreen)
        "partial" -> Triple("⚠", "частично поддержан", WarningAmber)
        "blocked", "failed" -> Triple("⚠", "заблокирован", DangerRed)
        "observation", "not-applicable" -> Triple("•", "наблюдение", SubtleText)
        else -> Triple("⚠", "требует проверки", WarningAmber)
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
        "verified" -> "✓" to AccentGreen
        "blocked" -> "⚠" to DangerRed
        "not-applicable" -> "•" to SubtleText
        else -> "⚠" to WarningAmber
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
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
        if (record.verification.isNotBlank()) {
            Text(
                text = "Проверка: ${record.verification}",
                color = SubtleText,
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
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = TextSecondary,
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
    onCheck: () -> Unit,
    onLogin: () -> Unit,
    onOpenToolsDialog: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    val isConnected = effectiveMcpStatus(server.status, server.error, server.errorCode).equals("connected", ignoreCase = true)
    val isFailed = server.status.equals("failed", ignoreCase = true) || server.error.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectorIcon32(name = server.name, isConnected = isConnected, isFailed = isFailed)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${if (isConnected) "✓" else if (isFailed) "⚠" else "•"} ${describeMcpStatus(effectiveMcpStatus(server.status, server.error, server.errorCode))}",
                    color = when {
                        isConnected -> AccentGreen
                        isFailed -> DangerRed
                        else -> WarningAmber
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextSecondary)
            }
        }

        HorizontalDivider(thickness = 1.dp, color = BorderSubtle)

        // Переключатель активности сервера.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (server.enabled) "Подключение включено" else "Подключение отключено",
                    color = TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Исполнитель: ПК (pc-bridge) • ${server.transport}",
                    color = TextSecondary,
                    fontSize = 11.5.sp
                )
            }
            Switch(
                checked = server.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = WhiteBtnText,
                    checkedTrackColor = WhiteBtnBg
                )
            )
        }

        // Технические MCP-параметры.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Технические параметры MCP",
                color = TextPrimary,
                fontSize = 13.sp,
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
                color = SubtleText,
                fontSize = 11.sp
            )
        }

        // Полный текст ошибки / блокера (если есть).
        if (server.error.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2D1618))
                    .border(1.dp, Color(0xFF5C2327), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "⚠ Диагностика ошибки подключения",
                    color = DangerRed,
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
                .clip(RoundedCornerShape(10.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (server.tools.isNotEmpty()) {
                    "✓ Доступные инструменты (${server.tools.size})"
                } else {
                    "⚠ Инструменты не получены"
                },
                color = if (server.tools.isNotEmpty()) AccentGreen else WarningAmber,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (server.tools.isNotEmpty()) {
                Text(
                    text = server.tools.joinToString(", "),
                    color = TextSecondary,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    text = "Выполните живое рукопожатие «Проверить» (initialize → notifications/initialized → tools/list).",
                    color = TextSecondary,
                    fontSize = 11.5.sp
                )
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
            Button(
                onClick = onLogin,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActivePillBg,
                    contentColor = TextPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Войти", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = onOpenToolsDialog,
                enabled = server.tools.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActivePillBg,
                    contentColor = TextPrimary
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
                    tint = DangerRed
                )
            }
        }
    }
}

@Composable
private fun SkillDetailSheetContent(
    skill: PcSkillItem,
    onInsertIntoChat: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShowcaseIcon42(
                categoryOrName = "skill ${skill.name}",
                accentColor = if (skill.enabled) AccentGreen else SubtleText
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skill.name,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Вызов в разговоре: /${skill.id.removePrefix("/")}",
                    color = AccentGreen,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextSecondary)
            }
        }

        HorizontalDivider(thickness = 1.dp, color = BorderSubtle)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (skill.enabled) "Навык активен" else "Навык отключён",
                    color = TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Исполнитель: ПК • SKILL.md",
                    color = TextSecondary,
                    fontSize = 11.5.sp
                )
            }
            Switch(
                checked = skill.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = WhiteBtnText,
                    checkedTrackColor = WhiteBtnBg
                )
            )
        }

        if (skill.description.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(IconBoxBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Описание", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(skill.description, color = TextSecondary, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }

        val instructions = skill.instruction.ifBlank { skill.instructionsPreview }
        if (instructions.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(IconBoxBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Инструкции SKILL.md", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = instructions,
                    color = TextSecondary,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }
        }

        if (skill.path.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(IconBoxBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(14.dp)
            ) {
                DetailRowMonospace(label = "Путь пакета", value = skill.path)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onInsertIntoChat,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WhiteBtnBg,
                    contentColor = WhiteBtnText
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Вставить в чат: /${skill.id.removePrefix("/")}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить навык",
                    tint = DangerRed
                )
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShowcaseIcon42(
                categoryOrName = "${plugin.category} ${plugin.name}",
                accentColor = if (plugin.enabled) AccentBlue else SubtleText
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${plugin.name} (v${plugin.version})",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                CompositionBadge(
                    supportStatus = composition.supportStatus,
                    fullSupportAllowed = composition.fullSupportAllowed
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextSecondary)
            }
        }

        HorizontalDivider(thickness = 1.dp, color = BorderSubtle)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (plugin.enabled) "Плагин включён" else "Плагин отключён",
                    color = TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${plugin.category} • Источник: ${plugin.source}",
                    color = TextSecondary,
                    fontSize = 11.5.sp
                )
            }
            Switch(
                checked = plugin.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = WhiteBtnText,
                    checkedTrackColor = WhiteBtnBg
                )
            )
        }

        if (plugin.description.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(IconBoxBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Описание пакета", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(plugin.description, color = TextSecondary, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }

        // Право распространения и лицензия.
        if (composition.licenseNote.isNotBlank() || composition.licenseRight.isNotBlank()) {
            val isGranted = composition.licenseRight == "granted"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(IconBoxBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${if (isGranted) "✓" else "⚠"} Право распространения и лицензия",
                    color = if (isGranted) AccentGreen else WarningAmber,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = composition.licenseNote.ifBlank { "Статус права: ${composition.licenseRight}" },
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // Полный список блокеров.
        if (composition.blockers.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2D1618))
                    .border(1.dp, Color(0xFF5C2327), RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "⚠ Блокеры совместимости (${composition.blockers.size})",
                    color = DangerRed,
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
                    color = TextPrimary,
                    fontSize = 13.sp,
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
                    tint = DangerRed
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShowcaseIcon42(
                categoryOrName = "${item.category} ${item.name}",
                accentColor = if (item.installable) AccentGreen else TextSecondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                CompositionBadge(
                    supportStatus = item.compatibility,
                    fullSupportAllowed = item.compatibility == "fully-supported"
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextSecondary)
            }
        }

        HorizontalDivider(thickness = 1.dp, color = BorderSubtle)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(IconBoxBg)
                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Паспорт позиции каталога",
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            DetailRowMonospace(label = "ID", value = item.id)
            DetailRowMonospace(label = "Продукт-источник", value = item.sourceProduct)
            DetailRowMonospace(label = "Категория", value = item.category)
            DetailRowMonospace(label = "Доступность", value = item.availability)
            if (item.installPath.isNotBlank()) {
                DetailRowMonospace(label = "Путь источника", value = item.installPath)
            }
        }

        if (item.blocker.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2B2111))
                    .border(1.dp, Color(0xFF57411D), RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "⚠ Статус совместимости и ограничения",
                    color = WarningAmber,
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
                    .clip(RoundedCornerShape(10.dp))
                    .background(IconBoxBg)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Право распространения / Лицензия",
                    color = TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = item.licenseNote,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
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
                color = SubtleText,
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
            color = SubtleText,
            fontSize = 11.5.sp,
            modifier = Modifier.width(132.dp)
        )
        Text(
            text = value,
            color = TextSecondary,
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
        focusedBorderColor = TextPrimary,
        unfocusedBorderColor = BorderSubtle,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = TextPrimary,
        focusedLabelColor = TextSecondary,
        unfocusedLabelColor = SubtleText
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = CardBg,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
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
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextSecondary)
                    }
                }

                // Segmented-переключатель типа расширения в диалоге.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BgCharcoal)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
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
                                color = if (selected) TextPrimary else TextSecondary,
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
                            color = SubtleText,
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
                            color = SubtleText,
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
