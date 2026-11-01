package com.google.antigravity.model

import org.json.JSONArray
import java.util.UUID

data class QueuedFollowUp(
    val id: String = UUID.randomUUID().toString(), val conversationId: String,
    val target: AgentExecutionTarget, val prompt: String,
    val attachments: List<AttachedFileItem> = emptyList(), val temporary: Boolean = false,
    val executionContextId: String = ""
)

/** Persist pending intent, never resume paid inference automatically on app startup. */
object FollowUpQueue {
    fun rebind(items: List<QueuedFollowUp>, from: String, to: String, target: AgentExecutionTarget): List<QueuedFollowUp> =
        items.map { if (it.conversationId == from && it.target == target) it.copy(conversationId = to,
            executionContextId = if (it.executionContextId == from) to else it.executionContextId) else it }

    fun encode(items: List<QueuedFollowUp>): String = JSONArray().apply {
        items.filterNot { it.temporary }.forEach { item -> put(org.json.JSONObject(ChatSessionStore.encode(SavedChat(
            item.conversationId, item.id, item.target, listOf(ChatMessage(id = item.id,
                role = MessageRole.USER, content = item.prompt, timestamp = "", attachments = item.attachments)))))
                    .put("queueExecutionContextId", item.executionContextId).toString()) }
    }.toString()

    fun decode(json: String): List<QueuedFollowUp> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index -> runCatching {
            val serialized = array.getString(index)
            val chat = ChatSessionStore.decode(serialized)
            val message = chat.messages.single()
            QueuedFollowUp(id = message.id, conversationId = chat.id, target = chat.target,
                prompt = message.content, attachments = message.attachments,
                executionContextId = org.json.JSONObject(serialized).optString("queueExecutionContextId"))
        }.getOrNull() }
    }.getOrDefault(emptyList())
}
