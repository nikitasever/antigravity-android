package com.google.antigravity.model

/**
 * Branch metadata for a message that has multiple versions / edits.
 * [branches] contains snapshots of the tail starting with the anchor message.
 * [selected] is 0-based index of the currently active branch in [branches].
 */
data class MessageVersionState(
    val selected: Int,
    val branches: List<List<ChatMessage>>
)

private fun MessageVersionState.validFor(id: String): Boolean = selected in branches.indices &&
    branches.all { branch -> branch.firstOrNull()?.let {
        it.id == id && it.role == MessageRole.USER && it.versionState == null
    } == true }

/** Keep the visible chat identity while separating remote model context for alternatives. */
fun conversationExecutionId(chatId: String, messages: List<ChatMessage>): String {
    val alternatives = messages.mapNotNull { message -> message.versionState
        ?.takeIf { it.selected > 0 }?.let { "${message.id}:${it.selected}" } }
    return if (alternatives.isEmpty()) chatId else java.util.UUID.nameUUIDFromBytes(
        (chatId + ":" + alternatives.joinToString("|")).toByteArray(Charsets.UTF_8)).toString()
}

/**
 * Creates a new version branch of a completed USER message anchor.
 *
 * Rules:
 * - Reject operation while any visible message isStreaming.
 * - [messageId] must point to a completed USER message (not streaming).
 * - [newText] cannot be blank (trimmed).
 * - Prefix up to anchor index is preserved.
 * - Version snapshot is the full tail from anchor inclusive.
 * - Strip versionState ONLY from the head of the snapshot to avoid recursive self-copy.
 * - Nested later anchors retain their version state.
 * - Editing archives current live tail (including following replies) into branches,
 *   adds a new version whose USER head has same stable anchor id, edited trimmed content,
 *   preserved attachments, and removes the old visible continuation.
 * - Visible edited head has group metadata selected = new index (last branch index).
 * - Does not mutate input list.
 */
fun forkMessageVersion(
    messages: List<ChatMessage>,
    messageId: String,
    newText: String
): List<ChatMessage>? {
    if (newText.isBlank()) return null
    if (messages.any { it.isStreaming }) return null

    val anchorIndex = messages.indexOfFirst { it.id == messageId }
    if (anchorIndex < 0) return null

    val anchor = messages[anchorIndex]
    if (anchor.role != MessageRole.USER || anchor.isStreaming) return null

    val prefix = messages.take(anchorIndex)
    val liveTail = messages.subList(anchorIndex, messages.size)

    val existingState = anchor.versionState
    if (existingState != null && !existingState.validFor(messageId)) return null
    val existingBranches = existingState?.branches

    // Snapshot head must have versionState stripped to avoid recursive self-copy
    val archivedHead = liveTail.first().copy(versionState = null)
    val archivedTail = listOf(archivedHead) + liveTail.drop(1)

    val newBranches: List<List<ChatMessage>> = if (existingBranches == null || existingBranches.isEmpty()) {
        listOf(archivedTail)
    } else {
        val selectedIdx = existingState.selected.coerceIn(0, existingBranches.lastIndex)
        existingBranches.mapIndexed { idx, branch ->
            if (idx == selectedIdx) archivedTail else branch
        }
    }

    val newEditedHead = anchor.copy(
        content = newText.trim(),
        versionState = null
    )
    val updatedBranches = newBranches + listOf(listOf(newEditedHead))
    val newSelectedIndex = updatedBranches.lastIndex

    val visibleHead = newEditedHead.copy(
        versionState = MessageVersionState(
            selected = newSelectedIndex,
            branches = updatedBranches
        )
    )

    return prefix + listOf(visibleHead)
}

/**
 * Switches the active version branch for a message anchor.
 *
 * Rules:
 * - Reject operation while any visible message isStreaming.
 * - [messageId] must point to a completed USER message with versionState.
 * - [index] must be a valid branch index in branches.
 * - If [index] == current selected index, returns original messages list.
 * - Prefix up to anchor index is preserved.
 * - Archives live selected tail (with versionState stripped from its head) into branches.
 * - Restores chosen tail, attaching updated group metadata (selected = [index]) to chosen head.
 * - Requires branch heads to have the same anchor id.
 * - Does not mutate input list.
 */
fun selectMessageVersion(
    messages: List<ChatMessage>,
    messageId: String,
    index: Int
): List<ChatMessage>? {
    if (messages.any { it.isStreaming }) return null

    val anchorIndex = messages.indexOfFirst { it.id == messageId }
    if (anchorIndex < 0) return null

    val anchor = messages[anchorIndex]
    if (anchor.role != MessageRole.USER || anchor.isStreaming) return null

    val state = anchor.versionState ?: return null
    if (!state.validFor(messageId)) return null
    if (index < 0 || index >= state.branches.size) return null
    if (index == state.selected) return messages

    val chosenBranch = state.branches[index]
    if (chosenBranch.isEmpty()) return null
    if (chosenBranch.first().id != messageId) return null

    val prefix = messages.take(anchorIndex)
    val liveTail = messages.subList(anchorIndex, messages.size)

    val archivedHead = liveTail.first().copy(versionState = null)
    val archivedTail = listOf(archivedHead) + liveTail.drop(1)

    val updatedBranches = state.branches.mapIndexed { idx, branch ->
        if (idx == state.selected) archivedTail else branch
    }

    val updatedState = MessageVersionState(
        selected = index,
        branches = updatedBranches
    )

    val chosenHead = chosenBranch.first().copy(versionState = updatedState)
    val restoredTail = listOf(chosenHead) + chosenBranch.drop(1)

    return prefix + restoredTail
}
