package com.google.antigravity.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Local routing checkpoints, never prompts, tokens, permissions or cached task results. */
internal class SwarmRunJournal(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val origin: SwarmRunOrigin, val createdAt: Long)

    fun load(): List<SwarmRunOrigin> = synchronized(lock) { read().map { it.origin } }

    fun remember(origin: SwarmRunOrigin) = synchronized(lock) {
        val entries = read().toMutableList()
        val existing = entries.indexOfFirst { it.origin.requestId == origin.requestId }
        if (existing >= 0) {
            val saved = entries[existing]
            require(saved.origin.conversationId == origin.conversationId && saved.origin.bridgeUrl == origin.bridgeUrl)
            if (saved.origin.cancellationRequested) origin.requestCancellation()
            entries[existing] = Entry(origin, saved.createdAt)
        } else {
            if (entries.size >= MAX_ENTRIES) {
                val removable = entries.indexOfFirst { !it.origin.cancellationRequested }
                if (removable < 0) throw IOException("Pending subagent cancellations fill the journal")
                entries.removeAt(removable)
            }
            entries.add(Entry(origin, now()))
        }
        write(entries)
    }

    /** Unknown/blank notification IDs cannot create a routing record. */
    fun requestCancellation(requestId: String): Boolean = synchronized(lock) {
        if (requestId.isBlank()) return@synchronized false
        val entries = read()
        val entry = entries.firstOrNull { it.origin.requestId == requestId } ?: return@synchronized false
        entry.origin.requestCancellation()
        write(entries)
        true
    }

    private fun read(): List<Entry> = runCatching {
        if (!file.isFile || file.length() > 262144) return@runCatching emptyList()
        val root = JSONObject(file.readText())
        if (root.getInt("version") != 1) return@runCatching emptyList()
        val array = root.getJSONArray("runs")
        require(array.length() <= MAX_ENTRIES)
        val timestamp = now()
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val created = item.getLong("createdAt")
                require(created >= timestamp - RETENTION_MS && created <= timestamp + 300000)
                val origin = SwarmRunOrigin(item.getString("requestId"), item.getString("conversationId"), item.getString("bridgeUrl"))
                require(origin.requestId.length <= 128 && origin.conversationId.length <= 256 && origin.bridgeUrl.length <= 8192)
                if (item.getBoolean("cancelRequested")) origin.requestCancellation()
                Entry(origin, created)
            }.getOrNull()
        }.distinctBy { it.origin.requestId }
    }.getOrDefault(emptyList())

    private fun write(entries: List<Entry>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        val root = JSONObject().put("version", 1).put("runs", JSONArray().apply {
            entries.forEach { (origin, createdAt) -> put(JSONObject()
                .put("requestId", origin.requestId).put("conversationId", origin.conversationId)
                .put("bridgeUrl", origin.bridgeUrl).put("createdAt", createdAt)
                .put("cancelRequested", origin.cancellationRequested)) }
        })
        temporary.outputStream().use { output ->
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private val lock = Any() // Receiver and ViewModel use separate instances in the same app process.
        private const val MAX_ENTRIES = 32
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        const val FILE_NAME = "subagent_runs.json"
    }
}
