package com.google.antigravity.model

// ---------------------------------------------------------------------------
// OAuth 2.1 для MCP-подключений (клиентская часть): нормализация статуса
// авторизации и машина переходов, которой пользуется AntigravityViewModel.
// Чистые функции без Android — тестируются McpOAuthStateTest без запуска
// эмулятора. Статусы приходят от моста (oauthStart/oauthStatus/oauthRevoke):
// authorized | not_authorized | pending | denied | failed | expired | revoked.
// ---------------------------------------------------------------------------

/**
 * Нормализует статус авторизации из ответа моста. Сервер отдаёт поле
 * authStatus (или status + authorized); неизвестные значения приводятся к
 * пустой строке («неизвестно»), кроме явного признака authorized=true.
 */
fun normalizeMcpAuthStatus(rawStatus: String, authorized: Boolean): String = when (rawStatus.trim().lowercase()) {
    "authorized", "not_authorized", "pending", "denied", "failed", "expired", "revoked", "superseded" -> rawStatus.trim().lowercase()
    "token_error", "error" -> "failed"
    else -> if (authorized) "authorized" else ""
}

/**
 * Человекочитаемый статус авторизации. Три обязательные метки: «Требуется
 * вход», «Авторизован», «Ошибка токена»; остальные — уточнения без выдумок.
 */
fun describeMcpAuthStatus(status: String): String = when (status.trim().lowercase()) {
    "authorized" -> "Авторизован"
    "pending" -> "Вход выполняется…"
    "failed" -> "Ошибка токена"
    "expired" -> "Срок действия входа истёк"
    "denied" -> "Вход отклонён"
    "revoked" -> "Доступ отозван"
    "superseded" -> "Вход заменён новой попыткой"
    "not_authorized" -> "Требуется вход"
    else -> "Статус авторизации неизвестен"
}

/**
 * Состояние авторизации MCP-подключения и переходы ViewModel:
 * - «Войти» (loginMcpServer) → [onLoginStarted]: сервер вернул authUrl,
 *   открывается системный браузер / Custom Tabs (Intent.ACTION_VIEW);
 * - возврат в приложение (onResume) или «Проверить» → [onStatusChecked]:
 *   свежий oauthStatus от моста обновляет статус и список инструментов;
 * - «Выйти» (revoke) → [onRevoked]: токены отозваны, вход требуется снова.
 */
data class McpAuthState(
    /** Нормализованный статус; "" — статус авторизации неизвестен. */
    val status: String = "",
    /** Адрес страницы входа, если сервер его вернул (authUrl). */
    val authUrl: String = "",
    /** Идентификатор сессии входа (sessionId ответа oauthStart). */
    val sessionId: String = ""
) {
    /** Авторизован только после подтверждённого статуса authorized. */
    val authorized: Boolean get() = status == "authorized"

    /** Метка для UI: требуется вход / авторизован / ошибка токена / … */
    val displayRu: String get() = describeMcpAuthStatus(status)

    /** Есть невыполненный вход с адресом страницы — можно открыть браузер. */
    val needsBrowserLogin: Boolean get() = !authorized && authUrl.isNotBlank()

    /** Доступен ли отзыв токенов (выход). */
    val canRevoke: Boolean get() = authorized

    /** «Войти»: получен authUrl — вход начат, ждём возврата из браузера. */
    fun onLoginStarted(authUrl: String, sessionId: String = ""): McpAuthState =
        copy(
            status = "pending",
            authUrl = authUrl.trim(),
            sessionId = sessionId.trim().ifBlank { this.sessionId }
        )

    /** Возврат в приложение / «Проверить»: свежий статус от моста. */
    fun onStatusChecked(rawStatus: String, authorized: Boolean, authUrl: String = ""): McpAuthState =
        copy(
            status = normalizeMcpAuthStatus(rawStatus, authorized),
            authUrl = authUrl.trim().ifBlank { this.authUrl }
        )

    /** Выход: токены отозваны мостом, состояние входа сброшено. */
    fun onRevoked(): McpAuthState = McpAuthState(status = "not_authorized")
}
