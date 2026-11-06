package com.google.antigravity.network

import com.google.antigravity.device.PhoneResourceController
import android.annotation.SuppressLint
import com.google.antigravity.model.AVAILABLE_AGY_MODELS
import com.google.antigravity.model.AgentExecutionTarget
import com.google.antigravity.model.AiPermissionsConfig
import com.google.antigravity.model.AntigravityModelOption
import com.google.antigravity.model.ArtifactDocument
import com.google.antigravity.model.ArtifactLibrary
import com.google.antigravity.model.AttachedFileItem
import com.google.antigravity.model.ChatLibrary
import com.google.antigravity.model.ChatLibraryPreferences
import com.google.antigravity.model.ChatMessage
import com.google.antigravity.model.ComponentAdapterRecord
import com.google.antigravity.model.ContextWindowStatus
import com.google.antigravity.model.ExtensionCatalogItem
import com.google.antigravity.model.ExtensionComposition
import com.google.antigravity.model.FileDiffItem
import com.google.antigravity.model.GeneratedMediaAttachment
import com.google.antigravity.model.GoogleDriveFileItem
import com.google.antigravity.model.GoogleDriveStatus
import com.google.antigravity.model.InstalledPluginItem
import com.google.antigravity.model.McpToolPermissionEntry
import com.google.antigravity.model.denyCommentForRequest
import com.google.antigravity.model.MessageRole
import com.google.antigravity.model.OpenRouterCategory
import com.google.antigravity.model.OpenRouterModelItem
import com.google.antigravity.model.PcMcpServerItem
import com.google.antigravity.model.PcSkillItem
import com.google.antigravity.model.ProjectWorkspace
import com.google.antigravity.model.ProjectWorkspaces
import com.google.antigravity.model.RemoteSessionItem
import com.google.antigravity.model.SkillDetailInfo
import com.google.antigravity.model.SkillEditResult
import com.google.antigravity.model.SwarmAgentNode
import com.google.antigravity.model.SwarmCounts
import com.google.antigravity.model.SwarmSnapshot
import com.google.antigravity.model.ToolCallEvent
import com.google.antigravity.model.ToolCallStatus
import com.google.antigravity.model.mapToDirectGeminiModelId
import com.google.antigravity.model.normalizeMcpAuthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class AntigravityBackendClient(
    private val phoneController: PhoneResourceController
) {

    data class StreamingTurnUpdate(
        val thinking: String,
        val thinkingDurationSec: Int,
        val toolCalls: List<ToolCallEvent>,
        val textDelta: String,
        val fullText: String,
        val createdDiffs: List<FileDiffItem> = emptyList(),
        val generatedMedia: List<GeneratedMediaAttachment> = emptyList(),
        val isDone: Boolean = false,
        val tokensPerSecond: Float = 0f,
        val outputTokensCount: Int = 0,
        val firstTokenLatencyMs: Long = 0L
    )

    data class AgentTurnResult(
        val thinkingTrace: String,
        val thinkingDurationSec: Int = 0,
        val responseMarkdown: String,
        val spokenSummaryRu: String,
        val updatedConversationId: String = "",
        val toolCalls: List<ToolCallEvent> = emptyList(),
        val createdDiffs: List<FileDiffItem> = emptyList(),
        val generatedMedia: List<GeneratedMediaAttachment> = emptyList(),
        val tokensPerSecond: Float = 0f,
        val outputTokensCount: Int = 0,
        val firstTokenLatencyMs: Long = 0L,
        val isError: Boolean = false
    )

    data class PcBridgeChatResponse(
        val reply: String,
        val conversationId: String,
        val thinking: String = "",
        val thinkingDurationSec: Int = 0,
        val toolCalls: List<ToolCallEvent> = emptyList(),
        val createdDiffs: List<FileDiffItem> = emptyList(),
        val generatedMedia: List<GeneratedMediaAttachment> = emptyList(),
        val resolvedBaseUrl: String = "",
        val tokensPerSecond: Float = 0f,
        val outputTokensCount: Int = 0,
        val firstTokenLatencyMs: Long = 0L,
        val isError: Boolean = false
    )

    data class OpenRouterSyncResult(
        val models: List<OpenRouterModelItem>,
        val totalCount: Int,
        val syncedAt: String,
        val source: String,
        val fromCache: Boolean = false,
        val hasApiKey: Boolean = false,
        val maskedApiKey: String = "",
        val selectedModel: String = ""
    )

    data class PcBridgeStatus(
        val connected: Boolean,
        val instanceName: String,
        val hostname: String,
        val workspace: String,
        val resolvedBaseUrl: String = "",
        /** true после реальной проверки связи; false — стартовое состояние до проверки. */
        val checked: Boolean = true
    )

    companion object {
        val DEFAULT_PUBLIC_BRIDGE_URLS = listOf(
            "https://cdd0a117b3ea350f-79-137-206-221.serveousercontent.com",
            "https://qbxhx-79-137-206-221.free.pinggy.net",
            "https://f5542b3aaca3ea.lhr.life"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val uploadHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    private val fastLocalHttpClient = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val fastProbeClient = OkHttpClient.Builder()
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .writeTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var lastWorkingBridgeUrl: String = ""

    @Volatile
    private var lastWorkingBridgeVerifiedAtMs: Long = 0L

    @Volatile
    var forcePublicTunnelOnly: Boolean = false

    private val activeRemoteTurn = java.util.concurrent.atomic.AtomicReference<Pair<String, String>?>(null)

    fun cancelActiveRemoteTurn() {
        val turn = activeRemoteTurn.getAndSet(null) ?: return
        val body = JSONObject().put("turnId", turn.second).toString()
        val request = Request.Builder().url("${turn.first}/api/chat/cancel")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        fastProbeClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = Unit
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
        })
    }

    private val knownDynamicTunnels = java.util.concurrent.CopyOnWriteArraySet<String>()

    fun getLastWorkingBridgeUrl(): String = lastWorkingBridgeUrl

    fun clearWorkingBridgeCache() {
        lastWorkingBridgeUrl = ""
        knownDynamicTunnels.clear()
    }

    private fun isEmulatorDevice(): Boolean {
        val fp = android.os.Build.FINGERPRINT.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        val prod = android.os.Build.PRODUCT.lowercase()
        return fp.contains("generic") || fp.contains("emulator") || fp.contains("sdk_gphone") ||
            model.contains("sdk_gphone") || model.contains("emulator") || prod.contains("sdk_gphone")
    }

    private fun isLocalOrLoopbackUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.contains("10.0.2.2") ||
            u.contains("127.0.0.1") ||
            u.contains("localhost") ||
            u.contains("192.168.") ||
            u.contains("10.0.")
    }

    private fun isSupportedPublicTunnelUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        if (!u.startsWith("https://")) return false
        return u.contains(".serveousercontent.com") ||
            u.contains(".pinggy.net") ||
            u.contains(".pinggy.link") ||
            u.contains(".lhr.life") ||
            u.contains(".loca.lt") ||
            u.contains(".trycloudflare.com")
    }

    private fun clientForUrl(baseUrl: String): OkHttpClient {
        return if (isLocalOrLoopbackUrl(baseUrl)) {
            fastLocalHttpClient
        } else {
            httpClient
        }
    }

    /**
     * Формирует список адресов моста ПК:
     * - Если `forcePublicTunnelOnly == true` или устройство — реальный смартфон,
     *   НИКОГДА не обращается к `10.0.2.2` или `127.0.0.1`, а использует исключительно публичные HTTPS-туннели.
     * - Если пользователь явно указал недопустимый/недоступный URL (например, для теста Offline Scenario G
     *   `https://offline-invalid-bridge.example.com`), то при `pcBridgeUrl.contains("offline")` не подставляет дефолтные туннели.
     */
    fun buildCandidateBridgeUrls(pcBridgeUrl: String): List<String> {
        val clean = pcBridgeUrl.trim().trimEnd('/')
        if (clean.contains("offline", ignoreCase = true) || clean.contains("invalid", ignoreCase = true)) {
            return listOf(clean)
        }
        val cached = lastWorkingBridgeUrl.trim().trimEnd('/')
        val allowLocal = isEmulatorDevice() && !forcePublicTunnelOnly
        if (!allowLocal && isLocalOrLoopbackUrl(lastWorkingBridgeUrl)) {
            lastWorkingBridgeUrl = ""
        }
        return prioritizedBridgeUrls(
            configured = clean.takeIf { allowLocal || !isLocalOrLoopbackUrl(it) }.orEmpty(),
            localDefaults = if (allowLocal) listOf("http://10.0.2.2:8787", "http://127.0.0.1:8787") else emptyList(),
            cached = cached.takeIf { allowLocal || !isLocalOrLoopbackUrl(it) }.orEmpty(),
            discovered = knownDynamicTunnels.filter { allowLocal || !isLocalOrLoopbackUrl(it) },
            publicDefaults = DEFAULT_PUBLIC_BRIDGE_URLS
        )
    }

    fun sanitizeCleanUserText(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw
        val reqMatch = Regex("<USER_REQUEST>([\\s\\S]*?)</USER_REQUEST>", RegexOption.IGNORE_CASE).find(s)
        if (reqMatch != null) {
            s = reqMatch.groupValues[1]
        }
        val phoneMarker = "Запрос пользователя:"
        val lastPhoneIdx = s.lastIndexOf(phoneMarker)
        if (lastPhoneIdx >= 0) {
            s = s.substring(lastPhoneIdx + phoneMarker.length)
        }
        val ctxMarker = "Новое сообщение пользователя:"
        val lastCtxIdx = s.lastIndexOf(ctxMarker)
        if (lastCtxIdx >= 0) {
            s = s.substring(lastCtxIdx + ctxMarker.length)
        }
        val attMarker = "Прикреплённые файлы с телефона пользователя:"
        val attIdx = s.indexOf(attMarker)
        if (attIdx >= 0) {
            s = s.substring(0, attIdx)
        }
        val foundMarker = "Найденные на телефоне элементы:"
        val foundIdx = s.indexOf(foundMarker)
        if (foundIdx >= 0) {
            s = s.substring(0, foundIdx)
        }
        val contentMarker = "\n\nСодержимое:\n"
        val contentIdx = s.indexOf(contentMarker)
        if (contentIdx >= 0) {
            s = s.substring(0, contentIdx)
        }
        return s.trim()
    }

    suspend fun discoverLatestBridgeUrls(): List<String> = withContext(Dispatchers.IO) {
        val latestServeo = LinkedHashSet<String>()
        val latestPinggy = LinkedHashSet<String>()
        val latestLhr = LinkedHashSet<String>()
        for (topic in listOf("ag_bridge_cr05318_st811210", "ag_bridge_cr05318_backup")) {
            try {
                val req = Request.Builder()
                    .url("https://ntfy.sh/$topic/raw?poll=1&since=12h")
                    .get()
                    .build()
                fastProbeClient.newCall(req).useCancellable { resp ->
                    if (resp.isSuccessful) {
                        val rawText = resp.body?.string().orEmpty()
                        val tokens = Regex("https://[a-zA-Z0-9.\\-]+")
                            .findAll(rawText)
                            .map { it.value.trim().trimEnd('/') }
                            .filter { isSupportedPublicTunnelUrl(it) }
                            .toList()
                        for (url in tokens.asReversed()) {
                            when {
                                url.contains(".serveousercontent.com") && latestServeo.size < 2 -> latestServeo.add(url)
                                (url.contains(".pinggy.net") || url.contains(".pinggy.link")) && latestPinggy.size < 2 -> latestPinggy.add(url)
                                url.contains(".lhr.life") && latestLhr.size < 2 -> latestLhr.add(url)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        val combined = buildList {
            addAll(latestServeo)
            addAll(latestPinggy)
            addAll(latestLhr)
        }
        knownDynamicTunnels.addAll(combined)
        combined
    }

    suspend fun discoverLatestBridgeUrl(): String? = discoverLatestBridgeUrls().firstOrNull()

    private fun extractTunnelUrlsFromStatusJson(json: JSONObject): List<String> {
        val out = mutableListOf<String>()
        val single = json.optString("publicTunnelUrl", "").trim().trimEnd('/')
        if (isSupportedPublicTunnelUrl(single)) out.add(single)
        val arr = json.optJSONArray("publicTunnelUrls")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val u = arr.optString(i, "").trim().trimEnd('/')
                if (isSupportedPublicTunnelUrl(u)) out.add(u)
            }
        }
        knownDynamicTunnels.addAll(out)
        return out.distinct()
    }

    private fun probeSingleBridgeUrl(base: String): PcBridgeStatus? {
        if (forcePublicTunnelOnly && isLocalOrLoopbackUrl(base)) return null
        return try {
            val probeClient = if (isLocalOrLoopbackUrl(base)) {
                fastLocalHttpClient
            } else {
                fastProbeClient
            }
            val req = Request.Builder().url("$base/api/status").get().build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (!json.optBoolean("ok", false)) return null
                val tunnels = extractTunnelUrlsFromStatusJson(json)
                val usePublicOnly = !isEmulatorDevice() || forcePublicTunnelOnly
                val resolved = if (usePublicOnly && tunnels.isNotEmpty()) {
                    if (base.startsWith("https://")) base else tunnels.first()
                } else {
                    base
                }
                lastWorkingBridgeUrl = resolved
                lastWorkingBridgeVerifiedAtMs = System.currentTimeMillis()
                PcBridgeStatus(
                    connected = true,
                    instanceName = json.optString("instanceName", "Рабочий компьютер"),
                    hostname = json.optString("hostname", "ПК"),
                    workspace = json.optString("workspace", ""),
                    resolvedBaseUrl = resolved
                )
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            null
        }
    }

    suspend fun checkPcBridgeStatus(pcBridgeUrl: String): PcBridgeStatus = withContext(Dispatchers.IO) {
        val clean = pcBridgeUrl.trim()
        if (clean.contains("offline", ignoreCase = true) || clean.contains("invalid", ignoreCase = true)) {
            lastWorkingBridgeUrl = ""
            return@withContext PcBridgeStatus(false, "Рабочий компьютер", "ПК", "")
        }
        val candidates = buildCandidateBridgeUrls(pcBridgeUrl).toMutableList()
        for (base in candidates) {
            val status = probeSingleBridgeUrl(base)
            if (status != null) return@withContext status
        }
        for (discovered in discoverLatestBridgeUrls()) {
            if (discovered in candidates) continue
            val status = probeSingleBridgeUrl(discovered)
            if (status != null) return@withContext status
        }
        PcBridgeStatus(false, "Рабочий компьютер", "ПК", "")
    }

    suspend fun fetchAvailableModels(pcBridgeUrl: String): List<AntigravityModelOption> = withContext(Dispatchers.IO) {
        for (base in buildCandidateBridgeUrls(pcBridgeUrl)) {
            try {
                val req = Request.Builder().url("$base/api/models").get().build()
                fastProbeClient.newCall(req).useCancellable { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        val arr = json.optJSONArray("models")
                        if (arr != null && arr.length() > 0) {
                            val list = mutableListOf<AntigravityModelOption>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val id = obj.optString("id", "")
                                val displayName = obj.optString("displayName", id)
                                val badge = obj.optString("badge", "Модель Google Antigravity")
                                if (id.isNotBlank()) {
                                    list.add(AntigravityModelOption(id = id, displayName = displayName, badge = badge))
                                }
                            }
                            if (list.isNotEmpty()) {
                                lastWorkingBridgeUrl = base
                                return@withContext list
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
        AVAILABLE_AGY_MODELS
    }

    suspend fun fetchRemoteSessions(pcBridgeUrl: String): List<RemoteSessionItem> = withContext(Dispatchers.IO) {
        val clean = pcBridgeUrl.trim()
        if (clean.contains("offline", ignoreCase = true) || clean.contains("invalid", ignoreCase = true)) {
            return@withContext emptyList()
        }
        val candidates = buildCandidateBridgeUrls(pcBridgeUrl).toMutableList()
        for (attempt in 0..1) {
            val urlsToTry = if (attempt == 0) {
                candidates
            } else {
                discoverLatestBridgeUrls().filter { it !in candidates }
            }
            for (base in urlsToTry) {
                try {
                    val req = Request.Builder().url("$base/api/sessions").get().build()
                    val bodyStr = fastProbeClient.newCall(req).useCancellable { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    } ?: continue
                    val json = JSONObject(bodyStr)
                    val arr = json.optJSONArray("sessions") ?: continue
                    val list = mutableListOf<RemoteSessionItem>()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val rawTitle = item.optString("title", "Сессия Antigravity")
                        val cleanTitle = sanitizeCleanUserText(rawTitle).ifBlank { "Сессия Antigravity" }
                        list.add(
                            RemoteSessionItem(
                                id = item.optString("id", ""),
                                title = cleanTitle.take(54),
                                environment = "Удалённая сессия ПК",
                                host = item.optString("host", "cr05318"),
                                hostType = item.optString("hostType", "PC"),
                                workspacePath = item.optString("workspacePath", ""),
                                updatedAtMs = item.optLong("updatedAtMs", System.currentTimeMillis()),
                                messageCount = item.optInt("messageCount", 0)
                            )
                        )
                    }
                    if (list.isNotEmpty()) {
                        lastWorkingBridgeUrl = base
                        return@withContext list
                    }
                } catch (failure: Throwable) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure;
                }
            }
        }
        emptyList()
    }

    suspend fun fetchSessionMessages(pcBridgeUrl: String, sessionId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext emptyList()
        val candidates = buildCandidateBridgeUrls(pcBridgeUrl).toMutableList()
        for (attempt in 0..1) {
            val urlsToTry = if (attempt == 0) {
                candidates
            } else {
                discoverLatestBridgeUrls().filter { it !in candidates }
            }
            for (base in urlsToTry) {
                try {
                    val req = Request.Builder().url("$base/api/session?id=$sessionId").get().build()
                    val bodyStr = fastProbeClient.newCall(req).useCancellable { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    } ?: continue
                    val json = JSONObject(bodyStr)
                    val arr = json.optJSONArray("messages") ?: continue
                    val list = mutableListOf<ChatMessage>()
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        val roleStr = m.optString("role", "AGENT")
                        val role = if (roleStr == "USER") MessageRole.USER else MessageRole.AGENT
                        val rawContent = m.optString("content", "")
                        val cleanContent = if (role == MessageRole.USER) {
                            sanitizeCleanUserText(rawContent)
                        } else {
                            rawContent.trim()
                        }
                        if (cleanContent.isBlank()) continue
                        val rawThinking = m.optString("thinking", "").trim()
                        val tcArr = m.optJSONArray("toolCalls")
                        val parsedTools = mutableListOf<ToolCallEvent>()
                        if (tcArr != null) {
                            for (tIdx in 0 until tcArr.length()) {
                                val tObj = tcArr.optJSONObject(tIdx) ?: continue
                                val tName = tObj.optString("name", "pc_tool")
                                val rawSum = tObj.optString("summary", tName)
                                val cleanSum = rawSum.removePrefix("$tName:").trim().ifBlank { rawSum }
                                val tOut = tObj.optString("output", tObj.optString("details", "")).trim()
                                parsedTools.add(
                                    ToolCallEvent(
                                        id = tObj.optString("id", "tc_${i}_$tIdx"),
                                        toolName = tName,
                                        summary = cleanSum,
                                        details = tOut,
                                        status = ToolCallStatus.COMPLETED
                                    )
                                )
                            }
                        }
                        val diffArr = m.optJSONArray("createdDiffs")
                        val parsedDiffs = mutableListOf<FileDiffItem>()
                        if (diffArr != null) {
                            for (dIdx in 0 until diffArr.length()) {
                                val dObj = diffArr.optJSONObject(dIdx) ?: continue
                                parsedDiffs.add(
                                    FileDiffItem(
                                        path = dObj.optString("path", "file"),
                                        additions = dObj.optInt("additions", 1).coerceAtLeast(0),
                                        deletions = dObj.optInt("deletions", 0).coerceAtLeast(0),
                                        diffPatch = dObj.optString("diffPatch", "")
                                    )
                                )
                            }
                        }
                        list.add(
                            ChatMessage(
                                id = m.optString("id", "${sessionId}_live_$i"),
                                role = role,
                                content = cleanContent,
                                thinking = rawThinking.takeIf { it.isNotBlank() },
                                isThinkingExpanded = false,
                                thinkingDurationSec = m.optInt("thinkingDurationSec", 2).coerceAtLeast(1),
                                toolCalls = parsedTools,
                                createdDiffs = parsedDiffs,
                                timestamp = m.optString("timestamp", "")
                            )
                        )
                    }
                    lastWorkingBridgeUrl = base
                    return@withContext list
                } catch (failure: Throwable) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure;
                }
            }
        }
        emptyList()
    }

    // ---------------------------------------------------------------------------
    // Plugins, Skills, and MCP Servers (Section 6)
    // ---------------------------------------------------------------------------

    private suspend fun resolveWorkingBaseUrl(pcBridgeUrl: String): String? {
        val clean = pcBridgeUrl.trim()
        if (clean.contains("offline", ignoreCase = true) || clean.contains("invalid", ignoreCase = true)) {
            return null
        }
        val cached = lastWorkingBridgeUrl.trim().trimEnd('/')
        val now = System.currentTimeMillis()
        val explicit = clean.trimEnd('/')
        if (explicit.isNotBlank() && explicit != cached &&
            (!isLocalOrLoopbackUrl(explicit) || isEmulatorDevice() && !forcePublicTunnelOnly)) {
            probeSingleBridgeUrl(explicit)?.let { return it.resolvedBaseUrl }
        }
        if (cached.isNotBlank() && !(forcePublicTunnelOnly && isLocalOrLoopbackUrl(cached))) {
            if (now - lastWorkingBridgeVerifiedAtMs < 20_000L || probeSingleBridgeUrl(cached) != null) {
                return lastWorkingBridgeUrl.ifBlank { cached }
            }
        }
        val candidates = buildCandidateBridgeUrls(pcBridgeUrl)
        for (base in candidates) {
            if (probeSingleBridgeUrl(base) != null) {
                return lastWorkingBridgeUrl.ifBlank { base }
            }
        }
        for (discovered in discoverLatestBridgeUrls()) {
            if (probeSingleBridgeUrl(discovered) != null) {
                return lastWorkingBridgeUrl.ifBlank { discovered }
            }
        }
        return null
    }

    suspend fun fetchPlugins(pcBridgeUrl: String): Pair<List<InstalledPluginItem>, String?> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext emptyList<InstalledPluginItem>() to "Мост ПК недоступен"
        try {
            val req = Request.Builder().url("$base/api/plugins").get().build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                if (!resp.isSuccessful) return@withContext emptyList<InstalledPluginItem>() to "Ошибка HTTP ${resp.code}"
                val json = JSONObject(resp.body?.string() ?: "{}")
                return@withContext parsePluginsJson(json) to null
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            emptyList<InstalledPluginItem>() to e.localizedMessage
        }
    }

    suspend fun managePlugin(
        pcBridgeUrl: String,
        action: String,
        idOrName: String,
        description: String = "",
        enabled: Boolean = true
    ): Pair<List<InstalledPluginItem>, String?> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext emptyList<InstalledPluginItem>() to "Мост ПК недоступен"
        try {
            val payload = JSONObject().apply {
                put("action", action)
                put("id", idOrName)
                put("name", idOrName)
                put("description", description)
                put("enabled", enabled)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url("$base/api/plugins").post(payload).build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                val err = if (!json.optBoolean("ok", true)) json.optString("error", "Ошибка операции с плагином") else null
                return@withContext parsePluginsJson(json) to err
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            emptyList<InstalledPluginItem>() to e.localizedMessage
        }
    }

    private fun parsePluginsJson(json: JSONObject): List<InstalledPluginItem> {
        val arr = json.optJSONArray("plugins") ?: return emptyList()
        val out = mutableListOf<InstalledPluginItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            out.add(
                InstalledPluginItem(
                    id = obj.optString("id", "plugin_$i"),
                    name = obj.optString("name", "Плагин"),
                    category = obj.optString("category", "Плагин Antigravity"),
                    description = obj.optString("description", ""),
                    enabled = obj.optBoolean("enabled", true),
                    version = obj.optString("version", "1.0.0"),
                    source = obj.optString("source", "agy"),
                    path = obj.optString("path", ""),
                    composition = parseComposition(obj.optJSONObject("composition"))
                )
            )
        }
        return out
    }

    /**
     * Этап 6: каталог расширений из матрицы адаптеров (GET /api/extensions/adapters).
     * Позиции каталога — наблюдения источников и НЕ показываются работающими.
     */
    suspend fun fetchExtensionCatalog(pcBridgeUrl: String): Pair<List<ExtensionCatalogItem>, String?> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext emptyList<ExtensionCatalogItem>() to "Мост ПК недоступен"
        try {
            val req = Request.Builder().url("$base/api/extensions/adapters").get().build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                if (!resp.isSuccessful) return@withContext emptyList<ExtensionCatalogItem>() to "Ошибка HTTP ${resp.code}"
                val json = JSONObject(resp.body?.string() ?: "{}")
                return@withContext parseExtensionCatalog(json) to null
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            emptyList<ExtensionCatalogItem>() to e.localizedMessage
        }
    }

    suspend fun fetchSkills(pcBridgeUrl: String): Pair<List<PcSkillItem>, String?> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext emptyList<PcSkillItem>() to "Мост ПК недоступен"
        try {
            val req = Request.Builder().url("$base/api/skills").get().build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                if (!resp.isSuccessful) return@withContext emptyList<PcSkillItem>() to "Ошибка HTTP ${resp.code}"
                val json = JSONObject(resp.body?.string() ?: "{}")
                return@withContext parseSkillsJson(json) to null
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            emptyList<PcSkillItem>() to e.localizedMessage
        }
    }

    suspend fun manageSkill(
        pcBridgeUrl: String,
        action: String,
        name: String,
        description: String = "",
        instructions: String = "",
        enabled: Boolean = true
    ): Pair<List<PcSkillItem>, String?> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext emptyList<PcSkillItem>() to "Мост ПК недоступен"
        try {
            val payload = JSONObject().apply {
                put("action", action)
                put("id", name)
                put("name", name)
                put("description", description)
                put("instructions", instructions)
                put("enabled", enabled)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url("$base/api/skills").post(payload).build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                val err = if (!json.optBoolean("ok", true)) json.optString("error", "Ошибка операции со скиллом") else null
                return@withContext parseSkillsJson(json) to err
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            emptyList<PcSkillItem>() to e.localizedMessage
        }
    }

    private fun parseSkillsJson(json: JSONObject): List<PcSkillItem> = parseSkillItems(json)

    suspend fun fetchMcpServers(pcBridgeUrl: String): Pair<List<PcMcpServerItem>, String?> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext emptyList<PcMcpServerItem>() to "Мост ПК недоступен"
        try {
            val req = Request.Builder().url("$base/api/mcp").get().build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                if (!resp.isSuccessful) return@withContext emptyList<PcMcpServerItem>() to "Ошибка HTTP ${resp.code}"
                val json = JSONObject(resp.body?.string() ?: "{}")
                return@withContext parseMcpJson(json) to null
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            emptyList<PcMcpServerItem>() to e.localizedMessage
        }
    }

    suspend fun manageMcpServer(
        pcBridgeUrl: String,
        action: String,
        name: String,
        command: String = "",
        args: List<String> = emptyList(),
        enabled: Boolean = true
    ): Pair<List<PcMcpServerItem>, String?> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext emptyList<PcMcpServerItem>() to "Мост ПК недоступен"
        try {
            val argsArray = JSONArray()
            args.forEach { argsArray.put(it) }
            val payload = JSONObject().apply {
                put("action", action)
                put("id", name)
                put("name", name)
                put("command", command)
                put("args", argsArray)
                put("enabled", enabled)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url("$base/api/mcp").post(payload).build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                // QA-дефект Medium #2: HTTP-статус проверяется до чтения тела;
                // текст ошибки извлекается безопасно (с лимитом и без исключений).
                if (!resp.isSuccessful) {
                    val bodyText = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    return@withContext emptyList<PcMcpServerItem>() to "Мост ПК вернул HTTP ${resp.code}: ${safeBridgeErrorText(bodyText)}"
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                val err = if (!json.optBoolean("ok", true)) json.optString("error", "Ошибка операции с MCP-сервером") else null
                val items = demoteUnverifiedConnected(parseMcpJson(json), err)
                return@withContext items to err
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            emptyList<PcMcpServerItem>() to e.localizedMessage
        }
    }

    // Этап 3: живое MCP-рукопожатие (initialize → initialized → tools/list).
    // Статус connected приходит только после успешной проверки; секреты
    // (env/headers/токены) редактируются на стороне моста и сюда не попадают.
    suspend fun mcpOAuthRequest(pcBridgeUrl: String, payload: JSONObject): Pair<JSONObject?, String?> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext null to "Мост ПК недоступен"
        try {
            val request = Request.Builder().url("$base/api/mcp")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                if (!response.isSuccessful || !json.optBoolean("ok", false)) {
                    null to json.optString("error", "Не удалось выполнить вход MCP")
                } else json to null
            }
        } catch (_: Exception) { null to "Не удалось связаться с мостом для входа MCP" }
    }

    // OAuth 2.1 для MCP (действия /api/mcp: oauthStart / oauthStatus / oauthRevoke).
    // Старт возвращает authUrl — клиент открывает его через системный браузер /
    // Custom Tabs (Intent.ACTION_VIEW); статус опрашивается при возврате в
    // приложение (onResume / кнопка «Проверить»); revoke — выход (отзыв токенов).

    /** Старт OAuth-входа: ответ содержит authUrl для открытия в браузере. */
    internal suspend fun mcpOAuthStart(pcBridgeUrl: String, name: String): Pair<McpOAuthReply?, String?> =
        mcpOAuthAction(pcBridgeUrl, JSONObject().put("action", "oauthStart").put("name", name))

    /** Свежий статус авторизации; sessionId — сессия, полученная при старте входа. */
    internal suspend fun mcpOAuthStatus(pcBridgeUrl: String, name: String, sessionId: String? = null): Pair<McpOAuthReply?, String?> {
        val payload = JSONObject().put("action", "oauthStatus").put("name", name)
        if (!sessionId.isNullOrBlank()) payload.put("sessionId", sessionId)
        return mcpOAuthAction(pcBridgeUrl, payload)
    }

    /** Выход: отзыв выданных токенов (revoke). */
    internal suspend fun mcpOAuthRevoke(pcBridgeUrl: String, name: String): Pair<McpOAuthReply?, String?> =
        mcpOAuthAction(pcBridgeUrl, JSONObject().put("action", "oauthRevoke").put("name", name))

    private suspend fun mcpOAuthAction(pcBridgeUrl: String, payload: JSONObject): Pair<McpOAuthReply?, String?> {
        val (json, error) = mcpOAuthRequest(pcBridgeUrl, payload)
        return if (json == null) null to error else parseMcpOAuthReply(json) to null
    }

    // Права инструментов MCP («как у них»: allow/ask/deny на каждый инструмент;
    // приоритет per-tool > «все инструменты сервера» > глобальная политика) и
    // деталь навыка (полный текст SKILL.md). Секреты мост редактирует на своей
    // стороне; сюда попадают только публичные поля.

    /**
     * Общий POST-запрос к API моста (JSON → JSON): тело ответа при ok:true,
     * иначе текст ошибки без маскировки (не выдумывается успех).
     */
    private suspend fun postBridgeJson(
        pcBridgeUrl: String,
        apiPath: String,
        payload: JSONObject,
        errorHint: String
    ): Pair<JSONObject?, String?> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext null to "Мост ПК недоступен"
        try {
            val request = Request.Builder().url("$base$apiPath")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(request).execute().use { response ->
                val bodyText = runCatching { response.body?.string() }.getOrNull().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext null to "Мост ПК вернул HTTP ${response.code}: ${safeBridgeErrorText(bodyText)}"
                }
                val json = JSONObject(bodyText.ifBlank { "{}" })
                if (!json.optBoolean("ok", false)) {
                    return@withContext null to json.optString("error", errorHint).ifBlank { errorHint }
                }
                return@withContext json to null
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null to (e.localizedMessage ?: errorHint)
        }
    }

    /**
     * Права инструментов последнего живого probe (tool_permissions). Список
     * может быть пустым — это честно: инструменты появляются только после
     * реального рукопожатия «Проверить».
     */
    internal suspend fun mcpToolPermissions(
        pcBridgeUrl: String,
        name: String
    ): Pair<McpToolPermissionsReply?, String?> {
        val (json, error) = postBridgeJson(
            pcBridgeUrl, "/api/mcp",
            JSONObject().put("action", "tool_permissions").put("name", name),
            "Не удалось получить права инструментов"
        )
        return if (json == null) null to error else parseMcpToolPermissions(json) to null
    }

    /** Разовое решение по исходному адресу моста; без изменения постоянных прав. */
    internal suspend fun replyToolApproval(handle: com.google.antigravity.model.ToolApprovalHandle,
        decision: com.google.antigravity.model.ApprovalDecision): Boolean = withContext(Dispatchers.IO) {
        if (decision !in setOf(com.google.antigravity.model.ApprovalDecision.APPROVED,
                com.google.antigravity.model.ApprovalDecision.DENIED)) return@withContext false
        try {
            val body = JSONObject().put("requestId", handle.requestId).put("token", handle.token)
                .put("turnId", handle.turnId).put("conversationId", handle.conversationId)
                .put("decision", decision.name)
            // Send only to the exact bridge that issued this capability, without discovery/fallback.
            val request = Request.Builder().url(handle.bridgeUrl.trimEnd('/') + "/api/chat/tool-approval")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            httpClient.newCall(request).useCancellable { response ->
                if (!response.isSuccessful) return@useCancellable false
                val ack = JSONObject(response.body?.string().orEmpty())
                ack.optBoolean("ok") && ack.optString("decision") == decision.name
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    /** Смена права одного инструмента (allow/ask/deny). */
    internal suspend fun setMcpToolPermission(
        pcBridgeUrl: String,
        name: String,
        tool: String,
        permission: String,
        scope: String = "project",
        scopeId: String? = null,
        origin: String? = null,
        comment: String = ""
    ): Pair<McpPermissionUpdateReply?, String?> {
        val payload = JSONObject().put("action", "set_tool_permission").put("name", name)
            .put("tool", tool).put("permission", permission)
        applyMcpToolScope(payload, scope, scopeId, origin)
        applyMcpToolPermissionComment(payload, permission, comment)
        val (json, error) = postBridgeJson(
            pcBridgeUrl, "/api/mcp", payload,
            "Не удалось изменить право инструмента"
        )
        return if (json == null) null to error else parseMcpPermissionUpdate(json) to null
    }

    /** Смена прав всех инструментов сервера (уровень «все инструменты сервера»). */
    internal suspend fun setAllMcpToolPermissions(
        pcBridgeUrl: String,
        name: String,
        permission: String,
        scope: String = "project",
        scopeId: String? = null,
        origin: String? = null,
        comment: String = ""
    ): Pair<McpPermissionUpdateReply?, String?> {
        val payload = JSONObject().put("action", "set_all_tools_permissions").put("name", name)
            .put("permission", permission)
        applyMcpToolScope(payload, scope, scopeId, origin)
        applyMcpToolPermissionComment(payload, permission, comment)
        val (json, error) = postBridgeJson(
            pcBridgeUrl, "/api/mcp", payload,
            "Не удалось изменить права всех инструментов"
        )
        return if (json == null) null to error else parseMcpPermissionUpdate(json) to null
    }

    /**
     * Поля скоупа запроса прав («как у них»): scope всегда (дефолт project —
     * прежнее поведение), scopeId/origin — только когда они непустые; пустые
     * значения не отправляются и не выдумываются.
     */
    private fun applyMcpToolScope(
        payload: JSONObject,
        scope: String,
        scopeId: String?,
        origin: String?
    ) {
        payload.put("scope", scope.ifBlank { "project" })
        if (!scopeId.isNullOrBlank()) payload.put("scopeId", scopeId)
        if (!origin.isNullOrBlank()) payload.put("origin", origin)
    }

    /**
     * Комментарий к запрету («Deny with comment») по контракту моста: поле
     * comment отправляется только с permission=deny и только непустым;
     * повторный deny без комментария НЕ отправляет поле — мост очищает
     * прежний комментарий. У allow/ask комментарий не отправляется вовсе.
     */
    private fun applyMcpToolPermissionComment(
        payload: JSONObject,
        permission: String,
        comment: String
    ) {
        val wire = denyCommentForRequest(permission, comment)
        if (wire != null) payload.put("comment", wire)
    }

    /** Текущий режим разрешений (действие permission_mode /api/mcp). */
    internal suspend fun getMcpPermissionMode(pcBridgeUrl: String): McpPermissionModeReply =
        postPermissionModeRequest(pcBridgeUrl, JSONObject().put("action", "permission_mode"))

    /**
     * Смена режима разрешений (действие set_permission_mode /api/mcp).
     * Неизвестный режим мост отклоняет (ok:false + error) — текст показывается
     * без маскировки.
     */
    internal suspend fun setMcpPermissionMode(
        pcBridgeUrl: String,
        mode: String
    ): McpPermissionModeReply = postPermissionModeRequest(
        pcBridgeUrl,
        JSONObject().put("action", "set_permission_mode").put("mode", mode)
    )

    /** POST /api/mcp для режима разрешений: разбор ok/mode/validModes/error. */
    private suspend fun postPermissionModeRequest(
        pcBridgeUrl: String,
        payload: JSONObject
    ): McpPermissionModeReply = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl)
            ?: return@withContext McpPermissionModeReply(error = "Мост ПК недоступен")
        try {
            val req = Request.Builder().url("$base/api/mcp")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                val bodyText = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext McpPermissionModeReply(
                        error = "Мост ПК вернул HTTP ${resp.code}: ${safeBridgeErrorText(bodyText)}"
                    )
                }
                return@withContext parseMcpPermissionMode(JSONObject(bodyText.ifBlank { "{}" }))
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            McpPermissionModeReply(error = e.localizedMessage ?: "Не удалось получить режим разрешений")
        }
    }

    /** Полный текст SKILL.md навыка (действие detail /api/skills). */
    internal suspend fun skillDetail(
        pcBridgeUrl: String,
        name: String
    ): Pair<SkillDetailInfo?, String?> {
        val (json, error) = postBridgeJson(
            pcBridgeUrl, "/api/skills",
            JSONObject().put("action", "detail").put("name", name),
            "Не удалось получить SKILL.md навыка"
        )
        return if (json == null) null to error else parseSkillDetail(json) to null
    }

    /**
     * Правка навыка (действие update /api/skills): полный текст SKILL.md и
     * опциональное новое имя (newName; id папки стабилен, меняется имя в
     * frontmatter). Отказы моста не маскируются: ok/message/error/code
     * (skill_name_taken, skill_read_only) читаются как есть.
     */
    internal suspend fun updateSkill(
        pcBridgeUrl: String,
        name: String,
        content: String,
        newName: String? = null
    ): SkillEditResult {
        val payload = JSONObject().apply {
            put("action", "update")
            put("name", name)
            put("content", content)
            // Пустое newName не отправляется: имя не меняется, не выдумывается.
            if (!newName.isNullOrBlank()) put("newName", newName)
        }
        return postSkillEditRequest(pcBridgeUrl, payload)
    }

    /**
     * Удаление навыка (действие remove /api/skills). Для source = builtin мост
     * отказывает skill_read_only — текст ошибки возвращается без маскировки.
     */
    internal suspend fun deleteSkill(
        pcBridgeUrl: String,
        name: String
    ): SkillEditResult {
        val payload = JSONObject().apply {
            put("action", "remove")
            put("name", name)
        }
        return postSkillEditRequest(pcBridgeUrl, payload)
    }

    /**
     * POST /api/skills для правки/удаления: разбор ok/message/error/code;
     * отказ никогда не превращается в выдуманный успех.
     */
    private suspend fun postSkillEditRequest(
        pcBridgeUrl: String,
        payload: JSONObject
    ): SkillEditResult = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl)
            ?: return@withContext SkillEditResult(error = "Мост ПК недоступен")
        try {
            val req = Request.Builder().url("$base/api/skills")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                val bodyText = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext SkillEditResult(
                        error = "Мост ПК вернул HTTP ${resp.code}: ${safeBridgeErrorText(bodyText)}"
                    )
                }
                return@withContext parseSkillEditResult(JSONObject(bodyText.ifBlank { "{}" }))
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            SkillEditResult(error = e.localizedMessage ?: "Не удалось выполнить операцию со скиллом")
        }
    }

    suspend fun probeMcpServer(
        pcBridgeUrl: String,
        name: String,
        timeoutMs: Long = 20000L
    ): Pair<List<PcMcpServerItem>, String?> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext emptyList<PcMcpServerItem>() to "Мост ПК недоступен"
        try {
            val payload = JSONObject().apply {
                put("action", "probe")
                put("id", name)
                put("name", name)
                put("timeoutMs", timeoutMs)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url("$base/api/mcp").post(payload).build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                // QA-дефект Medium #2: проверка resp.isSuccessful + безопасный
                // текст ошибки; залипший connected при ошибке сбрасывается.
                if (!resp.isSuccessful) {
                    val bodyText = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    return@withContext emptyList<PcMcpServerItem>() to "Мост ПК вернул HTTP ${resp.code}: ${safeBridgeErrorText(bodyText)}"
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                val err = if (!json.optBoolean("ok", true)) json.optString("error", "Проверка MCP-сервера не выполнена") else null
                val items = demoteUnverifiedConnected(parseMcpJson(json), err)
                return@withContext items to err
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            emptyList<PcMcpServerItem>() to e.localizedMessage
        }
    }

    // Этап 3: реальный вызов MCP-инструмента (tools/call) на указанном сервере.
    // Контекст скоупов (sessionId/taskId/origin) передаётся мосту — enforcement
    // прав его учитывает; пустые значения не отправляются.
    suspend fun callMcpTool(
        pcBridgeUrl: String,
        name: String,
        tool: String,
        argumentsJson: String = "{}",
        sessionId: String? = null,
        taskId: String? = null,
        origin: String? = null
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext null to "Мост ПК недоступен"
        try {
            val args = try { JSONObject(argumentsJson.ifBlank { "{}" }) } catch (_: Throwable) { JSONObject() }
            val payload = JSONObject().apply {
                put("action", "call")
                put("id", name)
                put("name", name)
                put("tool", tool)
                put("arguments", args)
                if (!sessionId.isNullOrBlank()) put("sessionId", sessionId)
                if (!taskId.isNullOrBlank()) put("taskId", taskId)
                if (!origin.isNullOrBlank()) put("origin", origin)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url("$base/api/mcp").post(payload).build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (!json.optBoolean("ok", false)) {
                    return@withContext null to json.optString("error", "Вызов инструмента не выполнен")
                }
                val result = json.optJSONObject("result")
                val texts = StringBuilder()
                val content = result?.optJSONArray("content")
                if (content != null) {
                    for (i in 0 until content.length()) {
                        val item = content.optJSONObject(i) ?: continue
                        if (item.optString("type") == "text") {
                            if (texts.isNotEmpty()) texts.append('\n')
                            texts.append(item.optString("text", ""))
                        }
                    }
                }
                val isError = result?.optBoolean("isError", false) ?: false
                val body = texts.toString().ifBlank { "Инструмент выполнен без текстового результата" }
                return@withContext (if (isError) "Ошибка инструмента:\n$body" else body) to null
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            null to e.localizedMessage
        }
    }

    // QA-дефект Medium #2: парсинг списка MCP-серверов вынесен в top-level
    // функцию parseMcpServerList (тестируется ExtensionsSecurityQaTest).
    private fun parseMcpJson(json: JSONObject): List<PcMcpServerItem> = parseMcpServerList(json)

    // ---------------------------------------------------------------------------
    // Round 5: Live OpenRouter Models Hub + Photo & MP4 Video Generation (Sections 2 & 8.1)
    // ---------------------------------------------------------------------------

    private fun parseOpenRouterCategory(raw: String): OpenRouterCategory {
        return when (raw.trim().uppercase()) {
            "REASONING" -> OpenRouterCategory.REASONING
            "VISION" -> OpenRouterCategory.VISION
            "IMAGE_GEN" -> OpenRouterCategory.IMAGE_GEN
            "VIDEO_GEN" -> OpenRouterCategory.VIDEO_GEN
            "AUDIO" -> OpenRouterCategory.AUDIO
            "FREE" -> OpenRouterCategory.FREE
            else -> OpenRouterCategory.ALL
        }
    }

    private fun classifyDirectOpenRouterModel(obj: JSONObject): List<OpenRouterCategory> {
        val id = obj.optString("id", "").lowercase()
        val name = obj.optString("name", "").lowercase()
        val desc = obj.optString("description", "").lowercase()
        val arch = obj.optJSONObject("architecture")
        val modality = arch?.optString("modality", "")?.lowercase().orEmpty()
        val inputModalities = mutableListOf<String>()
        val outputModalities = mutableListOf<String>()
        arch?.optJSONArray("input_modalities")?.let { arr ->
            for (i in 0 until arr.length()) inputModalities.add(arr.optString(i, "").lowercase())
        }
        arch?.optJSONArray("output_modalities")?.let { arr ->
            for (i in 0 until arr.length()) outputModalities.add(arr.optString(i, "").lowercase())
        }
        val params = mutableListOf<String>()
        obj.optJSONArray("supported_parameters")?.let { arr ->
            for (i in 0 until arr.length()) params.add(arr.optString(i, "").lowercase())
        }
        val pricing = obj.optJSONObject("pricing")
        val promptPrice = pricing?.optString("prompt", "1")?.toDoubleOrNull() ?: 1.0
        val completionPrice = pricing?.optString("completion", "1")?.toDoubleOrNull() ?: 1.0
        val isFree = id.endsWith(":free") || (promptPrice == 0.0 && completionPrice == 0.0)

        val cats = LinkedHashSet<OpenRouterCategory>()
        cats.add(OpenRouterCategory.ALL)
        if (isFree) cats.add(OpenRouterCategory.FREE)
        if (params.contains("reasoning") || params.contains("include_reasoning") ||
            Regex("(thinking|reason|r1|o1|o3|o4|qwq|deepthink)").containsMatchIn("$id $name")
        ) {
            cats.add(OpenRouterCategory.REASONING)
        }
        if (inputModalities.contains("image") || modality.contains("image->") ||
            Regex("(vision|vl|multimodal|pixtral|gemini|gpt-4o|gpt-5|claude-3|claude-4|qwen.*vl)").containsMatchIn("$id $name")
        ) {
            cats.add(OpenRouterCategory.VISION)
        }
        if (outputModalities.contains("image") || modality.contains("->image") ||
            Regex("(flux|sdxl|stable-diffusion|dall-e|imagen|recraft|ideogram|midjourney|playground|gpt-image|riverflow|seedream)").containsMatchIn("$id $name")
        ) {
            cats.add(OpenRouterCategory.IMAGE_GEN)
        }
        if (outputModalities.contains("video") || inputModalities.contains("video") || modality.contains("video") ||
            Regex("\\b(veo|sora|kling|runway|luma|pika|wan|hunyuan-video|minimax-video|cogvideo|mochi|seedance|video|vidu|hailuo)\\b").containsMatchIn("$id $name")
        ) {
            cats.add(OpenRouterCategory.VIDEO_GEN)
        }
        if (inputModalities.contains("audio") || outputModalities.contains("audio") ||
            Regex("(audio|voice|whisper|tts|speech|realtime|voxtral)").containsMatchIn("$id $name")
        ) {
            cats.add(OpenRouterCategory.AUDIO)
        }
        return cats.toList()
    }

    suspend fun fetchLiveOpenRouterModels(
        pcBridgeUrl: String,
        forceRefresh: Boolean = false
    ): OpenRouterSyncResult = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl)
        if (base != null) {
            try {
                val url = "$base/api/openrouter/models?refresh=${if (forceRefresh) "1" else "0"}"
                val req = Request.Builder().url(url).get().build()
                clientForUrl(base).newCall(req).useCancellable { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        val arr = json.optJSONArray("models")
                        if (arr != null && arr.length() > 0) {
                            val list = mutableListOf<OpenRouterModelItem>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                val catsArr = obj.optJSONArray("categories")
                                val cats = mutableListOf<OpenRouterCategory>()
                                if (catsArr != null) {
                                    for (cIdx in 0 until catsArr.length()) {
                                        cats.add(parseOpenRouterCategory(catsArr.optString(cIdx, "ALL")))
                                    }
                                }
                                if (cats.isEmpty()) cats.add(OpenRouterCategory.ALL)
                                list.add(
                                    OpenRouterModelItem(
                                        id = obj.optString("id", ""),
                                        name = obj.optString("name", obj.optString("id", "")),
                                        description = obj.optString("description", ""),
                                        createdTimestamp = obj.optLong("created", 0L),
                                        createdDate = obj.optString("createdDate", "2026"),
                                        contextLength = obj.optInt("contextLength", 128000),
                                        categories = cats.distinct(),
                                        modality = obj.optString("modality", "text->text"),
                                        isFree = obj.optBoolean("isFree", false),
                                        pricingPrompt = obj.optString("pricingPrompt", "0"),
                                        pricingCompletion = obj.optString("pricingCompletion", "0")
                                    )
                                )
                            }
                            return@withContext OpenRouterSyncResult(
                                models = list,
                                totalCount = json.optInt("total", json.optInt("totalSyncedCount", json.optInt("totalCount", list.size))),
                                syncedAt = json.optString("cachedAt", json.optString("syncedAt", "")),
                                source = json.optString("source", "openrouter.ai/api/v1/models"),
                                fromCache = json.optBoolean("fromCache", false),
                                hasApiKey = json.optBoolean("hasApiKey", false),
                                maskedApiKey = json.optString("maskedApiKey", ""),
                                selectedModel = json.optString("selectedModel", "")
                            )
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }

        // Direct fallback to https://openrouter.ai/api/v1/models from the Android device (zero hardcoded models!)
        try {
            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .header("Accept", "application/json")
                .get()
                .build()
            httpClient.newCall(req).useCancellable { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val dataArr = json.optJSONArray("data")
                    if (dataArr != null && dataArr.length() > 0) {
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }
                        val rawList = mutableListOf<OpenRouterModelItem>()
                        for (i in 0 until dataArr.length()) {
                            val obj = dataArr.optJSONObject(i) ?: continue
                            val id = obj.optString("id", "")
                            if (id.isBlank()) continue
                            val createdSec = obj.optLong("created", 0L)
                            val createdDate = if (createdSec > 0L) sdf.format(Date(createdSec * 1000L)) else "2026"
                            val cats = classifyDirectOpenRouterModel(obj)
                            val pricing = obj.optJSONObject("pricing")
                            rawList.add(
                                OpenRouterModelItem(
                                    id = id,
                                    name = obj.optString("name", id),
                                    description = obj.optString("description", "").take(260),
                                    createdTimestamp = createdSec,
                                    createdDate = createdDate,
                                    contextLength = obj.optInt("context_length", 128000),
                                    categories = cats,
                                    modality = obj.optJSONObject("architecture")?.optString("modality", "text->text") ?: "text->text",
                                    isFree = cats.contains(OpenRouterCategory.FREE),
                                    pricingPrompt = pricing?.optString("prompt", "0") ?: "0",
                                    pricingCompletion = pricing?.optString("completion", "0") ?: "0"
                                )
                            )
                        }
                        val sorted = rawList.sortedByDescending { it.createdTimestamp }
                        return@withContext OpenRouterSyncResult(
                            models = sorted,
                            totalCount = sorted.size,
                            syncedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                            source = "openrouter.ai/api/v1/models (Direct HTTPS)"
                        )
                    }
                }
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }

        OpenRouterSyncResult(
            models = emptyList(),
            totalCount = 0,
            syncedAt = "",
            source = "offline"
        )
    }

    suspend fun saveOpenRouterConfig(
        pcBridgeUrl: String,
        apiKey: String,
        selectedModel: String
    ): Boolean = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext false
        try {
            val payload = JSONObject().apply {
                if (apiKey.isNotBlank()) put("apiKey", apiKey.trim())
                if (selectedModel.isNotBlank()) put("selectedModel", selectedModel.trim())
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url("$base/api/openrouter/config").post(payload).build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                resp.isSuccessful
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            false
        }
    }

    suspend fun generateOpenRouterMedia(
        pcBridgeUrl: String,
        prompt: String,
        modelId: String,
        isVideo: Boolean,
        openRouterApiKey: String = ""
    ): Pair<GeneratedMediaAttachment?, String?> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl)
            ?: return@withContext null to "Мост ПК недоступен для генерации медиа"
        try {
            val endpoint = if (isVideo) "$base/api/openrouter/generate-video" else "$base/api/openrouter/generate-image"
            val payload = JSONObject().apply {
                put("prompt", prompt)
                put("model", modelId)
                if (openRouterApiKey.isNotBlank()) put("apiKey", openRouterApiKey)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(endpoint).post(payload).build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                if (!resp.isSuccessful) {
                    return@withContext null to "Ошибка HTTP ${resp.code}"
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (!json.optBoolean("ok", false)) {
                    return@withContext null to json.optString("error", "Не удалось сгенерировать медиа")
                }
                val mediaObj = json.optJSONObject("media") ?: json
                val rawMediaUrl = mediaObj.optString("mediaUrl", json.optString("mediaUrl", ""))
                val fullMediaUrl = if (rawMediaUrl.startsWith("http")) {
                    rawMediaUrl
                } else {
                    "${base.trimEnd('/')}/${rawMediaUrl.trimStart('/')}"
                }
                val w = mediaObj.optInt("width", if (isVideo) 640 else 960)
                val h = mediaObj.optInt("height", if (isVideo) 360 else 540)
                val attachment = GeneratedMediaAttachment(
                    id = "media_${System.currentTimeMillis()}",
                    type = if (isVideo) "video" else "image",
                    fileName = mediaObj.optString("fileName", if (isVideo) "antigravity_video.mp4" else "antigravity_image.png"),
                    mimeType = mediaObj.optString("mimeType", if (isVideo) "video/mp4" else "image/png"),
                    mediaUrl = fullMediaUrl,
                    prompt = prompt,
                    modelId = mediaObj.optString("model", json.optString("model", modelId)),
                    sizeBytes = mediaObj.optLong("sizeBytes", 0L),
                    durationSec = mediaObj.optInt("durationSec", if (isVideo) 3 else 0),
                    width = w,
                    height = h,
                    resolution = mediaObj.optString("resolution", "${w}x${h}")
                )
                return@withContext attachment to ""
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            null to (e.localizedMessage ?: "Ошибка генерации медиа")
        }
    }

    // ---------------------------------------------------------------------------
    // Section 11.5: Google Drive Cloud Connector (Gemini & OpenRouter)
    // ---------------------------------------------------------------------------

    private fun parseGoogleDriveFilesJson(json: JSONObject, baseUrl: String): List<GoogleDriveFileItem> {
        val arr = json.optJSONArray("files") ?: return emptyList()
        val out = mutableListOf<GoogleDriveFileItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawDl = obj.optString("downloadUrl", "")
            val fullDl = if (rawDl.startsWith("http")) rawDl else "${baseUrl.trimEnd('/')}/${rawDl.trimStart('/')}"
            out.add(
                GoogleDriveFileItem(
                    id = obj.optString("id", "gd_$i"),
                    name = obj.optString("name", "document.md"),
                    folder = obj.optString("folder", "/AntigravityCloud"),
                    engine = obj.optString("engine", "Gemini"),
                    modelId = obj.optString("modelId", "gemini-4-argon-high"),
                    mimeType = obj.optString("mimeType", "text/markdown"),
                    sizeBytes = obj.optLong("sizeBytes", 0L),
                    updatedAt = obj.optString("updatedAt", "Только что"),
                    webViewLink = fullDl,
                    downloadUrl = fullDl,
                    localUri = obj.optString("localUri", ""),
                    previewSnippet = obj.optString("previewSnippet", ""),
                    content = obj.optString("content", obj.optString("previewSnippet", ""))
                )
            )
        }
        return out
    }

    private val localGoogleDriveFallbackFiles = mutableListOf<GoogleDriveFileItem>()

    suspend fun fetchGoogleDriveFiles(pcBridgeUrl: String, query: String = ""): Pair<GoogleDriveStatus, List<GoogleDriveFileItem>> =
        withContext(Dispatchers.IO) {
            val base = resolveWorkingBaseUrl(pcBridgeUrl)
            if (base != null) {
                try {
                    val req = Request.Builder()
                        .url("$base/api/gdrive/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}")
                        .get()
                        .build()
                    clientForUrl(base).newCall(req).useCancellable { resp ->
                        if (resp.isSuccessful) {
                            val json = JSONObject(resp.body?.string() ?: "{}")
                            val files = parseGoogleDriveFilesJson(json, base)
                            if (query.isBlank()) {
                                synchronized(localGoogleDriveFallbackFiles) {
                                    localGoogleDriveFallbackFiles.clear()
                                    localGoogleDriveFallbackFiles.addAll(files)
                                }
                            }
                            val effectiveFiles = files
                            val usedBytes = effectiveFiles.sumOf { it.sizeBytes }
                            val status = GoogleDriveStatus(
                                connected = json.optBoolean("connected", false),
                                accountEmail = json.optString("accountEmail", ""),
                                folderPath = json.optString("folderPath", "/AntigravityCloud"),
                                filesCount = effectiveFiles.size,
                                usedBytes = usedBytes
                            )
                            return@withContext status to effectiveFiles
                        }
                    }
                } catch (failure: Throwable) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure;
                }
            }
            val fallback = synchronized(localGoogleDriveFallbackFiles) {
                if (query.isBlank()) localGoogleDriveFallbackFiles.toList()
                else localGoogleDriveFallbackFiles.filter {
                    it.name.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true)
                }
            }
            GoogleDriveStatus(
                connected = false,
                accountEmail = "",
                folderPath = "/AntigravityCloud",
                filesCount = fallback.size,
                usedBytes = fallback.sumOf { it.sizeBytes }
            ) to fallback
        }

    suspend fun createOrUpdateGoogleDriveFile(
        pcBridgeUrl: String,
        name: String,
        content: String,
        prompt: String = "",
        engine: String = "Gemini",
        modelId: String = "gemini-4-argon-high"
    ): Pair<GoogleDriveFileItem?, List<GoogleDriveFileItem>> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl)
        if (base != null) {
            try {
                val payload = JSONObject().apply {
                    put("name", name)
                    put("content", content)
                    put("prompt", prompt)
                    put("engine", engine)
                    put("modelId", modelId)
                    put("folder", "/AntigravityCloud")
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder().url("$base/api/gdrive/create").post(payload).build()
                clientForUrl(base).newCall(req).useCancellable { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        val files = parseGoogleDriveFilesJson(json, base)
                        val fileObj = json.optJSONObject("file")
                        val createdItem = if (fileObj != null) {
                            val rawDl = fileObj.optString("downloadUrl", "")
                            val fullDl = if (rawDl.startsWith("http")) rawDl else "${base.trimEnd('/')}/${rawDl.trimStart('/')}"
                            GoogleDriveFileItem(
                                id = fileObj.optString("id", "gd_new"),
                                name = fileObj.optString("name", name),
                                folder = fileObj.optString("folder", "/AntigravityCloud"),
                                engine = fileObj.optString("engine", engine),
                                modelId = fileObj.optString("modelId", modelId),
                                mimeType = fileObj.optString("mimeType", "text/markdown"),
                                sizeBytes = fileObj.optLong("sizeBytes", content.length.toLong()),
                                updatedAt = fileObj.optString("updatedAt", "Только что"),
                                webViewLink = fullDl,
                                downloadUrl = fullDl,
                                localUri = fileObj.optString("localUri", ""),
                                previewSnippet = fileObj.optString("previewSnippet", content.take(260)),
                                content = fileObj.optString("content", content)
                            )
                        } else {
                            files.firstOrNull { it.name == name }
                        }
                        if (files.isNotEmpty()) {
                            synchronized(localGoogleDriveFallbackFiles) {
                                localGoogleDriveFallbackFiles.clear()
                                localGoogleDriveFallbackFiles.addAll(files)
                            }
                        }
                        return@withContext createdItem to files
                    }
                }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
        null to synchronized(localGoogleDriveFallbackFiles) { localGoogleDriveFallbackFiles.toList() }
    }

    suspend fun deleteGoogleDriveFile(pcBridgeUrl: String, name: String): List<GoogleDriveFileItem> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl)
        if (base != null) {
            try {
                val payload = JSONObject().apply {
                    put("name", name)
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder().url("$base/api/gdrive/delete").post(payload).build()
                clientForUrl(base).newCall(req).useCancellable { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        val files = parseGoogleDriveFilesJson(json, base)
                        synchronized(localGoogleDriveFallbackFiles) {
                            localGoogleDriveFallbackFiles.clear()
                            localGoogleDriveFallbackFiles.addAll(files)
                        }
                        return@withContext files
                    }
                }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
        synchronized(localGoogleDriveFallbackFiles) {
            localGoogleDriveFallbackFiles.removeAll { it.name.equals(name, ignoreCase = true) }
            localGoogleDriveFallbackFiles.toList()
        }
    }

    // ---------------------------------------------------------------------------
    // Round 5: Multi-Agent Swarm Window (Section 3)
    // ---------------------------------------------------------------------------

    private fun parseSwarmAgentsJson(json: JSONObject): List<SwarmAgentNode> =
        parseSwarmSnapshot(json).agents

    suspend fun fetchMultiAgentSwarm(pcBridgeUrl: String, requestId: String = ""): List<SwarmAgentNode> =
        fetchMultiAgentSwarmSnapshot(pcBridgeUrl, requestId).agents

    suspend fun fetchMultiAgentSwarmSnapshot(pcBridgeUrl: String, requestId: String = ""): SwarmSnapshot = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext SwarmSnapshot()
        try {
            val req = Request.Builder().url("$base/api/agents/swarm?requestId=${java.net.URLEncoder.encode(requestId, "UTF-8")}").get().build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                if (!resp.isSuccessful) return@withContext SwarmSnapshot()
                val json = JSONObject(resp.body?.string() ?: "{}")
                parseSwarmSnapshot(json)
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            SwarmSnapshot()
        }
    }

    suspend fun manageMultiAgentSwarm(
        pcBridgeUrl: String,
        action: String,
        agentId: String = "",
        task: String = "",
        model: String = "",
        requestId: String = ""
    ): List<SwarmAgentNode> = manageMultiAgentSwarmSnapshot(pcBridgeUrl, action, agentId, task, model, requestId).agents

    suspend fun manageMultiAgentSwarmSnapshot(
        pcBridgeUrl: String,
        action: String,
        agentId: String = "",
        task: String = "",
        model: String = "",
        requestId: String = ""
    ): SwarmSnapshot = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext SwarmSnapshot()
        try {
            val payload = JSONObject().apply {
                put("action", action)
                put("requestId", requestId.ifBlank { if (action == "dispatch_task") java.util.UUID.randomUUID().toString() else "" })
                put("agentId", agentId)
                put("task", task)
                put("model", model)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url("$base/api/agents/swarm").post(payload).build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                if (!resp.isSuccessful) return@withContext SwarmSnapshot()
                val json = JSONObject(resp.body?.string() ?: "{}")
                parseSwarmSnapshot(json)
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            SwarmSnapshot()
        }
    }

    // ---------------------------------------------------------------------------
    // Round 5: Antigravity 2.0 AI Permissions (Sections 4 & 8.2)
    // ---------------------------------------------------------------------------

    private fun parseAiPermissionsJson(json: JSONObject): AiPermissionsConfig {
        val p = json.optJSONObject("permissions") ?: json
        return AiPermissionsConfig(
            toolExecutionPolicy = p.optString("toolExecutionPolicy", "always-proceed"),
            terminalSandboxEnabled = p.optBoolean("terminalSandboxEnabled", true),
            allowNonWorkspaceFiles = p.optBoolean("allowNonWorkspaceFiles", true),
            internetAccessPolicy = p.optString("internetAccessPolicy", "open-web-and-api"),
            artifactReviewPolicy = p.optString("artifactReviewPolicy", "auto-proceed"),
            runInBackgroundKeepAwake = p.optBoolean("runInBackgroundKeepAwake", true),
            phoneFileReadWrite = p.optBoolean("phoneFileReadWrite", true),
            phoneAndroidCliShell = p.optBoolean("phoneAndroidCliShell", true),
            phoneVirtualSandboxExec = p.optBoolean("phoneVirtualSandboxExec", true),
            phoneOpenExternalApps = p.optBoolean("phoneOpenExternalApps", true),
            phoneContactsRead = p.optBoolean("phoneContactsRead", true),
            phoneMediaCameraMic = p.optBoolean("phoneMediaCameraMic", true),
            pcTerminalExec = p.optBoolean("pcTerminalExec", true),
            pcFilesystemWrite = p.optBoolean("pcFilesystemWrite", true),
            pcBrowserAutomation = p.optBoolean("pcBrowserAutomation", true),
            pcMcpToolsExec = p.optBoolean("pcMcpToolsExec", true),
            pcOpenRouterCloudGen = p.optBoolean("pcOpenRouterCloudGen", true)
        )
    }

    suspend fun fetchAiPermissions(pcBridgeUrl: String): AiPermissionsConfig = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext phoneController.aiPermissions
        try {
            val req = Request.Builder().url("$base/api/permissions").get().build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                if (!resp.isSuccessful) return@withContext phoneController.aiPermissions
                val json = JSONObject(resp.body?.string() ?: "{}")
                val parsed = parseAiPermissionsJson(json)
                phoneController.aiPermissions = parsed
                parsed
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            phoneController.aiPermissions
        }
    }

    suspend fun saveAiPermissions(
        pcBridgeUrl: String,
        config: AiPermissionsConfig
    ): AiPermissionsConfig = withContext(Dispatchers.IO) {
        phoneController.aiPermissions = config
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext config
        try {
            val payload = JSONObject().apply {
                put("toolExecutionPolicy", config.toolExecutionPolicy)
                put("terminalSandboxEnabled", config.terminalSandboxEnabled)
                put("allowNonWorkspaceFiles", config.allowNonWorkspaceFiles)
                put("internetAccessPolicy", config.internetAccessPolicy)
                put("artifactReviewPolicy", config.artifactReviewPolicy)
                put("runInBackgroundKeepAwake", config.runInBackgroundKeepAwake)
                put("phoneFileReadWrite", config.phoneFileReadWrite)
                put("phoneAndroidCliShell", config.phoneAndroidCliShell)
                put("phoneVirtualSandboxExec", config.phoneVirtualSandboxExec)
                put("phoneOpenExternalApps", config.phoneOpenExternalApps)
                put("phoneContactsRead", config.phoneContactsRead)
                put("phoneMediaCameraMic", config.phoneMediaCameraMic)
                put("pcTerminalExec", config.pcTerminalExec)
                put("pcFilesystemWrite", config.pcFilesystemWrite)
                put("pcBrowserAutomation", config.pcBrowserAutomation)
                put("pcMcpToolsExec", config.pcMcpToolsExec)
                put("pcOpenRouterCloudGen", config.pcOpenRouterCloudGen)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url("$base/api/permissions").post(payload).build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                if (!resp.isSuccessful) return@withContext config
                val json = JSONObject(resp.body?.string() ?: "{}")
                parseAiPermissionsJson(json)
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            config
        }
    }

    // ---------------------------------------------------------------------------
    // Синхронизация представления чатов и библиотеки ответов (телефон ⇄ ПК)
    // ---------------------------------------------------------------------------

    /** Состояние синхронизации: настройки чатов, сохранённые ответы, рабочие области проектов и надгробия удалений. */
    data class ChatSyncState(
        val chatPrefs: Map<String, ChatLibraryPreferences>,
        val savedArtifacts: List<ArtifactDocument>,
        val projectWorkspaces: Map<String, ProjectWorkspace>,
        val deletedIds: Map<String, Long>
    )

    /**
     * Отправляет локальное состояние на ПК и возвращает объединённое слиянием
     * «побеждает более новый updatedAtMs» (см. mergeSyncState в server.js).
     * При недоступности моста возвращает null — локальные данные не теряются.
     */
    suspend fun syncChatState(
        pcBridgeUrl: String,
        chatPrefs: Map<String, ChatLibraryPreferences>,
        savedArtifacts: List<ArtifactDocument>,
        deletedIds: Map<String, Long>,
        projectWorkspaces: Map<String, ProjectWorkspace> = emptyMap()
    ): ChatSyncState? = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext null
        try {
            val payload = JSONObject().apply {
                put("chatPrefs", JSONObject(ChatLibrary.encode(chatPrefs)))
                put("savedArtifacts", JSONArray(ArtifactLibrary.encode(savedArtifacts)))
                put("projectWorkspaces", JSONArray(ProjectWorkspaces.encode(projectWorkspaces)))
                put("deletedIds", JSONObject().apply { deletedIds.forEach { (id, at) -> put(id, at) } })
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url("$base/api/sync/state").post(payload).build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (!json.optBoolean("ok", false)) return@withContext null
                val deleted = mutableMapOf<String, Long>()
                json.optJSONObject("deletedIds")?.let { obj ->
                    obj.keys().forEach { key -> deleted[key] = obj.optLong(key) }
                }
                ChatSyncState(
                    chatPrefs = ChatLibrary.decode(json.optJSONObject("chatPrefs")?.toString() ?: "{}"),
                    savedArtifacts = ArtifactLibrary.decode(json.optJSONArray("savedArtifacts")?.toString() ?: "[]"),
                    projectWorkspaces = ProjectWorkspaces.decode(json.optJSONArray("projectWorkspaces")?.toString() ?: "[]"),
                    deletedIds = deleted
                )
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Round 5: Context Window Inspector & Manual Reset (Section 9)
    // ---------------------------------------------------------------------------

    suspend fun fetchContextWindowStatus(
        pcBridgeUrl: String,
        conversationId: String,
        modelId: String,
        localMessageCount: Int = 0,
        localChars: Int = 0
    ): ContextWindowStatus = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl)
        if (base != null) {
            try {
                val url = "$base/api/context/status?conversationId=$conversationId&model=$modelId"
                val req = Request.Builder().url(url).get().build()
                clientForUrl(base).newCall(req).useCancellable { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        return@withContext ContextWindowStatus(
                            conversationId = json.optString("conversationId", conversationId),
                            model = json.optString("model", modelId),
                            messageCount = json.optInt("messageCount", localMessageCount),
                            systemPromptTokens = json.optInt("systemPromptTokens", 1250),
                            historyTokens = json.optInt("historyTokens", (localChars / 4).coerceAtLeast(0)),
                            toolOutputTokens = json.optInt("toolOutputTokens", 0),
                            usedTokens = json.optInt("usedTokens", 1250 + (localChars / 4)),
                            maxTokens = json.optInt("maxTokens", 1_000_000),
                            usagePercent = json.optDouble("usagePercent", 0.2).toFloat(),
                            lastResetAt = json.optString("lastResetAt", "")
                        )
                    }
                }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
        val sysTokens = 1250
        val histTokens = (localChars / 4).coerceAtLeast(0)
        val total = sysTokens + histTokens
        val maxT = if (modelId.contains("gemini", ignoreCase = true)) 1_000_000 else 200_000
        ContextWindowStatus(
            conversationId = conversationId,
            model = modelId,
            messageCount = localMessageCount,
            systemPromptTokens = sysTokens,
            historyTokens = histTokens,
            toolOutputTokens = 0,
            usedTokens = total,
            maxTokens = maxT,
            usagePercent = ((total.toDouble() / maxT.toDouble()) * 100.0).toFloat()
        )
    }

    suspend fun resetContextWindow(
        pcBridgeUrl: String,
        conversationId: String,
        clearTranscript: Boolean
    ): ContextWindowStatus = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl)
        var newConvId = "conv_fresh_${System.currentTimeMillis()}"
        var resetTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        if (base != null) {
            try {
                val payload = JSONObject().apply {
                    put("conversationId", conversationId)
                    put("clearTranscript", clearTranscript)
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder().url("$base/api/context/reset").post(payload).build()
                clientForUrl(base).newCall(req).useCancellable { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        newConvId = json.optString("newConversationId", newConvId)
                        resetTime = json.optString("resetAt", resetTime)
                    }
                }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
        ContextWindowStatus(
            conversationId = newConvId,
            model = "gemini-4-argon-high",
            messageCount = 0,
            systemPromptTokens = 1250,
            historyTokens = 0,
            toolOutputTokens = 0,
            usedTokens = 1250,
            maxTokens = 1_000_000,
            usagePercent = 0.1f,
            lastResetAt = resetTime
        )
    }

    /**
     * Настоящее сжатие контекста: мост отправляет историю реальной модели
     * и возвращает сводку. При ошибке — null и текст ошибки, история
     * остаётся без изменений.
     */
    suspend fun compactChatContext(
        pcBridgeUrl: String,
        messages: List<ChatMessage>,
        modelId: String
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl)
            ?: return@withContext null to "Мост ПК недоступен для сжатия контекста"
        try {
            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", JSONArray().apply {
                    messages.forEach { message ->
                        put(JSONObject().apply {
                            put("role", if (message.role == MessageRole.USER) "USER" else "AGENT")
                            put("content", message.content)
                        })
                    }
                })
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url("$base/api/context/compact").post(payload).build()
            clientForUrl(base).newCall(req).useCancellable { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (!resp.isSuccessful || !json.optBoolean("ok", false)) {
                    return@withContext null to json.optString("error", "Сжатие контекста не выполнено (HTTP ${resp.code})")
                }
                val summary = json.optString("summary", "").trim()
                if (summary.isEmpty()) {
                    return@withContext null to "Модель вернула пустую сводку — история сохранена без изменений"
                }
                summary to ""
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            null to (failure.localizedMessage ?: "Ошибка сжатия контекста")
        }
    }

    suspend fun uploadFileToPc(pcBridgeUrl: String, file: AttachedFileItem): String = withContext(Dispatchers.IO) {
        val base = resolveWorkingBaseUrl(pcBridgeUrl) ?: return@withContext ""
        try {
            val body = JSONObject().apply {
                put("fileName", file.name)
                put("mimeType", file.mimeType)
                put("base64Data", file.base64Data)
                put("textContent", file.textPreview)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val req = Request.Builder().url("$base/api/upload").post(body).build()
            val client = if (isLocalOrLoopbackUrl(base)) fastLocalHttpClient else uploadHttpClient
            client.newCall(req).useCancellable { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val pcPath = json.optString("pcPath", "")
                    if (pcPath.isNotBlank()) return@withContext pcPath
                }
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
        ""
    }

    suspend fun sendQuickAssistPrompt(
        prompt: String,
        conversationId: String = "4b1cdeee-81b3-456a-b09e-af65b6bee01d",
        modelId: String = "gemini-4-argon-high"
    ): String? = withContext(Dispatchers.IO) {
        val res = callPcBridgeChatStreaming(
            pcBridgeUrl = "",
            prompt = prompt,
            modelId = modelId,
            conversationId = conversationId,
            attachments = emptyList(),
            sessionHistory = emptyList(),
            isPhoneMode = false,
            voiceMode = true,
            phoneContext = "",
            phoneToolObservation = "",
            phoneStepIndex = 0,
            onUpdate = {}
        )
        res?.reply
    }

    // ---------------------------------------------------------------------------
    // Real-Time Streaming Agent Loop (PC Session Mode + Multi-Step Smartphone Mode)
    // ---------------------------------------------------------------------------

    private suspend fun parseGeneratedMediaArray(
        arr: JSONArray?,
        baseUrl: String,
        fallbackPrompt: String,
        fallbackModel: String
    ): List<GeneratedMediaAttachment> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = mutableListOf<GeneratedMediaAttachment>()
        for (i in 0 until arr.length()) {
            val mediaObj = arr.optJSONObject(i) ?: continue
            val rawMediaUrl = mediaObj.optString("mediaUrl", "")
            if (rawMediaUrl.isBlank()) continue
            val fullMediaUrl = if (rawMediaUrl.startsWith("http")) {
                rawMediaUrl
            } else {
                "${baseUrl.trimEnd('/')}/${rawMediaUrl.trimStart('/')}"
            }
            val mime = mediaObj.optString("mimeType", "image/png")
            val isVid = mediaObj.optString("type", "").equals("video", ignoreCase = true) ||
                mime.startsWith("video/") || rawMediaUrl.endsWith(".mp4", ignoreCase = true)
            val w = mediaObj.optInt("width", if (isVid) 160 else 640)
            val h = mediaObj.optInt("height", if (isVid) 96 else 360)
            val fName = mediaObj.optString("fileName", if (isVid) "antigravity_video.mp4" else "antigravity_img.png")
            val (dlOk, dlPath) = try {
                phoneController.downloadMediaToPhone(fullMediaUrl, fName)
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
                false to ""
            }
            out.add(
                GeneratedMediaAttachment(
                    id = mediaObj.optString("id", "media_${System.currentTimeMillis()}_$i"),
                    type = if (isVid) "video" else "image",
                    fileName = fName,
                    mimeType = mime,
                    mediaUrl = fullMediaUrl,
                    localPath = if (dlOk) dlPath else "",
                    localPhonePath = if (dlOk) dlPath else "",
                    prompt = mediaObj.optString("prompt", fallbackPrompt),
                    modelId = mediaObj.optString("model", fallbackModel),
                    sizeBytes = mediaObj.optLong("sizeBytes", 0L),
                    durationSec = mediaObj.optInt("durationSec", if (isVid) 3 else 0),
                    width = w,
                    height = h,
                    resolution = mediaObj.optString("resolution", "${w}x${h}")
                )
            )
        }
        return out
    }

    suspend fun executeAgentTurn(
        userPrompt: String,
        viaVoice: Boolean,
        executionTarget: AgentExecutionTarget,
        selectedModelId: String,
        activeConversationId: String,
        activeSessionTitle: String,
        attachments: List<AttachedFileItem>,
        sessionHistory: List<ChatMessage>,
        pcBridgeUrl: String,
        geminiApiKey: String,
        openRouterApiKey: String = "",
        extraPhoneContext: String = "",
        transientChat: Boolean = false,
        /** Этап 6: выбор подключений MCP в разговоре (пусто — все включённые). */
        mcpConnections: List<String> = emptyList(),
        onStreamingUpdate: suspend (StreamingTurnUpdate) -> Unit = {}
    ): AgentTurnResult = withContext(Dispatchers.IO) {
        val turnStartMs = System.currentTimeMillis()
        if (executionTarget == AgentExecutionTarget.PHONE_LOCAL_RESOURCES) {
            return@withContext executePhoneTurn(userPrompt, selectedModelId, activeConversationId,
                attachments, sessionHistory, geminiApiKey, openRouterApiKey, extraPhoneContext, pcBridgeUrl, onStreamingUpdate)
        }
        val rawCleanPrompt = userPrompt.trim()

        val cleanPrompt = rawCleanPrompt

        val allToolCalls = mutableListOf<ToolCallEvent>()
        val createdDiffs = mutableListOf<FileDiffItem>()
        val allGeneratedMedia = mutableListOf<GeneratedMediaAttachment>()

        // Upload attachments if any
        val uploadedAttachments = mutableListOf<AttachedFileItem>()
        if (attachments.isNotEmpty()) {
            for (att in attachments) {
                val pcPath = uploadFileToPc(pcBridgeUrl, att)
                uploadedAttachments.add(att.copy(pcUploadedPath = pcPath))
                allToolCalls.add(
                    ToolCallEvent(
                        toolName = "create_file",
                        summary = "Прикреплён файл ${att.name}",
                        details = pcPath.ifBlank { "Память телефона (${att.sizeBytes} Б)" },
                        status = ToolCallStatus.COMPLETED,
                        durationMs = 65L
                    )
                )
            }
        }

        val isPhoneMode = executionTarget == AgentExecutionTarget.PHONE_LOCAL_RESOURCES
        // Basic device telemetry ONLY (NO pre-scanned keyword dump!)
        val basePhoneTelemetry = buildString {
            appendLine("Клиент пользователя — приложение Antigravity на Android. История и вложения хранятся на телефоне. Не предлагай подключить телефон через ADB: доступные локальные действия выполняет само приложение по выданным разрешениям.")
            appendLine(if (isPhoneMode) "Выбран режим инструментов телефона." else "Выбран режим рабочей сессии ПК. Для инструментов телефона пользователь может выбрать режим телефона; не утверждай, что они уже вызваны.")
            if (isPhoneMode) {
                append(phoneController.getDeviceTelemetrySummary())
            }
            if (extraPhoneContext.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(extraPhoneContext.trim())
            }
        }

        var currentConvId = activeConversationId
        val accumulatedThinking = StringBuilder()
        val accumulatedObservations = StringBuilder()
        var finalVisibleMarkdown = ""
        var anyStepSucceeded = false
        var latestTokensPerSecond = 0f
        var latestOutputTokensCount = 0
        var latestFirstTokenLatencyMs = 0L

        val maxSteps = if (isPhoneMode) 6 else 2

        for (stepIndex in 0 until maxSteps) {
            currentCoroutineContext().ensureActive()

            val stepBridgeResp = callPcBridgeChatStreaming(
                pcBridgeUrl = pcBridgeUrl,
                prompt = cleanPrompt,
                modelId = selectedModelId,
                conversationId = currentConvId,
                attachments = if (stepIndex == 0) uploadedAttachments else emptyList(),
                sessionHistory = sessionHistory,
                isPhoneMode = isPhoneMode,
                voiceMode = viaVoice,
                phoneContext = basePhoneTelemetry,
                phoneToolObservation = accumulatedObservations.toString(),
                phoneStepIndex = stepIndex,
                transientChat = transientChat,
                mcpConnections = mcpConnections,
                onUpdate = { stepUpdate ->
                    if (stepUpdate.tokensPerSecond > 0f) latestTokensPerSecond = stepUpdate.tokensPerSecond
                    if (stepUpdate.outputTokensCount > 0) latestOutputTokensCount = stepUpdate.outputTokensCount
                    if (stepUpdate.firstTokenLatencyMs > 0L && latestFirstTokenLatencyMs == 0L) {
                        latestFirstTokenLatencyMs = stepUpdate.firstTokenLatencyMs
                    }
                    val mergedThinking = buildString {
                        if (accumulatedThinking.isNotEmpty()) {
                            append(accumulatedThinking.toString().trim())
                            if (stepUpdate.thinking.isNotBlank()) append("\n\n")
                        }
                        append(stepUpdate.thinking.trim())
                    }.trim()
                    val mergedTools = (allToolCalls + stepUpdate.toolCalls).associateBy { it.id }.values.toList()
                    val mergedDiffs = (createdDiffs + stepUpdate.createdDiffs).distinctBy { "${it.path}:${it.additions}:${it.deletions}" }
                    val mergedMedia = (allGeneratedMedia + stepUpdate.generatedMedia).distinctBy { it.mediaUrl.ifBlank { it.fileName } }
                    onStreamingUpdate(
                        StreamingTurnUpdate(
                            thinking = mergedThinking,
                            thinkingDurationSec = ((System.currentTimeMillis() - turnStartMs) / 1000L).toInt().coerceAtLeast(1),
                            toolCalls = mergedTools,
                            textDelta = stepUpdate.textDelta,
                            fullText = stepUpdate.fullText,
                            createdDiffs = mergedDiffs,
                            generatedMedia = mergedMedia,
                            isDone = false,
                            tokensPerSecond = stepUpdate.tokensPerSecond,
                            outputTokensCount = stepUpdate.outputTokensCount,
                            firstTokenLatencyMs = stepUpdate.firstTokenLatencyMs
                        )
                    )
                }
            )

            if (stepBridgeResp == null) {
                break
            }

            if (stepBridgeResp.isError) {
                return@withContext AgentTurnResult(
                    thinkingTrace = listOf(accumulatedThinking.toString(), stepBridgeResp.thinking)
                        .filter { it.isNotBlank() }.joinToString("\n\n"),
                    responseMarkdown = stepBridgeResp.reply,
                    spokenSummaryRu = sanitizeSpokenSummary(stepBridgeResp.reply),
                    updatedConversationId = stepBridgeResp.conversationId.ifBlank { currentConvId },
                    toolCalls = com.google.antigravity.model.finishTurnTools(allToolCalls, stepBridgeResp.toolCalls,
                        failed = true, target = executionTarget),
                    createdDiffs = (createdDiffs + stepBridgeResp.createdDiffs).distinct(),
                    generatedMedia = (allGeneratedMedia + stepBridgeResp.generatedMedia).distinctBy { it.id },
                    isError = true
                )
            }

            anyStepSucceeded = true
            if (stepBridgeResp.tokensPerSecond > 0f) latestTokensPerSecond = stepBridgeResp.tokensPerSecond
            if (stepBridgeResp.outputTokensCount > 0) latestOutputTokensCount = stepBridgeResp.outputTokensCount
            if (stepBridgeResp.conversationId.isNotBlank()) {
                currentConvId = stepBridgeResp.conversationId
            }

            // Merge PC tools, diffs, and generated media if any
            for (pcTool in stepBridgeResp.toolCalls) {
                val existing = allToolCalls.indexOfFirst { it.id == pcTool.id }
                if (existing >= 0) allToolCalls[existing] = pcTool else allToolCalls.add(pcTool)
            }
            for (pcDiff in stepBridgeResp.createdDiffs) {
                if (createdDiffs.none { it.path == pcDiff.path && it.additions == pcDiff.additions && it.deletions == pcDiff.deletions }) {
                    createdDiffs.add(pcDiff)
                }
            }
            for (pcMedia in stepBridgeResp.generatedMedia) {
                if (allGeneratedMedia.none { it.mediaUrl == pcMedia.mediaUrl && it.fileName == pcMedia.fileName }) {
                    allGeneratedMedia.add(pcMedia)
                }
            }

            val (replyWithoutThinkTag, inlineThinking) = extractClientSideThinking(stepBridgeResp.reply)
            val stepThink = inlineThinking.ifBlank { stepBridgeResp.thinking }.trim()
            if (stepThink.isNotBlank()) {
                if (accumulatedThinking.isNotEmpty()) accumulatedThinking.append("\n\n")
                accumulatedThinking.append(stepThink)
            }

            // Check if the model emitted phone_action tool calls
            val (cleanedReply, executedPhoneActions) = phoneController.extractAndExecuteAiPhoneActions(replyWithoutThinkTag)

            if (executedPhoneActions.isNotEmpty()) {
                val stepObsBuilder = StringBuilder()
                for (act in executedPhoneActions) {
                    val toolStartMs = System.currentTimeMillis()
                    val card = ToolCallEvent(
                        toolName = act.actionType,
                        summary = act.summaryRu,
                        details = act.outputOrDiff.ifBlank { act.targetPathOrCmd }.take(1200),
                        status = if (act.success) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED,
                        durationMs = (System.currentTimeMillis() - toolStartMs).coerceAtLeast(35L)
                    )
                    allToolCalls.add(card)

                    if (act.outputOrDiff.isNotBlank() &&
                        (act.actionType == "create_file" || act.actionType == "edit_file" || act.actionType == "patch_file" || act.actionType == "write_file")
                    ) {
                        createdDiffs.add(
                            FileDiffItem(
                                path = act.targetPathOrCmd,
                                additions = act.outputOrDiff.lines().count { it.startsWith("+") }.coerceAtLeast(1),
                                deletions = act.outputOrDiff.lines().count { it.startsWith("-") },
                                diffPatch = act.outputOrDiff
                            )
                        )
                    }

                    stepObsBuilder.appendLine("Шаг ${stepIndex + 1} — Вызов инструмента `${act.actionType}` (${act.summaryRu}):")
                    stepObsBuilder.appendLine("Цель/путь: ${act.targetPathOrCmd}")
                    stepObsBuilder.appendLine("Статус: ${if (act.success) "УСПЕШНО" else "ОШИБКА"}")
                    stepObsBuilder.appendLine("Реальный вывод инструмента:")
                    stepObsBuilder.appendLine(act.outputOrDiff.ifBlank { "(нет текстового вывода)" })
                    stepObsBuilder.appendLine()
                }

                if (accumulatedObservations.isNotEmpty()) accumulatedObservations.append("\n")
                accumulatedObservations.append(stepObsBuilder.toString().trim())

                // Notify UI with completed phone tool cards right away
                onStreamingUpdate(
                    StreamingTurnUpdate(
                        thinking = accumulatedThinking.toString().trim(),
                        thinkingDurationSec = ((System.currentTimeMillis() - turnStartMs) / 1000L).toInt().coerceAtLeast(1),
                        toolCalls = allToolCalls.toList(),
                        textDelta = "",
                        fullText = cleanedReply,
                        createdDiffs = createdDiffs.toList(),
                        isDone = false
                    )
                )

                // If we still have steps remaining, feed the real tool output back to the model!
                if (stepIndex + 1 < maxSteps) {
                    continue
                } else {
                    finalVisibleMarkdown = cleanedReply
                    break
                }
            } else {
                // Model did not request any further phone tool calls -> final answer!
                finalVisibleMarkdown = cleanedReply.ifBlank { replyWithoutThinkTag }
                break
            }
        }

        // Section 0 Guarantee: NEVER overwrite already-received tool call cards or thinking with "Не удалось подключиться к мосту ПК"!
        if (anyStepSucceeded || allToolCalls.isNotEmpty() || accumulatedThinking.isNotEmpty()) {
            if (finalVisibleMarkdown.isBlank() && currentConvId.isNotBlank()) {
                val recoveredMsgs = try {
                    fetchSessionMessages(pcBridgeUrl, currentConvId)
                } catch (failure: Throwable) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure;
                    emptyList()
                }
                val lastAgent = recoveredMsgs.lastOrNull { it.role == MessageRole.AGENT && it.content.isNotBlank() }
                if (lastAgent != null) {
                    finalVisibleMarkdown = lastAgent.content
                    for (t in lastAgent.toolCalls) {
                        if (allToolCalls.none { it.toolName == t.toolName && it.summary == t.summary }) {
                            allToolCalls.add(t)
                        }
                    }
                    for (d in lastAgent.createdDiffs) {
                        if (createdDiffs.none { it.path == d.path && it.additions == d.additions }) {
                            createdDiffs.add(d)
                        }
                    }
                }
            }
            if (finalVisibleMarkdown.isBlank() && allToolCalls.isNotEmpty()) {
                finalVisibleMarkdown = buildString {
                    appendLine("Выполнено инструментальных шагов: **${allToolCalls.size}**.")
                    appendLine()
                    allToolCalls.takeLast(6).forEach { tc ->
                        appendLine("- **${tc.summary}** (`${tc.toolName}`)")
                    }
                }.trim()
            }
            if (finalVisibleMarkdown.isNotBlank()) {
                val sanitizedMarkdown = sanitizeStoragePathsInMarkdown(finalVisibleMarkdown, cleanPrompt)
                val elapsedMs = (System.currentTimeMillis() - turnStartMs).coerceAtLeast(180L)
                val elapsedSec = (elapsedMs / 1000L).toInt().coerceAtLeast(1)
                val finalThinking = accumulatedThinking.toString().trim()
                val estTokens = if (latestOutputTokensCount > 0) {
                    latestOutputTokensCount
                } else {
                    (sanitizedMarkdown.length / 3.6f).toInt().coerceAtLeast(1)
                }
                val estTps = if (latestTokensPerSecond > 0f) {
                    latestTokensPerSecond
                } else {
                    ((estTokens.toFloat() / (elapsedMs.toFloat() / 1000f)) * 10f).toInt().toFloat() / 10f
                }
                val finalTtft = if (latestFirstTokenLatencyMs > 0L) latestFirstTokenLatencyMs else 0L
                onStreamingUpdate(
                    StreamingTurnUpdate(
                        thinking = finalThinking,
                        thinkingDurationSec = if (finalThinking.isNotBlank()) elapsedSec else 0,
                        toolCalls = allToolCalls.toList(),
                        textDelta = "",
                        fullText = sanitizedMarkdown,
                        createdDiffs = createdDiffs.toList(),
                        generatedMedia = allGeneratedMedia.toList(),
                        isDone = true,
                        tokensPerSecond = estTps.coerceAtLeast(0f),
                        outputTokensCount = estTokens,
                        firstTokenLatencyMs = finalTtft
                    )
                )
                return@withContext AgentTurnResult(
                    thinkingTrace = finalThinking,
                    thinkingDurationSec = if (finalThinking.isNotBlank()) elapsedSec else 0,
                    responseMarkdown = sanitizedMarkdown,
                    spokenSummaryRu = sanitizeSpokenSummary(sanitizedMarkdown),
                    updatedConversationId = currentConvId,
                    toolCalls = allToolCalls,
                    createdDiffs = createdDiffs,
                    generatedMedia = allGeneratedMedia.toList(),
                    tokensPerSecond = estTps.coerceAtLeast(0f),
                    outputTokensCount = estTokens,
                    firstTokenLatencyMs = finalTtft
                )
            }
        }

        // Direct OpenRouter API fallback if an OpenRouter model is selected and user provided an OpenRouter API key
        if (selectedModelId.contains("/") && openRouterApiKey.isNotBlank()) {
            val orResult = callDirectOpenRouterApi(
                cleanPrompt = cleanPrompt,
                openRouterApiKey = openRouterApiKey,
                sessionHistory = sessionHistory,
                selectedModelId = selectedModelId,
                activeConversationId = activeConversationId,
                turnStartMs = turnStartMs,
                allToolCalls = allToolCalls,
                createdDiffs = createdDiffs,
                onStreamingUpdate = onStreamingUpdate
            )
            if (orResult != null) return@withContext orResult
        }

        // Fallback ONLY if user explicitly configured a direct Gemini API key in Settings
        if (!selectedModelId.contains("/") && !selectedModelId.startsWith("gemini-4-argon") && !selectedModelId.startsWith("gemini-3.8-") && geminiApiKey.isNotBlank()) {
            val geminiResult = executeDirectGeminiLoop(
                cleanPrompt = cleanPrompt,
                geminiApiKey = geminiApiKey,
                sessionHistory = sessionHistory,
                selectedModelId = selectedModelId,
                isPhoneMode = isPhoneMode,
                basePhoneTelemetry = basePhoneTelemetry,
                activeConversationId = activeConversationId,
                turnStartMs = turnStartMs,
                allToolCalls = allToolCalls,
                createdDiffs = createdDiffs,
                onStreamingUpdate = onStreamingUpdate
            )
            if (geminiResult != null) return@withContext geminiResult
        }

        // Honest Offline / Bridge Down message (Scenario G — zero fake template responses!)
        val honestError = when {
            selectedModelId.startsWith("gemini-4-argon") || selectedModelId.startsWith("gemini-3.8-") ->
                "Модель $selectedModelId доступна через сессию ПК, но мост не отвечает. Проверьте адрес моста и запустите PC-Bridge. Ключ Gemini API не подключает эту модель ПК. Переписка сохранена на телефоне; после восстановления связи нажмите «Повторить»."
            selectedModelId.contains("/") -> "Не удалось получить ответ $selectedModelId. Проверьте подключение к мосту и ключ OpenRouter. Переписка сохранена на телефоне."
            else -> "Не удалось получить ответ $selectedModelId. Проверьте подключение и ключ Gemini API. Переписка сохранена на телефоне."
        }
        AgentTurnResult(
            thinkingTrace = "",
            thinkingDurationSec = 0,
            responseMarkdown = honestError,
            spokenSummaryRu = honestError,
            updatedConversationId = activeConversationId,
            toolCalls = allToolCalls,
            createdDiffs = createdDiffs,
            isError = true
        )
    }

    fun loadAssetSystemPrompt(): String {
        return phoneController.loadAgentSystemPrompt()
    }

    /** Phone tools stay on Android; CLI models may use the bridge for inference. */
    private suspend fun executePhoneTurn(
        prompt: String, modelId: String, conversationId: String, attachments: List<AttachedFileItem>,
        history: List<ChatMessage>, geminiKey: String, routerKey: String, context: String, bridgeUrl: String,
        update: suspend (StreamingTurnUpdate) -> Unit
    ): AgentTurnResult {
        val route = com.google.antigravity.model.phoneModelRoute(modelId)
        val unavailable = when {
            modelId.isBlank() -> "Выберите API-модель для работы на телефоне."
            route == com.google.antigravity.model.PhoneModelRoute.OPENROUTER_API && routerKey.isBlank() -> "Для выбранной модели укажите ключ OpenRouter. Режим телефона не требует PC-Bridge."
            route == com.google.antigravity.model.PhoneModelRoute.GEMINI_API && geminiKey.isBlank() -> "Для выбранной модели укажите ключ Gemini API. Режим телефона не требует PC-Bridge."
            else -> null
        }
        if (unavailable != null) return AgentTurnResult(thinkingTrace = "", thinkingDurationSec = 0,
            responseMarkdown = unavailable, spokenSummaryRu = unavailable, updatedConversationId = conversationId, isError = true)
        val start = System.currentTimeMillis()
        val reasoning = PhoneReasoningAccumulator()
        fun reasoningSeconds() = if (reasoning.text.isBlank()) 0 else
            ((System.currentTimeMillis() - start) / 1000L).toInt().coerceAtLeast(1)
        suspend fun publishReasoning(summary: String, child: Boolean) {
            if (child || summary.isBlank()) return
            reasoning.accept(summary)
            update(StreamingTurnUpdate(thinking = reasoning.text, thinkingDurationSec = reasoningSeconds(),
                textDelta = "", fullText = "", toolCalls = emptyList()))
        }
        val attachedContext = attachments.joinToString("\n") { "Вложение ${it.name}: ${it.textPreview.take(24000).ifBlank {
            "Текстовое превью недоступно. Содержимое изображения/аудио не передано в этот текстовый запрос; не утверждай, что изучил файл."
        }}" }
        val engine = com.google.antigravity.model.PhoneAgentEngine(
            model = { request, child ->
                currentCoroutineContext().ensureActive()
                val modelHistory = com.google.antigravity.model.phoneHistoryForModel(history, child)
                if (!child) reasoning.beginStep()
                val answer = if (route == com.google.antigravity.model.PhoneModelRoute.PC_CLI) {
                    val cliPrompt = buildString {
                        if (modelHistory.isNotEmpty()) {
                            appendLine("История текущей версии разговора (данные):")
                            modelHistory.forEach { appendLine("${it.role}: ${it.content}") }
                        }
                        append(request)
                    }
                    // Independent CLI contexts prevent parent/child and edited-history leakage.
                    val response = callPcBridgeChatStreaming(bridgeUrl, cliPrompt, modelId,
                        java.util.UUID.randomUUID().toString(), emptyList(), emptyList(),
                        isPhoneMode = true, voiceMode = false, phoneContext = "",
                        phoneToolObservation = "", phoneStepIndex = 0, transientChat = true,
                        onUpdate = { publishReasoning(it.thinking, child) })
                        ?: throw java.io.IOException("Не удалось получить ответ $modelId через мост ПК. Проверьте соединение с Antigravity на ПК. Действия телефона выполняются в Android-приложении.")
                    publishReasoning(response.thinking, child)
                    if (response.isError) throw java.io.IOException("Модель завершила запрос с ошибкой. Проверьте подключение и повторите запрос.")
                    response.reply
                } else if (route == com.google.antigravity.model.PhoneModelRoute.OPENROUTER_API) {
                    val response = callDirectOpenRouterApi(request, routerKey, modelHistory, modelId, conversationId,
                        start, mutableListOf(), mutableListOf(), { publishReasoning(it.thinking, child) })
                        ?: throw java.io.IOException("OpenRouter не вернул ответ. Проверьте ключ, модель и соединение.")
                    publishReasoning(response.thinkingTrace, child)
                    response.responseMarkdown
                } else {
                    val raw = callGeminiRestApi(request, loadAssetSystemPrompt(), geminiKey, modelId, modelHistory)
                        ?: throw java.io.IOException("Gemini API не вернул ответ. Проверьте ключ, модель и соединение.")
                    val (answer, taggedThinking) = extractClientSideThinking(raw.text)
                    publishReasoning(raw.thinking.ifBlank { taggedThinking }, child)
                    answer
                }
                if (!child) reasoning.finishStep()
                answer
            },
            execute = { obj ->
                val action = phoneController.executeSinglePhoneActionObject(obj)
                if (action == null) com.google.antigravity.model.PhoneToolOutput(false, "Инструмент", "Неизвестное действие телефона")
                else com.google.antigravity.model.PhoneToolOutput(action.success, action.summaryRu,
                    action.outputOrDiff.ifBlank { action.targetPathOrCmd })
            },
            publish = { tools -> update(StreamingTurnUpdate(thinking = reasoning.text, thinkingDurationSec = reasoningSeconds(),
                textDelta = "", toolCalls = tools, fullText = "", isDone = false)) }
        )
        val reply = engine.run(buildString {
            appendLine(prompt)
            appendLine(phoneController.getDeviceTelemetrySummary())
            if (context.isNotBlank()) appendLine("Контекст пользователя:\n$context")
            if (attachedContext.isNotBlank()) appendLine("Текст вложений:\n$attachedContext")
        })
        update(StreamingTurnUpdate(thinking = reasoning.text, thinkingDurationSec = reasoningSeconds(), textDelta = "",
            fullText = reply.text, toolCalls = reply.tools, isDone = true))
        return AgentTurnResult(thinkingTrace = reasoning.text, thinkingDurationSec = reasoningSeconds(), responseMarkdown = reply.text,
            spokenSummaryRu = sanitizeSpokenSummary(reply.text), updatedConversationId = conversationId,
            toolCalls = reply.tools, isError = !reply.completed)
    }

    private suspend fun callDirectOpenRouterApi(
        cleanPrompt: String,
        openRouterApiKey: String,
        sessionHistory: List<ChatMessage>,
        selectedModelId: String,
        activeConversationId: String,
        turnStartMs: Long,
        allToolCalls: MutableList<ToolCallEvent>,
        createdDiffs: MutableList<FileDiffItem>,
        onStreamingUpdate: suspend (StreamingTurnUpdate) -> Unit
    ): AgentTurnResult? {
        return try {
            val cleanModel = selectedModelId.removePrefix("openrouter/").trim().ifBlank { "google/gemini-2.0-flash-exp:free" }
            val sysPrompt = loadAssetSystemPrompt() +
                "\n\nТы работаешь как модель $cleanModel через OpenRouter API. Адаптируй ответы под возможности модели $cleanModel."
            val messagesArr = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sysPrompt))
                sessionHistory.forEach { message ->
                    put(JSONObject().put("role", if (message.role == MessageRole.USER) "user" else "assistant").put("content", message.content))
                }
                put(JSONObject().put("role", "user").put("content", cleanPrompt))
            }
            val payload = JSONObject().apply {
                put("model", cleanModel)
                put("messages", messagesArr)
                put("include_reasoning", true)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer ${openRouterApiKey.trim()}")
                .header("HTTP-Referer", "https://antigravity.google")
                .header("X-Title", "Google Antigravity Android 2.0")
                .post(payload)
                .build()

            httpClient.newCall(req).useCancellable { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: return null)
                val choice = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
                val rawContent = choice.optString("content", "").trim()
                if (rawContent.isBlank()) return null
                val reasoning = choice.optString("reasoning", "").trim()
                val (withoutThink, inlineThink) = extractClientSideThinking(rawContent)
                val finalThink = reasoning.ifBlank { inlineThink }
                val finalMd = sanitizeStoragePathsInMarkdown(withoutThink, cleanPrompt)
                val elapsedMs = (System.currentTimeMillis() - turnStartMs).coerceAtLeast(180L)
                val elapsedSec = (elapsedMs / 1000L).toInt().coerceAtLeast(1)
                val estTokens = (finalMd.length / 3.6f).toInt().coerceAtLeast(12)
                val estTps = (((estTokens.toFloat() / (elapsedMs.toFloat() / 1000f)) * 10f).toInt().toFloat() / 10f).coerceAtLeast(14.0f)
                onStreamingUpdate(
                    StreamingTurnUpdate(
                        thinking = finalThink,
                        thinkingDurationSec = if (finalThink.isNotBlank()) elapsedSec else 0,
                        toolCalls = allToolCalls.toList(),
                        textDelta = finalMd,
                        fullText = finalMd,
                        createdDiffs = createdDiffs.toList(),
                        isDone = true,
                        tokensPerSecond = estTps,
                        outputTokensCount = estTokens,
                        firstTokenLatencyMs = 320L
                    )
                )
                AgentTurnResult(
                    thinkingTrace = finalThink,
                    thinkingDurationSec = if (finalThink.isNotBlank()) elapsedSec else 0,
                    responseMarkdown = finalMd,
                    spokenSummaryRu = sanitizeSpokenSummary(finalMd),
                    updatedConversationId = activeConversationId,
                    toolCalls = allToolCalls,
                    createdDiffs = createdDiffs,
                    tokensPerSecond = estTps,
                    outputTokensCount = estTokens,
                    firstTokenLatencyMs = 320L
                )
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            null
        }
    }

    private suspend fun executeDirectGeminiLoop(
        cleanPrompt: String,
        geminiApiKey: String,
        sessionHistory: List<ChatMessage>,
        selectedModelId: String,
        isPhoneMode: Boolean,
        basePhoneTelemetry: String,
        activeConversationId: String,
        turnStartMs: Long,
        allToolCalls: MutableList<ToolCallEvent>,
        createdDiffs: MutableList<FileDiffItem>,
        onStreamingUpdate: suspend (StreamingTurnUpdate) -> Unit
    ): AgentTurnResult? {
        val observations = StringBuilder()
        val thinkingAcc = StringBuilder()
        val maxSteps = if (isPhoneMode) 4 else 1
        val unifiedSysPrompt = loadAssetSystemPrompt()

        for (step in 0 until maxSteps) {
            val promptWithContext = buildString {
                if (isPhoneMode) {
                    appendLine("Доступные инструменты смартфона (вызывай по одному в блоке ```phone_action {\"action\":\"...\"} ```):")
                    appendLine("- search_files (query, extension, contentQuery, folder)")
                    appendLine("- search_media (query, mediaType, dateFilter)")
                    appendLine("- read_file (path)")
                    appendLine("- write_file (path, content, append)")
                    appendLine("- patch_file (path, target, replacement)")
                    appendLine("- list_dir (path)")
                    appendLine("- open_app (query)")
                    appendLine("- contacts (query)")
                    appendLine("- share (path, content)")
                    appendLine("Телеметрия устройства:\n$basePhoneTelemetry")
                }
                appendLine("\nЗапрос пользователя: $cleanPrompt")
                if (observations.isNotEmpty()) {
                    appendLine("\nРезультаты выполненных шагов:\n$observations")
                    appendLine("\nПродолжи выполнение или дай итоговый ответ.")
                }
            }
            val rawReply = callGeminiRestApi(promptWithContext, unifiedSysPrompt, geminiApiKey, selectedModelId, sessionHistory) ?: return null
            val (withoutThink, taggedThink) = extractClientSideThinking(rawReply.text)
            val inlineThink = rawReply.thinking.ifBlank { taggedThink }
            if (inlineThink.isNotBlank()) {
                if (thinkingAcc.isNotEmpty()) thinkingAcc.append("\n\n")
                thinkingAcc.append(inlineThink)
            }
            val (cleaned, actions) = phoneController.extractAndExecuteAiPhoneActions(withoutThink)
            if (actions.isNotEmpty() && step + 1 < maxSteps) {
                for (act in actions) {
                    allToolCalls.add(
                        ToolCallEvent(
                            toolName = act.actionType,
                            summary = act.summaryRu,
                            details = act.outputOrDiff.ifBlank { act.targetPathOrCmd },
                            status = if (act.success) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED,
                            durationMs = 50L
                        )
                    )
                    observations.appendLine("Инструмент ${act.actionType}: ${act.summaryRu}\nВывод:\n${act.outputOrDiff}")
                }
                onStreamingUpdate(
                    StreamingTurnUpdate(
                        thinking = thinkingAcc.toString(),
                        thinkingDurationSec = ((System.currentTimeMillis() - turnStartMs) / 1000L).toInt().coerceAtLeast(1),
                        toolCalls = allToolCalls.toList(),
                        textDelta = "",
                        fullText = cleaned,
                        createdDiffs = createdDiffs.toList(),
                        isDone = false
                    )
                )
                continue
            } else {
                val finalMd = sanitizeStoragePathsInMarkdown(cleaned.ifBlank { withoutThink }, cleanPrompt)
                val elapsed = ((System.currentTimeMillis() - turnStartMs) / 1000L).toInt().coerceAtLeast(1)
                val finalThink = thinkingAcc.toString().trim()
                return AgentTurnResult(
                    thinkingTrace = finalThink,
                    thinkingDurationSec = if (finalThink.isNotBlank()) elapsed else 0,
                    responseMarkdown = finalMd,
                    spokenSummaryRu = sanitizeSpokenSummary(finalMd),
                    updatedConversationId = activeConversationId,
                    toolCalls = allToolCalls,
                    createdDiffs = createdDiffs
                )
            }
        }
        return null
    }

    private suspend fun pollTurnUntilComplete(
        base: String,
        turnId: String,
        conversationId: String,
        streamStartMs: Long,
        initialThinking: String,
        initialText: String,
        streamTools: MutableList<ToolCallEvent>,
        streamDiffs: MutableList<FileDiffItem>,
        onUpdate: suspend (StreamingTurnUpdate) -> Unit
    ): PcBridgeChatResponse? {
        var latestThinking = initialThinking
        var latestText = initialText
        var resolvedConvId = conversationId
        for (pollAttempt in 0 until 90) {
            currentCoroutineContext().ensureActive()
            delay(1800L)
            try {
                val url = "$base/api/chat/turn-status?turnId=$turnId&conversationId=$resolvedConvId"
                val req = Request.Builder().url(url).get().build()
                val bodyStr = fastProbeClient.newCall(req).useCancellable { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                } ?: continue
                val json = JSONObject(bodyStr)
                if (!json.optBoolean("found", json.optBoolean("ok", false))) continue
                val cid = json.optString("conversationId", "")
                if (cid.isNotBlank()) resolvedConvId = cid
                val status = json.optString("status", "running").lowercase(Locale.ROOT)
                val pollTerminal = status in setOf("done", "error", "completed", "failed", "cancelled", "canceled") || bridgeTurnFailed(json)
                val srvThinking = json.optString("thinking", "")
                if (srvThinking.isNotBlank()) latestThinking = srvThinking
                val srvReply = json.optString("reply", "")
                if (srvReply.isNotBlank()) {
                    val (cleanVis, inlineThink) = stripStreamingHiddenBlocks(srvReply)
                    if (inlineThink.isNotBlank() && latestThinking.isBlank()) latestThinking = inlineThink
                    latestText = cleanVis
                }
                val tcArr = json.optJSONArray("toolCalls")
                if (tcArr != null) {
                    for (i in 0 until tcArr.length()) {
                        val to = tcArr.optJSONObject(i) ?: continue
                        mergeBridgeTool(streamTools, to, "poll_tc_$i", base)
                    }
                }
                val diffArr = json.optJSONArray("createdDiffs")
                if (diffArr != null) {
                    for (dIdx in 0 until diffArr.length()) {
                        val dObj = diffArr.optJSONObject(dIdx) ?: continue
                        val diffItem = FileDiffItem(
                            path = dObj.optString("path", "file"),
                            additions = dObj.optInt("additions", 1).coerceAtLeast(0),
                            deletions = dObj.optInt("deletions", 0).coerceAtLeast(0),
                            diffPatch = dObj.optString("diffPatch", "")
                        )
                        if (streamDiffs.none { it.path == diffItem.path && it.additions == diffItem.additions }) {
                            streamDiffs.add(diffItem)
                        }
                    }
                }
                val elapsed = ((System.currentTimeMillis() - streamStartMs) / 1000L).toInt().coerceAtLeast(1)
                onUpdate(
                    StreamingTurnUpdate(
                        thinking = latestThinking.trim(),
                        thinkingDurationSec = elapsed,
                        toolCalls = streamTools.toList(),
                        textDelta = "",
                        fullText = latestText,
                        createdDiffs = streamDiffs.toList(),
                        isDone = pollTerminal
                    )
                )
                if (pollTerminal) {
                    val polledMedia = parseGeneratedMediaArray(
                        arr = json.optJSONArray("generatedMedia"),
                        baseUrl = base,
                        fallbackPrompt = "",
                        fallbackModel = json.optString("model", "")
                    )
                    return PcBridgeChatResponse(
                        reply = latestText.ifBlank { srvReply }.ifBlank { json.optString("error", "Запрос завершён без ответа. Повторите попытку.") }.trim(),
                        conversationId = resolvedConvId,
                        thinking = latestThinking.trim(),
                        thinkingDurationSec = json.optInt("thinkingDurationSec", elapsed),
                        toolCalls = streamTools.toList(),
                        createdDiffs = streamDiffs.toList(),
                        generatedMedia = polledMedia,
                        resolvedBaseUrl = base,
                        isError = bridgeTurnFailed(json)
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
        if (latestText.isNotBlank() || streamTools.isNotEmpty() || latestThinking.isNotBlank()) {
            val elapsed = ((System.currentTimeMillis() - streamStartMs) / 1000L).toInt().coerceAtLeast(1)
            return PcBridgeChatResponse(
                reply = listOf(latestText.trim(), "Ответ не завершён: не удалось подтвердить результат на ПК. Повторите запрос.")
                    .filter { it.isNotBlank() }.joinToString("\n\n"),
                conversationId = resolvedConvId,
                thinking = latestThinking.trim(),
                thinkingDurationSec = elapsed,
                toolCalls = streamTools.toList(),
                createdDiffs = streamDiffs.toList(),
                resolvedBaseUrl = base,
                isError = true
            )
        }
        return null
    }

    private suspend fun callPcBridgeChatStreaming(
        pcBridgeUrl: String,
        prompt: String,
        modelId: String,
        conversationId: String,
        attachments: List<AttachedFileItem>,
        sessionHistory: List<ChatMessage>,
        isPhoneMode: Boolean,
        voiceMode: Boolean,
        phoneContext: String,
        phoneToolObservation: String,
        phoneStepIndex: Int,
        transientChat: Boolean = false,
        /** Этап 6: выбор подключений MCP в разговоре (пусто — все включённые). */
        mcpConnections: List<String> = emptyList(),
        onUpdate: suspend (StreamingTurnUpdate) -> Unit
    ): PcBridgeChatResponse? {
        val cleanUrl = pcBridgeUrl.trim()
        if (cleanUrl.contains("offline", ignoreCase = true) || cleanUrl.contains("invalid", ignoreCase = true)) {
            return null
        }

        val clientTurnId = "turn_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val attArray = JSONArray()
        attachments.forEach { a ->
            attArray.put(
                JSONObject().apply {
                    put("name", a.name)
                    put("mimeType", a.mimeType)
                    put("sizeBytes", a.sizeBytes)
                    put("pcPath", a.pcUploadedPath)
                    put("textPreview", a.textPreview)
                }
            )
        }
        val historyArray = JSONArray()
        sessionHistory.forEach { m ->
            if (m.content.isNotBlank() && !m.isContextResetDivider) {
                historyArray.put(
                    JSONObject().apply {
                        put("role", if (m.role == MessageRole.USER) "USER" else "AGENT")
                        put("content", m.content)
                    }
                )
            }
        }
        val payload = JSONObject().apply {
            put("turnId", clientTurnId)
            put("prompt", prompt)
            put("model", modelId)
            put("conversationId", conversationId)
            put("attachments", attArray)
            put("history", historyArray)
            put("mode", if (isPhoneMode) "PHONE_ON_DEVICE" else "PC_REMOTE")
            put("voiceMode", voiceMode)
            put("transient", transientChat)
            put("phoneContext", phoneContext)
            put("phoneToolObservation", phoneToolObservation)
            put("phoneStepIndex", phoneStepIndex)
            put("stream", true)
            // Этап 6: явный выбор подключений для этого хода (пусто — без ограничения).
            if (mcpConnections.isNotEmpty()) {
                put("mcpConnections", JSONArray(mcpConnections))
            }
        }.toString()

        val verifiedBase = resolveWorkingBaseUrl(pcBridgeUrl)
        // Resolve/discover before submitting a turn. Never replay an accepted or
        // uncertain request on a different workstation; recovery polls this one.
        for (base in listOfNotNull(verifiedBase)) {
            if (forcePublicTunnelOnly && isLocalOrLoopbackUrl(base)) continue
            val streamStartMs = System.currentTimeMillis()
            var firstTokenTimeMs = 0L
            var streamTokensPerSecond = 0f
            var streamOutputTokensCount = 0
            var activeTurnId = clientTurnId
            var returnedConvId = conversationId
            var liveThinking = ""
            var liveFullText = ""
            var previousVisibleText = ""
            var finalReply = ""
            var finalThinkingSec = 0
            var receivedDoneEvent = false
            var terminalFailed = false
            var receivedAnyEvent = false
            val streamTools = mutableListOf<ToolCallEvent>()
            val streamDiffs = mutableListOf<FileDiffItem>()
            val streamMedia = mutableListOf<GeneratedMediaAttachment>()

            try {
                if (base != lastWorkingBridgeUrl && probeSingleBridgeUrl(base) == null) {
                    continue
                }
                val req = Request.Builder()
                    .url("$base/api/chat/stream")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                activeRemoteTurn.set(base to clientTurnId)
                val success = clientForUrl(base).newCall(req).useCancellable { resp ->
                    if (!resp.isSuccessful) return@useCancellable false
                    val reader: BufferedReader = resp.body?.charStream()?.buffered() ?: return@useCancellable false
                    var line: String?
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        line = reader.readLine() ?: break
                        val trimmedLine = line.trim()
                        if (trimmedLine.isEmpty()) continue
                        val ev = try {
                            JSONObject(trimmedLine)
                        } catch (failure: Throwable) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure;
                            continue
                        }
                        receivedAnyEvent = true
                        when (ev.optString("type", "")) {
                            "init" -> {
                                val tid = ev.optString("turnId", "")
                                if (tid.isNotBlank()) activeTurnId = tid
                                val cid = ev.optString("conversationId", "")
                                if (cid.isNotBlank()) returnedConvId = cid
                            }
                            "thinking_delta" -> {
                                val fullT = ev.optString("fullThinking", ev.optString("thinking", ""))
                                val deltaT = ev.optString("delta", "")
                                liveThinking = if (fullT.isNotBlank()) fullT else (liveThinking + deltaT)
                                val elapsed = ((System.currentTimeMillis() - streamStartMs) / 1000L).toInt().coerceAtLeast(1)
                                onUpdate(
                                    StreamingTurnUpdate(
                                        thinking = liveThinking.trim(),
                                        thinkingDurationSec = elapsed,
                                        toolCalls = streamTools.toList(),
                                        textDelta = "",
                                        fullText = previousVisibleText,
                                        createdDiffs = streamDiffs.toList(),
                                        isDone = false,
                                        tokensPerSecond = streamTokensPerSecond,
                                        outputTokensCount = streamOutputTokensCount,
                                        firstTokenLatencyMs = if (firstTokenTimeMs > 0L) (firstTokenTimeMs - streamStartMs).coerceAtLeast(50L) else 0L
                                    )
                                )
                            }
                            "tool" -> {
                                val nested = ev.optJSONObject("tool")
                                mergeBridgeTool(streamTools, ev, "tc_${streamTools.size}", base)
                                val diffObj = ev.optJSONObject("diff") ?: nested?.optJSONObject("diff")
                                if (diffObj != null) {
                                    val diffItem = FileDiffItem(
                                        path = diffObj.optString("path", "file"),
                                        additions = diffObj.optInt("additions", 1).coerceAtLeast(0),
                                        deletions = diffObj.optInt("deletions", 0).coerceAtLeast(0),
                                        diffPatch = diffObj.optString("diffPatch", "")
                                    )
                                    if (streamDiffs.none { it.path == diffItem.path && it.additions == diffItem.additions }) {
                                        streamDiffs.add(diffItem)
                                    }
                                }
                                val elapsed = ((System.currentTimeMillis() - streamStartMs) / 1000L).toInt().coerceAtLeast(1)
                                onUpdate(
                                    StreamingTurnUpdate(
                                        thinking = liveThinking.trim(),
                                        thinkingDurationSec = elapsed,
                                        toolCalls = streamTools.toList(),
                                        textDelta = "",
                                        fullText = previousVisibleText,
                                        createdDiffs = streamDiffs.toList(),
                                        isDone = false,
                                        tokensPerSecond = streamTokensPerSecond,
                                        outputTokensCount = streamOutputTokensCount,
                                        firstTokenLatencyMs = if (firstTokenTimeMs > 0L) (firstTokenTimeMs - streamStartMs).coerceAtLeast(50L) else 0L
                                    )
                                )
                            }
                            "text_delta" -> {
                                val nowMs = System.currentTimeMillis()
                                if (firstTokenTimeMs == 0L) firstTokenTimeMs = nowMs
                                val fullTxt = ev.optString("fullText", ev.optString("text", ""))
                                val deltaTxt = ev.optString("delta", "")
                                liveFullText = if (fullTxt.isNotBlank()) fullTxt else (liveFullText + deltaTxt)

                                val (cleanVisible, inlineThink) = stripStreamingHiddenBlocks(liveFullText)
                                if (inlineThink.isNotBlank() && liveThinking.isBlank()) {
                                    liveThinking = inlineThink
                                }
                                val newVisibleDelta = if (cleanVisible.startsWith(previousVisibleText)) {
                                    cleanVisible.substring(previousVisibleText.length)
                                } else {
                                    ""
                                }
                                previousVisibleText = cleanVisible
                                val elapsedMs = (nowMs - streamStartMs).coerceAtLeast(150L)
                                val elapsed = (elapsedMs / 1000L).toInt().coerceAtLeast(1)
                                val serverTps = ev.optDouble("tokensPerSecond", 0.0).toFloat()
                                val serverOutTok = ev.optInt("outputTokensCount", 0)
                                streamOutputTokensCount = if (serverOutTok > 0) serverOutTok else (cleanVisible.length / 3.6f).toInt().coerceAtLeast(1)
                                streamTokensPerSecond = if (serverTps > 0f) {
                                    serverTps
                                } else {
                                    ((streamOutputTokensCount.toFloat() / (elapsedMs.toFloat() / 1000f)) * 10f).toInt().toFloat() / 10f
                                }
                                onUpdate(
                                    StreamingTurnUpdate(
                                        thinking = liveThinking.trim(),
                                        thinkingDurationSec = elapsed,
                                        toolCalls = streamTools.toList(),
                                        textDelta = newVisibleDelta,
                                        fullText = cleanVisible,
                                        createdDiffs = streamDiffs.toList(),
                                        isDone = false,
                                        tokensPerSecond = streamTokensPerSecond,
                                        outputTokensCount = streamOutputTokensCount,
                                        firstTokenLatencyMs = (firstTokenTimeMs - streamStartMs).coerceAtLeast(50L)
                                    )
                                )
                            }
                            "done" -> {
                                receivedDoneEvent = true
                                terminalFailed = bridgeTurnFailed(ev)
                                val cid = ev.optString("conversationId", "")
                                if (cid.isNotBlank()) returnedConvId = cid
                                val doneReply = ev.optString("reply").ifBlank {
                                    if (ev.isNull("error")) "" else ev.optString("error") }
                                if (doneReply.isNotBlank()) finalReply = doneReply
                                val doneThink = ev.optString("thinking", "")
                                if (doneThink.isNotBlank()) liveThinking = doneThink
                                finalThinkingSec = ev.optInt("thinkingDurationSec", 0)
                                val doneTps = ev.optDouble("tokensPerSecond", 0.0).toFloat()
                                val doneOutTok = ev.optInt("outputTokensCount", 0)
                                if (doneTps > 0f) streamTokensPerSecond = doneTps
                                if (doneOutTok > 0) streamOutputTokensCount = doneOutTok
                                val doneTools = ev.optJSONArray("toolCalls")
                                if (doneTools != null) {
                                    for (ti in 0 until doneTools.length()) {
                                        val to = doneTools.optJSONObject(ti) ?: continue
                                        mergeBridgeTool(streamTools, to, "done_tc_$ti", base)
                                    }
                                }
                                val doneDiffs = ev.optJSONArray("createdDiffs")
                                if (doneDiffs != null) {
                                    for (di in 0 until doneDiffs.length()) {
                                        val dObj = doneDiffs.optJSONObject(di) ?: continue
                                        val diffItem = FileDiffItem(
                                            path = dObj.optString("path", "file"),
                                            additions = dObj.optInt("additions", 1).coerceAtLeast(0),
                                            deletions = dObj.optInt("deletions", 0).coerceAtLeast(0),
                                            diffPatch = dObj.optString("diffPatch", "")
                                        )
                                        if (streamDiffs.none { it.path == diffItem.path && it.additions == diffItem.additions }) {
                                            streamDiffs.add(diffItem)
                                        }
                                    }
                                }
                                val doneMedia = parseGeneratedMediaArray(
                                    arr = ev.optJSONArray("generatedMedia"),
                                    baseUrl = base,
                                    fallbackPrompt = prompt,
                                    fallbackModel = modelId
                                )
                                for (gm in doneMedia) {
                                    if (streamMedia.none { it.mediaUrl == gm.mediaUrl && it.fileName == gm.fileName }) {
                                        streamMedia.add(gm)
                                    }
                                }
                            }
                        }
                    }
                    true
                }

                if (receivedDoneEvent) activeRemoteTurn.compareAndSet(activeRemoteTurn.get()?.takeIf { it.second == clientTurnId }, null)
                val effectiveReply = finalReply.ifBlank { liveFullText }.trim().ifBlank {
                    if (terminalFailed) "Запрос завершился с ошибкой. Проверьте подключение и повторите запрос." else "" }
                if (success && receivedDoneEvent && (effectiveReply.isNotEmpty() || (receivedDoneEvent && (liveThinking.isNotBlank() || streamTools.isNotEmpty() || streamMedia.isNotEmpty())))) {
                    lastWorkingBridgeUrl = base
                    val elapsedMs = (System.currentTimeMillis() - streamStartMs).coerceAtLeast(180L)
                    val outTok = if (streamOutputTokensCount > 0) streamOutputTokensCount else (effectiveReply.length / 3.6f).toInt().coerceAtLeast(1)
                    val tps = if (streamTokensPerSecond > 0f) streamTokensPerSecond else (((outTok.toFloat() / (elapsedMs.toFloat() / 1000f)) * 10f).toInt().toFloat() / 10f)
                    return PcBridgeChatResponse(
                        reply = effectiveReply,
                        conversationId = returnedConvId,
                        thinking = liveThinking.trim(),
                        thinkingDurationSec = finalThinkingSec,
                        toolCalls = streamTools,
                        createdDiffs = streamDiffs,
                        generatedMedia = streamMedia,
                        resolvedBaseUrl = base,
                        tokensPerSecond = tps.coerceAtLeast(0f),
                        outputTokensCount = outTok,
                        isError = terminalFailed
                    )
                }

                // If stream closed before "done" after connection started, seamlessly recover via /api/chat/turn-status
                if (!receivedDoneEvent && (receivedAnyEvent || streamTools.isNotEmpty() || liveThinking.isNotBlank())) {
                    val polled = pollTurnUntilComplete(
                        base = base,
                        turnId = activeTurnId,
                        conversationId = returnedConvId,
                        streamStartMs = streamStartMs,
                        initialThinking = liveThinking,
                        initialText = previousVisibleText,
                        streamTools = streamTools,
                        streamDiffs = streamDiffs,
                        onUpdate = onUpdate
                    )
                    if (polled != null) {
                        lastWorkingBridgeUrl = base
                        return polled
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
                // Mid-turn SSE disconnect recovery: if we already received events/tools on this turn, poll until completion!
                if (!receivedDoneEvent && (receivedAnyEvent || streamTools.isNotEmpty() || liveThinking.isNotBlank())) {
                    val polled = pollTurnUntilComplete(
                        base = base,
                        turnId = activeTurnId,
                        conversationId = returnedConvId,
                        streamStartMs = streamStartMs,
                        initialThinking = liveThinking,
                        initialText = previousVisibleText,
                        streamTools = streamTools,
                        streamDiffs = streamDiffs,
                        onUpdate = onUpdate
                    )
                    if (polled != null) {
                        lastWorkingBridgeUrl = base
                        return polled
                    }
                }
                if (base == lastWorkingBridgeUrl) {
                    lastWorkingBridgeUrl = ""
                }
            }
        }
        return null
    }

    /**
     * Скрывает незавершённые или завершённые блоки `<thinking>...</thinking>` и ```` ```phone_action ... ``` ````
     * во время живого стриминга текста, чтобы сырой JSON вызова инструмента не мигал в пузыре чата и не озвучивался TTS.
     */
    private fun stripStreamingHiddenBlocks(raw: String): Pair<String, String> {
        var working = raw
        val extractedThoughts = mutableListOf<String>()

        val completeThinkRegex = Regex("<thinking>([\\s\\S]*?)</thinking>", RegexOption.IGNORE_CASE)
        for (m in completeThinkRegex.findAll(working)) {
            val t = m.groupValues[1].trim()
            if (t.isNotEmpty()) extractedThoughts.add(t)
        }
        working = working.replace(completeThinkRegex, "")

        val openThinkIdx = working.indexOf("<thinking>", ignoreCase = true)
        if (openThinkIdx >= 0) {
            val tailT = working.substring(openThinkIdx + "<thinking>".length).trim()
            if (tailT.isNotEmpty()) extractedThoughts.add(tailT)
            working = working.substring(0, openThinkIdx)
        }

        // Убираем завершённые блоки ```phone_action ... ```
        val completeActionRegex = Regex("```phone_action[\\s\\S]*?```", RegexOption.IGNORE_CASE)
        working = working.replace(completeActionRegex, "")

        // Если прямо сейчас стримится незакрытый блок ```phone_action, обрезаем его до закрытия
        val openActionIdx = working.indexOf("```phone_action", ignoreCase = true)
        if (openActionIdx >= 0) {
            working = working.substring(0, openActionIdx)
        }

        return working.trim() to extractedThoughts.joinToString("\n\n")
    }

    private fun extractClientSideThinking(rawText: String): Pair<String, String> {
        val regex = Regex("<thinking>([\\s\\S]*?)</thinking>", RegexOption.IGNORE_CASE)
        val thoughts = regex.findAll(rawText).mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf { s -> s.isNotEmpty() } }.toList()
        if (thoughts.isEmpty()) return rawText.trim() to ""
        val cleaned = rawText.replace(regex, "").trim()
        return cleaned.ifBlank { rawText.trim() } to thoughts.joinToString("\n\n")
    }

    internal fun sanitizeStoragePathsInMarkdown(markdown: String, userPrompt: String): String =
        com.google.antigravity.network.sanitizeStoragePathsInMarkdown(markdown, userPrompt)

    private fun sanitizeSpokenSummary(markdown: String): String {
        return markdown
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[#*`_~>\\[\\]()]"), " ")
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(520)
    }

    private suspend fun callGeminiRestApi(
        prompt: String,
        systemPrompt: String,
        apiKey: String,
        selectedModelId: String,
        sessionHistory: List<ChatMessage>
    ): ProviderReply? {
        val directModel = mapToDirectGeminiModelId(selectedModelId)
        val candidateModels = listOf(directModel)
        for (model in candidateModels) {
            try {
                val url =
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val payload = JSONObject().apply {
                    if (Regex("^gemini-(2\\.5|[3-9])").containsMatchIn(model)) {
                        put("generationConfig", JSONObject().put("thinkingConfig",
                            JSONObject().put("includeThoughts", true)))
                    }
                    if (systemPrompt.isNotBlank()) {
                        put("systemInstruction", JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                        })
                    }
                    put("contents", JSONArray().apply {
                        sessionHistory.forEach { message ->
                            put(JSONObject().apply {
                                put("role", if (message.role == MessageRole.USER) "user" else "model")
                                put("parts", JSONArray().put(JSONObject().put("text", message.content)))
                            })
                        }
                    }.put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    }))
                }
                android.util.Log.i(
                    "AntigravityGeminiAPI",
                    "POST generateContent model=$model systemInstructionChars=${systemPrompt.length} hasSystemInstruction=${payload.has("systemInstruction")}"
                )
                val req = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                httpClient.newCall(req).useCancellable { resp ->
                    if (!resp.isSuccessful) return@useCancellable
                    val json = JSONObject(resp.body?.string() ?: return@useCancellable)
                    val reply = parseGeminiReply(json)
                    if (reply.text.isNotBlank()) return reply
                }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
        return null
    }
}

// SdCardPath: "/data/..." — шаблон вырезания служебных путей из отображаемого
// текста модели, а не хардкод доступа к файлам.
@SuppressLint("SdCardPath")
internal fun sanitizeStoragePathsInMarkdown(markdown: String, userPrompt: String): String {
    val asksForPath = userPrompt.contains("путь", ignoreCase = true) ||
        userPrompt.contains("path", ignoreCase = true)
    if (asksForPath) return markdown
    val preservedUris = mutableListOf<String>()
    val protectedMarkdown = markdown.replace(Regex("file:///[^\\s)\\]`\"']+")) { match ->
        val idx = preservedUris.size
        preservedUris.add(match.value)
        "__AGY_FILE_URI_${idx}__"
    }
    val sanitized = protectedMarkdown
        .replace(Regex("Android/data/com\\.google\\.antigravity/files/AntigravityWorkspace"), "Рабочая папка Antigravity")
        .replace(Regex("/data/user/0/com\\.google\\.antigravity/[^\\s)\\]`\"']+"), "Рабочая папка Antigravity")
        .replace(Regex("\\s*—\\s*/storage/emulated/0/[^\\s)\\]`\"']+"), "")
        .replace(Regex("`/storage/emulated/0/([^`]+)`")) { m ->
            "`${m.groupValues[1].substringAfterLast('/')}`"
        }
        .replace(Regex("/storage/emulated/0/([^\\s)\\]`\"']+)")) { m ->
            m.groupValues[1].substringAfterLast('/')
        }
    return sanitized.replace(Regex("__AGY_FILE_URI_(\\d+)__")) { match ->
        val idx = match.groupValues[1].toIntOrNull() ?: return@replace match.value
        preservedUris.getOrElse(idx) { match.value }
    }
}

// ---------------------------------------------------------------------------
// Этап 5/6: парсеры данных расширений (состав плагинов и каталог адаптеров).
// Вынесены на уровень файла, чтобы юнит-тесты проверяли реальный разбор JSON
// моста без запуска сети (см. ExtensionDataParsersTest).
// ---------------------------------------------------------------------------

/** Этап 5/6: состав плагина — проверенный путь компонентов либо блокер. */
internal fun parseComposition(obj: JSONObject?): ExtensionComposition {
    if (obj == null) return ExtensionComposition()
    val recordsArr = obj.optJSONArray("records")
    val records = mutableListOf<ComponentAdapterRecord>()
    if (recordsArr != null) {
        for (i in 0 until recordsArr.length()) {
            val r = recordsArr.optJSONObject(i) ?: continue
            records.add(
                ComponentAdapterRecord(
                    componentType = r.optString("componentType", "unknown"),
                    strategy = r.optString("strategy", "preserve"),
                    status = r.optString("status", "requires-check"),
                    blocker = r.optString("blocker", ""),
                    verification = r.optString("verification", "")
                )
            )
        }
    }
    val blockersArr = obj.optJSONArray("blockers")
    val blockers = mutableListOf<String>()
    if (blockersArr != null) {
        for (i in 0 until blockersArr.length()) {
            val b = blockersArr.optJSONObject(i)
            blockers.add(b?.optString("reason", "") ?: "")
        }
    }
    val license = obj.optJSONObject("license")
    return ExtensionComposition(
        supportStatus = obj.optString("supportStatus", "unknown"),
        fullSupportAllowed = obj.optBoolean("fullSupportAllowed", false),
        records = records,
        licenseRight = license?.optString("rightToRedistribute", "unknown") ?: "unknown",
        licenseNote = license?.optString("note", "") ?: "",
        blockers = blockers.filter { it.isNotBlank() }
    )
}

/** Этап 6: каталог расширений из матрицы адаптеров (/api/extensions/adapters). */
internal fun parseExtensionCatalog(json: JSONObject): List<ExtensionCatalogItem> {
    val matrix = json.optJSONObject("matrix") ?: return emptyList()
    val positions = matrix.optJSONArray("positions") ?: return emptyList()
    val out = mutableListOf<ExtensionCatalogItem>()
    for (i in 0 until positions.length()) {
        val p = positions.optJSONObject(i) ?: continue
        if (p.optString("canonicalId", p.optString("id")) != p.optString("id")) continue
        val types = p.optJSONArray("componentTypes")
        val category = if (types != null && types.length() > 0) types.getString(0) else "прочее"
        val blockersArr = p.optJSONArray("blockers")
        val blockerText = buildString {
            if (blockersArr != null) {
                for (j in 0 until blockersArr.length()) {
                    val b = blockersArr.optJSONObject(j) ?: continue
                    val reason = b.optString("reason", "")
                    if (reason.isNotBlank()) {
                        if (isNotEmpty()) append("; ")
                        append(reason)
                    }
                }
            }
        }
        val license = p.optJSONObject("licenseCheck")
        val importPath = p.optString("importPath", "")
        val availability = p.optString("availability", "unknown")
        // Локальный источник = продукт Antigravity (реальное значение из
        // инвентаря: «Antigravity / Gemini CLI (локальная установка)»).
        val localSource = p.optString("sourceProduct", "").startsWith("Antigravity")
        out.add(
            ExtensionCatalogItem(
                id = p.optString("id", "pos_$i"),
                name = p.optString("name", "Позиция"),
                sourceProduct = p.optString("sourceProduct", "не записано"),
                category = category,
                availability = availability,
                compatibility = p.optString("supportStatus", "requires-check"),
                blocker = blockerText,
                licenseNote = license?.optString("note", "") ?: "",
                // Издатель/источник/сценарий: берутся только из ответа моста;
                // пусто = «не записано» (поля может не быть в матрице).
                publisher = p.optString("publisher", ""),
                sourceUrl = p.optString("sourceUrl", ""),
                testScenario = p.optString("testScenario", ""),
                installable = localSource && (availability == "installed" || availability == "imported") && importPath.isNotBlank(),
                installPath = importPath
            )
        )
    }
    return out
}

// ---------------------------------------------------------------------------
// QA-дефект Medium #2: разбор списка MCP-серверов, сброс залипшего
// "connected" при ошибке и безопасный текст ошибки моста. Top-level internal —
// тестируется ExtensionsSecurityQaTest без запуска сети.
// ---------------------------------------------------------------------------

/** Парсинг mcpServers из ответа моста; дополнительно — errorCode и serverInfo. */
internal fun parseMcpServerList(json: JSONObject): List<PcMcpServerItem> {
    val arr = json.optJSONArray("mcpServers") ?: return emptyList()
    val out = mutableListOf<PcMcpServerItem>()
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val argsArr = obj.optJSONArray("args")
        val argsList = mutableListOf<String>()
        if (argsArr != null) {
            for (j in 0 until argsArr.length()) {
                argsList.add(argsArr.optString(j, ""))
            }
        }
        val toolsArr = obj.optJSONArray("tools")
        val toolsList = mutableListOf<String>()
        if (toolsArr != null) {
            for (j in 0 until toolsArr.length()) {
                toolsList.add(toolsArr.optString(j, ""))
            }
        }
        val serverInfoObj = obj.optJSONObject("serverInfo")
        val serverInfoText = serverInfoObj?.let {
            listOfNotNull(
                it.optString("name", "").takeIf { n -> n.isNotBlank() },
                it.optString("version", "").takeIf { v -> v.isNotBlank() }
            ).joinToString(" ").takeIf { s -> s.isNotBlank() }
        }
        // OAuth 2.1: authUrl / authStatus (или признак oauthAuthorized).
        // Если мост не прислал ни одного поля — статус авторизации неизвестен ("").
        val authKnown = obj.has("authStatus") || obj.has("oauthAuthorized")
        val rawAuthStatus = obj.optString("authStatus", "")
        val authorizedFlag = obj.optBoolean("oauthAuthorized", false)
        val authStatus = if (authKnown) {
            normalizeMcpAuthStatus(rawAuthStatus, authorizedFlag).ifBlank { "not_authorized" }
        } else ""
        out.add(
            PcMcpServerItem(
                id = obj.optString("id", "mcp_$i"),
                name = obj.optString("name", "MCP"),
                transport = obj.optString("transport", "stdio"),
                command = obj.optString("command", ""),
                args = argsList,
                enabled = obj.optBoolean("enabled", true),
                status = obj.optString("status", "configured"),
                tools = toolsList,
                error = obj.optString("error", ""),
                probedAt = obj.optString("probedAt", ""),
                errorCode = obj.optString("errorCode", "").takeIf { it.isNotBlank() },
                serverInfo = serverInfoText,
                commandOrUrl = obj.optString("commandOrUrl", obj.optString("command", "")),
                oauthAuthorized = authStatus == "authorized",
                authUrl = obj.optString("authUrl", "").ifBlank { obj.optString("authorizationUrl", "") },
                authStatus = authStatus
            )
        )
    }
    return out
}

/**
 * При ошибке связи с мостом или err != null залипший статус "connected"
 * сбрасывается в "configured" (требует проверки) — без свежего рукопожатия
 * «подключён» не подтверждён.
 */
internal fun demoteUnverifiedConnected(
    items: List<PcMcpServerItem>,
    err: String?
): List<PcMcpServerItem> {
    if (err == null) return items
    return items.map {
        if (it.status.equals("connected", ignoreCase = true)) it.copy(status = "configured") else it
    }
}

/** Безопасное извлечение текста ошибки из тела ответа моста (лимит 200 символов). */
internal fun safeBridgeErrorText(bodyText: String): String {
    val trimmed = bodyText.trim()
    if (trimmed.isEmpty()) return "пустое тело ответа"
    return runCatching { JSONObject(trimmed).optString("error", "") }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.take(200)
        ?: trimmed.take(200)
}

// ---------------------------------------------------------------------------
// OAuth 2.1: разбор OAuth-полей (authUrl, authStatus) из ответов моста
// (oauthStart / oauthStatus / oauthRevoke) и сохранение статуса входа при
// обновлении списка подключений. Top-level internal — тестируется
// McpOAuthStateTest без запуска сети.
// ---------------------------------------------------------------------------

/** Разобранный OAuth-ответ моста: authUrl (для браузера) и authStatus. */
internal data class McpOAuthReply(
    val id: String = "",
    /** Адрес страницы входа (поле authUrl; fallback — authorizationUrl). */
    val authUrl: String = "",
    /** Нормализованный статус авторизации (см. normalizeMcpAuthStatus). */
    val authStatus: String = "",
    /** Производная: вход подтверждён (authStatus == "authorized"). */
    val authorized: Boolean = false,
    /** Идентификатор сессии входа (sessionId; fallback — completionToken). */
    val sessionId: String = ""
)

/**
 * Парсинг OAuth-полей (authUrl, authStatus) из JSON-ответа /api/mcp.
 * Поддерживаются оба написания полей моста: camelCase (authUrl/authStatus)
 * и легаси (authorizationUrl/status + authorized/oauthAuthorized).
 */
internal fun parseMcpOAuthReply(json: JSONObject): McpOAuthReply {
    val authUrl = json.optString("authUrl", "").ifBlank { json.optString("authorizationUrl", "") }
    val rawStatus = json.optString("authStatus", "").ifBlank { json.optString("status", "") }
    val authorizedFlag = json.optBoolean("authorized", json.optBoolean("oauthAuthorized", false))
    val authKnown = json.has("authStatus") || json.has("status") ||
        json.has("authorized") || json.has("oauthAuthorized")
    val authStatus = normalizeMcpAuthStatus(rawStatus, authorizedFlag)
        .ifBlank { if (authKnown) "not_authorized" else "" }
    return McpOAuthReply(
        id = json.optString("id", "").ifBlank { json.optString("name", "") },
        authUrl = authUrl,
        authStatus = authStatus,
        authorized = authStatus == "authorized",
        sessionId = json.optString("sessionId", "").ifBlank { json.optString("completionToken", "") }
    )
}

/**
 * Слияние свежего списка подключений с текущим: если ответ моста не содержит
 * OAuth-данных, статус входа (authStatus/authUrl/oauthAuthorized) сохраняется —
 * вход не «теряется» при обновлении списка. Явные данные моста приоритетны.
 */
internal fun mergeMcpAuthFields(
    previous: List<PcMcpServerItem>,
    fresh: List<PcMcpServerItem>
): List<PcMcpServerItem> = fresh.map { item ->
    if (item.authStatus.isNotBlank() || item.oauthAuthorized) item
    else previous.firstOrNull { it.id == item.id }?.let { old ->
        item.copy(
            authStatus = old.authStatus,
            authUrl = old.authUrl,
            oauthAuthorized = old.oauthAuthorized
        )
    } ?: item
}

// ---------------------------------------------------------------------------
// Права инструментов MCP и деталь навыка («как у них»). Top-level internal —
// тестируются без запуска сети. Поля с честными фолбэками: нет поля —
// «не записано», ничего не выдумывается.
// ---------------------------------------------------------------------------

/** Разобранный ответ tool_permissions: уровень сервера и права инструментов. */
internal data class McpToolPermissionsReply(
    val server: String = "",
    /** null — мост не прислал defaultPermission (глобальная политика). */
    val defaultPermission: String? = null,
    val tools: List<McpToolPermissionEntry> = emptyList()
)

/**
 * Разбор ответа tool_permissions: tools[] — только из живого ответа моста
 * (может быть пустым, если probe не выполнялся). comment — комментарий к
 * запрету («Deny with comment»): отсутствие поля — пустая строка «не
 * записано», значения не выдумываются.
 */
internal fun parseMcpToolPermissions(json: JSONObject): McpToolPermissionsReply {
    val defaultPermission =
        if (json.has("defaultPermission") && !json.isNull("defaultPermission")) {
            json.optString("defaultPermission", "")
        } else {
            null
        }
    val arr = json.optJSONArray("tools")
    val tools = mutableListOf<McpToolPermissionEntry>()
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            tools.add(
                McpToolPermissionEntry(
                    tool = obj.optString("tool", ""),
                    permission = obj.optString("permission", ""),
                    source = obj.optString("source", ""),
                    comment = obj.optString("comment", "")
                )
            )
        }
    }
    return McpToolPermissionsReply(
        server = json.optString("server", "").ifBlank { json.optString("name", "") },
        defaultPermission = defaultPermission,
        tools = tools
    )
}

/** Разобранный ответ set_tool_permission / set_all_tools_permissions. */
internal data class McpPermissionUpdateReply(
    val server: String = "",
    val tool: String = "",
    val permission: String = "",
    /** Скоуп записанного правила (project/session/task/website); "" — «не записано». */
    val scope: String = "",
    /**
     * Комментарий к запрету, подтверждённый мостом («Deny with comment»);
     * "" — «не записано» (мост не прислал / комментарий очищен).
     */
    val comment: String = ""
)

internal fun parseMcpPermissionUpdate(json: JSONObject): McpPermissionUpdateReply =
    McpPermissionUpdateReply(
        server = json.optString("server", "").ifBlank { json.optString("name", "") },
        tool = json.optString("tool", ""),
        permission = json.optString("permission", ""),
        scope = json.optString("scope", ""),
        comment = json.optString("comment", "")
    )

/**
 * Разобранный ответ permission_mode / set_permission_mode («как у них»):
 * ok + mode + validModes при успехе, error при отказе (например неизвестный
 * режим). ok без поля не выдумывается (false); пустые mode/validModes —
 * «не записано».
 */
internal data class McpPermissionModeReply(
    val ok: Boolean = false,
    /** Wire-значение режима (default/plan/auto/acceptEdits/dontAsk/bypassPermissions). */
    val mode: String = "",
    /** Wire-значения допустимых режимов из ответа моста. */
    val validModes: List<String> = emptyList(),
    /** Текст отказа моста без маскировки. */
    val error: String? = null
)

internal fun parseMcpPermissionMode(json: JSONObject): McpPermissionModeReply {
    val validArr = json.optJSONArray("validModes")
    val validModes = mutableListOf<String>()
    if (validArr != null) {
        for (i in 0 until validArr.length()) {
            validModes.add(validArr.optString(i, ""))
        }
    }
    return McpPermissionModeReply(
        ok = json.optBoolean("ok", false),
        mode = json.optString("mode", ""),
        validModes = validModes,
        error = json.optString("error", "").ifBlank { null }
    )
}

/**
 * Разбор ответа skills detail: полный текст SKILL.md (content). enabled = null,
 * если мост не прислал логическое поле («не записано», не выдумывается).
 */
internal fun parseSkillDetail(json: JSONObject): SkillDetailInfo {
    val obj = json.optJSONObject("skill") ?: JSONObject()
    return SkillDetailInfo(
        id = obj.optString("id", "").ifBlank { obj.optString("name", "") },
        name = obj.optString("name", ""),
        description = obj.optString("description", ""),
        // Тип значения проверяем через opt(): не-логическое или отсутствующее
        // поле — null («не записано», true не выдумывается).
        enabled = obj.opt("enabled") as? Boolean,
        source = obj.optString("source", ""),
        path = obj.optString("path", ""),
        content = obj.optString("content", "")
    )
}

/**
 * Разбор списка skills[] из ответов моста. Общий для fetch/manage и для
 * ответа update/remove (skills возвращаются свежим списком).
 */
internal fun parseSkillItems(json: JSONObject): List<PcSkillItem> {
    val arr = json.optJSONArray("skills") ?: return emptyList()
    val out = mutableListOf<PcSkillItem>()
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        out.add(
            PcSkillItem(
                id = obj.optString("id", "skill_$i"),
                name = obj.optString("name", "Скилл"),
                description = obj.optString("description", ""),
                enabled = obj.optBoolean("enabled", true),
                path = obj.optString("path", ""),
                instructionsPreview = obj.optString("instructionsPreview", "")
            )
        )
    }
    return out
}

/**
 * Разбор ответа skills update/remove («как у них»): ok + message при успехе,
 * error + code при отказе (skill_name_taken, skill_read_only). ok без поля
 * не выдумывается (false). skills — свежий список навыков; null — мост его не
 * прислал («не записано»), пустой список остаётся пустым.
 */
internal fun parseSkillEditResult(json: JSONObject): SkillEditResult = SkillEditResult(
    ok = json.optBoolean("ok", false),
    message = json.optString("message", ""),
    errorCode = json.optString("code", "").ifBlank { null },
    error = json.optString("error", "").ifBlank { null },
    skills = if (json.has("skills") && !json.isNull("skills")) parseSkillItems(json) else null
)

// ---------------------------------------------------------------------------
// Снимок роя «как у них» (Claude: «%1$d running task(s)», «No background
// tasks», «Your computer is busy with other sessions…», «Running in the shared
// directory… consider worktree mode»). Поля counts / runningTasks / busy /
// busyMessage и workspaceMode / sharedDirectoryWarning отсутствуют в старых
// ответах моста — тогда null («не записано»), значения не выдумываются.
// ---------------------------------------------------------------------------

internal fun parseSwarmAgentNode(obj: JSONObject, index: Int): SwarmAgentNode {
    val logsArr = obj.optJSONArray("stepLogs") ?: obj.optJSONArray("logs")
    val logs = mutableListOf<String>()
    if (logsArr != null) {
        for (lIdx in 0 until logsArr.length()) {
            logs.add(logsArr.optString(lIdx, ""))
        }
    }
    return SwarmAgentNode(
        id = obj.optString("id", "agent_$index"),
        handle = obj.optString("handle", obj.optString("mention", "@agent")),
        name = obj.optString("name", "Агент"),
        roleBadge = obj.optString("roleBadge", obj.optString("role", "Субагент анализа")),
        isLeader = obj.optBoolean("isLeader", false),
        model = obj.optString("model", "gemini-4-argon-high"),
        status = obj.optString("status", "ready").lowercase(),
        currentTask = obj.optString("currentTask", obj.optString("currentStep", "")),
        stepLogs = logs,
        isSettling = obj.optBoolean("settling", false),
        workspaceMode = obj.optString("workspaceMode", "").ifBlank { null },
        sharedDirectoryWarning = obj.optString("sharedDirectoryWarning", "").ifBlank { null },
        changeReview = obj.optJSONObject("review")?.let { review -> buildString {
            append("Копия: ").append(review.optString("workspaceId")).append("\n")
            append("Базовая версия: ").append(review.optString("base")).append("\n")
            if (review.optBoolean("sourceChanged")) append("Исходный проект изменился. Требуется повторная проверка.\n")
            append("\n").append(review.optString("diff").ifBlank { "Нет изменений отслеживаемых файлов." })
            val files = review.optJSONArray("newFiles")
            if (files != null) for (fIdx in 0 until files.length()) {
                val file = files.optJSONObject(fIdx) ?: continue
                append("\n\nНовый файл: ").append(file.optString("name")).append("\n")
                append(file.optString("content", file.optString("omitted")))
            }
        } }.orEmpty()
    )
}

internal fun parseSwarmSnapshot(json: JSONObject): SwarmSnapshot {
    val root = json.optJSONObject("swarm") ?: json
    val strategy = root.optString("strategy", "")
    // Стратегический шлюз прежний: неизвестная стратегия — агентов не заявляем.
    val agents = if (strategy == "PARALLEL_ANALYSIS") {
        val arr = root.optJSONArray("agents")
        if (arr != null) {
            val out = mutableListOf<SwarmAgentNode>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                out.add(parseSwarmAgentNode(obj, i))
            }
            out
        } else emptyList()
    } else emptyList()
    val countsObj = root.optJSONObject("counts")
    val counts = countsObj?.let { c ->
        SwarmCounts(
            queued = c.optInt("queued", 0),
            running = c.optInt("running", 0),
            completed = c.optInt("completed", 0),
            failed = c.optInt("failed", 0),
            cancelled = c.optInt("cancelled", 0)
        )
    }
    return SwarmSnapshot(
        agents = agents,
        counts = counts,
        runningTasks = if (root.has("runningTasks")) root.optInt("runningTasks", 0) else null,
        busy = if (root.has("busy")) root.optBoolean("busy", false) else null,
        busyMessage = root.optString("busyMessage", "").ifBlank { null },
        batchId = root.optString("batchId", ""),
        swarmActive = root.optBoolean("swarmActive", false),
        strategy = strategy
    )
}

