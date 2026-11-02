package com.google.antigravity.network

import com.google.antigravity.model.PcMcpServerItem
import com.google.antigravity.ui.components.effectiveMcpStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA/Security-аудит Этапов 2–7: точечные проверки Android-исправлений
 * Medium #1 (счётчик поколений — модель ниже) и Medium #2 (разбор
 * errorCode/serverInfo, сброс залипшего connected, безопасный текст ошибки).
 */
class ExtensionsSecurityQaTest {

    // --- Medium #2: parseMcpServerList парсит errorCode и serverInfo ---

    @Test
    fun `parseMcpServerList парсит errorCode и serverInfo`() {
        val json = JSONObject(
            """
            {
              "mcpServers": [
                {
                  "id": "mcp_demo",
                  "name": "demo",
                  "transport": "stdio",
                  "command": "node",
                  "args": ["srv.js"],
                  "enabled": true,
                  "status": "failed",
                  "tools": [],
                  "error": "handshake timeout",
                  "errorCode": "handshake_timeout",
                  "probedAt": "2026-11-02T00:00:00Z",
                  "serverInfo": {"name": "qa-server", "version": "1.2.3"}
                },
                {
                  "id": "mcp_plain",
                  "name": "plain",
                  "status": "connected",
                  "tools": ["echo"],
                  "serverInfo": {"name": "", "version": ""}
                }
              ]
            }
            """.trimIndent()
        )
        val items = parseMcpServerList(json)
        assertEquals(2, items.size)

        val demo = items[0]
        assertEquals("handshake_timeout", demo.errorCode)
        assertEquals("qa-server 1.2.3", demo.serverInfo)
        assertEquals("failed", demo.status)

        // Пустой serverInfo не превращается в выдуманную строку.
        assertNull(items[1].serverInfo)
        assertNull(items[1].errorCode)
        assertEquals("connected", items[1].status)
    }

    @Test
    fun `parseMcpServerList без mcpServers возвращает пустой список`() {
        assertTrue(parseMcpServerList(JSONObject("{}")).isEmpty())
    }

    // --- Medium #2: сброс залипшего connected при ошибке ---

    @Test
    fun `demoteUnverifiedConnected сбрасывает connected при err и сохраняет при успехе`() {
        val items = listOf(
            PcMcpServerItem(id = "a", name = "a", status = "connected"),
            PcMcpServerItem(id = "b", name = "b", status = "configured"),
            PcMcpServerItem(id = "c", name = "c", status = "disabled")
        )
        val withErr = demoteUnverifiedConnected(items, "мост недоступен")
        assertEquals("configured", withErr.first { it.id == "a" }.status)
        assertEquals("configured", withErr.first { it.id == "b" }.status)
        assertEquals("disabled", withErr.first { it.id == "c" }.status)

        // Без ошибки подтверждённый connected не трогается.
        val noErr = demoteUnverifiedConnected(items, null)
        assertEquals("connected", noErr.first { it.id == "a" }.status)
    }

    @Test
    fun `safeBridgeErrorText извлекает error из JSON и ограничивает длину`() {
        assertEquals("boom", safeBridgeErrorText("""{"ok":false,"error":"boom"}"""))
        assertEquals("пустое тело ответа", safeBridgeErrorText("   "))
        val long = safeBridgeErrorText("x".repeat(500))
        assertEquals(200, long.length)
        val longErr = safeBridgeErrorText("""{"error":"${"y".repeat(500)}"}""")
        assertEquals(200, longErr.length)
    }

    // --- Medium #2: effectiveMcpStatus в UI (ExtensionsScreen) ---

    @Test
    fun `effectiveMcpStatus сбрасывает залипший connected при ошибке`() {
        assertEquals("configured", effectiveMcpStatus("connected", "boom", null))
        assertEquals("configured", effectiveMcpStatus("connected", "", "handshake_timeout"))
        assertEquals("connected", effectiveMcpStatus("connected", "", null))
        assertEquals("failed", effectiveMcpStatus("failed", "boom", null))
        assertEquals("disabled", effectiveMcpStatus("disabled", "", null))
    }

    // --- Medium #1: счётчик поколений — запоздавший ответ старого refresh
    //     не перезаписывает свежее состояние ---

    @Test
    fun `ExtLoadSequencer отклоняет запоздавший ответ старого refresh`() {
        // Реальный класс из AntigravityViewModel (Medium #1): операция получает
        // номер поколения и применяет состояние только если она всё ещё последняя.
        val seq = com.google.antigravity.model.ExtLoadSequencer()
        val state = mutableListOf<String>()

        val oldRefresh = seq.next()
        val newToggle = seq.next()

        fun applyIfCurrent(generation: Int, value: String): Boolean {
            if (!seq.isCurrent(generation)) return false
            state.add(value)
            return true
        }

        // Старый refresh пришёл последним — его ответ отбрасывается.
        assertFalse(applyIfCurrent(oldRefresh, "stale-refresh"))
        // Новый запрос — текущий, его ответ применяется.
        assertTrue(applyIfCurrent(newToggle, "fresh-toggle"))
        assertEquals(listOf("fresh-toggle"), state)
    }
}
