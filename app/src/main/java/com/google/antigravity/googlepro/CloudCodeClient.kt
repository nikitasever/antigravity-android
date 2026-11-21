package com.google.antigravity.googlepro

import com.google.antigravity.model.ChatMessage
import com.google.antigravity.model.MessageRole
import com.google.antigravity.network.GeminiChatFailure
import com.google.antigravity.network.ProviderReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Состояние тарифа, полученное от CloudCode. */
data class ProState(
    val tierId: String,
    val tierName: String,
    val models: List<String>,
    val project: String,
    /** Причина отказа проверки региона (`ineligibleTiers`), если была. */
    val ineligibleReasons: List<String>,
    /** true — клиент пошёл дальше, несмотря на `ineligible` (перенос правки `inexigible`). */
    val passedGate: Boolean,
    /** Тариф, который клиент обязан выбрать сам, назвав проект (`allowedTiers`). */
    val defaultTierId: String = "",
    /** true — сервер ждёт проект от нас: без него тариф не выдаётся. */
    val needsProject: Boolean = false,
    /** Тариф не выдан: ни текущего тарифа, ни разрешённых нет. */
    val unlicensed: Boolean = false
) {
    val usable: Boolean get() = tierId.isNotBlank() || passedGate
}

/**
 * Разбор ответа `loadCodeAssist`.
 *
 * Ключевое отличие от настольного клиента: там строку `ineligible` патчат в
 * бинаре, чтобы тот считал тариф годным. Мы владеем клиентом, поэтому просто не
 * считаем `ineligible` приговором — переносим смысл правки, а не байты.
 */
internal fun parseCodeAssist(json: JSONObject): ProState {
    val tier = json.optJSONObject("currentTier")
    val reasons = mutableListOf<String>()
    val ineligible = json.optJSONArray("ineligibleTiers")
    if (ineligible != null) {
        for (i in 0 until ineligible.length()) {
            val item = ineligible.optJSONObject(i) ?: continue
            val text = item.optString("reasonMessage").ifBlank { item.optString("reasonCode") }
            val tierName = item.optString("tierName")
            if (text.isNotBlank()) reasons += if (tierName.isBlank()) text else "$tierName: $text"
        }
    }
    val models = LinkedHashSet<String>()
    fun collectModels(source: JSONObject?) {
        val configs = source?.optJSONArray("modelConfigs") ?: source?.optJSONArray("models") ?: return
        for (i in 0 until configs.length()) {
            val item = configs.optJSONObject(i) ?: continue
            val id = item.optString("modelId").ifBlank {
                item.optString("id").ifBlank { item.optString("model") }
            }
            if (id.isNotBlank()) models += id
        }
    }
    collectModels(tier)
    json.optJSONArray("allowedTiers")?.let { tiers ->
        for (i in 0 until tiers.length()) collectModels(tiers.optJSONObject(i))
    }
    // Разрешённые тарифы: один из них клиент выбирает сам, назвав проект.
    var defaultTier = ""
    var needsProject = false
    json.optJSONArray("allowedTiers")?.let { tiers ->
        for (i in 0 until tiers.length()) {
            val item = tiers.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            if (defaultTier.isBlank() || item.optBoolean("isDefault")) defaultTier = id
            if (item.optBoolean("userDefinedCloudaicompanionProject")) needsProject = true
        }
    }
    val tierPresent = tier != null && tier.optString("id").isNotBlank()
    val passedGate = reasons.isNotEmpty() || !tierPresent
    return ProState(
        tierId = tier?.optString("id").orEmpty(),
        tierName = tier?.optString("name")?.ifBlank { tier.optString("id") }.orEmpty(),
        models = models.toList(),
        project = json.optString("cloudaicompanionProject"),
        ineligibleReasons = reasons,
        passedGate = passedGate,
        defaultTierId = defaultTier,
        needsProject = needsProject && !tierPresent,
        unlicensed = !tierPresent && defaultTier.isBlank()
    )
}

/** Одна строка SSE (`data: {...}`) как JSON; всё остальное — null. */
internal fun parseSseLine(line: String): JSONObject? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("data:")) return null
    val payload = trimmed.removePrefix("data:").trim()
    if (payload.isEmpty() || payload == "[DONE]") return null
    return runCatching { JSONObject(payload) }.getOrNull()
}

/**
 * Полезная часть чанка: `v1internal` заворачивает ответ в `{"response": {...}}`,
 * а `/v1beta`-совместимый вид — нет. Разворачиваем, иначе текст не находится.
 */
internal fun unwrapChunk(chunk: JSONObject): JSONObject = chunk.optJSONObject("response") ?: chunk

/** Текст и размышления из чанка; `thought` — отдельная ветка, не показывается как ответ. */
internal fun collectChunkText(chunk: JSONObject): ProviderReply {
    val parts = unwrapChunk(chunk).optJSONArray("candidates")?.optJSONObject(0)
        ?.optJSONObject("content")?.optJSONArray("parts") ?: return ProviderReply("")
    val text = StringBuilder()
    val thinking = StringBuilder()
    for (i in 0 until parts.length()) {
        val part = parts.optJSONObject(i) ?: continue
        val value = part.optString("text", "")
        if (part.optBoolean("thought")) thinking.append(value) else text.append(value)
    }
    return ProviderReply(text.toString(), thinking.toString())
}

internal fun outputTokensOf(chunk: JSONObject): Int =
    unwrapChunk(chunk).optJSONObject("usageMetadata")?.optInt("candidatesTokenCount", 0) ?: 0

/**
 * Клиент CloudCode (`v1internal`).
 *
 * Маршрут выбирается по леджеру: где модель уже ответила — первым, где приходил
 * отказ региона — последним. Отказ на маршруте не завершает запрос, а передаёт
 * ход следующему (src/routes.rs анлокера, узел D26: соединения отказавшего
 * маршрута при этом закрываются, иначе пул ещё минуту продолжает получать 400).
 */
class CloudCodeClient(
    private val tokens: GoogleProTokenStore,
    private val resolver: RegionGateResolver,
    private val ledger: RouteLedger,
    private val fingerprint: () -> String,
    private val bypassEnabled: () -> Boolean = { true }
) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val clients = ConcurrentHashMap<GateRoute, OkHttpClient>()

    @Volatile
    var lastState: ProState? = null

    /** Снимок маршрутов для окна настроек: подпись и счёт. */
    fun routeSummary(): String =
        ledger.order(fingerprint()).joinToString(" → ") { it.labelRu }

    /** Проверка входа: тариф, почта, доступные модели. */
    suspend fun loadCodeAssist(): ProState = withContext(Dispatchers.IO) {
        val known = tokens.project
        val body = JSONObject()
            .put("metadata", GoogleProConfig.metadata())
        if (known.isNotBlank()) body.put("cloudaicompanionProject", known)
        val raw = call("v1internal:loadCodeAssist", body, stream = false)
        val state = parseCodeAssist(runCatching { JSONObject(raw) }.getOrElse {
            throw GeminiChatFailure(0, "CloudCode вернул некорректный ответ: ${raw.take(200)}")
        })
        val project = state.project.ifBlank { known }
        // Состав моделей есть только в отдельном ответе; тариф его не перечисляет.
        val catalog = runCatching { fetchAvailableModels(project) }.getOrDefault(emptyList())
        lastState = state.copy(project = project, models = (catalog + state.models).distinct())
        lastState!!
    }

    /**
     * Онбординг тарифа: сервер ждёт проект от клиента, без него тариф не выдаётся
     * (`userDefinedCloudaicompanionProject`). Проект закрепляем за токеном, чтобы
     * следующие запросы шли уже с ним.
     */
    suspend fun onboard(project: String, tierId: String = DEFAULT_TIER): ProState = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("tierId", tierId)
            .put("cloudaicompanionProject", project)
            .put("metadata", GoogleProConfig.metadata())
        val raw = call("v1internal:onboardUser", body, stream = false)
        val state = parseCodeAssist(runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject())
        tokens.saveProject(state.project.ifBlank { project })
        state
    }

    /** Реальные идентификаторы моделей аккаунта (в отличие от стартового списка). */
    private suspend fun fetchAvailableModels(project: String): List<String> {
        // `metadata` этот метод не принимает: с ним ответ — 400 «Unknown name metadata».
        val body = JSONObject()
        if (project.isNotBlank()) body.put("project", project)
        val raw = call("v1internal:fetchAvailableModels", body, stream = false)
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val models = json.optJSONObject("models") ?: return emptyList()
        val ids = mutableListOf<String>()
        for (key in models.keys()) {
            val id = models.optJSONObject(key)?.optString("modelId").orEmpty().ifBlank { key }
            // Служебные псевдонимы вроде "auto" в каталог моделей не выносим.
            if (id.isNotBlank() && id != "auto") ids += id
        }
        return ids
    }

    /**
     * Один ход модели. Мысль и текст отдаются по мере прихода чанков.
     */
    suspend fun generate(
        model: String,
        prompt: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        onThinking: suspend (String) -> Unit
    ): Pair<ProviderReply, Int> = withContext(Dispatchers.IO) {
        val project = lastState?.project?.ifBlank { tokens.project }.orEmpty()
        val contents = JSONArray()
        for (message in history) {
            val text = message.content
            if (text.isBlank()) continue
            contents.put(JSONObject()
                .put("role", if (message.role == MessageRole.USER) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        contents.put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt))))

        val inner = JSONObject()
            .put("contents", contents)
            .put("generationConfig", JSONObject()
                .put("thinkingConfig", JSONObject().put("includeThoughts", true)))
        if (systemPrompt.isNotBlank()) {
            inner.put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
        }
        val body = JSONObject()
            .put("model", model)
            .put("user_prompt_id", java.util.UUID.randomUUID().toString())
            .put("request_type", GoogleProConfig.REQUEST_TYPE)
            .put("user_agent", GoogleProConfig.USER_AGENT)
            .put("enabled_credit_types", JSONArray().also { array -> GoogleProConfig.CREDIT_TYPES.forEach(array::put) })
            .put("request", inner)
        if (project.isNotBlank()) body.put("project", project)

        val text = StringBuilder()
        val thinking = StringBuilder()
        var tokens = 0
        call("v1internal:streamGenerateContent?alt=sse", body, stream = true) { chunk ->
            currentCoroutineContext().ensureActive()
            val (answer, thoughts) = collectChunkText(chunk)
            if (thoughts.isNotBlank()) {
                thinking.append(thoughts)
                onThinking(thinking.toString().trim())
            }
            text.append(answer)
            val usage = outputTokensOf(chunk)
            if (usage > 0) tokens = usage
            chunk.optJSONObject("error")?.let { error ->
                throw GeminiChatFailure(error.optInt("code", 0), error.optString("message", "CloudCode вернул ошибку"))
            }
        }
        ProviderReply(text.toString().trim(), thinking.toString().trim()) to tokens
    }

    // --- транспорт ---------------------------------------------------------

    private suspend fun call(
        path: String,
        body: JSONObject,
        stream: Boolean,
        onChunk: suspend (JSONObject) -> Unit = {}
    ): String {
        val access = tokens.accessToken()
        val hosts = listOf(GoogleProConfig.PRIMARY_HOST, GoogleProConfig.SECONDARY_HOST)
        var lastRefusal: String? = null
        var lastError: Exception? = null
        for (host in hosts) {
            for (route in ledger.order(fingerprint()).filter { bypassEnabled() || it == GateRoute.DIRECT }) {
                currentCoroutineContext().ensureActive()
                val started = System.currentTimeMillis()
                try {
                    val result = attempt(host, path, body, access, route, stream, onChunk)
                    ledger.success(route, System.currentTimeMillis() - started)
                    return result
                } catch (failure: GateRefusal) {
                    ledger.refusal(route)
                    clients[route]?.connectionPool?.evictAll()
                    lastRefusal = failure.message
                } catch (failure: GeminiChatFailure) {
                    if (failure.status in RETRY_STATUSES) { lastError = failure; continue }
                    throw failure
                } catch (failure: java.io.IOException) {
                    currentCoroutineContext().ensureActive()
                    ledger.unreachable(route)
                    lastError = failure
                }
            }
        }
        throw GeminiChatFailure(
            0,
            lastRefusal?.let { "Google отвечает «$it» ни по одному маршруту. Включите DNS-обход в настройках Google AI Pro или VPN." }
                ?: lastError?.message
                ?: "Не удалось связаться с CloudCode ни по одному маршруту."
        )
    }

    private suspend fun attempt(
        host: String,
        path: String,
        body: JSONObject,
        access: String,
        route: GateRoute,
        stream: Boolean,
        onChunk: suspend (JSONObject) -> Unit
    ): String {
        val request = Request.Builder()
            .url("https://$host/$path")
            .header("Authorization", "Bearer $access")
            .header("User-Agent", GoogleProConfig.USER_AGENT)
            .header("X-Goog-Api-Client", "gl-kotlin/1.0.0")
            .header("Client-Metadata", GoogleProConfig.CLIENT_METADATA)
            // Заголовок `X-Goog-User-Project` здесь не ставится: сервер требует на
            // проекте право `serviceusage.services.use`, и без него отвечает 403
            // «Caller does not have required permission to use project …». Проект
            // достаточно передавать полем в теле — так запрос проходит.
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        clientFor(route).newCall(request).execute().use { response ->
            if (response.code == 400 || response.code == 403) {
                val errorBody = response.body?.string().orEmpty()
                if (errorBody.contains(GATE_REFUSAL_TEXT, ignoreCase = true)) {
                    throw GateRefusal(GATE_REFUSAL_TEXT)
                }
                throw GeminiChatFailure(response.code, describe(response.code, errorBody, host))
            }
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw GeminiChatFailure(response.code, describe(response.code, errorBody, host))
            }
            if (!stream) return response.body?.string().orEmpty()
            val source = response.body?.source() ?: return ""
            while (true) {
                val line = source.readUtf8Line() ?: break
                val chunk = parseSseLine(line) ?: continue
                onChunk(chunk)
            }
            return ""
        }
    }

    private fun describe(code: Int, body: String, host: String): String {
        val detail = runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message") ?: json.optString("message")
        }.getOrNull().orEmpty().take(220)
        val reason = when {
            body.contains("has not been used in project") ->
                "в проекте аккаунта не включён Cloud Code API. Включите cloudcode-pa.googleapis.com в консоли Google Cloud."
            body.contains("SUBSCRIPTION_REQUIRED") ->
                "аккаунту не выдана лицензия Antigravity: нужна подписка Google AI Pro или проект с включённым Cloud Code API."
            code == 401 -> "вход не принят: токен отозван или истёк. Войдите заново."
            code == 403 -> "аккаунту не разрешён доступ. Проверьте подписку Google AI Pro."
            code == 404 -> "модель недоступна на этом тарифе. Выберите другую."
            code == 429 -> "лимит подписки исчерпан. Повторите позже."
            code == 503 -> "CloudCode временно недоступен ($host)."
            else -> "повторите запрос позже."
        }
        return if (detail.isBlank()) reason else "$reason $detail"
    }

    private fun clientFor(route: GateRoute): OkHttpClient = clients.getOrPut(route) {
        OkHttpClient.Builder()
            .dns(resolver.dnsFor(route))
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private class GateRefusal(message: String) : Exception(message)

    private companion object {
        const val GATE_REFUSAL_TEXT = "User location is not supported"
        const val DEFAULT_TIER = "standard-tier"
        val RETRY_STATUSES = setOf(429, 500, 502, 503, 504)
    }
}
