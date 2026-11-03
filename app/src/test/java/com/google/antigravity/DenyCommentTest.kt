package com.google.antigravity

import com.google.antigravity.model.DenyCommentDialogState
import com.google.antigravity.model.McpToolPermissionEntry
import com.google.antigravity.model.McpToolPermissionsState
import com.google.antigravity.model.denyCommentForRequest
import com.google.antigravity.model.describeDenyComment
import com.google.antigravity.model.describeToolPermission
import com.google.antigravity.model.toolPermissionComment
import com.google.antigravity.network.parseMcpPermissionUpdate
import com.google.antigravity.network.parseMcpToolPermissions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Deny with comment» — запрет с пояснением пользователя (контракт моста,
 * референс Claude): комментарий читается из строк tool_permissions, отсутствие
 * поля — «не записано» и ничего не выдумывается, повторный deny без
 * комментария очищает прежний, комментарий действует и отправляется только с
 * deny, а UI-состояние диалога честно отражает действие кнопки.
 */
class DenyCommentTest {

    // ---------- Парсинг комментария (tool_permissions / set_tool_permission) ----------

    @Test
    fun `парсинг — комментарий к запрету читается из строки tool_permissions`() {
        val reply = parseMcpToolPermissions(
            JSONObject(
                """{"ok":true,"server":"srv","tools":[
                    {"tool":"run_command","permission":"deny","source":"tool",
                     "comment":"Запрещено политикой безопасности проекта"}
                ]}"""
            )
        )
        assertEquals(1, reply.tools.size)
        assertEquals(
            McpToolPermissionEntry(
                tool = "run_command",
                permission = "deny",
                source = "tool",
                comment = "Запрещено политикой безопасности проекта"
            ),
            reply.tools[0]
        )
    }

    @Test
    fun `парсинг — отсутствие комментария не выдумывается`() {
        // Поля comment нет вовсе — пустая строка «не записано», не заглушка.
        val withoutField = parseMcpToolPermissions(
            JSONObject(
                """{"ok":true,"server":"srv","tools":[
                    {"tool":"read_file","permission":"deny","source":"tool"}
                ]}"""
            )
        )
        assertEquals("", withoutField.tools[0].comment)
        // Явный null в JSON — тоже «не записано».
        val withNull = parseMcpToolPermissions(
            JSONObject(
                """{"ok":true,"server":"srv","tools":[
                    {"tool":"read_file","permission":"deny","source":"tool","comment":null}
                ]}"""
            )
        )
        assertEquals("", withNull.tools[0].comment)
    }

    @Test
    fun `set_tool_permission — комментарий подтверждается мостом в ответе`() {
        val reply = parseMcpPermissionUpdate(
            JSONObject(
                """{"ok":true,"server":"srv","tool":"run_command","permission":"deny","comment":"Не запускать без присмотра"}"""
            )
        )
        assertEquals("Не запускать без присмотра", reply.comment)
        // Ответ без поля comment — «не записано», ничего не выдумывается.
        val plain = parseMcpPermissionUpdate(
            JSONObject("""{"ok":true,"server":"srv","tool":"run_command","permission":"deny"}""")
        )
        assertEquals("", plain.comment)
    }

    // ---------- Повторный deny и правило «только при deny» ----------

    @Test
    fun `повторный deny без комментария очищает прежний`() {
        var state = McpToolPermissionsState(
            serverId = "srv",
            tools = listOf(
                McpToolPermissionEntry("run_command", "deny", "tool", "Старый комментарий")
            )
        )
        // Повторный deny без комментария (мост прислал comment = "") — очистка.
        state = state.onToolPermissionChanged("run_command", "deny", source = "tool", comment = "")
        assertEquals("", state.tools[0].comment)
        // Право остаётся deny — очищен только комментарий.
        assertEquals("deny", state.tools[0].permission)
    }

    @Test
    fun `комментарий хранится только при deny`() {
        // В строке состояния: у allow/ask комментарий не применяется.
        assertEquals("", toolPermissionComment("allow", "Пояснение"))
        assertEquals("", toolPermissionComment("ask", "Пояснение"))
        assertEquals("Пояснение", toolPermissionComment("deny", "Пояснение"))
        assertEquals("", toolPermissionComment("deny", ""))
        var state = McpToolPermissionsState(
            serverId = "srv",
            tools = listOf(McpToolPermissionEntry("read_file", "deny", "tool", "Было пояснение"))
        )
        // Смена deny на allow убирает комментарий из строки (он действует только при deny).
        state = state.onToolPermissionChanged("read_file", "allow", comment = "Было пояснение")
        assertEquals("", state.tools[0].comment)
    }

    @Test
    fun `комментарий для моста отправляется только с deny и только непустым`() {
        // Контракт: поле comment уходит только с deny и только непустым.
        assertEquals("Пояснение", denyCommentForRequest("deny", "Пояснение"))
        // Повторный deny без комментария — поле не отправляется (мост очищает).
        assertNull(denyCommentForRequest("deny", ""))
        assertNull(denyCommentForRequest("deny", "   "))
        // У allow/ask комментарий не отправляется вовсе.
        assertNull(denyCommentForRequest("allow", "Пояснение"))
        assertNull(denyCommentForRequest("ask", "Пояснение"))
    }

    // ---------- Честные подписи ----------

    @Test
    fun `подписи — deny с комментарием и не записано без выдумывания`() {
        assertEquals("Запретить с комментарием", describeToolPermission("deny", "Пояснение"))
        assertEquals("Запретить", describeToolPermission("deny", ""))
        // У allow/ask комментарий не приписывается.
        assertEquals("Всегда разрешать", describeToolPermission("allow", "Пояснение"))
        // Пустой комментарий — «не записано», не выдуманная строка.
        assertEquals("не записано", describeDenyComment(""))
        assertEquals("Пояснение", describeDenyComment("Пояснение"))
    }

    // ---------- UI-состояние диалога «Запретить с комментарием» ----------

    @Test
    fun `UI-состояние диалога — подпись кнопки честно отражает действие`() {
        // Пустой комментарий допустим — это «просто Запретить».
        val empty = DenyCommentDialogState(tool = "run_command", comment = "")
        assertEquals("Запретить", empty.confirmLabel)
        // Непустой комментарий — «Запретить с комментарием».
        val commented = empty.copy(comment = "Только под присмотром")
        assertEquals("Запретить с комментарием", commented.confirmLabel)
        // Пробелы — не комментарий: действие остаётся «просто Запретить».
        assertEquals("Запретить", empty.copy(comment = "   ").confirmLabel)
    }

    @Test
    fun `UI-состояние диалога — цель запрета и смена текста`() {
        val toolTarget = DenyCommentDialogState(tool = "run_command")
        assertTrue(!toolTarget.isAllTools)
        assertEquals("run_command", toolTarget.tool)
        // Все инструменты — отдельная цель (set_all_tools_permissions).
        val allTarget = DenyCommentDialogState(isAllTools = true)
        assertTrue(allTarget.isAllTools)
        assertEquals("", allTarget.tool)
        // Ввод текста меняет состояние (copy), цель сохраняется.
        val typed = toolTarget.copy(comment = "Нельзя в этом проекте")
        assertEquals("run_command", typed.tool)
        assertEquals("Нельзя в этом проекте", typed.comment)
        assertEquals("Запретить с комментарием", typed.confirmLabel)
    }

    // ---------- Комментарий при смене всех прав сервера ----------

    @Test
    fun `все инструменты — deny с комментарием доходит до строк уровня сервера`() {
        var state = McpToolPermissionsState(
            serverId = "srv",
            defaultPermission = "ask",
            tools = listOf(
                McpToolPermissionEntry("read_file", "allow", "tool", "свой запрет"),
                McpToolPermissionEntry("write_file", "ask", "server")
            )
        )
        state = state.onAllToolsPermissionChanged(
            "deny", source = "server", comment = "Пауза на время аудита"
        )
        assertEquals("deny", state.defaultPermission)
        // Собственное правило инструмента сохраняется вместе со своим комментарием.
        assertEquals("свой запрет", state.tools[0].comment)
        // Строки уровня сервера получают новый комментарий.
        assertEquals("Пауза на время аудита", state.tools[1].comment)
    }
}
