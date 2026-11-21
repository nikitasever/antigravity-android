package com.google.antigravity.googlepro

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal data class GoogleOAuthCallback(val state: String, val code: String, val error: String)

/**
 * Разбор ответа авторизации. Принимается только наш путь и только ожидаемое
 * состояние; чужой deep link не считается ответом (как у MCP-входа).
 */
internal fun parseGoogleOAuthCallback(raw: String, expectedState: String): GoogleOAuthCallback? = runCatching {
    val uri = URI(raw)
    require(uri.scheme == GoogleProConfig.DEEP_LINK_SCHEME && uri.rawAuthority == null)
    require(uri.path == GoogleProConfig.REDIRECT_PATH && uri.fragment == null)
    require(expectedState.isNotBlank())
    val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }
    fun single(key: String): String {
        val values = params.filter { it.first == key }.map { it.second }
        require(values.size <= 1)
        return values.singleOrNull().orEmpty()
    }
    val state = single("state")
    require(state == expectedState)
    val code = single("code")
    val error = single("error")
    require((code.isNotBlank() && error.isBlank()) || (error.isNotBlank() && code.isBlank()))
    require(code.length <= 4096 && error.length <= 256)
    GoogleOAuthCallback(state, code, error)
}.getOrNull()

/**
 * Вход в аккаунт Google по RFC 8252: PKCE, loopback-редирект и возврат в
 * приложение через тот же deep link, что у MCP-входа.
 *
 * Секрет клиента здесь не тайна — у installed-app клиента его видно в трафике;
 * защиту даёт PKCE (verifier известен только приложению).
 */
class GoogleProSignIn(
    private val http: OkHttpClient,
    private val clientId: String = GoogleProConfig.CLIENT_ID,
    private val clientSecret: String = GoogleProConfig.CLIENT_SECRET,
    private val scopes: List<String> = GoogleProConfig.SCOPES
) : Closeable {

    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
    private val loopbackUri = "http://127.0.0.1:${server.localPort}${GoogleProConfig.REDIRECT_PATH}"
    private val verifier = randomToken(64)
    private val state = randomToken(32)

    /**
     * Два адреса возврата: loopback (сработает в любом браузере на телефоне) и наш
     * deep link (если браузер или системный диалог открывает его напрямую).
     */
    val redirectUri: String = loopbackUri

    private val delivered = CompletableDeferred<String>()

    val authUrl: String = buildString {
        append(GoogleProConfig.AUTH_ENDPOINT)
        append("?client_id=").append(enc(clientId))
        append("&redirect_uri=").append(enc(loopbackUri))
        append("&response_type=code")
        append("&scope=").append(enc(scopes.joinToString(" ")))
        append("&state=").append(enc(state))
        append("&code_challenge=").append(enc(challenge(verifier)))
        append("&code_challenge_method=S256")
        append("&access_type=offline")
        append("&prompt=consent")
        append("&include_granted_scopes=true")
    }

    /** Ответ, пришедший deep link-ом из MainActivity. */
    fun deliver(uri: String) {
        if (!delivered.isCompleted) delivered.complete(uri)
    }

    /**
     * Ждём возврат браузера. Параллельно слушаем loopback, а `deliver` может
     * закрыть ожидание раньше. Возвращается код авторизации.
     */
    suspend fun awaitCode(timeoutMs: Long = 8 * 60_000L): String = withContext(Dispatchers.IO) {
        val loopbackJob = CompletableDeferred<String>()
        Thread({
            try {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!server.isClosed && System.currentTimeMillis() < deadline && !loopbackJob.isCompleted) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    socket.use { open ->
                        open.soTimeout = 2000
                        val line = StringBuilder()
                        val reader = open.getInputStream()
                        while (line.length < 8192) {
                            val next = reader.read()
                            if (next < 0 || next == 10) break
                            line.append(next.toChar())
                        }
                        val target = URI(line.toString().trim().split(' ').getOrElse(1) { "" })
                        val valid = target.path == GoogleProConfig.REDIRECT_PATH && target.rawAuthority == null
                        val callback = if (valid) "com.google.antigravity:${GoogleProConfig.REDIRECT_PATH}?${target.rawQuery.orEmpty()}" else ""
                        val ok = parseGoogleOAuthCallback(callback, state) != null
                        val body = if (ok) DONE_PAGE.replace("https://continue/", deepLink(callback))
                        else "<!doctype html><meta charset=\"utf-8\"><p>Неверный ответ авторизации.</p>"
                        val bytes = body.toByteArray()
                        open.getOutputStream().apply {
                            write(("HTTP/1.1 ${if (ok) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                            write(bytes); flush()
                        }
                        if (ok && !loopbackJob.isCompleted) loopbackJob.complete(callback)
                    }
                }
            } catch (_: Throwable) {
                // закрытый сокет при выходе — не ошибка входа
            }
        }, "google-pro-oauth").apply { isDaemon = true }.start()

        val raw = withTimeoutOrNull(timeoutMs) {
            // Первый завершившийся источник выигрывает, второй отменяется.
            kotlinx.coroutines.selects.select<String> {
                delivered.onAwait { it }
                loopbackJob.onAwait { it }
            }
        } ?: throw GoogleProAuthFailure("Браузер не вернул ответ за отведённое время. Попробуйте войти заново.")

        val callback = parseGoogleOAuthCallback(raw, state)
            ?: throw GoogleProAuthFailure("Неверный или истёкший ответ авторизации.")
        if (callback.error.isNotBlank()) throw GoogleProAuthFailure("Google отказал во входе: ${callback.error}")
        callback.code
    }

    /**
     * Обмен кода на токены. Возвращает тройку (access, refresh, срок жизни).
     *
     * Повторы по сети — не суеверие: обмен стартует, пока приложение ещё в фоне,
     * и Android успевает заморозить процесс, а с ним и резолвер имён. Код живёт
     * минуты, поэтому несколько попыток с паузой дешевле повторного входа.
     */
    suspend fun exchange(code: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(GoogleProConfig.TOKEN_ENDPOINT)
            .post(FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", redirectUri)
                .build())
            .build()
        var last: Exception? = null
        for (attempt in 1..TOKEN_ATTEMPTS) {
            try {
                val (body, status) = http.newCall(request).execute().use { it.body?.string().orEmpty() to it.code }
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (status !in 200..299 || json?.optString("access_token").isNullOrBlank()) {
                    throw GoogleProAuthFailure("Google не выдал токен (HTTP $status): ${body.take(220)}")
                }
                return@withContext json!!
            } catch (failure: java.io.IOException) {
                last = failure
                kotlinx.coroutines.delay(TOKEN_RETRY_DELAY_MS * attempt)
            }
        }
        throw GoogleProAuthFailure("Google не принял обмен кода: ${last?.message ?: "сеть недоступна"}")
    }

    override fun close() {
        runCatching { server.close() }
    }

    private companion object {
        const val TOKEN_ATTEMPTS = 4
        const val TOKEN_RETRY_DELAY_MS = 900L

        const val DONE_PAGE =
            "<!doctype html><html lang=\"ru\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
                "<title>Antigravity</title><p>Вход получен. Вернитесь в Antigravity.</p>" +
                "<p><a href=\"https://continue/\">Открыть Antigravity</a></p></html>"

        fun deepLink(callback: String): String = callback

        fun randomToken(bytes: Int): String =
            ByteArray(bytes).also { SecureRandom().nextBytes(it) }.let {
                Base64.getUrlEncoder().withoutPadding().encodeToString(it)
            }

        fun challenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
