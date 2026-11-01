package com.google.antigravity.model

import org.json.JSONObject

/** Presentation preferences belong to this device; remote transcripts stay intact. */
data class ChatLibraryPreferences(
    val title: String = "",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val lastOpenedAt: Long = 0L,
    /** Время последнего изменения названия/закрепления/архива; метка для слияния с ПК. */
    val updatedAtMs: Long = 0L
)

object ChatLibrary {
    fun encode(preferences: Map<String, ChatLibraryPreferences>): String = JSONObject().apply {
        preferences.forEach { (id, p) -> put(id, JSONObject().apply {
            put("title", p.title); put("pinned", p.pinned); put("archived", p.archived)
            put("lastOpenedAt", p.lastOpenedAt); put("updatedAtMs", p.updatedAtMs)
        }) }
    }.toString()

    fun decode(text: String): Map<String, ChatLibraryPreferences> = runCatching {
        val json = JSONObject(text)
        json.keys().asSequence().mapNotNull { id -> json.optJSONObject(id)?.let { p ->
            id to ChatLibraryPreferences(p.optString("title"), p.optBoolean("pinned"),
                p.optBoolean("archived"), p.optLong("lastOpenedAt"), p.optLong("updatedAtMs"))
        } }.toMap()
    }.getOrDefault(emptyMap())

    /**
     * Слияние локальных настроек с настройками ПК: содержимое (название,
     * закрепление, архив) побеждает по `updatedAtMs`, `lastOpenedAt` берётся
     * как максимум. Записи, существующие только на одной стороне, сохраняются.
     */
    fun mergeSynced(
        local: Map<String, ChatLibraryPreferences>,
        remote: Map<String, ChatLibraryPreferences>
    ): Map<String, ChatLibraryPreferences> {
        val ids = local.keys + remote.keys
        return ids.associateWith { id ->
            val l = local[id]
            val r = remote[id]
            when {
                l == null -> r!!
                r == null -> l
                r.updatedAtMs > l.updatedAtMs ->
                    r.copy(lastOpenedAt = maxOf(l.lastOpenedAt, r.lastOpenedAt))
                else -> l.copy(lastOpenedAt = maxOf(l.lastOpenedAt, r.lastOpenedAt))
            }
        }
    }

    fun visible(
        sessions: List<RemoteSessionItem>, preferences: Map<String, ChatLibraryPreferences>,
        query: String, archived: Boolean, contentMatches: Set<String> = emptySet()
    ): List<RemoteSessionItem> {
        val search = query.trim()
        return sessions.distinctBy { it.id }.map { session ->
            val p = preferences[session.id] ?: ChatLibraryPreferences()
            if (p.title.isBlank()) session else session.copy(title = p.title)
        }.filter { session ->
            (preferences[session.id]?.archived == true) == archived &&
                (search.isBlank() || session.title.contains(search, true) ||
                    session.host.contains(search, true) || session.id in contentMatches)
        }.sortedWith(compareByDescending<RemoteSessionItem> { preferences[it.id]?.pinned == true }
            .thenByDescending { preferences[it.id]?.lastOpenedAt?.takeIf { at -> at > 0 } ?: it.updatedAtMs })
    }

    fun search(messages: Map<String, List<ChatMessage>>, query: String): Set<String> {
        val search = query.trim()
        if (search.isEmpty()) return emptySet()
        return messages.filterValues { history -> history.any { message ->
            message.content.contains(search, true) || message.attachments.any { it.name.contains(search, true) }
        } }.keys
    }
}
