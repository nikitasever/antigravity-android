package com.google.antigravity.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class SavedChat(val id: String, val title: String, val target: AgentExecutionTarget, val messages: List<ChatMessage>)

/** Private local history; a failed write leaves the previous complete file intact. */
class ChatSessionStore(private val directory: File) {
    @Synchronized fun save(chat: SavedChat) {
        if (chat.id.isBlank() || chat.messages.isEmpty()) return
        directory.mkdirs()
        val name = MessageDigest.getInstance("SHA-256").digest(chat.id.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, "$name.json")
        val temporary = File(directory, "$name.tmp")
        temporary.writeText(encode(chat))
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized fun load(): List<SavedChat> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" }.mapNotNull { file ->
            runCatching { decode(file.readText()) }.getOrNull()
        }

    companion object {
        fun encode(chat: SavedChat): String = JSONObject().apply {
            put("id", chat.id); put("title", chat.title); put("target", chat.target.name)
            put("messages", JSONArray().apply {
                chat.messages.forEach { m -> put(JSONObject().apply {
                    put("id", m.id); put("role", m.role.name); put("content", m.content)
                    put("timestamp", m.timestamp); put("thinking", m.thinking ?: ""); put("wasStreaming", m.isStreaming)
                    put("thinkingDurationSec", m.thinkingDurationSec); put("viaVoice", m.viaVoice)
                    put("isContextResetDivider", m.isContextResetDivider); put("modelBadge", m.modelBadge)
                    put("tokensPerSecond", m.tokensPerSecond); put("outputTokensCount", m.outputTokensCount)
                    put("firstTokenLatencyMs", m.firstTokenLatencyMs)
                    m.versionState?.let { state ->
                        put("versionState", JSONObject().put("selected", state.selected).put("branches", JSONArray().apply {
                            state.branches.forEach { branch -> put(encode(SavedChat("version", "", chat.target, branch))) }
                        }))
                    }
                    put("attachments", JSONArray().apply { m.attachments.forEach { a -> put(JSONObject().apply {
                        put("id", a.id); put("name", a.name); put("uriString", a.uriString)
                        put("sizeBytes", a.sizeBytes); put("mimeType", a.mimeType); put("textPreview", a.textPreview)
                        put("base64Data", a.base64Data); put("pcUploadedPath", a.pcUploadedPath)
                    }) } })
                    put("tools", JSONArray().apply { m.toolCalls.forEach { t -> put(JSONObject().apply {
                        put("id", t.id); put("toolName", t.toolName); put("summary", t.summary)
                        put("details", t.details); put("status", t.status.name); put("durationMs", t.durationMs)
                        put("parentTaskId", t.parentTaskId ?: ""); put("executionTarget", t.executionTarget?.name ?: "")
                        put("leadingText", t.leadingText)
                        put("unifiedDiff", t.unifiedDiff ?: JSONObject.NULL)
                        put("toolTitleRu", t.toolTitleRu)
                        put("requiresApproval", t.requiresApproval)
                        put("approvalDecision", t.approvalDecision.name)
                    }) } })
                    put("diffs", JSONArray().apply { m.createdDiffs.forEach { d -> put(JSONObject().apply {
                        put("path", d.path); put("additions", d.additions); put("deletions", d.deletions)
                        put("diffPatch", d.diffPatch); put("language", d.language); put("fullPath", d.fullPath)
                    }) } })
                    put("media", JSONArray().apply { m.generatedMedia.forEach { g -> put(JSONObject().apply {
                        put("id", g.id); put("type", g.type); put("fileName", g.fileName); put("mediaUrl", g.mediaUrl)
                        put("localPath", g.localPath); put("prompt", g.prompt); put("modelId", g.modelId)
                        put("mimeType", g.mimeType); put("resolution", g.resolution); put("width", g.width)
                        put("height", g.height); put("durationSec", g.durationSec); put("sizeBytes", g.sizeBytes)
                        put("localPhonePath", g.localPhonePath ?: "")
                    }) } })
                }) }
            })
        }.toString()

        private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }.orEmpty()

        fun decode(text: String): SavedChat {
            val root = JSONObject(text)
            val messages = root.objects("messages").map { m -> ChatMessage(
                id = m.getString("id"), role = MessageRole.valueOf(m.getString("role")),
                content = m.getString("content").ifBlank { if (m.optBoolean("wasStreaming")) "Ответ прерван при закрытии приложения. Повторите запрос." else "" }, timestamp = m.optString("timestamp"),
                thinking = m.optString("thinking").takeIf { it.isNotBlank() },
                thinkingDurationSec = m.optInt("thinkingDurationSec"), viaVoice = m.optBoolean("viaVoice"),
                isStreaming = false, isThinkingExpanded = false,
                versionState = m.optJSONObject("versionState")?.let { state ->
                    val branches = state.getJSONArray("branches")
                    MessageVersionState(state.getInt("selected"), (0 until branches.length()).map {
                        decode(branches.getString(it)).messages
                    })
                },
                isContextResetDivider = m.optBoolean("isContextResetDivider"), modelBadge = m.optString("modelBadge"),
                tokensPerSecond = m.optDouble("tokensPerSecond", 0.0).toFloat(),
                outputTokensCount = m.optInt("outputTokensCount"), firstTokenLatencyMs = m.optLong("firstTokenLatencyMs"),
                attachments = m.objects("attachments").map { a -> AttachedFileItem(
                    id = a.getString("id"), name = a.getString("name"), uriString = a.optString("uriString"),
                    sizeBytes = a.optLong("sizeBytes"), mimeType = a.optString("mimeType"), textPreview = a.optString("textPreview"),
                    base64Data = a.optString("base64Data"), pcUploadedPath = a.optString("pcUploadedPath")
                ) },
                toolCalls = interruptToolCalls(m.objects("tools").map { t -> ToolCallEvent(
                    id = t.getString("id"), toolName = t.getString("toolName"), summary = t.optString("summary"),
                    details = t.optString("details"), durationMs = t.optLong("durationMs"),
                    parentTaskId = t.optString("parentTaskId").takeIf { it.isNotBlank() },
                    leadingText = t.optString("leadingText"),
                    unifiedDiff = if (t.isNull("unifiedDiff")) null else t.optString("unifiedDiff"),
                    toolTitleRu = t.optString("toolTitleRu").ifBlank { humanizeToolNameRu(t.getString("toolName")) },
                    requiresApproval = t.optBoolean("requiresApproval"),
                    approvalDecision = t.optString("approvalDecision").takeIf { it.isNotBlank() }
                        ?.let { runCatching { ApprovalDecision.valueOf(it) }.getOrDefault(ApprovalDecision.PENDING) }
                        ?: ApprovalDecision.PENDING,
                    executionTarget = t.optString("executionTarget").takeIf { it.isNotBlank() }
                        ?.let { runCatching { AgentExecutionTarget.valueOf(it) }.getOrNull() },
                    status = ToolCallStatus.valueOf(t.getString("status"))
                ) }, "Выполнение прервано при закрытии приложения. Задайте новый запрос, чтобы продолжить."),
                createdDiffs = m.objects("diffs").map { d -> FileDiffItem(
                    path = d.getString("path"), additions = d.optInt("additions"), deletions = d.optInt("deletions"),
                    diffPatch = d.optString("diffPatch"), language = d.optString("language"), fullPath = d.optString("fullPath")
                ) },
                generatedMedia = m.objects("media").map { g -> GeneratedMediaAttachment(
                    id = g.getString("id"), type = g.getString("type"), fileName = g.getString("fileName"),
                    mediaUrl = g.getString("mediaUrl"), localPath = g.optString("localPath"), prompt = g.optString("prompt"),
                    modelId = g.optString("modelId"), mimeType = g.optString("mimeType"), resolution = g.optString("resolution"),
                    width = g.optInt("width"), height = g.optInt("height"), durationSec = g.optInt("durationSec"),
                    sizeBytes = g.optLong("sizeBytes"), localPhonePath = g.optString("localPhonePath").takeIf { it.isNotBlank() }
                ) }
            ) }
            return SavedChat(root.getString("id"), root.getString("title"),
                AgentExecutionTarget.valueOf(root.getString("target")), messages)
        }
    }
}
