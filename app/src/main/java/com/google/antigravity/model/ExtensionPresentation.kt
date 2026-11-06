package com.google.antigravity.model

import java.util.Locale

/** Mirrors the three sections of ExtensionsScreen; unknown package kinds belong to plugins. */
fun extensionCatalogTab(category: String): Int = when (category.trim().lowercase(Locale.ROOT)) {
    "mcp-server", "connector", "app" -> 0
    "skill", "skill-assets", "command", "agent", "output-style" -> 1
    else -> 2
}

data class ExtensionErrorCopy(val title: String, val detail: String)

fun extensionEmptyCopy(defaultTitle: String, defaultDetail: String, query: String,
    loading: Boolean, failed: Boolean, hasItems: Boolean): ExtensionErrorCopy = when {
    !hasItems && loading -> ExtensionErrorCopy("Загрузка списка…", "Сохранённые элементы появятся здесь после загрузки.")
    !hasItems && failed -> ExtensionErrorCopy("Список пока недоступен", "Проверьте подключение и обновите список.")
    query.isNotBlank() -> ExtensionErrorCopy("Ничего не найдено", "Попробуйте другое название или очистите поиск.")
    else -> ExtensionErrorCopy(defaultTitle, defaultDetail)
}

/** Error strings may contain URLs, headers, tokens or server internals. Never echo them in UI. */
fun extensionErrorCopy(raw: String): ExtensionErrorCopy {
    val text = raw.lowercase(Locale.ROOT)
    return when {
        "нет проверенного переносимого источника" in text || "нет источника url" in text ->
            ExtensionErrorCopy("Пакет недоступен", "Для установки или обновления нужен проверенный источник пакета.")
        Regex("\\b(401|403)\\b").containsMatchIn(text) || "unauthorized" in text || "forbidden" in text ->
            ExtensionErrorCopy("Нет доступа", "Проверьте вход и разрешения подключения.")
        Regex("\\b50[0234]\\b").containsMatchIn(text) ->
            ExtensionErrorCopy("Сервис временно недоступен", "Попробуйте обновить список позже.")
        "timeout" in text || "timed out" in text || "время ожидания" in text ->
            ExtensionErrorCopy("Ответ задерживается", "Не удалось дождаться ответа. Проверьте соединение и обновите список.")
        listOf("failed to connect", "connection refused", "unable to resolve host", "unknownhost",
            "no route to host", "network is unreachable", "мост пк недоступен", "socket", "connectexception")
            .any { it in text } ->
            ExtensionErrorCopy("Нет подключения к ПК", "Проверьте, что компьютер и мост включены.")
        "https" in text && ("небезопас" in text || "требуется" in text) ->
            ExtensionErrorCopy("Не удалось открыть вход", "Для входа нужен защищённый HTTPS-адрес подключения.")
        else -> ExtensionErrorCopy("Не удалось выполнить действие", "Проверьте подключение и настройки расширения.")
    }
}
