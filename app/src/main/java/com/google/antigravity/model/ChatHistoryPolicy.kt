package com.google.antigravity.model

/** A retry/edit replaces the selected turn and retains all preceding turns. */
data class ChatResubmission(
    val history: List<ChatMessage>,
    val prompt: String,
    val attachments: List<AttachedFileItem>
)

/**
 * История для модели: всё после последнего разделителя контекста. Сводка
 * сжатия (`isCompactionSummary`) предшествует оставшимся сообщениям — так
 * модель получает сжатую историю, а локальный список сообщений не меняется.
 */
fun chatHistoryForModel(messages: List<ChatMessage>): List<ChatMessage> {
    val cutIndex = messages.indexOfLast { it.isContextResetDivider }
    val tail = messages.drop(cutIndex + 1)
        .filter { !it.isStreaming && it.role != MessageRole.SYSTEM && it.content.isNotBlank() }
    val summaryLead = messages.getOrNull(cutIndex)
        ?.takeIf { it.isCompactionSummary && it.content.isNotBlank() }
    return listOfNotNull(summaryLead) + tail
}

/** Server transcripts can lag behind the completed local turn or omit UI metadata. */
fun mergeRemoteChatHistory(local: List<ChatMessage>, remote: List<ChatMessage>): List<ChatMessage> {
    // A linear backend transcript cannot overwrite local branch selection or archives.
    if (local.any { it.versionState != null }) return local
    if (remote.size < local.size) return local
    return remote.mapIndexed { index, message ->
        val previous = local.getOrNull(index)
        if (previous == null || previous.role != message.role || previous.content != message.content) message
        else message.copy(
            id = previous.id, isThinkingExpanded = previous.isThinkingExpanded,
            attachments = message.attachments.ifEmpty { previous.attachments },
            toolCalls = if (previous.toolCalls.size > message.toolCalls.size) previous.toolCalls else message.toolCalls,
            createdDiffs = message.createdDiffs.ifEmpty { previous.createdDiffs },
            generatedMedia = message.generatedMedia.ifEmpty { previous.generatedMedia },
            modelBadge = message.modelBadge.ifBlank { previous.modelBadge },
            tokensPerSecond = message.tokensPerSecond.takeIf { it > 0f } ?: previous.tokensPerSecond,
            outputTokensCount = message.outputTokensCount.takeIf { it > 0 } ?: previous.outputTokensCount,
            firstTokenLatencyMs = message.firstTokenLatencyMs.takeIf { it > 0L } ?: previous.firstTokenLatencyMs
        )
    }
}

fun prepareChatRetry(messages: List<ChatMessage>, responseId: String): ChatResubmission? {
    val responseIndex = messages.indexOfFirst { it.id == responseId && it.role == MessageRole.AGENT }
    if (responseIndex < 0) return null
    val userIndex = (responseIndex - 1 downTo 0).firstOrNull {
        messages[it].role == MessageRole.USER && !messages[it].isContextResetDivider
    } ?: return null
    val user = messages[userIndex]
    return ChatResubmission(messages.take(userIndex), user.content, user.attachments)
}

fun prepareChatEdit(messages: List<ChatMessage>, messageId: String, text: String): ChatResubmission? {
    val index = messages.indexOfFirst { it.id == messageId && it.role == MessageRole.USER }
    if (index < 0 || text.isBlank()) return null
    return ChatResubmission(messages.take(index), text.trim(), messages[index].attachments)
}

/** A separate branch copies visible history through the selected completed message. */
fun prepareChatBranch(messages: List<ChatMessage>, messageId: String): List<ChatMessage>? {
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0 || messages[index].isStreaming) return null
    return messages.take(index + 1).filterNot { it.isStreaming }
}
