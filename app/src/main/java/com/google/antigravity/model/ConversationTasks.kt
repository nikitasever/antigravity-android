package com.google.antigravity.model

/** Only actual delegation events become task rows; child tools stay in the activity trace. */
fun conversationSubagents(messages: List<ChatMessage>): List<SwarmAgentNode> = messages.flatMap { message ->
    message.toolCalls.filter { it.toolName == "delegate_task" && it.parentTaskId == null }.map { tool ->
        SwarmAgentNode(
            id = tool.id, name = tool.summary, model = message.modelBadge,
            status = tool.status.name.lowercase(), currentTask = tool.summary,
            stepLogs = listOfNotNull(tool.details.takeIf { it.isNotBlank() })
        )
    }
}

/** Remote counters belong to the remote snapshot, never to a merged conversation list. */
fun conversationTaskPanel(agents: List<SwarmAgentNode>, remote: SubagentPanelState? = null) =
    SubagentPanelState(agents = agents, runningTasks = agents.count { it.isInProgress },
        busy = remote?.busy, busyMessage = remote?.busyMessage)
