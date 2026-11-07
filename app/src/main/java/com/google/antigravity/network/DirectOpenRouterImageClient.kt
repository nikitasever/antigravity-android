package com.google.antigravity.network

import com.google.antigravity.model.GeneratedMediaAttachment
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class OpenRouterImageFailure(message: String) : Exception(message)

/** One paid request to a canonical endpoint. No bridge, remote URL downloads or paid fallback. */
internal class DirectOpenRouterImageClient(client: OkHttpClient = OkHttpClient(),
    private val imageBounds: (ByteArray) -> Pair<Int, Int>? = { bytes ->
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth > 0 && options.outHeight > 0 && options.outWidth <= 32768 && options.outHeight <= 32768 &&
            options.outWidth.toLong() * options.outHeight <= 100_000_000) options.outWidth to options.outHeight else null
    }) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

    suspend fun execute(model: String, prompt: String, key: String, directory: File,
        knownChatImageModel: Boolean = false,
        onSaved: suspend (GeneratedMediaAttachment) -> Unit = {}): GeneratedMediaAttachment = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw OpenRouterImageFailure("Укажите ключ OpenRouter на телефоне.")
        if (model.isBlank() || prompt.isBlank()) throw OpenRouterImageFailure("Выберите модель и опишите изображение.")
        val catalog = json(Request.Builder().url("https://openrouter.ai/api/v1/images/models").build(), key, 4 * 1024 * 1024)
        val rows = catalog.optJSONArray("data") ?: throw OpenRouterImageFailure("OpenRouter не вернул каталог изображений.")
        val dedicated = (0 until rows.length()).any { rows.optJSONObject(it)?.optString("id") == model }
        if (!dedicated && !knownChatImageModel) throw OpenRouterImageFailure("Выбранная модель отсутствует в Image API. Обновите каталог и выберите модель изображений.")
        val payload = JSONObject().put("model", model)
        if (dedicated) payload.put("prompt", prompt).put("n", 1)
        else payload.put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("modalities", JSONArray().put("image").put("text")).put("stream", false)
        val response = json(Request.Builder().url(if (dedicated) "https://openrouter.ai/api/v1/images"
            else "https://openrouter.ai/api/v1/chat/completions")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build(), key, 32 * 1024 * 1024)
        val encoded = if (dedicated) response.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json").orEmpty()
            else {
                val message = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val uri = message?.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("image_url")?.optString("url").orEmpty()
                if (!uri.startsWith("data:image/") || !uri.substringBefore(',').endsWith(";base64"))
                    throw OpenRouterImageFailure("Модель не вернула изображение как base64. Ссылки провайдера автоматически не загружаются.")
                uri.substringAfter(',', "")
            }
        if (encoded.isBlank() || encoded.length > 28 * 1024 * 1024) throw OpenRouterImageFailure("Изображение отсутствует или превышает лимит размера.")
        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
            throw OpenRouterImageFailure("OpenRouter вернул повреждённые данные изображения.")
        }
        if (bytes.size > 20 * 1024 * 1024) throw OpenRouterImageFailure("Изображение превышает 20 МБ.")
        val (mime, extension) = imageType(bytes) ?: throw OpenRouterImageFailure("Ответ не содержит поддерживаемое изображение PNG, JPEG или WebP.")
        val bounds = imageBounds(bytes) ?: throw OpenRouterImageFailure("Изображение повреждено или имеет неподдерживаемый размер.")
        currentCoroutineContext().ensureActive()
        // Once the complete result is available, persist it and bind it to its original chat together.
        withContext(NonCancellable) {
            if (!directory.isDirectory && !directory.mkdirs()) throw OpenRouterImageFailure("Не удалось создать папку изображений на телефоне.")
            val id = UUID.randomUUID().toString()
            val file = File(directory, "openrouter_$id.$extension")
            val partial = File(directory, ".image_$id.part")
            try {
                java.io.FileOutputStream(partial).use { it.write(bytes); it.fd.sync() }
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally { partial.delete() }
            val media = GeneratedMediaAttachment(id = id, type = "image", fileName = file.name, mediaUrl = "",
                localPath = file.absolutePath, localPhonePath = file.absolutePath, mimeType = mime,
                modelId = model, prompt = prompt, sizeBytes = file.length(), width = bounds.first, height = bounds.second)
            onSaved(media)
            media
        }
    }

    private suspend fun json(request: Request, key: String, limit: Int): JSONObject =
        http.newCall(request.newBuilder().header("Authorization", "Bearer ${key.trim()}")
            .header("X-OpenRouter-Title", "Antigravity Android").build()).useCancellable { response ->
            if (response.code != 200) throw apiFailure(response.code)
            val body = response.body ?: throw OpenRouterImageFailure("Пустой ответ OpenRouter.")
            if (body.contentLength() > limit) throw OpenRouterImageFailure("Ответ OpenRouter превышает допустимый размер.")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(16384)
                while (true) {
                    val read = input.read(buffer); if (read < 0) break
                    if (output.size() + read > limit) throw OpenRouterImageFailure("Ответ OpenRouter превышает допустимый размер.")
                    output.write(buffer, 0, read)
                }
            }
            val json = try { JSONObject(output.toString("UTF-8")) } catch (_: org.json.JSONException) {
                throw OpenRouterImageFailure("Некорректный ответ OpenRouter.")
            }
            if (json.has("error") && !json.isNull("error")) throw apiFailure(json.optJSONObject("error")?.optInt("code", 0) ?: 0)
            json
        }

    private fun apiFailure(code: Int) = OpenRouterImageFailure(when (code) {
        401 -> "Недействительный ключ OpenRouter (HTTP 401). Проверьте ключ на телефоне."
        402 -> "Недостаточно средств OpenRouter (HTTP 402). Проверьте баланс и лимит ключа."
        429 -> "Достигнут лимит OpenRouter (HTTP 429). Повторите позже вручную."
        else -> "OpenRouter не вернул изображение${if (code > 0) " (HTTP $code)" else ""}. Автоматический повтор не отправлялся."
    })

    private fun imageType(bytes: ByteArray): Pair<String, String>? = when {
        bytes.size >= 24 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() } -> "image/png" to "png"
        bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg" to "jpg"
        bytes.size >= 16 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp" to "webp"
        else -> null
    }
}
