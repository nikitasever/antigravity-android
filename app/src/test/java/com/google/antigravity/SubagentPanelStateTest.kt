package com.google.antigravity

import com.google.antigravity.model.SubagentNotificationTexts
import com.google.antigravity.model.SubagentPanelState
import com.google.antigravity.model.SubagentPlurals
import com.google.antigravity.model.SwarmAgentNode
import com.google.antigravity.model.SwarmCounts
import com.google.antigravity.model.SwarmSnapshot
import com.google.antigravity.network.parseSwarmSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Панель субагентов и уведомления «как у них»: склонения, разбор новых полей
// снимка моста (включая их отсутствие — без выдуманных значений), переходы
// состояния панели и фактические тексты уведомлений.
class SubagentPanelStateTest {

    @Test
    fun `plural задачи — все ветки склонения`() {
        assertEquals("задач", SubagentPlurals.tasks(0))
        assertEquals("задача", SubagentPlurals.tasks(1))
        assertEquals("задачи", SubagentPlurals.tasks(2))
        assertEquals("задачи", SubagentPlurals.tasks(4))
        assertEquals("задач", SubagentPlurals.tasks(5))
        assertEquals("задач", SubagentPlurals.tasks(11))
        assertEquals("задач", SubagentPlurals.tasks(12))
        assertEquals("задача", SubagentPlurals.tasks(21))
        assertEquals("задачи", SubagentPlurals.tasks(22))
        assertEquals("задачи", SubagentPlurals.tasks(102))
        assertEquals("задач", SubagentPlurals.tasks(112))
        assertEquals("задача", SubagentPlurals.tasks(101))
    }

    @Test
    fun `runningLabel и startedLabel — формы после числительного`() {
        assertEquals("1 задача выполняется", SubagentPlurals.runningLabel(1))
        assertEquals("2 задачи выполняются", SubagentPlurals.runningLabel(2))
        assertEquals("5 задач выполняются", SubagentPlurals.runningLabel(5))
        assertEquals("Выполняется 1 задача…", SubagentPlurals.startedLabel(1))
        assertEquals("Выполняются 3 задачи…", SubagentPlurals.startedLabel(3))
    }

    @Test
    fun `парсинг снимка — новые поля моста читаются`() {
        val json = JSONObject(
            """{"strategy":"PARALLEL_ANALYSIS","batchId":"b1","swarmActive":true,
             "counts":{"queued":1,"running":2,"completed":3,"failed":0,"cancelled":0},
             "runningTasks":3,"busy":true,
             "busyMessage":"Компьютер занят другими сессиями — повторите через несколько минут.",
             "agents":[{"id":"a1","name":"Разработчик","status":"running","workspaceMode":"isolated"},
                       {"id":"a2","name":"Исследователь","status":"queued","workspaceMode":"shared",
                        "sharedDirectoryWarning":"Работа в общем каталоге проекта (только чтение)."}]}"""
        )
        val snap = parseSwarmSnapshot(json)
        assertEquals(2, snap.agents.size)
        assertEquals(3, snap.runningTasks ?: -1)
        assertEquals(true, snap.busy ?: false)
        assertNotNull(snap.counts)
        assertEquals(2, snap.counts?.running ?: -1)
        assertEquals("isolated", snap.agents[0].workspaceMode)
        assertNull(snap.agents[0].sharedDirectoryWarning)
        assertEquals("shared", snap.agents[1].workspaceMode)
        assertEquals("Работа в общем каталоге проекта (только чтение).", snap.agents[1].sharedDirectoryWarning)
    }

    @Test
    fun `парсинг снимка — отсутствие новых полей не выдумывает значения`() {
        val json = JSONObject("""{"strategy":"PARALLEL_ANALYSIS","agents":[{"id":"a1","name":"Р","status":"running"}]}""")
        val snap = parseSwarmSnapshot(json)
        assertNull(snap.counts)
        assertNull(snap.runningTasks)
        assertNull(snap.busy)
        assertNull(snap.busyMessage)
        assertNull(snap.agents[0].workspaceMode)
        assertNull(snap.agents[0].sharedDirectoryWarning)
    }

    @Test
    fun `парсинг снимка — неизвестная стратегия не заявляет агентов`() {
        val json = JSONObject("""{"strategy":"OTHER","agents":[{"id":"a1"}],"runningTasks":2}""")
        val snap = parseSwarmSnapshot(json)
        assertTrue(snap.agents.isEmpty())
        assertEquals(2, snap.runningTasks ?: -1)
    }

    @Test
    fun `панель — производные значения при старых ответах без полей снимка`() {
        val agents = listOf(
            SwarmAgentNode(id = "a1", name = "Разработчик", status = "running"),
            SwarmAgentNode(id = "a2", name = "Рецензент", status = "queued"),
            SwarmAgentNode(id = "a3", name = "Рецензент", status = "completed")
        )
        val state = SubagentPanelState(agents = agents)
        assertNull(state.counterLabel)
        assertEquals(2, state.effectiveRunningTasks)
        assertEquals("2 задачи выполняются", state.pillLabel(2))
        assertNull(state.effectiveBusyMessage)
        val counts = state.effectiveCounts
        assertEquals(1, counts.completed)
        assertEquals(1, counts.running)
        assertEquals(1, counts.queued)
    }

    @Test
    fun `панель — значения снимка приоритетнее производных`() {
        val snapshot = SwarmSnapshot(
            agents = listOf(SwarmAgentNode(id = "a1", name = "Разработчик", status = "running")),
            counts = SwarmCounts(running = 7),
            runningTasks = 7,
            busy = true,
            busyMessage = "Компьютер занят другими сессиями — повторите через несколько минут."
        )
        val state = SubagentPanelState.from(snapshot)
        assertEquals(7, state.effectiveRunningTasks)
        assertEquals("7 задач выполняются", state.counterLabel)
        assertEquals("Компьютер занят другими сессиями — повторите через несколько минут.", state.effectiveBusyMessage)
        assertEquals(7, state.effectiveCounts.running)
    }

    @Test
    fun `панель — занятость без своего текста получает дефолтное сообщение`() {
        val state = SubagentPanelState(
            agents = listOf(SwarmAgentNode(id = "a1", name = "Р", status = "running")),
            busy = true
        )
        assertEquals(SubagentPanelState.DEFAULT_BUSY_MESSAGE, state.effectiveBusyMessage)
    }

    @Test
    fun `подсказки режима каталога — shared isolated и отсутствие данных`() {
        val shared = SwarmAgentNode(
            id = "a", name = "Агент", status = "running",
            workspaceMode = "shared", sharedDirectoryWarning = "Текст предупреждения"
        )
        val sharedDefault = shared.copy(sharedDirectoryWarning = null)
        val isolated = SwarmAgentNode(id = "b", name = "Агент", status = "running", workspaceMode = "isolated")
        val unknown = SwarmAgentNode(id = "c", name = "Агент", status = "running")
        val state = SubagentPanelState(agents = listOf(shared, sharedDefault, isolated, unknown))
        assertEquals("Текст предупреждения", state.hintFor(shared))
        assertEquals(SubagentPanelState.DEFAULT_SHARED_WARNING, state.hintFor(sharedDefault))
        assertEquals("изолированная рабочая копия", state.hintFor(isolated))
        assertNull(state.hintFor(unknown))
    }

    @Test
    fun `тексты уведомлений — только фактические счётчики`() {
        assertEquals("Выполняется 1 задача…", SubagentNotificationTexts.started(1))
        assertEquals("Выполняются 2 задачи…", SubagentNotificationTexts.started(2))
        assertEquals("Задачи завершены", SubagentNotificationTexts.finishedTitle(false))
        assertEquals("Задачи завершены с ошибками", SubagentNotificationTexts.finishedTitle(true))
        assertEquals("Завершено: 2, ошибок: 1", SubagentNotificationTexts.finishedBody(SwarmCounts(completed = 2, failed = 1)))
        assertEquals("Завершено: 3", SubagentNotificationTexts.finishedBody(SwarmCounts(completed = 3)))
        assertEquals("Счётчики завершения не записаны мостом.", SubagentNotificationTexts.finishedBody(null))
        assertEquals("Задачи отменены. Отменено: 2.", SubagentNotificationTexts.cancelledBody(SwarmCounts(cancelled = 2)))
        assertEquals("Задачи отменены. Счётчики не записаны мостом.", SubagentNotificationTexts.cancelledBody(null))
    }
}
