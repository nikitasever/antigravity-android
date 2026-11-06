package com.google.antigravity.network

import com.google.antigravity.model.*
import org.json.JSONObject
import java.util.Locale

internal fun bridgeTurnFailed(payload: JSONObject): Boolean =
    payload.opt("ok") == false || payload.optBoolean("isError") ||
        payload.optBoolean("cancelled") || payload.optString("status").lowercase(Locale.ROOT) in
        setOf("error", "failed", "cancelled", "canceled") ||
        (!payload.isNull("error") && payload.optString("error").isNotBlank())

/** Both streaming `tool` and terminal/polled `toolCalls` use this wire contract. */
internal fun mergeBridgeTool(events: MutableList<ToolCallEvent>, payload: JSONObject, fallbackId: String, bridgeUrl: String = "") {
    val nested = payload.optJSONObject("tool")
    fun field(key: String): String? = when {
        payload.has(key) && !payload.isNull(key) -> payload.optString(key)
        nested?.has(key) == true && !nested.isNull(key) -> nested.optString(key)
        else -> null
    }
    val explicitId = field("id")?.takeIf { it.isNotBlank() }
    val name = field("toolName") ?: field("name")
    val summary = field("summary")
    val index = if (explicitId != null) events.indexOfFirst { it.id == explicitId }
        else events.indexOfFirst { it.toolName == name && it.summary == (summary ?: name) }
    val before = events.getOrNull(index)
    val status = if (field("ok") == "false" || field("isError") == "true") ToolCallStatus.FAILED
        else when (field("status")?.lowercase(Locale.ROOT)) {
        null, "" -> when {
            before?.status in setOf(ToolCallStatus.FAILED, ToolCallStatus.CANCELLED, ToolCallStatus.DENIED) -> before!!.status
            field("output") != null || field("details") != null -> ToolCallStatus.COMPLETED // Legacy tool result.
            else -> before?.status ?: ToolCallStatus.COMPLETED
        }
        "running", "queued", "active", "working" -> ToolCallStatus.RUNNING
        "completed", "success", "done" -> ToolCallStatus.COMPLETED
        "failed", "error" -> ToolCallStatus.FAILED
        "cancelled", "canceled" -> ToolCallStatus.CANCELLED
        "denied" -> ToolCallStatus.DENIED
        "awaiting_approval" -> ToolCallStatus.AWAITING_APPROVAL
        "approved" -> ToolCallStatus.APPROVED
        else -> before?.status ?: ToolCallStatus.RUNNING // An unknown status is not evidence of success.
    }
    val base = before ?: ToolCallEvent(id = explicitId ?: fallbackId,
        toolName = name ?: "pc_tool", summary = summary ?: name ?: "Инструмент ПК", details = "", unifiedDiff = null)
    val a = payload.optJSONObject("approval") ?: nested?.optJSONObject("approval")
    val handle = a?.takeIf { bridgeUrl.isNotBlank() && listOf("requestId", "token", "turnId", "conversationId")
        .all { key -> !it.isNull(key) && it.optString(key).isNotBlank() } }?.let {
        ToolApprovalHandle(it.getString("requestId"), it.getString("token"), it.getString("turnId"),
            it.getString("conversationId"), bridgeUrl, it.optString("preview")) }
    val terminal = status in setOf(ToolCallStatus.COMPLETED, ToolCallStatus.FAILED, ToolCallStatus.CANCELLED, ToolCallStatus.DENIED)
    val event = base.copy(
        toolName = name ?: base.toolName, summary = summary ?: before?.summary ?: name ?: base.summary,
        toolTitleRu = field("toolTitleRu") ?: if (name != null && name != base.toolName) humanizeToolNameRu(name) else base.toolTitleRu,
        details = field("output") ?: field("details") ?: base.details,
        status = status, unifiedDiff = if (payload.has("unifiedDiff") && payload.isNull("unifiedDiff")) null
            else field("unifiedDiff") ?: base.unifiedDiff,
        executionTarget = field("executionTarget")?.let { runCatching { AgentExecutionTarget.valueOf(it) }.getOrNull() }
            ?: base.executionTarget,
        parentTaskId = field("parentTaskId")?.takeIf { it.isNotBlank() } ?: base.parentTaskId,
        leadingText = field("leadingText") ?: base.leadingText,
        durationMs = field("durationMs")?.toLongOrNull() ?: base.durationMs,
        requiresApproval = field("requiresApproval")?.toBooleanStrictOrNull() ?: base.requiresApproval,
        approvalDecision = field("approvalDecision")?.let { runCatching { ApprovalDecision.valueOf(it.uppercase(Locale.ROOT)) }.getOrNull() }
            ?: base.approvalDecision,
        approvalHandle = if (terminal) null else handle ?: base.approvalHandle,
        approvalSending = if (terminal) false else base.approvalSending,
        approvalError = if (terminal) "" else base.approvalError
    )
    if (index >= 0) events[index] = event else events.add(event)
}
