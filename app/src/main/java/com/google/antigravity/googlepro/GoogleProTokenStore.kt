package com.google.antigravity.googlepro

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Токен не получен: пользователю нужно снова войти, повтор не поможет. */
internal class GoogleProAuthFailure(message: String) : Exception(message)

/**
 * Хранилище токенов подписки.
 *
 * Файл лежит в `noBackupFilesDir` (как незавершённый MCP-вход): токен не уезжает
 * в облачную резервную копию и не переживает переустановку «случайным» образом.
 * Права — только владельцу.
 *
 * Обновление одним замком: параллельные ходы не должны обменивать один и тот же
 * refresh-токен дважды — Google отдаёт новый refresh и старый может погаснуть.
 */
class GoogleProTokenStore(
    dir: File,
    private val http: OkHttpClient
) {

    data class Tokens(
        val access: String,
        val refresh: String,
        val expiresAt: Long,
        val email: String,
        /** Проект, закреплённый за тарифом при онбординге; без него сервис не отвечает. */
        val project: String = ""
    ) {
        val expired: Boolean get() = expiresAt <= System.currentTimeMillis() + REFRESH_MARGIN_MS
    }

    private val file = File(dir, "google-pro-auth.json")
    private val mutex = Mutex()

    fun load(): Tokens? = runCatching {
        val json = JSONObject(file.readText())
        val access = json.optString("access")
        val refresh = json.optString("refresh")
        // Вход бывает и одним refresh-токеном: пустой access сам по себе не отказ.
        if (access.isBlank() && refresh.isBlank()) null else Tokens(
            access = access,
            refresh = refresh,
            expiresAt = json.optLong("expiresAt", 0),
            email = json.optString("email"),
            project = json.optString("project")
        )
    }.getOrNull()

    val signedIn: Boolean get() = file.exists()

    val email: String get() = load()?.email.orEmpty()

    /** Проект тарифа, закреплённый при онбординге (пусто, пока его нет). */
    val project: String get() = load()?.project.orEmpty()

    fun saveProject(project: String) {
        val current = load() ?: return
        save(current.access, current.refresh, (current.expiresAt - System.currentTimeMillis()) / 1000, current.email, project)
    }

    fun save(access: String, refresh: String, expiresInSec: Long, email: String, project: String = "") {
        val merged = if (refresh.isBlank()) load()?.refresh.orEmpty() else refresh
        val json = JSONObject()
            .put("access", access)
            .put("refresh", merged)
            .put("expiresAt", if (access.isBlank()) 0L else System.currentTimeMillis() + expiresInSec.coerceAtLeast(60) * 1000)
            .put("email", email.ifBlank { load()?.email.orEmpty() })
            .put("project", project.ifBlank { load()?.project.orEmpty() })
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.toString())
            file.delete()
            tmp.renameTo(file)
            file.setReadable(false, false)
            file.setReadable(true, true)
        }
    }

    fun clear() {
        file.delete()
    }

    /** Действующий токен доступа; истёкший обновляется заранее. */
    suspend fun accessToken(): String = mutex.withLock {
        val current = load() ?: throw GoogleProAuthFailure("Нет входа в аккаунт Google. Войдите заново в настройках.")
        if (current.access.isNotBlank() && !current.expired) return@withLock current.access
        if (current.refresh.isBlank()) {
            clear()
            throw GoogleProAuthFailure("Срок входа истёк и обновление недоступно. Войдите заново.")
        }
        val response = runCatching {
            http.newCall(
                Request.Builder().url(GoogleProConfig.TOKEN_ENDPOINT)
                    .post(FormBody.Builder()
                        .add("client_id", GoogleProConfig.CLIENT_ID)
                        .add("client_secret", GoogleProConfig.CLIENT_SECRET)
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", current.refresh)
                        .build())
                    .build()
            ).execute().use { it.body?.string().orEmpty() to it.code }
        }.getOrElse {
            // Сеть могла пропасть: старый токен ещё может быть жив, отдаём его.
            return@withLock current.access
        }
        val json = runCatching { JSONObject(response.first) }.getOrNull()
        val access = json?.optString("access_token").orEmpty()
        if (response.second !in 200..299 || access.isBlank()) {
            clear()
            throw GoogleProAuthFailure("Google не подтвердил сессию (HTTP ${response.second}). Войдите заново.")
        }
        save(access, json!!.optString("refresh_token"), json.optLong("expires_in", 3600), current.email)
        access
    }

    /** Адрес почты из ID-токена не читаем: сверяем через userinfo тем же токеном. */
    suspend fun resolveEmail(accessToken: String): String = runCatching {
        http.newCall(
            Request.Builder().url(GoogleProConfig.USERINFO_ENDPOINT)
                .header("Authorization", "Bearer $accessToken")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) "" else JSONObject(response.body?.string().orEmpty()).optString("email")
        }
    }.getOrDefault("")

    private companion object {
        const val REFRESH_MARGIN_MS = 120_000L

        @Suppress("unused")
        val NO_PROXY_TAG = Unit
    }
}

/**
 * Системный DNS с публичным запасом.
 *
 * Вход и обновление токена идут через `oauth2.googleapis.com`; если системный
 * резолвер на секунду сбоит (в эмуляторе это обычное дело), вход падал бы
 * «Unable to resolve host». Публичные резолверы спрашиваются только как запас.
 */
internal object SystemThenPublicDns : okhttp3.Dns {
    override fun lookup(hostname: String): List<java.net.InetAddress> {
        runCatching { okhttp3.Dns.SYSTEM.lookup(hostname) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        for (server in GoogleProConfig.REFERENCE_ADDRESSES) {
            val addresses = query(hostname, server)
            if (addresses.isNotEmpty()) return addresses.map { java.net.InetAddress.getByName(it) }
        }
        return okhttp3.Dns.SYSTEM.lookup(hostname)
    }

    private fun query(host: String, server: String): List<String> = runCatching {
        val id = kotlin.random.Random.nextInt(1, 0xFFFF)
        val query = DnsWire.query(id, host)
        java.net.DatagramSocket().use { socket ->
            socket.soTimeout = 900
            socket.connect(java.net.InetSocketAddress(server, 53))
            socket.send(java.net.DatagramPacket(query, query.size))
            val buffer = ByteArray(1500)
            val packet = java.net.DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            DnsWire.answerAddresses(buffer.copyOf(packet.length), id)
        }
    }.getOrDefault(emptyList())
}

internal fun defaultTokenHttp(): OkHttpClient = OkHttpClient.Builder()
    .dns(SystemThenPublicDns)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()
