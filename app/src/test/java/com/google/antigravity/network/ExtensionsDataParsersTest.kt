package com.google.antigravity.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Этап 6 (EXTENSIONS_INTEGRATION_PLAN.md): парсинг состава плагинов и каталога
 * расширений из реального JSON моста. Статусы не должны противоречить
 * реальному исполнению: «полностью поддержан» только при проверенном составе и
 * праве; каталог — наблюдения, не работающие сервисы.
 */
class ExtensionsDataParsersTest {

    @Test
    fun `parseComposition разбирает состав статусы блокеры и право распространения`() {
        val json = JSONObject(
            """
            {
              "supportStatus": "partial",
              "fullSupportAllowed": false,
              "records": [
                {"componentType": "hook", "strategy": "own-executor", "status": "verified", "verification": "hook-engine"},
                {"componentType": "lsp-server", "strategy": "preserve", "status": "blocked", "blocker": "lspServers не переносится"}
              ],
              "license": {"rightToRedistribute": "granted", "note": "собственный код проекта"},
              "blockers": [{"reason": "состав пуст: только plugin.json"}, {"reason": ""}]
            }
            """.trimIndent()
        )
        val composition = parseComposition(json)
        assertEquals("partial", composition.supportStatus)
        assertFalse(composition.fullSupportAllowed)
        assertEquals(2, composition.records.size)
        val hook = composition.records.first { it.componentType == "hook" }
        assertEquals("own-executor", hook.strategy)
        assertEquals("verified", hook.status)
        val lsp = composition.records.first { it.componentType == "lsp-server" }
        assertEquals("blocked", lsp.status)
        assertNotNull(lsp.blocker)
        assertEquals("granted", composition.licenseRight)
        assertEquals("собственный код проекта", composition.licenseNote)
        // Пустые причины блокеров отфильтровываются.
        assertEquals(listOf("состав пуст: только plugin.json"), composition.blockers)
    }

    @Test
    fun `parseComposition без composition честно возвращает unknown`() {
        val composition = parseComposition(null)
        assertEquals("unknown", composition.supportStatus)
        assertFalse(composition.fullSupportAllowed)
        assertTrue(composition.records.isEmpty())
        assertEquals("unknown", composition.licenseRight)
    }

    @Test
    fun `parseExtensionCatalog разбирает позиции источник категорию и совместимость`() {
        val json = JSONObject(
            """
            {
              "ok": true,
              "matrix": {
                "positions": [
                  {
                    "id": "local-mcp-ag-workstation-info",
                    "name": "MCP ag-workstation-info",
                    "sourceProduct": "Antigravity / Gemini CLI (локальная установка)",
                    "availability": "installed",
                    "componentTypes": ["mcp-server"],
                    "importPath": "C:\\Users\\test\\.gemini\\config\\mcp_config.json",
                    "supportStatus": "fully-supported",
                    "licenseCheck": {"rightToRedistribute": "granted", "note": "собственный код проекта"},
                    "blockers": []
                  },
                  {
                    "id": "claude-web-card-figma",
                    "name": "Figma",
                    "sourceProduct": "claude.ai (web)",
                    "availability": "visible",
                    "componentTypes": ["catalog-card"],
                    "importPath": "n/a",
                    "supportStatus": "observation",
                    "licenseCheck": {"rightToRedistribute": "unknown", "note": "лицензия не зафиксирована"},
                    "blockers": [{"reason": "облако Anthropic"}, {"reason": "не подключено"}]
                  }
                ]
              }
            }
            """.trimIndent()
        )
        val catalog = parseExtensionCatalog(json)
        assertEquals(2, catalog.size)

        val local = catalog.first { it.id == "local-mcp-ag-workstation-info" }
        assertEquals("mcp-server", local.category)
        assertEquals("Antigravity / Gemini CLI (локальная установка)", local.sourceProduct)
        assertEquals("fully-supported", local.compatibility)
        assertTrue("локальная установленная позиция с путём устанавливаема", local.installable)
        assertEquals("собственный код проекта", local.licenseNote)

        val cloud = catalog.first { it.id == "claude-web-card-figma" }
        assertEquals("catalog-card", cloud.category)
        assertEquals("claude.ai (web)", cloud.sourceProduct)
        assertEquals("observation", cloud.compatibility)
        assertFalse("облачная карточка не устанавливается", cloud.installable)
        assertEquals("облако Anthropic; не подключено", cloud.blocker)
    }

    @Test
    fun `неподключённый источник без пути не помечается устанавливаемым`() {
        val json = JSONObject(
            """
            {
              "matrix": {
                "positions": [
                  {
                    "id": "imported-without-path",
                    "name": "Импортировано без пути",
                    "sourceProduct": "Antigravity local",
                    "availability": "imported",
                    "componentTypes": ["plugin"],
                    "importPath": "",
                    "supportStatus": "blocked",
                    "blockers": [{"reason": "нет пути"}]
                  }
                ]
              }
            }
            """.trimIndent()
        )
        val item = parseExtensionCatalog(json).single()
        assertFalse("без importPath установка недоступна", item.installable)
        assertEquals("заблокирована", "blocked", item.compatibility)
    }
}
