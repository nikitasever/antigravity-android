package com.google.antigravity.model

val ToolCallStatus.isUnfinished: Boolean
    get() = this == ToolCallStatus.RUNNING || this == ToolCallStatus.AWAITING_APPROVAL || this == ToolCallStatus.APPROVED

/** An interrupted turn cannot retain runnable tools or approvals. Completed evidence stays intact. */
fun interruptToolCalls(
    tools: List<ToolCallEvent>,
    reason: String,
    cancelled: Boolean = false
): List<ToolCallEvent> = tools.map { tool ->
    if (!tool.status.isUnfinished) tool else tool.copy(
        status = if (cancelled) ToolCallStatus.CANCELLED else ToolCallStatus.FAILED,
        details = listOf(tool.details, reason).filter { it.isNotBlank() }.joinToString("\n\n"),
        approvalHandle = null, approvalSending = false, approvalError = ""
    )
}

/** A terminal payload may contain only changes, or no tools at all on failure. */
fun finishTurnTools(streamed: List<ToolCallEvent>, final: List<ToolCallEvent>, failed: Boolean,
    target: AgentExecutionTarget): List<ToolCallEvent> {
    val merged = linkedMapOf<String, ToolCallEvent>()
    (streamed + final).forEach { merged[it.id] = it.copy(executionTarget = it.executionTarget ?: target) }
    return if (failed) interruptToolCalls(merged.values.toList(), "Ответ завершился с ошибкой. Действие не завершено.")
        else merged.values.toList()
}
