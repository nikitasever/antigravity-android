package com.google.antigravity.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Contains only application-owned messages: provider bodies may contain credentials or prompts. */
class OpenRouterChatFailure(val status: Int, message: String) : Exception(message)

internal data class OpenRouterChatReply(val text: String, val reasoning: String, val outputTokens: Int)

/** Direct phone inference. One POST, exact selected model, no redirects or automatic replay. */
internal class DirectOpenRouterChatClient(client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(240, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS).build()

    suspend fun complete(model: String, key: String, messages: JSONArray): OpenRouterChatReply = withContext(Dispatchers.IO) {
        val selected = model.trim()
        if (selected.isEmpty()) throw OpenRouterChatFailure(0, "Выберите модель OpenRouter.")
        if (key.isBlank()) throw failure(401)
        val payload = JSONObject().put("model", selected).put("messages", messages)
            .put("stream", false).put("max_tokens", 4096).put("include_reasoning", true)
        val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${key.trim()}")
            .header("X-OpenRouter-Title", "Antigravity Android")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(request).useCancellable { response ->
            if (!response.isSuccessful) throw failure(response.code)
            val json = try { JSONObject(response.body?.string().orEmpty()) } catch (_: org.json.JSONException) {
                throw OpenRouterChatFailure(0, "OpenRouter вернул некорректный ответ. Повторите запрос вручную.")
            }
            if (json.has("error") && !json.isNull("error")) throw failure(json.optJSONObject("error")?.optInt("code", 0) ?: 0)
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
            val message = choice?.optJSONObject("message")
                ?: throw OpenRouterChatFailure(0, "OpenRouter не вернул сообщение модели.")
            val content = message.opt("content")
            val text = when (content) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it) }
                    .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                else -> ""
            }.trim()
            if (text.isEmpty()) throw OpenRouterChatFailure(0, if (choice?.optString("finish_reason") == "length")
                "Модель исчерпала лимит ответа до готового текста. Сократите запрос или выберите другую модель."
                else "Модель не вернула текст. Проверьте, что выбрана текстовая модель; для фото и видео используйте генерацию медиа.")
            OpenRouterChatReply(text, message.optString("reasoning", "").takeUnless { it == "null" }.orEmpty(),
                json.optJSONObject("usage")?.optInt("completion_tokens", 0)?.coerceAtLeast(0) ?: 0)
        }
    }

    private fun failure(status: Int) = OpenRouterChatFailure(status, when (status) {
        400, 422 -> "OpenRouter отклонил параметры запроса (HTTP $status). Проверьте выбранную модель."
        401 -> "Ключ OpenRouter отсутствует, недействителен или отозван (HTTP 401). Сохраните действующий ключ на телефоне."
        402 -> "Недостаточно средств OpenRouter для запроса (HTTP 402). Проверьте баланс и лимит ключа."
        403 -> "OpenRouter запретил запрос (HTTP 403). Проверьте доступ ключа и ограничения провайдера."
        404 -> "Выбранная модель OpenRouter недоступна (HTTP 404). Обновите список и выберите доступную модель."
        408, 504 -> "OpenRouter не успел завершить запрос (HTTP $status). Автоматический повтор не отправлялся."
        429 -> "Достигнут лимит OpenRouter (HTTP 429). Подождите и повторите запрос вручную."
        502, 503 -> "Провайдер модели временно недоступен (HTTP $status). Повторите позже или выберите другую модель."
        in 300..399 -> "OpenRouter вернул перенаправление. Ключ не передавался на другой адрес."
        else -> "OpenRouter не смог завершить запрос${if (status > 0) " (HTTP $status)" else ""}. Повторите позже."
    })
}
