package com.google.antigravity

import com.google.antigravity.model.McpToolPermissionScope
import com.google.antigravity.model.McpToolScopeContext
import com.google.antigravity.model.McpToolScopeRequest
import com.google.antigravity.model.PcMcpServerItem
import com.google.antigravity.model.describeMcpPermissionSource
import com.google.antigravity.model.mcpPermissionSourcePriority
import com.google.antigravity.model.mcpServerOrigin
import com.google.antigravity.model.resolveMcpToolScope
import com.google.antigravity.network.parseMcpPermissionUpdate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скоупы прав инструментов MCP «как у них» (Claude: «Always allow for this
 * project/session/task/website»): выбор скоупа, честные отказы до отправки
 * (website без origin, session/task без scopeId), подписи и приоритет
 * источников. Семантика — наша честная модель по мотивам Claude; точная
 * семантика референса не подтверждена («требует проверки»).
 */
class McpToolScopesTest {

    private val fullContext = McpToolScopeContext(
        sessionId = "conv-42",
        taskId = "task-7",
        serverOrigin = "https://api.example.com:8443"
    )

    // ---------- Выбор скоупа ----------

    @Test
    fun `выбор скоупа — все четыре варианта готовят запрос`() {
        val project = resolveMcpToolScope(McpToolPermissionScope.PROJECT, fullContext)
        assertTrue(project is McpToolScopeRequest.Ready)
        project as McpToolScopeRequest.Ready
        assertEquals("project", project.scope)
        assertNull(project.scopeId)
        assertNull(project.origin)

        val session = resolveMcpToolScope(McpToolPermissionScope.SESSION, fullContext)
        assertTrue(session is McpToolScopeRequest.Ready)
        assertEquals("session", (session as McpToolScopeRequest.Ready).scope)
        assertEquals("conv-42", session.scopeId)

        val task = resolveMcpToolScope(McpToolPermissionScope.TASK, fullContext)
        assertTrue(task is McpToolScopeRequest.Ready)
        assertEquals("task", (task as McpToolScopeRequest.Ready).scope)
        assertEquals("task-7", task.scopeId)

        val website = resolveMcpToolScope(McpToolPermissionScope.WEBSITE, fullContext)
        assertTrue(website is McpToolScopeRequest.Ready)
        assertEquals("website", (website as McpToolScopeRequest.Ready).scope)
        assertEquals("https://api.example.com:8443", website.origin)
    }

    @Test
    fun `website без origin — честный отказ до отправки`() {
        val request = resolveMcpToolScope(
            McpToolPermissionScope.WEBSITE,
            McpToolScopeContext(sessionId = "conv-42", taskId = "task-7")
        )
        assertTrue(request is McpToolScopeRequest.Refused)
        request as McpToolScopeRequest.Refused
        assertTrue(request.message.contains("только для HTTP-подключений"))
        assertTrue(request.message.contains("stdio"))
    }

    @Test
    fun `session без sessionId — отказ до отправки`() {
        val request = resolveMcpToolScope(
            McpToolPermissionScope.SESSION,
            McpToolScopeContext(taskId = "task-7")
        )
        assertTrue(request is McpToolScopeRequest.Refused)
        assertTrue((request as McpToolScopeRequest.Refused).message.contains("sessionId"))
    }

    @Test
    fun `task без taskId — честное нет активной задачи`() {
        val request = resolveMcpToolScope(
            McpToolPermissionScope.TASK,
            McpToolScopeContext(sessionId = "conv-42")
        )
        assertTrue(request is McpToolScopeRequest.Refused)
        assertTrue((request as McpToolScopeRequest.Refused).message.contains("нет активной задачи"))
    }

    // ---------- Подписи и приоритеты ----------

    @Test
    fun `подписи скоупов — честные ярлыки и значения полей`() {
        assertEquals("Для этого проекта (сохраняется на ПК)", McpToolPermissionScope.PROJECT.label)
        assertEquals("Для этой сессии (до перезапуска моста)", McpToolPermissionScope.SESSION.label)
        assertEquals("Для этой задачи (до перезапуска моста)", McpToolPermissionScope.TASK.label)
        assertEquals("Для этого сайта (только HTTP-подключения)", McpToolPermissionScope.WEBSITE.label)
        assertEquals("project", McpToolPermissionScope.PROJECT.wire)
        assertEquals("session", McpToolPermissionScope.SESSION.wire)
        assertEquals("task", McpToolPermissionScope.TASK.wire)
        assertEquals("website", McpToolPermissionScope.WEBSITE.wire)
        // Источник строки tool_permissions: project — per-tool, остальные — скоупы.
        assertEquals("tool", McpToolPermissionScope.PROJECT.rowSource)
        assertEquals("session", McpToolPermissionScope.SESSION.rowSource)
        assertEquals("task", McpToolPermissionScope.TASK.rowSource)
        assertEquals("website", McpToolPermissionScope.WEBSITE.rowSource)
    }

    @Test
    fun `подписи скоупа сайта — честная граница про HTTP-подключения`() {
        assertTrue(McpToolPermissionScope.WEBSITE.hint.contains("только для HTTP-подключений"))
        assertTrue(McpToolPermissionScope.WEBSITE.hint.contains("stdio"))
    }

    @Test
    fun `приоритет источников — task выше session выше tool website server policy`() {
        val order = listOf("task", "session", "tool", "website", "server", "policy")
            .map { mcpPermissionSourcePriority(it) }
        assertEquals(order, order.sortedDescending())
        assertTrue(mcpPermissionSourcePriority("task") > mcpPermissionSourcePriority("session"))
        assertTrue(mcpPermissionSourcePriority("session") > mcpPermissionSourcePriority("tool"))
        assertTrue(mcpPermissionSourcePriority("tool") > mcpPermissionSourcePriority("website"))
        assertTrue(mcpPermissionSourcePriority("website") > mcpPermissionSourcePriority("server"))
        assertTrue(mcpPermissionSourcePriority("server") > mcpPermissionSourcePriority("policy"))
        // Неизвестный источник ниже всех известных, без выдумывания.
        assertTrue(mcpPermissionSourcePriority("policy") > mcpPermissionSourcePriority(""))
    }

    @Test
    fun `подписи источников — скоупы и не записано`() {
        assertEquals("для этой задачи", describeMcpPermissionSource("task"))
        assertEquals("для этой сессии", describeMcpPermissionSource("session"))
        assertEquals("для этого проекта", describeMcpPermissionSource("tool"))
        assertEquals("для этого сайта", describeMcpPermissionSource("website"))
        assertEquals("все инструменты сервера", describeMcpPermissionSource("server"))
        assertEquals("глобальная политика", describeMcpPermissionSource("policy"))
        assertEquals("не записано", describeMcpPermissionSource(""))
        assertEquals("custom", describeMcpPermissionSource("custom"))
    }

    // ---------- Контекст: origin и разбор ответа ----------

    @Test
    fun `origin HTTP-подключения — stdio и пустой URL без origin`() {
        val http = PcMcpServerItem(
            id = "h", name = "h", transport = "http",
            command = "https://api.example.com:8443/mcp"
        )
        assertEquals("https://api.example.com:8443", mcpServerOrigin(http))
        val stdio = PcMcpServerItem(id = "s", name = "s", command = "node", args = listOf("server.js"))
        assertEquals("", mcpServerOrigin(stdio))
    }

    @Test
    fun `set_tool_permission — scope читается из ответа и без поля не записано`() {
        val reply = parseMcpPermissionUpdate(
            JSONObject("""{"ok":true,"server":"srv","tool":"read_file","permission":"allow","scope":"session"}""")
        )
        assertEquals("session", reply.scope)
        val legacy = parseMcpPermissionUpdate(
            JSONObject("""{"ok":true,"server":"srv","tool":"read_file","permission":"allow"}""")
        )
        assertEquals("", legacy.scope)
    }
}
