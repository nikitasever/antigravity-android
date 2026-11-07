package com.google.antigravity.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal enum class VideoTransport { PC_BRIDGE, PHONE_API }

internal data class VideoRun(
    val requestId: String, val userMessageId: String, val conversationId: String, val title: String,
    val target: AgentExecutionTarget, val bridgeUrl: String, val model: String, val prompt: String,
    val timestamp: String, val keyHash: String = "", val paused: Boolean = false, val lastError: String = "",
    val transport: VideoTransport = VideoTransport.PC_BRIDGE, val providerJobId: String = "",
    val submissionAttempted: Boolean = false
) {
    init {
        require(requestId.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        require(userMessageId.isNotBlank() && conversationId.isNotBlank() && model.isNotBlank() && prompt.isNotBlank())
        require(prompt.length <= 16000 && bridgeUrl.length <= 8192 && title.length <= 4096)
        require(userMessageId.length <= 128 && conversationId.length <= 256 && model.length <= 256 && lastError.length <= 1500)
        require(keyHash.isEmpty() || keyHash.matches(Regex("[a-f0-9]{64}")))
        val address = URI(bridgeUrl)
        require(address.scheme in setOf("http", "https") && !address.host.isNullOrBlank())
        require(address.userInfo == null && address.rawQuery == null && address.rawFragment == null)
        require(providerJobId.isEmpty() || validProviderId(providerJobId))
        if (transport == VideoTransport.PHONE_API) require(bridgeUrl == DIRECT_API_BASE && keyHash.length == 64)
        else require(providerJobId.isEmpty() && !submissionAttempted)
        require(providerJobId.isEmpty() || submissionAttempted)
    }
    companion object {
        const val DIRECT_API_BASE = "https://openrouter.ai/api/v1"
        fun validProviderId(id: String) = id != "." && id != ".." && id.matches(Regex("[a-zA-Z0-9_.:-]{1,160}"))
    }
}

/** Checkpoint authorizes no new submission: recovery must use status-only. Corruption fails closed. */
internal class VideoRunStore(private val file: File) {
    fun load(): List<VideoRun> = synchronized(lock) { read() }
    fun save(run: VideoRun) = synchronized(lock) {
        val entries = read().toMutableList()
        val index = entries.indexOfFirst { it.requestId == run.requestId }
        if (index >= 0) {
            val old = entries[index]
            require(!old.submissionAttempted || run.submissionAttempted) { "Submission cannot be reset" }
            require(old.providerJobId.isEmpty() || old.providerJobId == run.providerJobId) { "Provider job cannot change" }
            require(old.copy(paused = run.paused, lastError = run.lastError, providerJobId = run.providerJobId,
                submissionAttempted = run.submissionAttempted) == run) { "Video origin cannot change" }
            entries[index] = run
        } else {
            if (entries.size >= 32) throw IOException("Журнал ожидающих видеозадач заполнен")
            entries.add(run)
        }
        write(entries)
    }
    fun remove(requestId: String) = synchronized(lock) { write(read().filterNot { it.requestId == requestId }) }
    private fun read(): List<VideoRun> {
        if (!file.exists()) return emptyList()
        try {
            require(file.length() <= 4 * 1024 * 1024)
            val root = JSONObject(file.readText())
            require(root.getInt("version") in 1..2)
            val rows = root.getJSONArray("runs")
            require(rows.length() <= 32)
            return (0 until rows.length()).map { index -> rows.getJSONObject(index).let { row ->
                VideoRun(row.getString("requestId"), row.getString("userMessageId"), row.getString("conversationId"), row.getString("title"),
                    AgentExecutionTarget.valueOf(row.getString("target")), row.getString("bridgeUrl"), row.getString("model"),
                    row.getString("prompt"), row.getString("timestamp"), row.getString("keyHash"), row.getBoolean("paused"), row.getString("lastError"),
                    VideoTransport.valueOf(row.optString("transport", VideoTransport.PC_BRIDGE.name)),
                    row.optString("providerJobId", ""), row.optBoolean("submissionAttempted", false))
            } }.also { require(it.map { row -> row.requestId }.distinct().size == it.size) }
        } catch (error: Exception) { throw IOException("Журнал видеозадач повреждён; повторная генерация не выполнялась", error) }
    }
    private fun write(runs: List<VideoRun>) {
        file.parentFile?.mkdirs()
        val rows = JSONArray().apply { runs.forEach { run -> put(JSONObject()
            .put("requestId", run.requestId).put("userMessageId", run.userMessageId).put("conversationId", run.conversationId)
            .put("title", run.title).put("target", run.target.name).put("bridgeUrl", run.bridgeUrl).put("model", run.model)
            .put("prompt", run.prompt).put("timestamp", run.timestamp).put("keyHash", run.keyHash)
            .put("paused", run.paused).put("lastError", run.lastError).put("transport", run.transport.name)
            .put("providerJobId", run.providerJobId).put("submissionAttempted", run.submissionAttempted)) } }
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.outputStream().use { output ->
            output.write(JSONObject().put("version", 2).put("runs", rows).toString().toByteArray(Charsets.UTF_8)); output.fd.sync()
        }
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    companion object { private val lock = Any(); const val FILE_NAME = "openrouter_video_runs.json" }
}
