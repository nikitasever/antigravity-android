package com.google.antigravity.network

import com.google.antigravity.model.GeneratedMediaAttachment
import com.google.antigravity.model.VideoRun
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** Canonical OpenRouter endpoints only. No paid retries, bridge fallback or provider URL persistence. */
internal class DirectOpenRouterVideoClient(client: OkHttpClient = OkHttpClient(), private val pollIntervalMs: Long = 30000,
    private val maxPolls: Int = 40) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(90, TimeUnit.SECONDS).build()
    private val base = (VideoRun.DIRECT_API_BASE + "/videos").toHttpUrl()

    suspend fun execute(run: VideoRun, key: String, directory: File,
        onSubmitting: suspend () -> Unit, onAccepted: suspend (String) -> Unit,
        onProgress: suspend (String) -> Unit): GeneratedMediaAttachment = withContext(Dispatchers.IO) {
        require(key.isNotBlank())
        require(run.transport == com.google.antigravity.model.VideoTransport.PHONE_API)
        require(run.keyHash == java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
        var id = run.providerJobId
        if (id.isEmpty()) {
            if (run.submissionAttempted) throw VideoJobFailure("unknown", "Ответ запуска потерян; ID провайдера неизвестен. Новая генерация не отправлялась")
            val models = json(Request.Builder().url(base.newBuilder().addPathSegment("models").build()).build(), key)
            val candidates = models.optJSONArray("data")
            val capabilities = (0 until (candidates?.length() ?: 0)).mapNotNull { candidates?.optJSONObject(it) }
                .firstOrNull { it.optString("id") == run.model }
                ?: throw VideoJobFailure("failed", "Модель отсутствует в видео-каталоге OpenRouter")
            val payload = JSONObject().put("model", run.model).put("prompt", run.prompt)
            capabilities.optJSONArray("supported_durations")?.let { durations ->
                (0 until durations.length()).mapNotNull { index ->
                    (durations.opt(index) as? Number)?.toDouble()?.takeIf { it > 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
                }.minOrNull()?.let { payload.put("duration", it) }
            }
            onSubmitting() // Must durably commit before the only paid POST.
            onProgress("submitting")
            val response = try {
                json(Request.Builder().url(base).post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build(), key, submitting = true)
            } catch (error: CancellationException) { throw error }
            catch (error: VideoJobFailure) { throw error }
            catch (_: Exception) { throw VideoJobFailure("unknown", "Ответ запуска потерян. Повторная генерация не выполнялась") }
            id = response.optString("id").takeIf { VideoRun.validProviderId(it) }
                ?: throw VideoJobFailure("unknown", "OpenRouter не вернул однозначный ID задачи. Повторная генерация не выполнялась")
            onAccepted(id) // Persist even an immediately completed job before accessing content.
        }
        val jobUrl = base.newBuilder().addPathSegment(id).build()
        var polls = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val job = json(Request.Builder().url(jobUrl).build(), key)
            if (job.optString("id") != id) throw IOException("OpenRouter вернул другую видеозадачу")
            val state = job.optString("status")
            onProgress(state)
            when (state) {
                "completed" -> return@withContext content(run, key, jobUrl.newBuilder().addPathSegment("content").addQueryParameter("index", "0").build(), directory,
                    job.optInt("duration", 0).coerceAtLeast(0))
                "failed", "cancelled", "expired" -> throw VideoJobFailure(state, safe(job.optString("error").ifBlank { "Видеозадача: $state" }, key))
                "pending", "in_progress" -> Unit
                else -> throw VideoJobFailure("unknown", "OpenRouter вернул неизвестный статус. Повторная генерация не выполнялась")
            }
            if (polls++ >= maxPolls) throw IOException("Ожидание приостановлено; ID задачи сохранён на телефоне")
            delay(pollIntervalMs)
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }

    private suspend fun json(request: Request, key: String, submitting: Boolean = false): JSONObject =
        http.newCall(request.newBuilder().header("Authorization", "Bearer $key").build()).useCancellable { response ->
            val source = response.body?.source() ?: throw IOException("Пустой ответ OpenRouter")
            source.request(4L * 1024 * 1024 + 1)
            if (source.buffer.size > 4L * 1024 * 1024) throw IOException("Слишком большой ответ OpenRouter")
            val body = source.readUtf8()
            val data = runCatching { JSONObject(body) }.getOrNull()
            if (!response.isSuccessful) {
                val detail = safe(data?.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "OpenRouter HTTP ${response.code}" }, key)
                if (submitting) throw VideoJobFailure(if (response.code in 400..499) "failed" else "unknown", detail, response.code)
                throw IOException(detail)
            }
            data ?: throw IOException("OpenRouter вернул некорректный JSON")
        }

    private suspend fun content(run: VideoRun, key: String, original: HttpUrl, directory: File, duration: Int): GeneratedMediaAttachment {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Не удалось создать каталог медиа на телефоне")
        val temporary = File.createTempFile("video-download-", ".part", directory)
        try {
            var url = original
            repeat(4) { hop ->
                currentCoroutineContext().ensureActive()
                val request = Request.Builder().url(url).apply { if (hop == 0) header("Authorization", "Bearer $key") }.build()
                var redirect: HttpUrl? = null
                val result = http.newBuilder().callTimeout(130, TimeUnit.SECONDS).build().newCall(request).useCancellable { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        redirect = response.header("Location")?.let { url.resolve(it) }
                            ?.takeIf { it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() }
                            ?: throw IOException("Небезопасный адрес загрузки видео")
                        null
                    } else {
                        if (response.code != 200) throw IOException("Загрузка полного видео: HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Нет видеофайла")
                        val expected = body.contentLength()
                        val maximum = 256L * 1024 * 1024
                        if (expected > maximum) throw IOException("Видео превышает 256 МБ")
                        var received = 0L
                        body.byteStream().use { input -> temporary.outputStream().use { output ->
                            val buffer = ByteArray(16384)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > maximum) throw IOException("Видео превышает 256 МБ")
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        } }
                        if (received <= 0 || expected >= 0 && received != expected) throw IOException("Видеофайл получен не полностью")
                        val head = temporary.inputStream().use { input -> ByteArray(1024).let { bytes -> bytes.copyOf(input.read(bytes).coerceAtLeast(0)) } }
                        val format = format(head) ?: throw IOException("OpenRouter вернул неизвестный формат видео")
                        val fileName = "openrouter_video_${run.requestId}.${format.first}"
                        val destination = File(directory, fileName)
                        currentCoroutineContext().ensureActive()
                        try { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                        GeneratedMediaAttachment(id = run.requestId, type = "video", fileName = fileName, mediaUrl = "",
                            localPath = destination.absolutePath, localPhonePath = destination.absolutePath,
                            modelId = run.model, prompt = run.prompt, mimeType = format.second, durationSec = duration,
                            width = 0, height = 0, resolution = "", sizeBytes = received)
                    }
                }
                if (result != null) return result
                url = redirect ?: throw IOException("Не получен адрес видео")
            }
            throw IOException("Слишком много перенаправлений загрузки видео")
        } finally { temporary.delete() }
    }

    private fun safe(message: String, key: String) = message.replace(key, "[скрыто]").replace(Regex("https?://\\S+"), "[URL]").take(1000)
    private fun format(head: ByteArray): Pair<String, String>? {
        if (head.size >= 12 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" &&
            Regex("isom|iso[2-9]|mp41|mp42|avc1|dash|M4V |MSNV").matches(String(head, 8, 4, Charsets.US_ASCII))) return "mp4" to "video/mp4"
        if (head.size >= 4 && head.take(4) == listOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte()) &&
            String(head, Charsets.ISO_8859_1).contains("webm")) return "webm" to "video/webm"
        return null
    }
}
