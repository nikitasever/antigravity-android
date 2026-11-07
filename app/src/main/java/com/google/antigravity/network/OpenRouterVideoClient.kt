package com.google.antigravity.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class VideoJobFailure(val state: String, message: String, val httpStatus: Int = 0) : java.io.IOException(message) {
    val terminal: Boolean get() = state in setOf("failed", "cancelled", "expired")
}

/** One submit, then status/content only. Routing and key stay with the original bridge. */
internal class OpenRouterVideoClient(client: OkHttpClient, private val pollIntervalMs: Long = 30000,
    private val maxPolls: Int = 40) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(90, TimeUnit.SECONDS).build()

    suspend fun execute(base: String, requestId: String, model: String, prompt: String, key: String,
        resume: Boolean = false, onProgress: suspend (String) -> Unit = {}): JSONObject = withContext(Dispatchers.IO) {
        require(requestId.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        suspend fun post(route: String, submit: Boolean = false): JSONObject {
            val payload = JSONObject().put("requestId", requestId)
            if (key.isNotBlank()) payload.put("apiKey", key)
            if (submit) payload.put("model", model).put("prompt", prompt)
            val request = Request.Builder().url(base.trimEnd('/') + "/api/openrouter/" + route)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val transport = if (route == "video-download") http.newBuilder().callTimeout(130, TimeUnit.SECONDS).build() else http
            return transport.newCall(request).useCancellable { response ->
                val data = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrDefault(JSONObject())
                if (!response.isSuccessful || !data.optBoolean("ok")) {
                    val detail = data.optString("error", "HTTP ${response.code}").take(1000)
                    val job = data.optJSONObject("job")
                    val state = if (job?.optString("requestId") == requestId) job.optString("status") else ""
                    throw VideoJobFailure(state, if (key.isBlank()) detail else detail.replace(key, "[скрыто]"), response.code)
                }
                data
            }
        }
        fun job(data: JSONObject): JSONObject {
            val job = data.optJSONObject("job") ?: error("Мост не вернул видеозадачу")
            check(job.optString("requestId") == requestId) { "Мост вернул другую видеозадачу" }
            return job
        }
        var current = job(post(if (resume) "video-status" else "generate-video", !resume))
        var polls = 0
        while (true) {
            val status = current.optString("status")
            onProgress(status)
            when (status) {
                "completed" -> return@withContext post("video-download").optJSONObject("media")
                    ?.takeIf { it.optString("mediaUrl").isNotBlank() } ?: error("Мост не вернул готовый видеофайл")
                "failed", "cancelled", "expired", "unknown" -> {
                    val detail = current.optString("error").ifBlank { "Видеозадача $requestId: $status" }
                    throw VideoJobFailure(status, if (key.isBlank()) detail else detail.replace(key, "[скрыто]"))
                }
                "pending", "in_progress", "submitting" -> Unit
                else -> error("Неизвестный статус видеозадачи")
            }
            if (polls++ >= maxPolls) throw java.io.IOException("Ожидание приостановлено. Задача $requestId сохранена на мосту; повторный запуск не выполнялся.")
            delay(pollIntervalMs)
            current = job(post("video-status"))
        }
        @Suppress("UNREACHABLE_CODE") JSONObject()
    }
}
