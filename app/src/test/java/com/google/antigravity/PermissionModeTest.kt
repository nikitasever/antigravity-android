package com.google.antigravity

import com.google.antigravity.model.McpPermissionMode
import com.google.antigravity.model.McpPermissionModeState
import com.google.antigravity.network.parseMcpPermissionMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Режимы разрешений MCP «как у них» (Claude: default/plan/auto/acceptEdits/
 * dontAsk/bypassPermissions): разбор ответов моста, подписи всех шести
 * значений и переходы состояния. Состав режимов — как в Claude; точная
 * семантика их Auto-режима (модельная оценка рисков и prompt injection) не
 * воспроизводится — «требует проверки»; наше «Автоматически» — эвристика.
 */
class PermissionModeTest {

    // ---------- Разбор ответов моста ----------

    @Test
    fun `парсинг — режим и допустимые значения читаются`() {
        val reply = parseMcpPermissionMode(
            JSONObject(
                """{"ok":true,"mode":"auto","validModes":
                   ["default","plan","auto","acceptEdits","dontAsk","bypassPermissions"]}"""
            )
        )
        assertTrue(reply.ok)
        assertEquals("auto", reply.mode)
        assertEquals(6, reply.validModes.size)
        assertEquals("acceptEdits", reply.validModes[3])
        assertNull(reply.error)
    }

    @Test
    fun `отказ неизвестного режима — ok false и текст ошибки без маскировки`() {
        val reply = parseMcpPermissionMode(
            JSONObject("""{"ok":false,"error":"Неизвестный режим: turbo"}""")
        )
        assertFalse(reply.ok)
        assertEquals("", reply.mode)
        assertEquals("Неизвестный режим: turbo", reply.error)
    }

    @Test
    fun `разбор — отсутствующие поля не выдумываются`() {
        val reply = parseMcpPermissionMode(JSONObject("""{"ok":true}"""))
        assertTrue(reply.ok)
        // mode и validModes не записаны — пустые значения, ничего не выдумывается.
        assertEquals("", reply.mode)
        assertTrue(reply.validModes.isEmpty())
        assertNull(reply.error)
        // ok без поля — не успех.
        assertFalse(parseMcpPermissionMode(JSONObject("""{"mode":"plan"}""")).ok)
    }

    @Test
    fun `неизвестное значение моста — режим не выдумывается`() {
        assertEquals(McpPermissionMode.AUTO, McpPermissionMode.fromWire("auto"))
        assertEquals(McpPermissionMode.ACCEPT_EDITS, McpPermissionMode.fromWire("acceptEdits"))
        assertEquals(McpPermissionMode.BYPASS_PERMISSIONS, McpPermissionMode.fromWire("bypassPermissions"))
        assertNull(McpPermissionMode.fromWire("turbo"))
        assertNull(McpPermissionMode.fromWire(""))
    }

    // ---------- Подписи всех шести значений ----------

    @Test
    fun `подписи всех шести режимов — честные ярлыки по заданию`() {
        assertEquals("Обычный (по правам инструментов)", McpPermissionMode.DEFAULT.label)
        assertEquals("План (без изменений и выполнения)", McpPermissionMode.PLAN.label)
        assertEquals(
            "Автоматически (наша эвристическая оценка риска — не их Auto-режим)",
            McpPermissionMode.AUTO.label
        )
        assertEquals("Принимать правки (правки файлов автоматически)", McpPermissionMode.ACCEPT_EDITS.label)
        assertEquals("Не спрашивать", McpPermissionMode.DONT_ASK.label)
        assertEquals("Обход разрешений", McpPermissionMode.BYPASS_PERMISSIONS.label)
        assertEquals("default", McpPermissionMode.DEFAULT.wire)
        assertEquals("plan", McpPermissionMode.PLAN.wire)
        assertEquals("auto", McpPermissionMode.AUTO.wire)
        assertEquals("acceptEdits", McpPermissionMode.ACCEPT_EDITS.wire)
        assertEquals("dontAsk", McpPermissionMode.DONT_ASK.wire)
        assertEquals("bypassPermissions", McpPermissionMode.BYPASS_PERMISSIONS.wire)
    }

    @Test
    fun `режим Автоматически — label содержит честную границу про эвристику`() {
        // Подпись заявляет границу: это НАША эвристика, а не их Auto-режим.
        assertTrue(McpPermissionMode.AUTO.label.contains("эвристическая оценка риска"))
        assertTrue(McpPermissionMode.AUTO.label.contains("не их Auto-режим"))
        // Подсказка фиксирует непроизведённую модельную проверку честно.
        assertTrue(McpPermissionMode.AUTO.hint.contains("Оценка риска — эвристика по именам инструментов и аргументам"))
        assertTrue(McpPermissionMode.AUTO.hint.contains("требует проверки"))
    }

    @Test
    fun `обход разрешений — deny прав никогда не обходится в подсказке`() {
        assertTrue(McpPermissionMode.BYPASS_PERMISSIONS.hint.contains("deny прав никогда не обходится"))
    }

    // ---------- Переходы состояния (как выполняет AntigravityViewModel) ----------

    @Test
    fun `переход — загрузка и успешный ответ обновляют состояние`() {
        var state = McpPermissionModeState()
        state = state.onLoading()
        assertTrue(state.loading)
        assertFalse(state.loaded)
        state = state.onLoaded(
            mode = McpPermissionMode.AUTO,
            validModes = listOf("default", "plan", "auto")
        )
        assertFalse(state.loading)
        assertTrue(state.loaded)
        assertEquals(McpPermissionMode.AUTO, state.mode)
        assertEquals(3, state.validModes.size)
    }

    @Test
    fun `отказ сервера — ошибка видима текстом и режим не выдумывается`() {
        var state = McpPermissionModeState(loaded = true, mode = McpPermissionMode.DEFAULT)
        state = state.onLoading()
        state = state.onError("Неизвестный режим: turbo")
        assertFalse(state.loading)
        assertEquals("Неизвестный режим: turbo", state.error)
        // Прежний режим остаётся фактом, ошибка не подменяется успехом.
        assertEquals(McpPermissionMode.DEFAULT, state.mode)
    }

    @Test
    fun `смена режима — состояние обновляется и ошибка сбрасывается`() {
        var state = McpPermissionModeState(loaded = true, mode = McpPermissionMode.DEFAULT)
        state = state.onError("Мост ПК вернул HTTP 500")
        state = state.onModeChanged(McpPermissionMode.PLAN)
        assertEquals(McpPermissionMode.PLAN, state.mode)
        assertEquals("", state.error)
        assertFalse(state.loading)
    }
}
