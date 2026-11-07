package com.google.antigravity.model

// Панель фоновых задач и субагентов «как у них» (референс Claude: «%1$d running
// task(s)», «No background tasks», «Your computer is busy with other sessions…»,
// «Running in the shared directory… consider worktree mode»). Чистая логика
// без Android-контекста: склонения, производное состояние панели и тексты
// уведомлений — только из реальных данных снимка, ничего не выдумывается.

/** Русские формы существительного «задача» после числительного. */
object SubagentPlurals {
    fun tasks(count: Int): String {
        val n = kotlin.math.abs(count)
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "задача"
            mod10 in 2..4 && mod100 !in 12..14 -> "задачи"
            else -> "задач"
        }
    }

    /** «1 задача выполняется» / «2 задачи выполняются» / «5 задач выполняются». */
    fun runningLabel(count: Int): String {
        val verb = if (count == 1) "выполняется" else "выполняются"
        return "$count ${tasks(count)} $verb"
    }

    /** «Выполняется 1 задача…» / «Выполняются 3 задачи…». */
    fun startedLabel(count: Int): String {
        return if (count == 1) "Выполняется 1 задача…" else "Выполняются $count ${tasks(count)}…"
    }
}

/**
 * Состояние панели фоновых задач. Поля снимка от старых ответов моста могут
 * отсутствовать (null) — тогда честно выводятся производные значения из статусов
 * агентов либо прежний вид панели, без выдуманных счётчиков.
 */
data class SubagentPanelState(
    val agents: List<SwarmAgentNode> = emptyList(),
    val counts: SwarmCounts? = null,
    val runningTasks: Int? = null,
    val busy: Boolean? = null,
    val busyMessage: String? = null
) {
    val isEmpty: Boolean get() = agents.isEmpty()

    /** Счётчик: из снимка; при отсутствии поля — по статусам агентов. */
    val effectiveRunningTasks: Int get() = runningTasks ?: agents.count { it.isInProgress }

    /**
     * Счётчик заголовка панели «как у них» («%1$d running task(s)»). null —
     // поля снимка отсутствуют (старый ответ моста): прежний вид без нового
     * счётчика, значения не выдумываются.
     */
    val counterLabel: String?
        get() = if (runningTasks != null || counts != null) SubagentPlurals.runningLabel(effectiveRunningTasks) else null

    /** Текст пилюли: производный счётчик из статусов агентов (как прежде, с верными формами). */
    fun pillLabel(activeCount: Int): String = SubagentPlurals.runningLabel(activeCount)

    /** Сообщение занятости «как у них»; null — не занят/нет данных. */
    val effectiveBusyMessage: String?
        get() = when {
            !busyMessage.isNullOrBlank() -> busyMessage
            busy == true -> DEFAULT_BUSY_MESSAGE
            else -> null
        }

    /** Подсказка режима рабочего каталога агента; null — данных нет. */
    fun hintFor(agent: SwarmAgentNode): String? = when {
        !agent.sharedDirectoryWarning.isNullOrBlank() -> agent.sharedDirectoryWarning
        agent.workspaceMode == "shared" -> DEFAULT_SHARED_WARNING
        agent.workspaceMode == "isolated" -> "изолированная рабочая копия"
        else -> null
    }

    /** Счётчики для фактов уведомлений: из снимка, иначе по статусам агентов. */
    val effectiveCounts: SwarmCounts
        get() = counts ?: SwarmCounts(
            queued = agents.count { it.status == "queued" },
            running = agents.count { it.isRunning },
            completed = agents.count { it.status == "completed" },
            failed = agents.count { it.status == "failed" },
            cancelled = agents.count { it.status == "cancelled" }
        )

    companion object {
        // Тексты-аналоги строк референса («Your computer is busy with other
        // sessions…» / «Running in the shared directory… consider worktree mode»).
        const val DEFAULT_BUSY_MESSAGE = "Компьютер занят другими сессиями — повторите через несколько минут."
        const val DEFAULT_SHARED_WARNING = "Работа в общем каталоге проекта (только чтение). Другие сессии могут конфликтовать при записи — режим кода выполняется в изолированной рабочей копии."

        val EMPTY = SubagentPanelState()

        fun from(snapshot: SwarmSnapshot): SubagentPanelState = SubagentPanelState(
            agents = snapshot.agents,
            counts = snapshot.counts,
            runningTasks = snapshot.runningTasks,
            busy = snapshot.busy,
            busyMessage = snapshot.busyMessage
        )
    }
}

/**
 * Тексты уведомлений о пачке субагентов «как у них» (их notification-события
 * SessionAnswer/SessionReply → наши честные аналоги «Отменить»/«Открыть»).
 * Только реальные счётчики; без данных — «не записано», без выдумок.
 */
object SubagentNotificationTexts {
    fun started(runningTasks: Int): String = SubagentPlurals.startedLabel(runningTasks)

    fun finishedTitle(hasFailures: Boolean): String =
        if (hasFailures) "Задачи завершены с ошибками" else "Задачи завершены"

    fun finishedBody(counts: SwarmCounts?): String {
        if (counts == null) return "Счётчики завершения не записаны мостом."
        val parts = mutableListOf<String>()
        parts.add("Завершено: ${counts.completed}")
        if (counts.failed > 0) parts.add("ошибок: ${counts.failed}")
        if (counts.cancelled > 0) parts.add("отменено: ${counts.cancelled}")
        return parts.joinToString(", ")
    }

    fun cancelledBody(counts: SwarmCounts?): String {
        if (counts == null) return "Задачи отменены. Счётчики не записаны мостом."
        return "Задачи отменены. Отменено: ${counts.cancelled}."
    }
}
