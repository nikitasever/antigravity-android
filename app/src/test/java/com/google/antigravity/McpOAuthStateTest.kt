package com.google.antigravity

import com.google.antigravity.model.McpAuthState
import com.google.antigravity.model.PcMcpServerItem
import com.google.antigravity.model.describeMcpAuthStatus
import com.google.antigravity.model.normalizeMcpAuthStatus
import com.google.antigravity.network.mergeMcpAuthFields
import com.google.antigravity.network.parseMcpOAuthReply
import com.google.antigravity.network.parseMcpServerList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Этап 8 / OAuth 2.1 (клиент): парсинг OAuth-полей (authUrl, authStatus) из
 * ответов моста /api/mcp (oauthStart / oauthStatus / oauthRevoke) и переходы
 * состояния авторизации, которые выполняет AntigravityViewModel:
 * «Войти» → браузер (Intent.ACTION_VIEW) → возврат (onResume / «Проверить»)
 * → обновление статуса и инструментов → «Выйти» (revoke).
 */
class McpOAuthStateTest {

    // ---------- Парсинг OAuth-полей (authUrl, authStatus) ----------

    @Test
    fun `oauthStart — разбор authUrl и authStatus из camelCase-полей`() {
        val reply = parseMcpOAuthReply(
            JSONObject("""{"ok":true,"id":"srv","authUrl":"https://idp.example/auth?state=s1","authStatus":"pending","sessionId":"sess-1"}""")
        )
        assertEquals("srv", reply.id)
        assertEquals("https://idp.example/auth?state=s1", reply.authUrl)
        assertEquals("pending", reply.authStatus)
        assertEquals("sess-1", reply.sessionId)
        assertFalse(reply.authorized)
    }

    @Test
    fun `oauthStatus — авторизованный вход`() {
        val reply = parseMcpOAuthReply(JSONObject("""{"ok":true,"id":"srv","authStatus":"authorized"}"""))
        assertEquals("authorized", reply.authStatus)
        assertTrue(reply.authorized)
    }

    @Test
    fun `oauthStatus — ошибка токена`() {
        val reply = parseMcpOAuthReply(JSONObject("""{"ok":true,"id":"srv","authStatus":"failed"}"""))
        assertEquals("failed", reply.authStatus)
        assertEquals("Ошибка токена", describeMcpAuthStatus(reply.authStatus))
    }

    @Test
    fun `oauthStatus — сессия истекла (unknown_session) без выдуманного статуса`() {
        val reply = parseMcpOAuthReply(
            JSONObject("""{"ok":true,"id":"srv","status":"expired","authorized":false,"errorCode":"unknown_session"}""")
        )
        assertEquals("expired", reply.authStatus)
        assertFalse(reply.authorized)
    }

    @Test
    fun `fallback на легаси-поля authorizationUrl и status`() {
        val reply = parseMcpOAuthReply(
            JSONObject("""{"ok":true,"name":"srv","authorizationUrl":"https://idp.example/auth2","status":"authorized","authorized":true}""")
        )
        assertEquals("srv", reply.id)
        assertEquals("https://idp.example/auth2", reply.authUrl)
        assertEquals("authorized", reply.authStatus)
        assertTrue(reply.authorized)
    }

    @Test
    fun `ответ без OAuth-полей — статус неизвестен, вход не приписывается`() {
        val reply = parseMcpOAuthReply(JSONObject("""{"ok":true}"""))
        assertEquals("", reply.authStatus)
        assertEquals("", reply.authUrl)
        assertFalse(reply.authorized)
    }

    @Test
    fun `список подключений — authUrl и authStatus разбираются из mcpServers`() {
        val list = parseMcpServerList(
            JSONObject(
                """{"mcpServers":[
                    {"id":"a","name":"A","commandOrUrl":"https://mcp.example","authUrl":"https://idp.example/auth","authStatus":"not_authorized"},
                    {"id":"b","name":"B","commandOrUrl":"https://mcp2.example","oauthAuthorized":true},
                    {"id":"c","name":"C","command":"node","args":[]}
                ]}"""
            )
        )
        assertEquals(3, list.size)
        assertEquals("https://idp.example/auth", list[0].authUrl)
        assertEquals("not_authorized", list[0].authStatus)
        assertFalse(list[0].oauthAuthorized)
        // oauthAuthorized без authStatus производит статус authorized.
        assertEquals("authorized", list[1].authStatus)
        assertTrue(list[1].oauthAuthorized)
        // Без OAuth-полей статус остаётся неизвестным ("").
        assertEquals("", list[2].authStatus)
        assertFalse(list[2].oauthAuthorized)
    }

    @Test
    fun `mergeMcpAuthFields сохраняет статус входа, если ответ списка без OAuth-полей`() {
        val previous = listOf(
            PcMcpServerItem(
                id = "a",
                name = "A",
                authStatus = "pending",
                authUrl = "https://idp.example/auth",
                oauthAuthorized = false
            )
        )
        val fresh = listOf(PcMcpServerItem(id = "a", name = "A", tools = listOf("t1")))
        val merged = mergeMcpAuthFields(previous, fresh)
        assertEquals("pending", merged[0].authStatus)
        assertEquals("https://idp.example/auth", merged[0].authUrl)
        assertEquals(listOf("t1"), merged[0].tools)
    }

    @Test
    fun `mergeMcpAuthFields — явные данные моста приоритетнее локальных`() {
        val previous = listOf(
            PcMcpServerItem(id = "a", name = "A", authStatus = "authorized", oauthAuthorized = true)
        )
        val fresh = listOf(
            PcMcpServerItem(id = "a", name = "A", authStatus = "not_authorized", oauthAuthorized = false)
        )
        val merged = mergeMcpAuthFields(previous, fresh)
        assertEquals("not_authorized", merged[0].authStatus)
        assertFalse(merged[0].oauthAuthorized)
    }

    // ---------- Переходы состояния (AntigravityViewModel) ----------

    @Test
    fun `Войти → pending с authUrl, возврат из браузера → авторизован`() {
        // loginMcpServer → backendClient.mcpOAuthStart → onLoginStarted(authUrl, sessionId)
        var state = McpAuthState()
        assertFalse(state.needsBrowserLogin)
        state = state.onLoginStarted("https://idp.example/auth?state=s1", "sess-1")
        assertEquals("pending", state.status)
        assertEquals("Вход выполняется…", state.displayRu)
        assertTrue(state.needsBrowserLogin) // браузер открыт по authUrl (Intent.ACTION_VIEW)

        // onAppResumed / «Проверить» → backendClient.mcpOAuthStatus → onStatusChecked
        state = state.onStatusChecked("authorized", true)
        assertEquals("authorized", state.status)
        assertEquals("Авторизован", state.displayRu)
        assertTrue(state.authorized)
        assertTrue(state.canRevoke) // доступен выход (revoke)
        assertFalse(state.needsBrowserLogin) // повторное открытие браузера не нужно
    }

    @Test
    fun `Проверить после возврата — oauthStatus failed → ошибка токена`() {
        var state = McpAuthState().onLoginStarted("https://idp.example/auth", "sess-2")
        state = state.onStatusChecked("failed", false)
        assertEquals("failed", state.status)
        assertEquals("Ошибка токена", state.displayRu)
        assertFalse(state.authorized)
        assertFalse(state.canRevoke)
        // Адрес входа сохраняется — «Войти» повторяет вход.
        assertEquals("https://idp.example/auth", state.authUrl)
        assertTrue(state.needsBrowserLogin)
    }

    @Test
    fun `Выйти (revoke) из авторизованного состояния → требуется вход`() {
        var state = McpAuthState().onStatusChecked("authorized", true)
        assertTrue(state.canRevoke)
        // loginMcpServer при oauthAuthorized → backendClient.mcpOAuthRevoke → onRevoked
        state = state.onRevoked()
        assertEquals("not_authorized", state.status)
        assertEquals("Требуется вход", state.displayRu)
        assertFalse(state.authorized)
        assertFalse(state.canRevoke)
    }

    @Test
    fun `истёкшая сессия входа — expired → требуется повторный вход`() {
        val state = McpAuthState()
            .onLoginStarted("https://idp.example/auth", "sess-3")
            .onStatusChecked("expired", false)
        assertEquals("expired", state.status)
        assertEquals("Срок действия входа истёк", state.displayRu)
        assertTrue(state.needsBrowserLogin)
    }

    @Test
    fun `отклонённый вход — denied → вход отклонён`() {
        val state = McpAuthState()
            .onLoginStarted("https://idp.example/auth", "sess-4")
            .onStatusChecked("denied", false)
        assertEquals("denied", state.status)
        assertEquals("Вход отклонён", state.displayRu)
    }

    @Test
    fun `попытка заменена — superseded без выдуманного статуса входа`() {
        val state = McpAuthState()
            .onLoginStarted("https://idp.example/auth", "sess-6")
            .onStatusChecked("superseded", false)
        assertEquals("superseded", state.status)
        assertEquals("Вход заменён новой попыткой", state.displayRu)
        assertFalse(state.authorized)
    }

    @Test
    fun `onStatusChecked не теряет authUrl и sessionId, если мост их не прислал`() {
        val state = McpAuthState()
            .onLoginStarted("https://idp.example/auth", "sess-5")
            .onStatusChecked("pending", false)
        assertEquals("pending", state.status)
        assertEquals("https://idp.example/auth", state.authUrl)
        assertEquals("sess-5", state.sessionId)
    }

    @Test
    fun `метки статуса — требуется вход, авторизован, ошибка токена`() {
        assertEquals("Требуется вход", describeMcpAuthStatus("not_authorized"))
        assertEquals("Авторизован", describeMcpAuthStatus("authorized"))
        assertEquals("Ошибка токена", describeMcpAuthStatus("failed"))
        assertEquals("Статус авторизации неизвестен", describeMcpAuthStatus(""))
    }

    @Test
    fun `normalizeMcpAuthStatus — неизвестные значения и признак authorized`() {
        assertEquals("authorized", normalizeMcpAuthStatus("", true))
        assertEquals("", normalizeMcpAuthStatus("", false))
        assertEquals("failed", normalizeMcpAuthStatus("token_error", false))
        assertEquals("pending", normalizeMcpAuthStatus("PENDING", false))
        assertEquals("", normalizeMcpAuthStatus("что-то новое", false))
    }
}
