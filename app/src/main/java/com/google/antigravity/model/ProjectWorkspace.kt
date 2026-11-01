package com.google.antigravity.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Документ проекта (project knowledge): имя и содержимое добавляются в контекст
 * всех чатов, связанных с проектом. Хранится на телефоне.
 */
data class ProjectDocument(
    val id: String,
    val name: String,
    val content: String,
    val addedAt: Long = 0L
)

/**
 * Локальная рабочая область проекта (по образцу Projects ChatGPT/Claude):
 * инструкции проекта, документы в контексте и связанные чаты.
 *
 * Инструкции и документы входят в контекст запросов модели только для чатов,
 * связанных с этим проектом. Состояние сохраняется на телефоне и переживает
 * перезапуск; обновление списка проектов с ПК его не изменяет.
 */
data class ProjectWorkspace(
    val id: String,
    val name: String,
    val instructions: String = "",
    val documents: List<ProjectDocument> = emptyList(),
    val linkedChatIds: List<String> = emptyList(),
    /** Время последнего изменения; метка для слияния с ПК. */
    val updatedAtMs: Long = 0L
) {
    fun withInstructions(text: String, now: Long = System.currentTimeMillis()): ProjectWorkspace =
        if (instructions == text.trim()) this else copy(instructions = text.trim(), updatedAtMs = now)

    fun withDocument(document: ProjectDocument, now: Long = System.currentTimeMillis()): ProjectWorkspace =
        if (documents.any { it == document }) this
        else copy(documents = documents.filterNot { it.id == document.id } + document, updatedAtMs = now)

    fun withoutDocument(documentId: String, now: Long = System.currentTimeMillis()): ProjectWorkspace =
        if (documents.none { it.id == documentId }) this
        else copy(documents = documents.filterNot { it.id == documentId }, updatedAtMs = now)

    fun withLinkedChat(chatId: String, now: Long = System.currentTimeMillis()): ProjectWorkspace =
        if (chatId.isBlank() || chatId in linkedChatIds) this
        else copy(linkedChatIds = linkedChatIds + chatId, updatedAtMs = now)

    fun withoutLinkedChat(chatId: String, now: Long = System.currentTimeMillis()): ProjectWorkspace =
        if (chatId !in linkedChatIds) this
        else copy(linkedChatIds = linkedChatIds.filterNot { it == chatId }, updatedAtMs = now)
}

object ProjectWorkspaces {
    /** Стабильный идентификатор рабочей области для проекта ПК. */
    fun idFor(projectName: String): String = "project_" + projectName.trim().replace(Regex("\\s+"), "_")

    fun encode(workspaces: Map<String, ProjectWorkspace>): String = JSONArray().apply {
        workspaces.values.sortedBy { it.id }.forEach { w ->
            put(JSONObject().apply {
                put("id", w.id)
                put("name", w.name)
                put("instructions", w.instructions)
                put("updatedAtMs", w.updatedAtMs)
                put("linkedChatIds", JSONArray(w.linkedChatIds))
                put("documents", JSONArray().apply {
                    w.documents.forEach { d -> put(JSONObject().apply {
                        put("id", d.id); put("name", d.name); put("content", d.content)
                        put("addedAt", d.addedAt)
                    }) }
                })
            })
        }
    }.toString()

    fun decode(text: String): Map<String, ProjectWorkspace> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { i -> runCatching {
            val w = array.getJSONObject(i)
            val id = w.getString("id")
            if (id.isBlank()) return@runCatching null
            ProjectWorkspace(
                id = id,
                name = w.optString("name").ifBlank { id },
                instructions = w.optString("instructions"),
                updatedAtMs = w.optLong("updatedAtMs"),
                linkedChatIds = w.optJSONArray("linkedChatIds").orEmpty().let { arr ->
                    (0 until arr.length()).mapNotNull { j -> arr.optString(j).takeIf { it.isNotBlank() } }
                },
                documents = w.optJSONArray("documents").orEmpty().let { arr ->
                    (0 until arr.length()).mapNotNull { j -> runCatching {
                        val d = arr.getJSONObject(j)
                        val docId = d.optString("id").ifBlank { return@runCatching null }
                        ProjectDocument(id = docId, name = d.optString("name").ifBlank { docId },
                            content = d.optString("content"), addedAt = d.optLong("addedAt"))
                    }.getOrNull() }
                }
            )
        }.getOrNull() }
            .filterNotNull()
            .associateBy { it.id }
    }.getOrDefault(emptyMap())

    /**
     * Слияние рабочих областей с ПК: по каждому ID побеждает более новый
     * `updatedAtMs`; записи, существующие только на одной стороне, сохраняются.
     */
    fun mergeSynced(
        local: Map<String, ProjectWorkspace>,
        remote: Map<String, ProjectWorkspace>
    ): Map<String, ProjectWorkspace> {
        val ids = local.keys + remote.keys
        return ids.associateWith { id ->
            val l = local[id]
            val r = remote[id]
            when {
                l == null -> r!!
                r == null -> l
                r.updatedAtMs > l.updatedAtMs -> r
                else -> l
            }
        }
    }

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

    /**
     * Контекст проекта для запроса модели: инструкции проекта и документы.
     * Возвращается пустая строка, если проекта нет или контекст пуст —
     * запрос остаётся без изменений.
     */
    fun contextMarkdown(workspace: ProjectWorkspace?): String {
        if (workspace == null) return ""
        val parts = mutableListOf<String>()
        if (workspace.instructions.isNotBlank()) {
            parts += "**Инструкции проекта «${workspace.name}»:**\n${workspace.instructions.trim()}"
        }
        workspace.documents.filter { it.content.isNotBlank() }.forEach { doc ->
            parts += "**Документ проекта «${doc.name}»:**\n```\n${doc.content.trim()}\n```"
        }
        return parts.joinToString("\n\n")
    }
}
