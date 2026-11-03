package com.google.antigravity

import com.google.antigravity.model.McpToolPermissionEntry
import com.google.antigravity.model.McpToolPermissionsState
import com.google.antigravity.model.SkillDetailInfo
import com.google.antigravity.model.SkillDetailState
import com.google.antigravity.model.describeToolPermission
import com.google.antigravity.network.parseExtensionCatalog
import com.google.antigravity.network.parseMcpPermissionUpdate
import com.google.antigravity.network.parseMcpToolPermissions
import com.google.antigravity.network.parseSkillDetail
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Как у них» (поверхности Claude из декомпиляции): права инструментов MCP
 * (tool_permissions / set_tool_permission / set_all_tools_permissions) и деталь
 * навыка (skills detail, полный текст SKILL.md). Парсеры и переходы состояния:
 * данные только из ответов моста, отсутствующие поля — «не записано», отказ
 * сервера виден как ошибка и никогда не превращается в выдуманный успех.
 */
class McpToolPermissionsTest {

    // ---------- Парсинг ответов моста ----------

    @Test
    fun `tool_permissions — разбор полного ответа с уровнями источников`() {
        val reply = parseMcpToolPermissions(
            JSONObject(
                """{"ok":true,"server":"srv","defaultPermission":"ask","tools":[
                    {"tool":"read_file","permission":"allow","source":"tool"},
                    {"tool":"write_file","permission":"ask","source":"server"},
                    {"tool":"run_command","permission":"deny","source":"policy"}
                ]}"""
            )
        )
        assertEquals("srv", reply.server)
        assertEquals("ask", reply.defaultPermission)
        assertEquals(3, reply.tools.size)
        assertEquals(McpToolPermissionEntry("read_file", "allow", "tool"), reply.tools[0])
        assertEquals(McpToolPermissionEntry("write_file", "ask", "server"), reply.tools[1])
        assertEquals(McpToolPermissionEntry("run_command", "deny", "policy"), reply.tools[2])
    }

    @Test
    fun `tool_permissions — пустой список и отсутствие defaultPermission не выдумываются`() {
        val reply = parseMcpToolPermissions(JSONObject("""{"ok":true,"server":"srv","tools":[]}"""))
        assertTrue(reply.tools.isEmpty())
        // Поле defaultPermission отсутствует — null (глобальная политика), не значение.
        assertNull(reply.defaultPermission)
    }

    @Test
    fun `tool_permissions — запись без имени инструмента сохраняется без выдумывания`() {
        val reply = parseMcpToolPermissions(
            JSONObject("""{"ok":true,"server":"srv","tools":[{"permission":"allow","source":"tool"}]}""")
        )
        assertEquals(1, reply.tools.size)
        // Нет поля tool — пустая строка, имя не выдумывается.
        assertEquals("", reply.tools[0].tool)
        assertEquals("allow", reply.tools[0].permission)
    }

    @Test
    fun `tool_permissions — null defaultPermission в JSON остаётся отсутствием`() {
        val reply = parseMcpToolPermissions(
            JSONObject("""{"ok":true,"server":"srv","defaultPermission":null,"tools":[]}""")
        )
        assertNull(reply.defaultPermission)
    }

    @Test
    fun `set_tool_permission — разбор ответа смены права`() {
        val reply = parseMcpPermissionUpdate(
            JSONObject("""{"ok":true,"server":"srv","tool":"read_file","permission":"deny"}""")
        )
        assertEquals("srv", reply.server)
        assertEquals("read_file", reply.tool)
        assertEquals("deny", reply.permission)
    }

    @Test
    fun `set_all_tools_permissions — разбор ответа без поля tool`() {
        val reply = parseMcpPermissionUpdate(
            JSONObject("""{"ok":true,"server":"srv","permission":"allow"}""")
        )
        assertEquals("srv", reply.server)
        assertEquals("", reply.tool)
        assertEquals("allow", reply.permission)
    }

    @Test
    fun `skill detail — полный текст content и enabled`() {
        val info = parseSkillDetail(
            JSONObject(
                """{"ok":true,"skill":{"id":"kotlin-check","name":"kotlin-check","description":"Проверка Kotlin","enabled":true,"source":"~/.gemini/config/skills","path":"/skills/kotlin-check","content":"---\nname: kotlin-check\n---\nИнструкции"}}"""
            )
        )
        assertEquals("kotlin-check", info.id)
        assertEquals("Проверка Kotlin", info.description)
        assertEquals(true, info.enabled)
        assertEquals("~/.gemini/config/skills", info.source)
        assertTrue(info.content.startsWith("---"))
    }

    @Test
    fun `skill detail — отсутствующие поля — честные фолбэки без выдумывания`() {
        val info = parseSkillDetail(JSONObject("""{"ok":true,"skill":{"name":"x"}}"""))
        assertEquals("x", info.id)
        assertEquals("", info.description)
        // enabled отсутствует — null («не записано»), true не выдумывается.
        assertNull(info.enabled)
        assertEquals("", info.source)
        assertEquals("", info.path)
        assertEquals("", info.content)
    }

    @Test
    fun `skill detail — нет объекта skill — пустая деталь без выдумок`() {
        val info = parseSkillDetail(JSONObject("""{"ok":true}"""))
        assertEquals(SkillDetailInfo(), info)
    }

    // ---------- Метки прав ----------

    @Test
    fun `describeToolPermission — метки как у них и не записано`() {
        assertEquals("Всегда разрешать", describeToolPermission("allow"))
        assertEquals("Спрашивать", describeToolPermission("ask"))
        assertEquals("Запретить", describeToolPermission("deny"))
        assertEquals("не записано", describeToolPermission(""))
    }

    @Test
    fun `describeToolPermission — неизвестное значение показывается как есть`() {
        assertEquals("custom", describeToolPermission("custom"))
    }

    // ---------- Переходы состояния (как выполняет AntigravityViewModel) ----------

    @Test
    fun `переход — загрузка и успешный ответ обновляют состояние прав`() {
        var state = McpToolPermissionsState(serverId = "srv")
        state = state.onLoading()
        assertTrue(state.loading)
        assertFalse(state.loaded)
        state = state.onLoaded(
            defaultPermission = "ask",
            tools = listOf(McpToolPermissionEntry("read_file", "allow", "tool"))
        )
        assertFalse(state.loading)
        assertTrue(state.loaded)
        assertEquals("ask", state.defaultPermission)
        assertEquals(1, state.tools.size)
    }

    @Test
    fun `переход — смена права инструмента обновляет только его строку`() {
        var state = McpToolPermissionsState(
            serverId = "srv",
            tools = listOf(
                McpToolPermissionEntry("read_file", "allow", "tool"),
                McpToolPermissionEntry("write_file", "ask", "tool")
            )
        )
        state = state.onToolPermissionChanged("write_file", "deny")
        assertEquals("allow", state.tools[0].permission)
        assertEquals("deny", state.tools[1].permission)
        // Источник права становится per-tool (как у них).
        assertEquals("tool", state.tools[1].source)
    }

    @Test
    fun `переход — права всех инструментов меняют серверный уровень, per-tool сохраняется`() {
        var state = McpToolPermissionsState(
            serverId = "srv",
            defaultPermission = "ask",
            tools = listOf(
                McpToolPermissionEntry("read_file", "allow", "tool"),
                McpToolPermissionEntry("write_file", "ask", "server")
            )
        )
        state = state.onAllToolsPermissionChanged("deny")
        assertEquals("deny", state.defaultPermission)
        // Собственное право инструмента приоритетнее уровня «все инструменты».
        assertEquals("allow", state.tools[0].permission)
        assertEquals("deny", state.tools[1].permission)
        assertEquals("server", state.tools[1].source)
    }

    @Test
    fun `отказ сервера — ошибка видима текстом, успех не выдумывается`() {
        var state = McpToolPermissionsState(
            serverId = "srv",
            loaded = true,
            tools = listOf(McpToolPermissionEntry("read_file", "allow", "tool"))
        )
        state = state.onLoading()
        state = state.onError("Мост ПК вернул HTTP 500")
        assertFalse(state.loading)
        assertEquals("Мост ПК вернул HTTP 500", state.error)
        // Прежние данные не превращаются в «успех» — ошибка остаётся видимой.
        assertTrue(state.loaded)
        assertEquals(1, state.tools.size)
    }

    @Test
    fun `переход — деталь навыка — загрузка, успех и честная ошибка`() {
        var state = SkillDetailState(skillId = "kotlin-check")
        state = state.onLoading()
        assertTrue(state.loading)
        state = state.onLoaded(SkillDetailInfo(id = "kotlin-check", content = "текст"))
        assertFalse(state.loading)
        assertEquals("текст", state.detail?.content)
        state = state.onLoading()
        state = state.onError("SKILL.md не получен")
        assertEquals("SKILL.md не получен", state.error)
        // Ошибка не подменяется данными: деталь остаётся прежней, флаг ошибки виден.
        assertTrue(state.error.isNotBlank())
    }

    // ---------- Каталог: новые поля позиции ----------

    @Test
    fun `каталог — publisher sourceUrl и testScenario разбираются из позиции`() {
        val items = parseExtensionCatalog(
            JSONObject(
                """{"matrix":{"positions":[{
                    "id":"pos-1","name":"Позиция","sourceProduct":"claude.ai (web)",
                    "componentTypes":["connector"],"availability":"unknown",
                    "supportStatus":"requires-check","publisher":"Anthropic",
                    "sourceUrl":"https://example.test/p","testScenario":"Проверить вход"
                }]}}"""
            )
        )
        assertEquals(1, items.size)
        assertEquals("Anthropic", items[0].publisher)
        assertEquals("https://example.test/p", items[0].sourceUrl)
        assertEquals("Проверить вход", items[0].testScenario)
    }

    @Test
    fun `каталог — без новых полей остаётся пусто, без выдуманных значений`() {
        val items = parseExtensionCatalog(
            JSONObject(
                """{"matrix":{"positions":[{
                    "id":"pos-1","name":"Позиция","sourceProduct":"claude.ai (web)",
                    "componentTypes":["connector"],"availability":"unknown",
                    "supportStatus":"requires-check"
                }]}}"""
            )
        )
        assertEquals("", items[0].publisher)
        assertEquals("", items[0].sourceUrl)
        assertEquals("", items[0].testScenario)
    }
}
