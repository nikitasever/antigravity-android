package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class ExtensionPresentationTest {
    @Test fun failedOrPendingLoadDoesNotClaimNothingIsInstalled() {
        assertEquals("Загрузка списка…", extensionEmptyCopy("Нет плагинов", "", "", true, false, false).title)
        assertEquals("Список пока недоступен", extensionEmptyCopy("Нет плагинов", "", "", false, true, false).title)
        assertEquals("Нет плагинов", extensionEmptyCopy("Нет плагинов", "", "", false, false, false).title)
    }

    @Test fun filteredCachedItemsAreSearchMissesEvenWhenRefreshFailed() {
        assertEquals("Ничего не найдено", extensionEmptyCopy("Нет плагинов", "", "missing", false, true, true).title)
    }

    @Test fun catalogKindsBelongToExactlyTheirSection() {
        listOf("mcp-server", "connector", "app").forEach { assertEquals(0, extensionCatalogTab(it)) }
        listOf("skill", "skill-assets", "command", "agent", "output-style").forEach { assertEquals(1, extensionCatalogTab(it)) }
        listOf("plugin", "catalog-card", "new-package-kind").forEach { assertEquals(2, extensionCatalogTab(it)) }
        assertEquals(0, extensionCatalogTab(" MCP-SERVER "))
    }

    @Test fun transportFailuresDoNotExposeAddressesOrHeaders() {
        val raw = "failed to connect to https://user:password@private.example:8787/path?token=secret Authorization: Bearer abc"
        val copy = extensionErrorCopy(raw)
        assertEquals("Нет подключения к ПК", copy.title)
        listOf("private.example", "secret", "password", "Bearer", "8787").forEach {
            assertFalse(copy.toString().contains(it))
        }
    }

    @Test fun errorRecoveryDistinguishesTimeoutAndAccess() {
        assertEquals("Ответ задерживается", extensionErrorCopy("SocketTimeoutException: timeout").title)
        assertEquals("Нет доступа", extensionErrorCopy("HTTP 403 Forbidden").title)
        assertEquals("Нет доступа", extensionErrorCopy("HTTP 401").title)
        assertEquals("Сервис временно недоступен", extensionErrorCopy("Ошибка HTTP 503").title)
    }

    @Test fun unknownServerBodiesAreNeverEchoed() {
        val copy = extensionErrorCopy("{\"error\":\"sk-secret-key /home/private/file exception\"}")
        assertEquals("Не удалось выполнить действие", copy.title)
        assertFalse(copy.toString().contains("sk-secret-key"))
        assertEquals("Пакет недоступен", extensionErrorCopy("У позиции нет проверенного переносимого источника.").title)
    }
}
