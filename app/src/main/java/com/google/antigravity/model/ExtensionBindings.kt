package com.google.antigravity.model

import org.json.JSONArray

/**
 * Этап 8 (EXTENSIONS_INTEGRATION_PLAN.md): сохранность пользовательских
 * состояний расширений при перезапуске Android-клиента.
 *
 * Кодек выбранных привязок (выбранные MCP-подключения разговора) без
 * Android-зависимостей: состояние кодируется в JSON-массив строк, хранится в
 * SharedPreferences (`antigravity_prefs_v4`, ключ `mcp_connection_bindings`)
 * и перечитывается при старте. На стороне ПК то же состояние продублировано
 * пользовательским состоянием реестра (uploads/extensions-user-state.json) и
 * переживает перезапуск pc-bridge. Секреты в привязки не входят — только
 * идентификаторы подключений.
 */
object ExtensionBindings {
    /** Кодирует набор идентификаторов подключений в стабильный JSON-массив. */
    fun encode(connections: Set<String>): String = JSONArray().apply {
        connections.filter { it.isNotBlank() }.sorted().forEach { put(it) }
    }.toString()

    /** Декодирует состояние; повреждённый ввод — пустой набор без исключений. */
    fun decode(text: String?): Set<String> = runCatching {
        val arr = JSONArray(text ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            (arr.opt(i) as? String)?.takeIf { it.isNotBlank() }
        }.toSet()
    }.getOrDefault(emptySet())
}
