package com.google.antigravity.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Снимок прежнего содержимого локальной сохранённой копии. Версии создаются
 * при редактировании, восстановлении и повторном сохранении того же ответа.
 */
data class ArtifactVersion(
    val id: String,
    val content: String,
    val savedAt: Long = 0L
)

object ArtifactLibrary {
    /** Максимум хранимых версий на документ; старые снимки отбрасываются. */
    const val MAX_VERSIONS = 20

    fun savedMessage(message: ChatMessage): ArtifactDocument? {
        if (message.content.isBlank() || message.isStreaming) return null
        return ArtifactDocument(id = "saved_message_${message.id}",
            title = message.content.lineSequence().firstOrNull { it.isNotBlank() }
                .orEmpty().trim().trimStart('#', ' ', '*', '`').take(100).ifBlank { "Сохранённый ответ" },
            summary = message.content.replace(Regex("\\s+"), " ").take(160), content = message.content)
    }

    /** Переименование локальной копии; содержимое и версии не меняются. */
    fun renamed(document: ArtifactDocument, title: String, now: Long = System.currentTimeMillis()): ArtifactDocument {
        val clean = title.trim()
        if (clean.isBlank()) return document
        return document.copy(title = clean.take(100), updatedAtMs = now)
    }

    /** Закрепление/открепление артефакта (отображается первым в библиотеке). */
    fun pinned(document: ArtifactDocument, pinned: Boolean, now: Long = System.currentTimeMillis()): ArtifactDocument {
        if (document.pinned == pinned) return document
        return document.copy(pinned = pinned, updatedAtMs = now)
    }

    /**
     * Редактирование содержимого: прежний текст сохраняется как версия,
     * сводка пересчитывается. Идентичное содержимое ничего не меняет.
     */
    fun edited(document: ArtifactDocument, newContent: String, now: Long = System.currentTimeMillis()): ArtifactDocument {
        if (newContent == document.content) return document
        return withVersionSnapshot(document, now).copy(
            content = newContent,
            summary = newContent.replace(Regex("\\s+"), " ").take(160),
            updatedAtMs = now
        )
    }

    /**
     * Восстановление версии: текущий текст предварительно сохраняется,
     * сама версия остаётся в истории (восстановление не разрушительно).
     */
    fun restored(document: ArtifactDocument, versionId: String, now: Long = System.currentTimeMillis()): ArtifactDocument? {
        val version = document.versions.find { it.id == versionId } ?: return null
        return edited(document, version.content, now)
    }

    /**
     * Повторное сохранение того же ответа (тот же ID): свежее содержимое
     * становится текущим, прежнее уходит в версии. Дубликат не создаётся.
     */
    fun resaved(existing: ArtifactDocument, fresh: ArtifactDocument, now: Long = System.currentTimeMillis()): ArtifactDocument {
        if (existing.content == fresh.content) return existing
        return withVersionSnapshot(existing, now).copy(
            content = fresh.content, summary = fresh.summary, updatedAtMs = now
        )
    }

    /**
     * Слияние локальных копий с копиями ПК: по каждому ID побеждает более
     * новый `updatedAtMs`; копии, существующие только на одной стороне,
     * сохраняются. Локальный порядок списка не нарушается.
     */
    fun mergeSynced(local: List<ArtifactDocument>, remote: List<ArtifactDocument>): List<ArtifactDocument> {
        val remoteById = remote.associateBy { it.id }
        val mergedLocal = local.map { doc ->
            val r = remoteById[doc.id]
            if (r != null && r.updatedAtMs > doc.updatedAtMs) r else doc
        }
        val localIds = local.map { it.id }.toSet()
        return mergedLocal + remote.filterNot { it.id in localIds }
    }

    private fun withVersionSnapshot(document: ArtifactDocument, now: Long): ArtifactDocument {
        val versions = document.versions
        if (versions.lastOrNull()?.content == document.content) return document
        val snapshot = ArtifactVersion(
            id = "ver_" + java.util.UUID.randomUUID().toString().take(12),
            content = document.content, savedAt = now
        )
        return document.copy(versions = (versions + snapshot).takeLast(MAX_VERSIONS))
    }

    fun encode(documents: List<ArtifactDocument>): String = JSONArray().apply {
        documents.filter { it.id.startsWith("saved_message_") }.distinctBy { it.id }.forEach { d ->
            put(JSONObject().apply {
                put("id", d.id); put("title", d.title); put("summary", d.summary)
                put("content", d.content); put("type", d.type.name); put("updatedAt", d.updatedAt)
                put("updatedAtMs", d.updatedAtMs); put("pinned", d.pinned)
                put("versions", JSONArray().apply {
                    d.versions.takeLast(MAX_VERSIONS).forEach { v -> put(JSONObject().apply {
                        put("id", v.id); put("content", v.content); put("savedAt", v.savedAt)
                    }) }
                })
            })
        }
    }.toString()

    fun decode(text: String): List<ArtifactDocument> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { i -> runCatching {
            val d = array.getJSONObject(i)
            ArtifactDocument(id = d.getString("id"), title = d.getString("title"),
                summary = d.optString("summary"), content = d.getString("content"),
                type = runCatching { ArtifactType.valueOf(d.optString("type")) }.getOrDefault(ArtifactType.MARKDOWN),
                updatedAt = d.optString("updatedAt", "Сегодня"),
                updatedAtMs = d.optLong("updatedAtMs"),
                pinned = d.optBoolean("pinned"),
                versions = d.optJSONArray("versions").orEmpty().let { arr ->
                    (0 until arr.length()).mapNotNull { j -> runCatching {
                        val v = arr.getJSONObject(j)
                        ArtifactVersion(id = v.optString("id").ifBlank { "ver_$j" },
                            content = v.optString("content"), savedAt = v.optLong("savedAt"))
                    }.getOrNull() }
                })
        }.getOrNull() }.filter { it.id.startsWith("saved_message_") }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()
}
