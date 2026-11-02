package com.google.antigravity.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
// Этап 6 (EXTENSIONS_INTEGRATION_PLAN.md): единый интерфейс расширений.
// Вход «Расширения»: Подключения / Навыки / Плагины; внутри — Мои / Каталог,
// поиск, категории, источник и совместимость. Технические MCP-параметры — в
// подробностях подключения. Карточка показывает состав, доступные инструменты,
// права, исполнитель, зависимости, источник оплаты/лимитов ТОЛЬКО если они
// проверены, и последнюю успешную проверку. Неподключённый сервис не показывается
// работающим.
// ---------------------------------------------------------------------------

private val CardBg = Color(0xFF171717)
private val SubtleText = Color(0xFF8E8E8E)
private val AccentGreen = Color(0xFF10A37F)
private val AccentBlue = Color(0xFF60A5FA)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Шапка.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TwoLineMenuCircleButton(onClick = onOpenDrawer)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Расширения", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (isLoading) "Синхронизация с рабочей станцией ПК…" else "Подключения, навыки и плагины",
                    color = if (isLoading) AccentGreen else SubtleText,
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить", tint = Color(0xFFECECEC))
            }
            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Установить", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (!errorText.isNullOrBlank()) {
            androidx.compose.material3.Surface(
                color = Color(0xFF2D1618),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(errorText, color = Color(0xFFFCA5A5), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismissError, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color(0xFFFCA5A5))
                    }
                }
            }
        }

        // Верхние вкладки: Подключения / Навыки / Плагины.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExtensionsTab.entries.forEachIndexed { index, tab ->
                FilterChip(
                    selected = activeTab == index,
                    onClick = { activeTab = index },
                    label = { Text(tab.title) }
                )
            }
        }

        // Мои / Каталог + поиск (категории, источник и совместимость — на карточках).
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = scopeMine, onClick = { scopeMine = true }, label = { Text(ExtensionsScope.MINE.title) })
            FilterChip(selected = !scopeMine, onClick = { scopeMine = false }, label = { Text(ExtensionsScope.CATALOG.title) })
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                placeholder = { Text("Поиск", fontSize = 13.sp, color = SubtleText) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = Color(0xFF2A2A2A),
                    cursorColor = AccentGreen
                )
            )
        }

        val query = searchQuery.trim().lowercase()
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (scopeMine) {
                when (activeTab) {
                    0 -> {
                        val filtered = mcpServers.filter { matchesQuery(query, it.name, it.commandOrUrl, describeMcpStatus(it.status)) }
                        if (filtered.isEmpty()) {
                            item {
                                EmptyExtensionsCard(
                                    "Подключения не добавлены",
                                    "Нажмите «Установить», чтобы зарегистрировать MCP-сервер (agy mcp add). Инструменты появятся после живого рукопожатия «Проверить»."
                                )
                            }
                        } else {
                            items(filtered, key = { "mcp_${it.id}" }) { m ->
                                McpConnectionCard(
                                    server = m,
                                    onCheck = { onCheckMcp(m.id) },
                                    onLogin = { onLoginMcp(m.id) },
                                    onCallTool = { mcpToolCallServer = m },
                                    onToggle = { onToggleMcp(m.id) },
                                    onDelete = { onDeleteMcp(m.id) }
                                )
                            }
                        }
                    }
                    1 -> {
                        val filtered = skills.filter { matchesQuery(query, it.name, it.description, it.path) }
                        if (filtered.isEmpty()) {
                            item {
                                EmptyExtensionsCard(
                                    "Навыки не найдены",
                                    "Навыки — SKILL.md в каталогах навыков рабочей станции. «Установить» создаёт навык; выбор навыка работает в разговоре командой /имя."
                                )
                            }
                        } else {
                            items(filtered, key = { "skill_${it.id}" }) { s ->
                                SkillCard(
                                    skill = s,
                                    onInsertIntoChat = { onInsertSkillIntoChat(s.id.removePrefix("/")) },
                                    onToggle = { onToggleSkill(s.id) },
                                    onDelete = { onDeleteSkill(s.id) }
                                )
                            }
                        }
                    }
                    2 -> {
                        val filtered = plugins.filter { matchesQuery(query, it.name, it.description, it.source, it.category) }
                        if (filtered.isEmpty()) {
                            item {
                                EmptyExtensionsCard(
                                    "Плагины не установлены",
                                    "«Установить» принимает URL или локальный путь (реальный импорт); состав и право распространения проверяются для каждого пакета."
                                )
                            }
                        } else {
                            items(filtered, key = { "plugin_${it.id}" }) { p ->
                                PluginCard(
                                    plugin = p,
                                    onToggle = { onTogglePlugin(p.id) },
                                    onUpdate = { onUpdatePlugin(p.id) },
                                    onDelete = { onDeletePlugin(p.id) }
                                )
                            }
                        }
                    }
                }
            } else {
                // Каталог: наблюдения источников. Неподключённое не показывается работающим.
                val tabCategories = when (activeTab) {
                    0 -> setOf("mcp-server", "connector", "app")
                    1 -> setOf("skill", "skill-assets", "command", "agent", "output-style")
                    else -> null // «Плагины» — все прочие категории
                }
                val filtered = catalog.filter { item ->
                    val catOk = tabCategories == null || tabCategories.contains(item.category) ||
                        (activeTab == 2 && !setOf("mcp-server", "connector", "app", "skill", "skill-assets", "command", "agent", "output-style").contains(item.category))
                    catOk && matchesQuery(query, item.name, item.sourceProduct, item.category, item.compatibility)
                }
                if (filtered.isEmpty()) {
                    item {
                        EmptyExtensionsCard(
                            "В каталоге ничего не найдено",
                            "Каталог строится по инвентарю источников (Claude/ChatGPT/Codex/Antigravity). Уточните поиск или обновите данные."
                        )
                    }
                } else {
                    items(filtered, key = { "cat_${it.id}" }) { item ->
                        CatalogCard(item = item, onInstall = { onInstallCatalogItem(item) })
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
        Dialog(onDismissRequest = { mcpToolCallServer = null; onDismissToolResult() }) {
            androidx.compose.material3.Surface(
                color = CardBg,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
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
                            "Инструменты ${server.name}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { mcpToolCallServer = null; onDismissToolResult() }) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color(0xFFA0A0A0))
                        }
                    }
                    if (server.tools.isEmpty()) {
                        Text(
                            "Список инструментов не получен: выполните «Проверить» (живое MCP-рукопожатие). Инструменты берутся только из живого tools/list.",
                            color = Color(0xFFF59E0B),
                            fontSize = 12.sp
                        )
                    } else {
                        Text("Инструменты из живого tools/list:", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        server.tools.forEach { tool ->
                            Button(
                                onClick = { onCallMcpTool(server.id, tool) },
                                enabled = !isMcpToolCallRunning,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                            ) {
                                Text(tool, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    mcpToolResult?.let { result ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Результат:", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(result, color = Color(0xFFE5E5E5), fontSize = 12.sp)
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

// ---------------------------------------------------------------------------
// Карточка подключения (MCP). Технические параметры — в подробностях; статус —
// только проверенный (connected после рукопожатия).
// ---------------------------------------------------------------------------

@Composable
private fun McpConnectionCard(
    server: PcMcpServerItem,
    onCheck: () -> Unit,
    onLogin: () -> Unit,
    onCallTool: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    var detailsExpanded by remember(server.id) { mutableStateOf(false) }
    androidx.compose.material3.Surface(
        color = CardBg,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = server.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = describeMcpStatus(server.status),
                    color = when (server.status) {
                        "connected" -> AccentGreen
                        "failed" -> DangerRed
                        "disabled" -> Color(0xFF6B7280)
                        else -> Color(0xFFF59E0B)
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            Text("Исполнитель: ПК (pc-bridge) • ${server.transport}", color = AccentBlue, fontSize = 11.sp)
            if (server.tools.isNotEmpty()) {
                Text(
                    "Инструменты (${server.tools.size}): ${server.tools.take(4).joinToString(", ")}",
                    color = AccentGreen,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text("Инструменты: не получены (нет живого tools/list)", color = Color(0xFFF59E0B), fontSize = 11.sp)
            }
            if (server.error.isNotBlank()) {
                Text(server.error, color = DangerRed, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (server.probedAt.isNotBlank()) {
                Text("Последняя успешная проверка: ${server.probedAt}", color = Color(0xFF6B7280), fontSize = 10.sp, maxLines = 1)
            }
            // Технические MCP-параметры — в подробностях подключения.
            TextButton(onClick = { detailsExpanded = !detailsExpanded }, contentPadding = PaddingValues(0.dp)) {
                Icon(
                    if (detailsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (detailsExpanded) "Скрыть подробности" else "Подробности подключения",
                    tint = AccentBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Технические параметры", color = AccentBlue, fontSize = 12.sp)
            }
            if (detailsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Транспорт: ${server.transport}", color = Color(0xFFA0A0A0), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Область: ${server.scope}", color = Color(0xFFA0A0A0), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "Команда: ${server.command} ${server.args.joinToString(" ")}".trim(),
                        color = Color(0xFFA0A0A0),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("Права: инструменты ПК по списку разрешений", color = Color(0xFF6B7280), fontSize = 10.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onCheck) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Проверить", color = AccentBlue, fontSize = 12.sp)
                }
                // «Войти»: пробой с параметрами авторизации из конфига. Полный
                // OAuth-цикл (discovery/PKCE/refresh/revoke) — за границей
                // Этапов 3–5; сервер, требующий OAuth-входа, честно вернёт
                // ошибку авторизации в статусе.
                TextButton(onClick = onLogin) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Войти", color = AccentBlue, fontSize = 12.sp)
                }
                TextButton(onClick = onCallTool, enabled = server.tools.isNotEmpty()) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (server.tools.isNotEmpty()) AccentGreen else Color(0xFF4B5563),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Вызвать", color = if (server.tools.isNotEmpty()) AccentGreen else Color(0xFF4B5563), fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(if (server.enabled) "Включён" else "Отключён", color = SubtleText, fontSize = 11.sp)
                Switch(checked = server.enabled, onCheckedChange = { onToggle() })
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить подключение", tint = DangerRed)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Карточка навыка.
// ---------------------------------------------------------------------------

@Composable
private fun SkillCard(
    skill: PcSkillItem,
    onInsertIntoChat: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    androidx.compose.material3.Surface(
        color = CardBg,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(skill.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Исполнитель: ПК • SKILL.md", color = AccentGreen, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                skill.description.ifBlank { skill.instructionsPreview },
                color = Color(0xFFA0A0A0),
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onInsertIntoChat) {
                    Text("Вставить в чат: /${skill.id.removePrefix("/")}", color = AccentBlue, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(if (skill.enabled) "Включён" else "Отключён", color = SubtleText, fontSize = 11.sp)
                Switch(checked = skill.enabled, onCheckedChange = { onToggle() })
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить навык", tint = DangerRed)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Карточка плагина: состав, статус поддержки, право распространения.
// ---------------------------------------------------------------------------

@Composable
private fun PluginCard(
    plugin: InstalledPluginItem,
    onToggle: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit
) {
    var detailsExpanded by remember(plugin.id) { mutableStateOf(false) }
    val composition = plugin.composition
    androidx.compose.material3.Surface(
        color = CardBg,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plugin.name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("v${plugin.version}", color = AccentGreen, fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
            Text("${plugin.category} • ${plugin.source}", color = AccentBlue, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(plugin.description, color = Color(0xFFA0A0A0), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)

            // Состав и совместимость (Этап 5): проверенный путь либо блокер.
            CompositionBadge(composition.supportStatus, composition.fullSupportAllowed)
            if (composition.records.isNotEmpty()) {
                TextButton(onClick = { detailsExpanded = !detailsExpanded }, contentPadding = PaddingValues(0.dp)) {
                    Icon(
                        if (detailsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (detailsExpanded) "Скрыть состав" else "Показать состав",
                        tint = AccentBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Состав (${composition.records.size})", color = AccentBlue, fontSize = 12.sp)
                }
                if (detailsExpanded) {
                    composition.records.forEach { record ->
                        ComponentRecordLine(record)
                    }
                }
            }
            if (composition.licenseNote.isNotBlank()) {
                Text(
                    "Право распространения: ${composition.licenseNote}",
                    color = if (composition.licenseRight == "granted") AccentGreen else Color(0xFFF59E0B),
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            composition.blockers.forEach { blocker ->
                Text(blocker, color = DangerRed, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onUpdate) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Обновить", color = AccentBlue, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(if (plugin.enabled) "Включён" else "Отключён", color = SubtleText, fontSize = 11.sp)
                Switch(checked = plugin.enabled, onCheckedChange = { onToggle() })
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить плагин", tint = DangerRed)
                }
            }
        }
    }
}

@Composable
private fun CompositionBadge(supportStatus: String, fullSupportAllowed: Boolean) {
    val (label, color) = when (supportStatus) {
        "fully-supported" -> ("полностью поддержан" to AccentGreen)
        "partial" -> ("частично поддержан" to Color(0xFFF59E0B))
        "blocked" -> ("заблокирован" to DangerRed)
        "observation" -> ("наблюдение (не исполняется)" to Color(0xFF6B7280))
        else -> ("требует проверки" to Color(0xFFF59E0B))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Check, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (fullSupportAllowed) "$label ✓" else label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ComponentRecordLine(record: ComponentAdapterRecord) {
    val statusColor = when (record.status) {
        "verified" -> AccentGreen
        "blocked" -> DangerRed
        "not-applicable" -> Color(0xFF6B7280)
        else -> Color(0xFFF59E0B)
    }
    Column(modifier = Modifier.padding(start = 8.dp)) {
        Text(
            "${record.componentType} — ${record.strategy} — ${record.status}",
            color = statusColor,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (record.blocker.isNotBlank()) {
            Text(record.blocker, color = Color(0xFFA0A0A0), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------
// Карточка каталога: источник и совместимость; неподключённое не показывается
// работающим, установка — только из проверенного локального источника.
// ---------------------------------------------------------------------------

@Composable
private fun CatalogCard(item: ExtensionCatalogItem, onInstall: () -> Unit) {
    androidx.compose.material3.Surface(
        color = CardBg,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Источник: ${item.sourceProduct}", color = AccentBlue, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Категория: ${item.category} • Доступность: ${item.availability}", color = SubtleText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CompositionBadge(item.compatibility, fullSupportAllowed = false)
            if (item.blocker.isNotBlank()) {
                Text(item.blocker, color = Color(0xFFF59E0B), fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (item.licenseNote.isNotBlank()) {
                Text("Лицензия: ${item.licenseNote}", color = SubtleText, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.installable) {
                    Button(
                        onClick = onInstall,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Установить", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text(
                        "Установка недоступна: позиция каталога без проверенного переносимого источника",
                        color = Color(0xFF6B7280),
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyExtensionsCard(title: String, subtitle: String) {
    androidx.compose.material3.Surface(
        color = CardBg,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF9E9E9E), fontSize = 12.5.sp)
        }
    }
}

// ---------------------------------------------------------------------------
// Диалог установки: URL/путь (реальный импорт) или создание локального пакета.
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

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            color = CardBg,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Установить расширение", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color(0xFFA0A0A0))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Подключение", fontSize = 12.sp) })
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Навык", fontSize = 12.sp) })
                    FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("Плагин", fontSize = 12.sp) })
                }
                when (tab) {
                    0 -> {
                        OutlinedTextField(
                            value = mcpName,
                            onValueChange = { mcpName = it },
                            label = { Text("Имя MCP-сервера") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = mcpCmd,
                            onValueChange = { mcpCmd = it },
                            label = { Text("Команда запуска (node, python…)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = mcpArgs,
                            onValueChange = { mcpArgs = it },
                            label = { Text("Аргументы (через пробел)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Инструменты появятся только после живого рукопожатия («Проверить»).", color = SubtleText, fontSize = 11.sp)
                        Button(
                            onClick = { onAddMcp(mcpName.trim(), mcpCmd.trim(), mcpArgs.trim()) },
                            enabled = mcpName.isNotBlank() && mcpCmd.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) { Text("Добавить подключение") }
                    }
                    1 -> {
                        OutlinedTextField(
                            value = skillName,
                            onValueChange = { skillName = it },
                            label = { Text("Имя навыка (lowercase-kebab)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = skillDesc,
                            onValueChange = { skillDesc = it },
                            label = { Text("Описание") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = skillInstr,
                            onValueChange = { skillInstr = it },
                            label = { Text("Инструкции SKILL.md") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { onAddSkill(skillName.trim(), skillDesc.trim(), skillInstr.trim()) },
                            enabled = skillName.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) { Text("Создать навык") }
                    }
                    else -> {
                        OutlinedTextField(
                            value = pluginSource,
                            onValueChange = { pluginSource = it },
                            label = { Text("URL или локальный путь пакета") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pluginDesc,
                            onValueChange = { pluginDesc = it },
                            label = { Text("Описание (для создания локального пакета)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "URL — установка с источника; локальное имя — создание нового пакета (без фиктивной установки).",
                            color = SubtleText,
                            fontSize = 11.sp
                        )
                        Button(
                            onClick = { onAddPlugin(pluginSource.trim(), pluginDesc.trim()) },
                            enabled = pluginSource.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) { Text("Продолжить") }
                    }
                }
            }
        }
    }
}
