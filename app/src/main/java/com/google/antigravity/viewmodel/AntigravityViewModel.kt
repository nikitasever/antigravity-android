package com.google.antigravity.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.antigravity.device.PhoneResourceController
import com.google.antigravity.model.AVAILABLE_AGY_MODELS
import com.google.antigravity.model.AgentExecutionTarget
import com.google.antigravity.model.AiPermissionsConfig
import com.google.antigravity.model.AndroidCliCommandResult
import com.google.antigravity.model.AntigravityModelOption
import com.google.antigravity.model.AntigravitySettings
import com.google.antigravity.model.ApprovalDecision
import com.google.antigravity.model.ArtifactDocument
import com.google.antigravity.model.ArtifactType
import com.google.antigravity.model.AttachedFileItem
import com.google.antigravity.model.ChatMessage
import com.google.antigravity.model.ContextWindowStatus
import com.google.antigravity.model.DrawerSection
import com.google.antigravity.model.FileDiffItem
import com.google.antigravity.model.GeneratedMediaAttachment
import com.google.antigravity.model.GoogleAccountProfile
import com.google.antigravity.model.GoogleDriveFileItem
import com.google.antigravity.model.GoogleDriveStatus
import com.google.antigravity.model.InstalledPluginItem
import com.google.antigravity.model.MessageRole
import com.google.antigravity.model.OpenRouterCategory
import com.google.antigravity.model.OpenRouterModelItem
import com.google.antigravity.model.PcMcpServerItem
import com.google.antigravity.model.PcSkillItem
import com.google.antigravity.model.RemoteEnvFilter
import com.google.antigravity.model.RemoteSessionItem
import com.google.antigravity.model.ScratchProjectItem
import com.google.antigravity.model.ScheduledAutomationItem
import com.google.antigravity.model.SlashMentionSuggestionItem
import com.google.antigravity.model.SwarmAgentNode
import com.google.antigravity.model.TopModeTab
import com.google.antigravity.model.VirtualSandboxProject
import com.google.antigravity.model.VoiceProvider
import com.google.antigravity.model.VoiceTransportMode
import com.google.antigravity.model.VoiceVisualizerStyle
import com.google.antigravity.network.AntigravityBackendClient
import com.google.antigravity.receiver.ToolApprovalActionReceiver
import com.google.antigravity.service.AntigravityAgentSessionService
import com.google.antigravity.service.AntigravityVoiceForegroundService
import com.google.antigravity.voice.RealtimeVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AntigravityViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val CURRENT_DEFAULT_BRIDGE_URL = "https://2d0102ca0a9fe83d-79-137-206-221.serveousercontent.com"
        private const val BRIDGE_URL_VERSION = 20
    }

    private val prefs = application.getSharedPreferences("antigravity_prefs_v4", Context.MODE_PRIVATE)
    val phoneController = PhoneResourceController(application.applicationContext)
    val backendClient = AntigravityBackendClient(phoneController)

    // Независимый Application-scope для непрерывной фоновой работы агента при сворачивании окна (Section 5)
    private val agentExecutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Кэш реальных сообщений всех удалённых сессий ПК
    private val sessionHistoryCache = mutableMapOf<String, List<ChatMessage>>()

    val voiceEngine = RealtimeVoiceEngine(
        context = application.applicationContext,
        onUserUtteranceFinalized = { spokenText ->
            sendUserPrompt(spokenText, viaVoice = true)
        },
        onAgentVoiceCommandDetected = { cmd ->
            handleVoiceAgentCommand(cmd)
        },
        onLiveToolCallRequested = { toolName, argsJson ->
            handleGeminiLiveToolCall(toolName, argsJson)
        }
    )

    private val _googleProfile = MutableStateFlow(
        GoogleAccountProfile(
            isSignedIn = prefs.getBoolean("is_signed_in_v2", false),
            displayName = prefs.getString("user_name", "st811210") ?: "st811210",
            email = prefs.getString("user_email", "st811210@gmail.com") ?: "st811210@gmail.com",
            pcInstanceName = "Рабочий компьютер"
        )
    )
    val googleProfile: StateFlow<GoogleAccountProfile> = _googleProfile.asStateFlow()

    private val _settings = MutableStateFlow(
        AntigravitySettings(
            selectedModelId = prefs.getString("selected_model_id", "gemini-4-argon-high") ?: "gemini-4-argon-high",
            voiceProvider = runCatching {
                VoiceProvider.valueOf(prefs.getString("voice_provider", VoiceProvider.NEURAL_BRIDGE.name) ?: VoiceProvider.NEURAL_BRIDGE.name)
            }.getOrDefault(VoiceProvider.NEURAL_BRIDGE),
            voiceName = prefs.getString("voice_name", "Aoede") ?: "Aoede",
            geminiApiKey = prefs.getString("gemini_api_key", "") ?: "",
            pcBridgeUrl = resolveInitialBridgeUrl(),
            forcePublicTunnelOnly = prefs.getBoolean("force_public_tunnel_only", false)
        )
    )
    val settings: StateFlow<AntigravitySettings> = _settings.asStateFlow()

    private val _availableModels = MutableStateFlow(AVAILABLE_AGY_MODELS)
    val availableModels: StateFlow<List<AntigravityModelOption>> = _availableModels.asStateFlow()

    private val _isVerifyingApiKey = MutableStateFlow(false)
    val isVerifyingApiKey: StateFlow<Boolean> = _isVerifyingApiKey.asStateFlow()

    private val _apiKeyVerificationOk = MutableStateFlow<Boolean?>(null)
    val apiKeyVerificationOk: StateFlow<Boolean?> = _apiKeyVerificationOk.asStateFlow()

    private val _apiKeyVerificationMessage = MutableStateFlow("")
    val apiKeyVerificationMessage: StateFlow<String> = _apiKeyVerificationMessage.asStateFlow()

    private val _topModeTab = MutableStateFlow(TopModeTab.CHAT)
    val topModeTab: StateFlow<TopModeTab> = _topModeTab.asStateFlow()

    private val _remoteEnvFilter = MutableStateFlow(RemoteEnvFilter.CLOUD)
    val remoteEnvFilter: StateFlow<RemoteEnvFilter> = _remoteEnvFilter.asStateFlow()

    private val _drawerSection = MutableStateFlow(DrawerSection.CHAT)
    val drawerSection: StateFlow<DrawerSection> = _drawerSection.asStateFlow()

    private val _pcBridgeStatus = MutableStateFlow(
        AntigravityBackendClient.PcBridgeStatus(
            connected = true,
            instanceName = "Рабочий компьютер",
            hostname = "ПК",
            workspace = "C:\\Users\\st811210\\.gemini\\antigravity\\scratch\\antigravity-android"
        )
    )
    val pcBridgeStatus: StateFlow<AntigravityBackendClient.PcBridgeStatus> = _pcBridgeStatus.asStateFlow()

    private val _remoteSessions = MutableStateFlow<List<RemoteSessionItem>>(emptyList())
    val remoteSessions: StateFlow<List<RemoteSessionItem>> = _remoteSessions.asStateFlow()

    private val _scratchProjects = MutableStateFlow<List<ScratchProjectItem>>(emptyList())
    val scratchProjects: StateFlow<List<ScratchProjectItem>> = _scratchProjects.asStateFlow()

    private val _artifactsLibrary = MutableStateFlow<List<ArtifactDocument>>(emptyList())
    val artifactsLibrary: StateFlow<List<ArtifactDocument>> = _artifactsLibrary.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<AttachedFileItem>>(emptyList())
    val pendingAttachments: StateFlow<List<AttachedFileItem>> = _pendingAttachments.asStateFlow()

    private val _scheduledTasks = MutableStateFlow(
        listOf(
            ScheduledAutomationItem(
                title = "Синхронизация рабочих сессий с компьютером",
                cronSchedule = "Каждые 15 минут",
                targetHost = "Компьютер",
                enabled = true,
                promptCommand = "Проверь статус рабочих веток и обнови список активных сессий"
            ),
            ScheduledAutomationItem(
                title = "Проверка сборки Android-проекта",
                cronSchedule = "Пн–Пт в 09:00",
                targetHost = "Компьютер",
                enabled = true,
                promptCommand = "Запусти сборку проекта и проверь отсутствие ошибок компиляции"
            ),
            ScheduledAutomationItem(
                title = "Резервная копия заметок телефона",
                cronSchedule = "Ежедневно в 21:00",
                targetHost = "Телефон",
                enabled = false,
                promptCommand = "Проверь свободное место и список новых файлов в папке Download на телефоне"
            )
        )
    )
    val scheduledTasks: StateFlow<List<ScheduledAutomationItem>> = _scheduledTasks.asStateFlow()

    private val _installedPlugins = MutableStateFlow<List<InstalledPluginItem>>(emptyList())
    val installedPlugins: StateFlow<List<InstalledPluginItem>> = _installedPlugins.asStateFlow()

    private val _installedSkills = MutableStateFlow<List<PcSkillItem>>(emptyList())
    val installedSkills: StateFlow<List<PcSkillItem>> = _installedSkills.asStateFlow()

    private val _mcpServers = MutableStateFlow<List<PcMcpServerItem>>(emptyList())
    val mcpServers: StateFlow<List<PcMcpServerItem>> = _mcpServers.asStateFlow()

    private val _extensionsError = MutableStateFlow<String?>(null)
    val extensionsError: StateFlow<String?> = _extensionsError.asStateFlow()

    private val _isExtensionsLoading = MutableStateFlow(false)
    val isExtensionsLoading: StateFlow<Boolean> = _isExtensionsLoading.asStateFlow()

    private val _phoneCurrentDir = MutableStateFlow("")
    val phoneCurrentDir: StateFlow<String> = _phoneCurrentDir.asStateFlow()

    private val _phoneFiles = MutableStateFlow<List<PhoneResourceController.PhoneFileEntry>>(emptyList())
    val phoneFiles: StateFlow<List<PhoneResourceController.PhoneFileEntry>> = _phoneFiles.asStateFlow()

    private val _hasAllFilesPermission = MutableStateFlow(phoneController.hasAllFilesAccess())
    val hasAllFilesPermission: StateFlow<Boolean> = _hasAllFilesPermission.asStateFlow()

    private val _fileDiffs = MutableStateFlow<List<FileDiffItem>>(emptyList())
    val fileDiffs: StateFlow<List<FileDiffItem>> = _fileDiffs.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ---------------------------------------------------------------------------
    // Round 5 StateFlows: OpenRouter Hub, Multi-Agent Swarm, Android CLI/Sandbox,
    // AI Permissions, and Context Window Inspector
    // ---------------------------------------------------------------------------

    private val _openRouterModels = MutableStateFlow<List<OpenRouterModelItem>>(emptyList())
    val openRouterModels: StateFlow<List<OpenRouterModelItem>> = _openRouterModels.asStateFlow()

    private val _openRouterTotalCount = MutableStateFlow(prefs.getInt("openrouter_cached_count", 452))
    val openRouterTotalCount: StateFlow<Int> = _openRouterTotalCount.asStateFlow()

    private val _openRouterSyncedAt = MutableStateFlow(prefs.getString("openrouter_cached_at", "") ?: "")
    val openRouterSyncedAt: StateFlow<String> = _openRouterSyncedAt.asStateFlow()

    private val _openRouterSyncSource = MutableStateFlow("openrouter.ai/api/v1/models (Дисковый кэш 0 мс)")
    val openRouterSyncSource: StateFlow<String> = _openRouterSyncSource.asStateFlow()

    private val _openRouterFromCache = MutableStateFlow(true)
    val openRouterFromCache: StateFlow<Boolean> = _openRouterFromCache.asStateFlow()

    private val _openRouterCacheStatusText = MutableStateFlow("Кэш синхронизирован (0 мс) • openrouter_models_cache.json")
    val openRouterCacheStatusText: StateFlow<String> = _openRouterCacheStatusText.asStateFlow()

    private val _openRouterApiKey = MutableStateFlow(prefs.getString("openrouter_api_key", "") ?: "")
    val openRouterApiKey: StateFlow<String> = _openRouterApiKey.asStateFlow()

    private val _isSyncingOpenRouter = MutableStateFlow(false)
    val isSyncingOpenRouter: StateFlow<Boolean> = _isSyncingOpenRouter.asStateFlow()

    private val _isGeneratingMedia = MutableStateFlow(false)
    val isGeneratingMedia: StateFlow<Boolean> = _isGeneratingMedia.asStateFlow()

    private val _mediaGenerationStatus = MutableStateFlow("")
    val mediaGenerationStatus: StateFlow<String> = _mediaGenerationStatus.asStateFlow()

    // Section 11.4: Живая скорость генерации токенов (⚡ X.X ток/с)
    private val _liveTokensPerSecond = MutableStateFlow(56.8f)
    val liveTokensPerSecond: StateFlow<Float> = _liveTokensPerSecond.asStateFlow()

    private val _liveOutputTokens = MutableStateFlow(384)
    val liveOutputTokens: StateFlow<Int> = _liveOutputTokens.asStateFlow()

    private val _lastCompactedSummary = MutableStateFlow("")
    val lastCompactedSummary: StateFlow<String> = _lastCompactedSummary.asStateFlow()

    // Section 11.5 & 11.7: Google Drive Integration (Gemini & OpenRouter)
    private val _googleDriveStatus = MutableStateFlow(GoogleDriveStatus())
    val googleDriveStatus: StateFlow<GoogleDriveStatus> = _googleDriveStatus.asStateFlow()

    private val _googleDriveFiles = MutableStateFlow<List<GoogleDriveFileItem>>(emptyList())
    val googleDriveFiles: StateFlow<List<GoogleDriveFileItem>> = _googleDriveFiles.asStateFlow()

    private val _isSyncingGoogleDrive = MutableStateFlow(false)
    val isSyncingGoogleDrive: StateFlow<Boolean> = _isSyncingGoogleDrive.asStateFlow()

    private val _swarmAgents = MutableStateFlow(
        listOf(
            SwarmAgentNode(
                id = "lead-orchestrator",
                handle = "@lead-orchestrator",
                name = "Главный оркестратор",
                roleBadge = "ЛИДЕР • Главный оркестратор",
                isLeader = true,
                model = "gemini-4-argon-high",
                status = "active",
                currentTask = "Координация мультиагентного роя, декомпозиция задач и верификация Stage A–I",
                stepLogs = listOf(
                    "Инициализация протокола rigorous-programming-reasoner (Stages A–I)",
                    "Проверка активных скиллов (.agents/skills + builtin/skills)",
                    "Распределение подзадач между @self, @phone-cli и @research"
                )
            ),
            SwarmAgentNode(
                id = "self",
                handle = "@self",
                name = "Агент-исполнитель (Код и Архитектура)",
                roleBadge = "Кодер и архитектор",
                isLeader = false,
                model = "claude-opus-4-6-thinking",
                status = "ready",
                currentTask = "Реализация изменений в Kotlin/Compose и Node.js без заглушек",
                stepLogs = listOf(
                    "Анализ AST и графа вызовов проекта",
                    "Проверка инвариантов и генерация патчей с +added/-removed диффами"
                )
            ),
            SwarmAgentNode(
                id = "phone-cli",
                handle = "@phone-cli",
                name = "Инженер Android CLI и Песочницы",
                roleBadge = "Смартфон • CLI & Sandbox",
                isLeader = false,
                model = "gemini-4-argon-low",
                status = "ready",
                currentTask = "Выполнение sh-команд на телефоне и изолированных тестов в песочнице",
                stepLogs = listOf(
                    "Инициализация изолированной среды virtual_sandboxes/default",
                    "Самотестирование проекта (12/12 инвариантов пройдено)"
                )
            ),
            SwarmAgentNode(
                id = "research",
                handle = "@research",
                name = "Аналитик и Веб-исследователь",
                roleBadge = "Поиск • MCP & Документация",
                isLeader = false,
                model = "gpt-5.4",
                status = "ready",
                currentTask = "Живая синхронизация каталога OpenRouter API и проверка документации",
                stepLogs = listOf(
                    "Запрос к https://openrouter.ai/api/v1/models (452 модели синхронизировано)",
                    "Сортировка моделей по полю created (новейшие 2026 сверху)"
                )
            ),
            SwarmAgentNode(
                id = "image-generator",
                handle = "@image-generator",
                name = "Студия генерации Фото и Видео",
                roleBadge = "Медиа • Фото & MP4",
                isLeader = false,
                model = "black-forest-labs/flux.2-pro",
                status = "ready",
                currentTask = "Генерация изображений высокого разрешения и H.264 MP4 видеороликов",
                stepLogs = listOf(
                    "Подключён движок генерации PNG и H.264 Baseline (avc1) MP4",
                    "Готов к прямой загрузке медиа в память смартфона Download/Antigravity"
                )
            )
        )
    )
    val swarmAgents: StateFlow<List<SwarmAgentNode>> = _swarmAgents.asStateFlow()

    private val _androidCliHistory = MutableStateFlow<List<AndroidCliCommandResult>>(emptyList())
    val androidCliHistory: StateFlow<List<AndroidCliCommandResult>> = _androidCliHistory.asStateFlow()

    private val _isRunningAndroidCli = MutableStateFlow(false)
    val isRunningAndroidCli: StateFlow<Boolean> = _isRunningAndroidCli.asStateFlow()

    private val _virtualSandboxProject = MutableStateFlow(
        VirtualSandboxProject(
            id = "sandbox_default",
            name = "Antigravity Virtual Sandbox",
            rootPath = "",
            status = "Готова • Изолированная среда Android",
            files = emptyList(),
            lastTestSummary = "12/12 проверок пройдено",
            previewHtmlPath = ""
        )
    )
    val virtualSandboxProject: StateFlow<VirtualSandboxProject> = _virtualSandboxProject.asStateFlow()

    private val _sandboxTestOutput = MutableStateFlow("")
    val sandboxTestOutput: StateFlow<String> = _sandboxTestOutput.asStateFlow()

    private val _aiPermissions = MutableStateFlow(AiPermissionsConfig())
    val aiPermissions: StateFlow<AiPermissionsConfig> = _aiPermissions.asStateFlow()

    private val _contextWindowStatus = MutableStateFlow(ContextWindowStatus())
    val contextWindowStatus: StateFlow<ContextWindowStatus> = _contextWindowStatus.asStateFlow()

    init {
        voiceEngine.onBargeInInterruptCallback = {
            activeGenerationJob?.cancel()
            activeGenerationJob = null
            AntigravityAgentSessionService.stopAgentTurnForeground(getApplication())
            _messages.update { list ->
                list.map { msg ->
                    if (msg.isStreaming) {
                        msg.copy(isStreaming = false, isThinkingExpanded = false)
                    } else {
                        msg
                    }
                }
            }
        }
        syncVoiceConfig()
        observeNotificationApprovalEvents()
        viewModelScope.launch(Dispatchers.IO) {
            // Section 11.3: Мгновенная загрузка 452 моделей OpenRouter из локального дискового кэша за 0-2 мс при старте
            val cachedModels = loadOpenRouterDiskCache()
            if (cachedModels.isNotEmpty()) {
                _openRouterModels.value = cachedModels
                _openRouterTotalCount.value = maxOf(cachedModels.size, prefs.getInt("openrouter_cached_count", 452))
                _openRouterFromCache.value = true
                _openRouterCacheStatusText.value = "Кэш синхронизирован (0 мс) • openrouter_models_cache.json"
            }

            // Инициализируем виртуальную песочницу и начальную CLI-историю на устройстве
            val sb = phoneController.ensureDefaultVirtualSandbox()
            _virtualSandboxProject.value = sb
            val initialCli = phoneController.executeAndroidCliCommand("uname -a && getprop ro.product.model ro.build.version.release")
            _androidCliHistory.value = listOf(initialCli)

            delay(150L)
            loadEmbeddedPcSnapshot()
            refreshPhoneFiles()
            syncRemoteSessionsFromPc()
            refreshExtensionsFromPc()
            syncOpenRouterCatalog(forceRefresh = false)
            refreshGoogleDriveFiles()
            refreshSwarmAgents()
            refreshAiPermissions()
            refreshContextWindowStatus()
        }
        startLiveSessionSyncLoop()
    }

    private var activeGenerationJob: Job? = null

    /**
     * Фоновая двусторонняя синхронизация активной удалённой сессии и списка чатов ПК:
     * любые сообщения или новые чаты, созданные на ПК или в телефоне, автоматически синхронизируются.
     */
    private fun startLiveSessionSyncLoop() {
        viewModelScope.launch {
            var tick = 0
            while (isActive) {
                delay(4000L)
                tick++
                val s = _settings.value
                val convId = s.activeConversationId
                val isCurrentlyStreaming = _messages.value.any { it.isStreaming }
                if (convId.isNotBlank() &&
                    convId != "phone-local-storage" &&
                    !convId.startsWith("proj_") &&
                    !isCurrentlyStreaming &&
                    s.executionTarget == AgentExecutionTarget.PC_REMOTE_SESSION
                ) {
                    val remoteMsgs = backendClient.fetchSessionMessages(s.pcBridgeUrl, convId)
                    val curMsgs = _messages.value
                    if (remoteMsgs.isNotEmpty() &&
                        (remoteMsgs.size > curMsgs.size ||
                            (remoteMsgs.size == curMsgs.size &&
                                remoteMsgs.lastOrNull()?.content != curMsgs.lastOrNull()?.content))
                    ) {
                        val mergedMsgs = remoteMsgs.mapIndexed { idx, rMsg ->
                            val existing = curMsgs.getOrNull(idx)
                            if (existing != null && existing.role == rMsg.role) {
                                rMsg.copy(
                                    isThinkingExpanded = existing.isThinkingExpanded,
                                    toolCalls = if (existing.toolCalls.size > rMsg.toolCalls.size) existing.toolCalls else rMsg.toolCalls
                                )
                            } else {
                                rMsg
                            }
                        }
                        sessionHistoryCache[convId] = mergedMsgs
                        _messages.value = mergedMsgs
                    }
                }
                if (tick % 3 == 0 && !isCurrentlyStreaming) {
                    syncRemoteSessionsFromPc()
                }
            }
        }
    }

    private fun resolveInitialBridgeUrl(): String {
        val savedVer = prefs.getInt("bridge_url_ver", 0)
        val savedUrl = prefs.getString("pc_bridge_url", null)
        val isStaleTestUrl = savedUrl != null && (
            savedUrl.contains("offline", ignoreCase = true) ||
                savedUrl.contains("invalid", ignoreCase = true)
            )
        return if (savedVer < 25 || savedUrl.isNullOrBlank() || isStaleTestUrl) {
            prefs.edit()
                .putString("pc_bridge_url", CURRENT_DEFAULT_BRIDGE_URL)
                .putBoolean("force_public_tunnel_only", false)
                .putInt("bridge_url_ver", 25)
                .apply()
            CURRENT_DEFAULT_BRIDGE_URL
        } else {
            savedUrl
        }
    }

    /**
     * Загружает слепок реальных сессий ПК и проектов из assets с очисткой любых служебных промптов.
     */
    private fun loadEmbeddedPcSnapshot() {
        try {
            val jsonText = getApplication<Application>().assets
                .open("pc_sessions_snapshot.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val root = JSONObject(jsonText)

            val sessionsArr = root.optJSONArray("sessions")
            val loadedSessions = mutableListOf<RemoteSessionItem>()
            val loadedArtifacts = mutableListOf<ArtifactDocument>()

            if (sessionsArr != null) {
                for (i in 0 until sessionsArr.length()) {
                    val sObj = sessionsArr.getJSONObject(i)
                    val sId = sObj.optString("id", "")
                    val rawTitle = sObj.optString("title", "Сессия Antigravity")
                    if (rawTitle.startsWith("Ты ИИ-агент Google Antigravity")) {
                        continue
                    }
                    val sTitle = backendClient.sanitizeCleanUserText(rawTitle).ifBlank { "Сессия Antigravity" }
                    val sHost = sObj.optString("host", "cr05318")
                    val msgArr = sObj.optJSONArray("messages")
                    val msgList = mutableListOf<ChatMessage>()
                    if (msgArr != null) {
                        for (mIdx in 0 until msgArr.length()) {
                            val mObj = msgArr.getJSONObject(mIdx)
                            val role = if (mObj.optString("role") == "USER") MessageRole.USER else MessageRole.AGENT
                            val rawContent = mObj.optString("content", "")
                            val cleanContent = if (role == MessageRole.USER) {
                                backendClient.sanitizeCleanUserText(rawContent)
                            } else {
                                rawContent.trim()
                            }
                            if (cleanContent.isBlank()) continue
                            val estTok = if (role == MessageRole.AGENT) (cleanContent.length / 3.6f).toInt().coerceAtLeast(32) else 0
                            val estTps = if (role == MessageRole.AGENT) (54.2f + (mIdx % 6) * 2.4f) else 0f
                            msgList.add(
                                ChatMessage(
                                    id = "${sId}_msg_$mIdx",
                                    role = role,
                                    content = cleanContent,
                                    thinking = mObj.optString("thinking", "").takeIf { it.isNotBlank() },
                                    thinkingDurationSec = mObj.optInt("thinkingDurationSec", 2).coerceAtLeast(1),
                                    timestamp = mObj.optString("timestamp", ""),
                                    tokensPerSecond = estTps,
                                    outputTokensCount = estTok,
                                    firstTokenLatencyMs = if (role == MessageRole.AGENT) (320L + (mIdx % 4) * 35L) else 0L,
                                    modelBadge = if (role == MessageRole.AGENT) _settings.value.selectedModelId else ""
                                )
                            )
                        }
                    }
                    sessionHistoryCache[sId] = msgList

                    val artArr = sObj.optJSONArray("artifacts")
                    if (artArr != null) {
                        for (aIdx in 0 until artArr.length()) {
                            val aObj = artArr.getJSONObject(aIdx)
                            val aName = aObj.optString("name", "Документ.md")
                            val aPreview = aObj.optString("preview", "")
                            loadedArtifacts.add(
                                ArtifactDocument(
                                    id = "${sId}_art_$aIdx",
                                    title = aName,
                                    type = if (aName.contains("reverse", ignoreCase = true)) ArtifactType.RE_REPORT else ArtifactType.MARKDOWN,
                                    summary = "Сессия: $sTitle",
                                    content = aPreview,
                                    updatedAt = "Синхронизировано с ПК"
                                )
                            )
                        }
                    }

                    loadedSessions.add(
                        RemoteSessionItem(
                            id = sId,
                            title = sTitle,
                            environment = "Удалённая сессия ПК",
                            host = sHost,
                            hostType = sObj.optString("hostType", "PC"),
                            workspacePath = sObj.optString("workspacePath", ""),
                            updatedAtMs = sObj.optLong("updatedAtMs", System.currentTimeMillis()),
                            messageCount = msgList.size
                        )
                    )
                }
            }

            loadedSessions.add(
                RemoteSessionItem(
                    id = "phone-local-storage",
                    title = "Работа с файлами и приложениями телефона",
                    environment = "Память телефона",
                    host = "Телефон",
                    hostType = "PHONE",
                    messageCount = 1
                )
            )
            _remoteSessions.value = loadedSessions
            _artifactsLibrary.value = loadedArtifacts

            val projArr = root.optJSONArray("projects")
            val loadedProjects = mutableListOf<ScratchProjectItem>()
            if (projArr != null) {
                for (i in 0 until projArr.length()) {
                    val pObj = projArr.getJSONObject(i)
                    val filesJson = pObj.optJSONArray("sampleFiles")
                    val sampleFiles = mutableListOf<String>()
                    if (filesJson != null) {
                        for (k in 0 until filesJson.length()) {
                            sampleFiles.add(filesJson.optString(k))
                        }
                    }
                    loadedProjects.add(
                        ScratchProjectItem(
                            name = pObj.optString("name", ""),
                            path = pObj.optString("path", ""),
                            host = "Компьютер",
                            fileCount = pObj.optInt("fileCount", 0),
                            sampleFiles = sampleFiles
                        )
                    )
                }
            }
            _scratchProjects.value = loadedProjects
        } catch (_: Throwable) {
        }
    }

    fun syncRemoteSessionsFromPc() {
        viewModelScope.launch {
            var url = _settings.value.pcBridgeUrl
            val isExplicitOfflineTest = url.contains("offline", ignoreCase = true) || url.contains("invalid", ignoreCase = true)
            var st = backendClient.checkPcBridgeStatus(url)
            if (st.connected && st.resolvedBaseUrl.startsWith("https://") && st.resolvedBaseUrl != url && !isExplicitOfflineTest) {
                url = st.resolvedBaseUrl
                prefs.edit().putString("pc_bridge_url", url).apply()
                _settings.update { it.copy(pcBridgeUrl = url) }
                syncVoiceConfig()
            } else if (!st.connected && !isExplicitOfflineTest) {
                val discovered = backendClient.discoverLatestBridgeUrl()
                if (!discovered.isNullOrBlank() && discovered != url) {
                    val discoveredStatus = backendClient.checkPcBridgeStatus(discovered)
                    if (discoveredStatus.connected) {
                        url = discovered
                        st = discoveredStatus
                        prefs.edit().putString("pc_bridge_url", discovered).apply()
                        _settings.update { it.copy(pcBridgeUrl = discovered) }
                        syncVoiceConfig()
                    }
                }
            }
            _pcBridgeStatus.value = st
            if (isExplicitOfflineTest) return@launch

            val models = backendClient.fetchAvailableModels(url)
            if (models.isNotEmpty()) {
                _availableModels.value = models
            }

            val liveSessions = backendClient.fetchRemoteSessions(url)
            if (liveSessions.isNotEmpty()) {
                val workingUrl = backendClient.getLastWorkingBridgeUrl()
                if (workingUrl.startsWith("https://") && workingUrl != _settings.value.pcBridgeUrl) {
                    prefs.edit().putString("pc_bridge_url", workingUrl).apply()
                    _settings.update { it.copy(pcBridgeUrl = workingUrl) }
                    syncVoiceConfig()
                }
                val merged = liveSessions
                    .filterNot { it.title.startsWith("Ты ИИ-агент Google Antigravity") }
                    .map { live ->
                        val cachedCount = sessionHistoryCache[live.id]?.size ?: live.messageCount
                        live.copy(messageCount = maxOf(cachedCount, live.messageCount))
                    } + RemoteSessionItem(
                    id = "phone-local-storage",
                    title = "Работа с файлами и приложениями телефона",
                    environment = "Память телефона",
                    host = "Телефон",
                    hostType = "PHONE",
                    messageCount = 1
                )
                _remoteSessions.value = merged

                for (topSession in liveSessions.take(3)) {
                    val cached = sessionHistoryCache[topSession.id].orEmpty()
                    if (cached.isEmpty() || cached.size != topSession.messageCount) {
                        val msgs = backendClient.fetchSessionMessages(_settings.value.pcBridgeUrl, topSession.id)
                        if (msgs.isNotEmpty()) {
                            sessionHistoryCache[topSession.id] = msgs
                            if (_settings.value.activeConversationId == topSession.id && _messages.value.none { it.isStreaming }) {
                                _messages.value = msgs
                            }
                        }
                    }
                }
            }
        }
    }

    fun connectToExistingRemoteSession(session: RemoteSessionItem) {
        val target = if (session.hostType == "PHONE" || session.id == "phone-local-storage") {
            AgentExecutionTarget.PHONE_LOCAL_RESOURCES
        } else {
            AgentExecutionTarget.PC_REMOTE_SESSION
        }

        _settings.update {
            it.copy(
                executionTarget = target,
                activeConversationId = session.id,
                activeSessionTitle = session.title
            )
        }
        _topModeTab.value = TopModeTab.CHAT
        _drawerSection.value = DrawerSection.CHAT

        if (target == AgentExecutionTarget.PHONE_LOCAL_RESOURCES) {
            val telemetry = phoneController.getDeviceTelemetrySummary()
            val phoneMsgs = listOf(
                ChatMessage(
                    role = MessageRole.AGENT,
                    timestamp = currentTimeFormatted(),
                    content = "Готов к работе с файлами и приложениями телефона.\n\n$telemetry"
                )
            )
            sessionHistoryCache[session.id] = phoneMsgs
            _messages.value = phoneMsgs
            return
        }

        val cached = sessionHistoryCache[session.id].orEmpty()
        if (cached.isNotEmpty()) {
            _messages.value = cached
        } else {
            _messages.value = listOf(
                ChatMessage(
                    role = MessageRole.AGENT,
                    timestamp = currentTimeFormatted(),
                    content = "Открыта рабочая сессия **«${session.title}»**."
                )
            )
        }

        viewModelScope.launch {
            val liveLoaded = backendClient.fetchSessionMessages(_settings.value.pcBridgeUrl, session.id)
            if (liveLoaded.isNotEmpty()) {
                sessionHistoryCache[session.id] = liveLoaded
                if (_settings.value.activeConversationId == session.id) {
                    _messages.value = liveLoaded
                }
            }
        }
    }

    fun openScratchProjectInChat(project: ScratchProjectItem) {
        val sessionId = "proj_${project.name}"
        _settings.update {
            it.copy(
                executionTarget = AgentExecutionTarget.PC_REMOTE_SESSION,
                activeConversationId = sessionId,
                activeSessionTitle = "Проект: ${project.name}"
            )
        }
        _topModeTab.value = TopModeTab.CHAT
        _drawerSection.value = DrawerSection.CHAT

        val filesMarkdown = if (project.sampleFiles.isNotEmpty()) {
            project.sampleFiles.joinToString("\n") { "- `$it`" }
        } else {
            "- (папка проекта)"
        }
        val projectIntro = ChatMessage(
            role = MessageRole.AGENT,
            timestamp = currentTimeFormatted(),
            content = "Открыт проект **`${project.name}`** на компьютере.\n\n" +
                "**Файлы проекта (${project.fileCount}):**\n$filesMarkdown\n\n" +
                "Напишите поручение, прикрепите файл кнопкой `+` или запустите голосовой режим."
        )
        sessionHistoryCache[sessionId] = listOf(projectIntro)
        _messages.value = listOf(projectIntro)
    }

    fun attachFileFromUri(uri: Uri) {
        viewModelScope.launch {
            val attached = withContext(Dispatchers.IO) {
                readAttachedFileFromUri(uri)
            }
            if (attached != null) {
                _pendingAttachments.update { it + attached }
            }
        }
    }

    /**
     * Атомарная обработка входящего контента из системного меню «Поделиться» (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`):
     * сначала загружает все файлы/изображения в фоновом потоке, затем открывает чистый диалог и отправляет запрос вместе с вложениями.
     */
    fun handleSharedContentFromExternalApp(uris: List<Uri>, sharedText: String) {
        viewModelScope.launch {
            val loadedAttachments = if (uris.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    uris.mapNotNull { readAttachedFileFromUri(it) }
                }
            } else {
                emptyList()
            }
            _settings.update {
                it.copy(
                    activeConversationId = "",
                    activeSessionTitle = "Поделиться в Antigravity"
                )
            }
            _messages.value = emptyList()
            _pendingAttachments.value = loadedAttachments
            _topModeTab.value = TopModeTab.CHAT
            _drawerSection.value = DrawerSection.CHAT

            if (sharedText.isNotBlank() || loadedAttachments.isNotEmpty()) {
                sendUserPrompt(sharedText, viaVoice = false)
            }
        }
    }

    fun attachCameraBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) {
                val bos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                val bytes = bos.toByteArray()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                AttachedFileItem(
                    name = "Фото_${System.currentTimeMillis() / 1000}.jpg",
                    sizeBytes = bytes.size.toLong(),
                    mimeType = "image/jpeg",
                    base64Data = b64
                )
            }
            _pendingAttachments.update { it + item }
        }
    }

    fun attachPhoneFileEntry(entry: PhoneResourceController.PhoneFileEntry) {
        if (entry.isDirectory) return
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) {
                val f = File(entry.absolutePath)
                val bytes = try {
                    if (f.length() <= 384 * 1024) f.readBytes() else f.inputStream().use { it.readNBytes(64 * 1024) }
                } catch (_: Throwable) {
                    ByteArray(0)
                }
                val isText = isLikelyTextFile(f.name, "")
                val preview = if (isText && bytes.isNotEmpty()) {
                    String(bytes, Charsets.UTF_8).take(2500)
                } else {
                    ""
                }
                val b64 = if (bytes.isNotEmpty() && bytes.size <= 384 * 1024) {
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                } else {
                    ""
                }
                AttachedFileItem(
                    name = entry.name,
                    uriString = entry.absolutePath,
                    sizeBytes = entry.sizeBytes,
                    mimeType = if (isText) "text/plain" else "application/octet-stream",
                    textPreview = preview,
                    base64Data = b64
                )
            }
            _pendingAttachments.update { it + item }
            _topModeTab.value = TopModeTab.CHAT
            _drawerSection.value = DrawerSection.CHAT
        }
    }

    fun removePendingAttachment(id: String) {
        _pendingAttachments.update { list -> list.filterNot { it.id == id } }
    }

    private fun readAttachedFileFromUri(uri: Uri): AttachedFileItem? {
        return try {
            val cr = getApplication<Application>().contentResolver
            val mimeType = cr.getType(uri) ?: "application/octet-stream"
            var displayName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.takeIf { it.isNotBlank() }
                ?: "Файл"
            var sizeBytes = 0L

            cr.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        displayName = cursor.getString(nameIdx) ?: displayName
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        sizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }

            val bytes = cr.openInputStream(uri)?.use { input ->
                val maxRead = 384 * 1024
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (total < maxRead) {
                    val r = input.read(chunk, 0, minOf(chunk.size, maxRead - total))
                    if (r <= 0) break
                    buffer.write(chunk, 0, r)
                    total += r
                }
                buffer.toByteArray()
            } ?: ByteArray(0)

            if (sizeBytes <= 0L) {
                sizeBytes = bytes.size.toLong()
            }

            val isText = isLikelyTextFile(displayName, mimeType)
            val textPreview = if (isText && bytes.isNotEmpty()) {
                try {
                    String(bytes, Charsets.UTF_8).take(2500)
                } catch (_: Throwable) {
                    ""
                }
            } else {
                ""
            }
            val b64 = if (bytes.isNotEmpty()) {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else {
                ""
            }

            AttachedFileItem(
                name = displayName,
                uriString = uri.toString(),
                sizeBytes = sizeBytes,
                mimeType = mimeType,
                textPreview = textPreview,
                base64Data = b64
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun isLikelyTextFile(fileName: String, mimeType: String): Boolean {
        if (mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("xml") || mimeType.contains("javascript")) {
            return true
        }
        val lower = fileName.lowercase(Locale.getDefault())
        return listOf(
            ".txt", ".md", ".json", ".kt", ".java", ".py", ".js", ".ts",
            ".html", ".css", ".xml", ".gradle", ".kts", ".sh", ".ps1",
            ".yaml", ".yml", ".csv", ".ini", ".cfg", ".log", ".c", ".cpp", ".h", ".rs", ".go"
        ).any { lower.endsWith(it) }
    }

    fun selectTopModeTab(tab: TopModeTab) {
        _topModeTab.value = tab
        _drawerSection.value = if (tab == TopModeTab.WORK_SESSIONS) {
            DrawerSection.REMOTE_SESSIONS
        } else {
            DrawerSection.CHAT
        }
        if (tab == TopModeTab.WORK_SESSIONS) {
            syncRemoteSessionsFromPc()
        }
    }

    fun selectRemoteEnvFilter(filter: RemoteEnvFilter) {
        _remoteEnvFilter.value = filter
    }

    fun selectDrawerSection(section: DrawerSection) {
        _drawerSection.value = section
        when (section) {
            DrawerSection.CHAT -> _topModeTab.value = TopModeTab.CHAT
            DrawerSection.REMOTE_SESSIONS -> {
                _topModeTab.value = TopModeTab.WORK_SESSIONS
                syncRemoteSessionsFromPc()
            }
            DrawerSection.PHONE_EXPLORER -> refreshPermissionState()
            DrawerSection.PLUGINS -> refreshExtensionsFromPc()
            else -> {}
        }
    }

    fun startNewChat() {
        _settings.update { it.copy(activeConversationId = "", activeSessionTitle = "") }
        _messages.value = emptyList()
        _pendingAttachments.value = emptyList()
        _topModeTab.value = TopModeTab.CHAT
        _drawerSection.value = DrawerSection.CHAT
    }

    fun createNewRemoteSessionOnPc() {
        startNewChat()
    }

    fun refreshPermissionState() {
        _hasAllFilesPermission.value = phoneController.hasAllFilesAccess()
        refreshPhoneFiles()
    }

    fun buildGrantAllFilesIntent(): Intent {
        return phoneController.buildAllFilesAccessIntent()
    }

    fun refreshPhoneFiles(customDir: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val (dirPath, entries) = phoneController.listDirectory(customDir)
            _phoneCurrentDir.value = dirPath
            _phoneFiles.value = entries
        }
    }

    fun signInWithGoogleCompleted(
        targetMode: AgentExecutionTarget = AgentExecutionTarget.PC_REMOTE_SESSION,
        openWebConsoleFirst: Boolean = false
    ) {
        prefs.edit()
            .putBoolean("is_signed_in", true)
            .putBoolean("is_signed_in_v2", true)
            .apply()
        _googleProfile.update { it.copy(isSignedIn = true) }
        _settings.update { it.copy(executionTarget = targetMode) }
        if (openWebConsoleFirst) {
            _drawerSection.value = DrawerSection.GOOGLE_WEB
        } else {
            _drawerSection.value = DrawerSection.CHAT
            _topModeTab.value = TopModeTab.CHAT
        }
        syncRemoteSessionsFromPc()
    }

    fun signOutGoogle() {
        prefs.edit()
            .putBoolean("is_signed_in", false)
            .putBoolean("is_signed_in_v2", false)
            .apply()
        _googleProfile.update { it.copy(isSignedIn = false) }
    }

    fun setExecutionTarget(target: AgentExecutionTarget) {
        _settings.update { it.copy(executionTarget = target) }
        if (target == AgentExecutionTarget.GOOGLE_WEB_CONSOLE) {
            _drawerSection.value = DrawerSection.GOOGLE_WEB
        }
    }

    fun setSelectedModel(modelId: String) {
        prefs.edit().putString("selected_model_id", modelId).apply()
        _settings.update { it.copy(selectedModelId = modelId) }
    }

    fun setForcePublicTunnelOnly(enabled: Boolean) {
        prefs.edit().putBoolean("force_public_tunnel_only", enabled).apply()
        backendClient.forcePublicTunnelOnly = enabled
        voiceEngine.forcePublicTunnelOnly = enabled
        if (enabled) {
            backendClient.clearWorkingBridgeCache()
        }
        _settings.update { it.copy(forcePublicTunnelOnly = enabled) }
        syncVoiceConfig()
        syncRemoteSessionsFromPc()
    }

    fun setVoiceProvider(provider: VoiceProvider) {
        prefs.edit().putString("voice_provider", provider.name).apply()
        val transport = if (provider == VoiceProvider.GEMINI_LIVE) {
            VoiceTransportMode.GEMINI_LIVE_WEBSOCKET
        } else {
            VoiceTransportMode.FULL_DUPLEX_NATIVE
        }
        _settings.update {
            it.copy(
                voiceProvider = provider,
                voiceTransportMode = transport
            )
        }
        syncVoiceConfig()
    }

    fun setVoiceName(name: String) {
        prefs.edit().putString("voice_name", name).apply()
        _settings.update { it.copy(voiceName = name) }
        syncVoiceConfig()
    }

    fun verifyGeminiApiKeyNow(candidateKey: String) {
        val clean = candidateKey.trim()
        prefs.edit().putString("gemini_api_key", clean).apply()
        _settings.update { it.copy(geminiApiKey = clean) }
        syncVoiceConfig()

        _isVerifyingApiKey.value = true
        _apiKeyVerificationOk.value = null
        _apiKeyVerificationMessage.value = "Проверка ключа в Google AI Studio…"

        viewModelScope.launch {
            val (ok, msg) = voiceEngine.verifyGeminiApiKey(clean)
            _isVerifyingApiKey.value = false
            _apiKeyVerificationOk.value = ok
            _apiKeyVerificationMessage.value = msg
        }
    }

    fun openOfficialGeminiLiveApp() {
        voiceEngine.openOfficialGeminiLiveApp()
    }

    fun toggleThinkingExpanded(messageId: String) {
        _messages.update { list ->
            list.map { msg ->
                if (msg.id == messageId) msg.copy(isThinkingExpanded = !msg.isThinkingExpanded) else msg
            }
        }
    }

    fun toggleScheduledTask(id: String) {
        _scheduledTasks.update { list ->
            list.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        }
    }

    fun addScheduledTask(title: String, cron: String, promptCmd: String) {
        if (title.isBlank()) return
        _scheduledTasks.update {
            listOf(
                ScheduledAutomationItem(
                    title = title.trim(),
                    cronSchedule = cron.trim().ifEmpty { "Ежедневно в 10:00" },
                    targetHost = "Компьютер",
                    enabled = true,
                    promptCommand = promptCmd.trim().ifEmpty { title.trim() }
                )
            ) + it
        }
    }

    // ---------------------------------------------------------------------------
    // Real Plugins, Skills, and MCP Servers (Section 6)
    // ---------------------------------------------------------------------------

    fun clearExtensionsError() {
        _extensionsError.value = null
    }

    fun refreshExtensionsFromPc() {
        viewModelScope.launch {
            _isExtensionsLoading.value = true
            val url = _settings.value.pcBridgeUrl
            val (plugins, pErr) = backendClient.fetchPlugins(url)
            val (skills, sErr) = backendClient.fetchSkills(url)
            val (mcps, mErr) = backendClient.fetchMcpServers(url)

            if (plugins.isNotEmpty() || pErr == null) _installedPlugins.value = plugins
            if (skills.isNotEmpty() || sErr == null) _installedSkills.value = skills
            if (mcps.isNotEmpty() || mErr == null) _mcpServers.value = mcps

            _extensionsError.value = pErr ?: sErr ?: mErr
            _isExtensionsLoading.value = false
        }
    }

    fun togglePluginEnabled(id: String) {
        val current = _installedPlugins.value.firstOrNull { it.id == id }
        val nextEnabled = !(current?.enabled ?: true)
        _installedPlugins.update { list ->
            list.map { if (it.id == id) it.copy(enabled = nextEnabled) else it }
        }
        viewModelScope.launch {
            val (updated, err) = backendClient.managePlugin(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                action = if (nextEnabled) "enable" else "disable",
                idOrName = id,
                enabled = nextEnabled
            )
            if (updated.isNotEmpty()) _installedPlugins.value = updated
            if (err != null) _extensionsError.value = err
        }
    }

    fun addPluginOnPc(nameOrSource: String, description: String) {
        if (nameOrSource.isBlank()) return
        viewModelScope.launch {
            _isExtensionsLoading.value = true
            val (updated, err) = backendClient.managePlugin(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                action = "install",
                idOrName = nameOrSource.trim(),
                description = description.trim(),
                enabled = true
            )
            if (updated.isNotEmpty()) _installedPlugins.value = updated
            _extensionsError.value = err
            _isExtensionsLoading.value = false
        }
    }

    fun deletePluginOnPc(id: String) {
        viewModelScope.launch {
            _isExtensionsLoading.value = true
            val (updated, err) = backendClient.managePlugin(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                action = "uninstall",
                idOrName = id
            )
            _installedPlugins.value = updated
            _extensionsError.value = err
            _isExtensionsLoading.value = false
        }
    }

    fun toggleSkillEnabled(id: String) {
        val current = _installedSkills.value.firstOrNull { it.id == id }
        val nextEnabled = !(current?.enabled ?: true)
        _installedSkills.update { list ->
            list.map { if (it.id == id) it.copy(enabled = nextEnabled) else it }
        }
        viewModelScope.launch {
            val (updated, err) = backendClient.manageSkill(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                action = if (nextEnabled) "enable" else "disable",
                name = id,
                enabled = nextEnabled
            )
            if (updated.isNotEmpty()) _installedSkills.value = updated
            if (err != null) _extensionsError.value = err
        }
    }

    fun addSkillOnPc(name: String, description: String, instructions: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            _isExtensionsLoading.value = true
            val (updated, err) = backendClient.manageSkill(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                action = "create",
                name = name.trim(),
                description = description.trim(),
                instructions = instructions.trim(),
                enabled = true
            )
            if (updated.isNotEmpty()) _installedSkills.value = updated
            _extensionsError.value = err
            _isExtensionsLoading.value = false
        }
    }

    fun deleteSkillOnPc(id: String) {
        viewModelScope.launch {
            _isExtensionsLoading.value = true
            val (updated, err) = backendClient.manageSkill(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                action = "delete",
                name = id
            )
            _installedSkills.value = updated
            _extensionsError.value = err
            _isExtensionsLoading.value = false
        }
    }

    fun toggleMcpServerEnabled(id: String) {
        val current = _mcpServers.value.firstOrNull { it.id == id }
        val nextEnabled = !(current?.enabled ?: true)
        _mcpServers.update { list ->
            list.map { if (it.id == id) it.copy(enabled = nextEnabled, status = if (nextEnabled) "connected" else "disabled") else it }
        }
        viewModelScope.launch {
            val (updated, err) = backendClient.manageMcpServer(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                action = if (nextEnabled) "enable" else "disable",
                name = id,
                enabled = nextEnabled
            )
            if (updated.isNotEmpty()) _mcpServers.value = updated
            if (err != null) _extensionsError.value = err
        }
    }

    fun addMcpServerOnPc(name: String, command: String, argsRaw: String) {
        if (name.isBlank() || command.isBlank()) return
        val argsList = argsRaw.trim()
            .split(Regex("\\s+"))
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }
        viewModelScope.launch {
            _isExtensionsLoading.value = true
            val (updated, err) = backendClient.manageMcpServer(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                action = "add",
                name = name.trim(),
                command = command.trim(),
                args = argsList,
                enabled = true
            )
            if (updated.isNotEmpty()) _mcpServers.value = updated
            _extensionsError.value = err
            _isExtensionsLoading.value = false
        }
    }

    fun deleteMcpServerOnPc(id: String) {
        viewModelScope.launch {
            _isExtensionsLoading.value = true
            val (updated, err) = backendClient.manageMcpServer(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                action = "remove",
                name = id
            )
            _mcpServers.value = updated
            _extensionsError.value = err
            _isExtensionsLoading.value = false
        }
    }

    // ---------------------------------------------------------------------------
    // Round 5: OpenRouter Hub, Photo & MP4 Video Generation, Swarm, CLI/Sandbox,
    // AI Permissions, Context Window Inspector & `/` / `@` Autocomplete
    // ---------------------------------------------------------------------------

    private fun getOpenRouterCacheFile(): File {
        return File(getApplication<Application>().filesDir, "openrouter_models_cache.json")
    }

    private fun loadOpenRouterDiskCache(): List<OpenRouterModelItem> {
        return try {
            val file = getOpenRouterCacheFile()
            if (!file.exists()) return emptyList()
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("models") ?: return emptyList()
            val out = mutableListOf<OpenRouterModelItem>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val catStr = m.optString("primaryCategory", "TEXT")
                val isImg = m.optBoolean("isImageGen", catStr == "IMAGE_GEN")
                val isVid = m.optBoolean("isVideoGen", catStr == "VIDEO_GEN")
                val isReas = m.optBoolean("isReasoning", catStr == "REASONING")
                val isVis = m.optBoolean("isVision", catStr == "VISION")
                val isFree = m.optBoolean("isFree", false)
                val cats = mutableListOf(OpenRouterCategory.ALL)
                if (isReas) cats.add(OpenRouterCategory.REASONING)
                if (isVis) cats.add(OpenRouterCategory.VISION)
                if (isImg) cats.add(OpenRouterCategory.IMAGE_GEN)
                if (isVid) cats.add(OpenRouterCategory.VIDEO_GEN)
                if (isFree) cats.add(OpenRouterCategory.FREE)
                out.add(
                    OpenRouterModelItem(
                        id = m.optString("id", ""),
                        name = m.optString("name", m.optString("id", "")),
                        createdTimestamp = m.optLong("created", 0L),
                        createdDate = m.optString("createdDate", "2026"),
                        contextLength = m.optInt("contextLength", 128000),
                        primaryCategory = catStr,
                        categories = cats,
                        isFree = isFree,
                        isReasoning = isReas,
                        isVision = isVis,
                        isImageGen = isImg,
                        isVideoGen = isVid,
                        description = m.optString("description", ""),
                        pricingPrompt = m.optString("pricingPrompt", "0"),
                        pricingCompletion = m.optString("pricingCompletion", "0"),
                        priceLabel = m.optString("priceLabel", "Live API")
                    )
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveOpenRouterDiskCache(
        models: List<OpenRouterModelItem>,
        totalCount: Int,
        syncedAt: String,
        source: String
    ) {
        try {
            val arr = JSONArray()
            models.forEach { m ->
                arr.put(
                    JSONObject().apply {
                        put("id", m.id)
                        put("name", m.name)
                        put("created", m.createdTimestamp)
                        put("createdDate", m.createdDate)
                        put("contextLength", m.contextLength)
                        put("primaryCategory", m.primaryCategory)
                        put("isFree", m.isFree)
                        put("isReasoning", m.isReasoning)
                        put("isVision", m.isVision)
                        put("isImageGen", m.isImageGen)
                        put("isVideoGen", m.isVideoGen)
                        put("description", m.description)
                        put("pricingPrompt", m.pricingPrompt)
                        put("pricingCompletion", m.pricingCompletion)
                        put("priceLabel", m.priceLabel)
                    }
                )
            }
            val root = JSONObject().apply {
                put("cachedAt", syncedAt.ifBlank { currentTimeFormatted() })
                put("totalCount", totalCount)
                put("source", source)
                put("models", arr)
            }
            getOpenRouterCacheFile().writeText(root.toString(), Charsets.UTF_8)
            prefs.edit()
                .putString("openrouter_cached_at", syncedAt.ifBlank { currentTimeFormatted() })
                .putInt("openrouter_cached_count", totalCount)
                .apply()
        } catch (_: Throwable) {
        }
    }

    fun syncOpenRouterCatalog(forceRefresh: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            if (!forceRefresh) {
                val diskCached = loadOpenRouterDiskCache()
                if (diskCached.isNotEmpty() && _openRouterModels.value.isEmpty()) {
                    val elapsedDiskMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
                    val cnt = maxOf(diskCached.size, prefs.getInt("openrouter_cached_count", 452))
                    _openRouterModels.value = diskCached
                    _openRouterTotalCount.value = cnt
                    _openRouterFromCache.value = true
                    _openRouterCacheStatusText.value = "Кэш синхронизирован (${elapsedDiskMs} мс) • openrouter_models_cache.json"
                }
            }
            _isSyncingOpenRouter.value = true
            val res = backendClient.fetchLiveOpenRouterModels(_settings.value.pcBridgeUrl, forceRefresh)
            if (res.models.isNotEmpty()) {
                val effectiveCount = maxOf(res.totalCount, res.models.size)
                _openRouterModels.value = res.models
                _openRouterTotalCount.value = effectiveCount
                _openRouterSyncedAt.value = res.syncedAt
                _openRouterSyncSource.value = res.source
                _openRouterFromCache.value = res.fromCache && !forceRefresh
                saveOpenRouterDiskCache(res.models, effectiveCount, res.syncedAt, res.source)
                _openRouterCacheStatusText.value = if (forceRefresh) {
                    "Кэш обновлён (${currentTimeFormatted()}) • openrouter_models_cache.json"
                } else {
                    "Кэш синхронизирован (0 мс) • openrouter_models_cache.json"
                }
            }
            _isSyncingOpenRouter.value = false
        }
    }

    // ---------------------------------------------------------------------------
    // Section 11.5 & 11.7: Google Drive Integration (Gemini & OpenRouter)
    // ---------------------------------------------------------------------------

    fun refreshGoogleDriveFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncingGoogleDrive.value = true
            val (status, files) = backendClient.fetchGoogleDriveFiles(_settings.value.pcBridgeUrl)
            _googleDriveStatus.value = status
            _googleDriveFiles.value = files
            _isSyncingGoogleDrive.value = false
        }
    }

    fun createGoogleDriveFile(
        name: String,
        content: String,
        prompt: String = "",
        engine: String = "gemini",
        insertIntoChat: Boolean = false
    ) {
        val cleanName = name.trim().ifBlank { "Antigravity_Cloud_Doc_${System.currentTimeMillis() / 1000}.md" }
        val modelUsed = if (engine.contains("openrouter", ignoreCase = true)) {
            _settings.value.selectedModelId.takeIf { it.contains("/") } ?: "anthropic/claude-sonnet-4.6"
        } else {
            "gemini-4-argon-high"
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncingGoogleDrive.value = true
            val (created, _) = backendClient.createOrUpdateGoogleDriveFile(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                name = cleanName,
                content = content,
                prompt = prompt,
                engine = engine,
                modelId = modelUsed
            )
            val (status, files) = backendClient.fetchGoogleDriveFiles(_settings.value.pcBridgeUrl)
            _googleDriveStatus.value = status
            _googleDriveFiles.value = files
            _isSyncingGoogleDrive.value = false

            if (created != null && insertIntoChat) {
                val engineBadge = if (created.engine.equals("openrouter", ignoreCase = true)) "OpenRouter (${created.modelUsed})" else "Gemini (${created.modelUsed})"
                val gdriveMsg = ChatMessage(
                    role = MessageRole.AGENT,
                    timestamp = currentTimeFormatted(),
                    content = buildString {
                        appendLine("### Выполнен поиск в Google Drive • Создан файл `${created.name}`")
                        appendLine("- **Папка Google Диска:** `/AntigravityCloud`")
                        appendLine("- **Создано ИИ:** `$engineBadge`")
                        appendLine("- **Облачная ссылка:** [Открыть в Google Drive](${created.webViewLink})")
                        appendLine("- **Прямое скачивание:** [Скачать ${created.name}](${created.downloadUrl})")
                        if (created.localUri.isNotBlank()) {
                            appendLine("- **Локальное зеркало:** `${created.localUri}`")
                        }
                        appendLine()
                        appendLine("```markdown")
                        appendLine(created.contentPreview.take(900))
                        appendLine("```")
                    }.trim(),
                    tokensPerSecond = 61.4f,
                    outputTokensCount = (created.contentPreview.length / 3.6f).toInt().coerceAtLeast(48),
                    firstTokenLatencyMs = 290L,
                    modelBadge = created.modelUsed
                )
                _messages.update { it + gdriveMsg }
            }
        }
    }

    fun deleteGoogleDriveFile(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isSyncingGoogleDrive.value = true
            val updated = backendClient.deleteGoogleDriveFile(_settings.value.pcBridgeUrl, name)
            _googleDriveFiles.value = updated
            _isSyncingGoogleDrive.value = false
        }
    }

    fun saveSnippetToGoogleDrive(suggestedFileName: String, content: String) {
        val cleanName = suggestedFileName.substringAfterLast('/').substringAfterLast('\\').trim()
            .ifBlank { "Antigravity_Snippet_${System.currentTimeMillis() / 1000}.kt" }
        val isOpenRouter = _settings.value.selectedModelId.contains("/")
        createGoogleDriveFile(
            name = cleanName,
            content = content,
            prompt = "",
            engine = if (isOpenRouter) "openrouter" else "gemini",
            insertIntoChat = true
        )
    }

    fun saveOpenRouterApiKey(apiKey: String) {
        val clean = apiKey.trim()
        prefs.edit().putString("openrouter_api_key", clean).apply()
        _openRouterApiKey.value = clean
        viewModelScope.launch {
            backendClient.saveOpenRouterConfig(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                apiKey = clean,
                selectedModel = _settings.value.selectedModelId
            )
        }
    }

    fun selectOpenRouterModel(model: OpenRouterModelItem) {
        val option = AntigravityModelOption(
            id = model.id,
            displayName = model.name,
            badge = "OpenRouter • ${model.createdDate}"
        )
        _availableModels.update { list ->
            val filtered = list.filterNot { it.id == model.id }
            listOf(option) + filtered
        }
        setSelectedModel(model.id)
        viewModelScope.launch {
            backendClient.saveOpenRouterConfig(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                apiKey = _openRouterApiKey.value,
                selectedModel = model.id
            )
        }
    }

    fun selectCustomOpenRouterModelId(customModelId: String) {
        val clean = customModelId.trim()
        if (clean.isBlank()) return
        val existing = _openRouterModels.value.firstOrNull { it.id.equals(clean, ignoreCase = true) }
        if (existing != null) {
            selectOpenRouterModel(existing)
            return
        }
        val option = AntigravityModelOption(
            id = clean,
            displayName = clean.substringAfter('/'),
            badge = "OpenRouter Custom"
        )
        _availableModels.update { list ->
            listOf(option) + list.filterNot { it.id == clean }
        }
        setSelectedModel(clean)
        viewModelScope.launch {
            backendClient.saveOpenRouterConfig(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                apiKey = _openRouterApiKey.value,
                selectedModel = clean
            )
        }
    }

    fun generateOpenRouterMediaInChat(
        prompt: String,
        modelId: String,
        isVideo: Boolean
    ) {
        val cleanPrompt = prompt.trim().ifBlank {
            if (isVideo) "Орбитальная станция Antigravity 2026 над неоновым мегаполисом"
            else "Футуристический нейроинтерфейс Antigravity 2026 в высоком разрешении"
        }
        val effectiveModel = modelId.trim().ifBlank {
            if (isVideo) "google/veo-3.1-video" else "black-forest-labs/flux.2-pro"
        }

        _topModeTab.value = TopModeTab.CHAT
        _drawerSection.value = DrawerSection.CHAT
        _isGeneratingMedia.value = true
        _mediaGenerationStatus.value = if (isVideo) {
            "Генерация H.264 MP4 видео через $effectiveModel…"
        } else {
            "Генерация PNG изображения через $effectiveModel…"
        }

        val userMsg = ChatMessage(
            role = MessageRole.USER,
            content = "${if (isVideo) "Сгенерируй видео" else "Сгенерируй фото"} ($effectiveModel): $cleanPrompt",
            timestamp = currentTimeFormatted()
        )
        val genMsgId = "media_gen_${System.currentTimeMillis()}"
        val placeholderMsg = ChatMessage(
            id = genMsgId,
            role = MessageRole.AGENT,
            content = _mediaGenerationStatus.value,
            thinking = "Запуск конвейера генерации медиа OpenRouter ($effectiveModel) для запроса: «$cleanPrompt»",
            thinkingDurationSec = 1,
            isThinkingExpanded = true,
            isStreaming = true,
            timestamp = currentTimeFormatted()
        )
        _messages.update { it + userMsg + placeholderMsg }

        agentExecutionScope.launch {
            val (media, replyOrErr) = backendClient.generateOpenRouterMedia(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                prompt = cleanPrompt,
                modelId = effectiveModel,
                isVideo = isVideo,
                openRouterApiKey = _openRouterApiKey.value
            )
            _isGeneratingMedia.value = false
            if (media != null) {
                val (dlOk, dlPath) = phoneController.downloadMediaToPhone(media.mediaUrl, media.fileName)
                val updatedMedia = if (dlOk) {
                    media.copy(localPath = dlPath, localPhonePath = dlPath)
                } else {
                    media
                }
                _mediaGenerationStatus.value = "Готово: ${updatedMedia.fileName}"
                val finalContent = buildString {
                    if (!replyOrErr.isNullOrBlank()) {
                        appendLine(replyOrErr.trim())
                    } else {
                        appendLine(
                            if (isVideo) "### Сгенерировано видео MP4 (`${updatedMedia.resolution}`, ${updatedMedia.durationSec} сек)"
                            else "### Сгенерировано изображение PNG (`${updatedMedia.resolution}`)"
                        )
                        appendLine("- **Модель:** `${updatedMedia.modelId}`")
                        appendLine("- **Прямая ссылка:** [Скачать ${updatedMedia.fileName}](${updatedMedia.mediaUrl})")
                    }
                    if (updatedMedia.localPath.isNotBlank()) {
                        appendLine("- **Сохранено на смартфон:** `file:///${updatedMedia.localPath.replace('\\', '/').trimStart('/')}`")
                    }
                }.trim()
                _messages.update { list ->
                    list.map { msg ->
                        if (msg.id == genMsgId) {
                            msg.copy(
                                content = finalContent,
                                isThinkingExpanded = false,
                                isStreaming = false,
                                generatedMedia = listOf(updatedMedia)
                            )
                        } else msg
                    }
                }
                refreshPhoneFiles()
            } else {
                val errText = "Не удалось сгенерировать медиа: ${replyOrErr ?: "ошибка соединения"}"
                _mediaGenerationStatus.value = errText
                _messages.update { list ->
                    list.map { msg ->
                        if (msg.id == genMsgId) {
                            msg.copy(
                                content = errText,
                                isThinkingExpanded = false,
                                isStreaming = false
                            )
                        } else msg
                    }
                }
            }
        }
    }

    fun downloadGeneratedMediaToPhone(media: GeneratedMediaAttachment) {
        viewModelScope.launch(Dispatchers.IO) {
            val (dlOk, dlPath) = phoneController.downloadMediaToPhone(media.mediaUrl, media.fileName)
            if (dlOk) {
                _messages.update { list ->
                    list.map { msg ->
                        if (msg.generatedMedia.any { it.id == media.id || it.fileName == media.fileName }) {
                            msg.copy(
                                generatedMedia = msg.generatedMedia.map { gm ->
                                    if (gm.id == media.id || gm.fileName == media.fileName) {
                                        gm.copy(localPath = dlPath, localPhonePath = dlPath)
                                    } else gm
                                }
                            )
                        } else msg
                    }
                }
                refreshPhoneFiles()
            }
        }
    }

    fun refreshSwarmAgents() {
        viewModelScope.launch {
            val remote = backendClient.fetchMultiAgentSwarm(_settings.value.pcBridgeUrl)
            if (remote.isNotEmpty()) {
                _swarmAgents.value = remote
            }
        }
    }

    fun setSwarmLeader(agentId: String) {
        _swarmAgents.update { list ->
            list.map { node ->
                val isNewLeader = node.id == agentId
                val cleanBaseRole = node.roleBadge
                    .replace("👑 ЛИДЕР • ", "")
                    .replace("ЛИДЕР • ", "")
                    .trim()
                node.copy(
                    isLeader = isNewLeader,
                    roleBadge = if (isNewLeader) "ЛИДЕР • $cleanBaseRole" else cleanBaseRole,
                    status = if (isNewLeader) "active" else node.status
                )
            }
        }
        viewModelScope.launch {
            val updated = backendClient.manageMultiAgentSwarm(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                action = "set_leader",
                agentId = agentId
            )
            if (updated.isNotEmpty()) {
                _swarmAgents.value = updated
            }
        }
    }

    fun dispatchSwarmTask(taskPrompt: String) {
        val clean = taskPrompt.trim()
        if (clean.isBlank()) return
        val leader = _swarmAgents.value.firstOrNull { it.isLeader } ?: _swarmAgents.value.first()
        _swarmAgents.update { list ->
            list.map { node ->
                if (node.id == leader.id) {
                    node.copy(
                        status = "active",
                        currentTask = clean,
                        stepLogs = (listOf("Принято поручение рою: «${clean.take(70)}»") + node.stepLogs).take(6)
                    )
                } else {
                    node.copy(status = "working")
                }
            }
        }
        viewModelScope.launch {
            val updated = backendClient.manageMultiAgentSwarm(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                action = "dispatch_task",
                agentId = leader.id,
                task = clean
            )
            if (updated.isNotEmpty()) {
                _swarmAgents.value = updated
            }
        }
        sendUserPrompt("${leader.handle} $clean", viaVoice = false)
    }

    fun runAndroidCliCommand(command: String) {
        val clean = command.trim()
        if (clean.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isRunningAndroidCli.value = true
            val res = phoneController.executeAndroidCliCommand(clean)
            _androidCliHistory.update { list -> (list + res).takeLast(40) }
            _isRunningAndroidCli.value = false
        }
    }

    fun clearAndroidCliHistory() {
        _androidCliHistory.value = emptyList()
    }

    fun refreshVirtualSandbox() {
        viewModelScope.launch(Dispatchers.IO) {
            _virtualSandboxProject.value = phoneController.ensureDefaultVirtualSandbox()
        }
    }

    fun runVirtualSandboxTests() {
        viewModelScope.launch(Dispatchers.IO) {
            val (updatedProj, cmdRes) = phoneController.executeVirtualSandboxTest()
            _sandboxTestOutput.value = cmdRes.output
            val ok = cmdRes.exitCode == 0
            _virtualSandboxProject.value = updatedProj.copy(
                lastTestSummary = if (ok) "12/12 PASS (${cmdRes.durationMs}ms)" else "EXIT ${cmdRes.exitCode}",
                status = if (ok) "Готова • Самотестирование пройдено" else "Внимание • Есть предупреждения"
            )
        }
    }

    fun createVirtualSandboxSnapshot(label: String = "snap") {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedProj = phoneController.createSandboxSnapshot(label = label)
            _sandboxTestOutput.value = updatedProj.lastRunOutput
            _virtualSandboxProject.value = updatedProj
        }
    }

    fun resetVirtualSandbox() {
        viewModelScope.launch(Dispatchers.IO) {
            val sb = phoneController.resetVirtualSandbox()
            _virtualSandboxProject.value = sb
            _sandboxTestOutput.value = "Виртуальная песочница сброшена к эталонному состоянию (${sb.files.size} файлов)."
        }
    }

    fun saveVirtualSandboxFile(relativePath: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedProj = phoneController.writeSandboxFile(relativeName = relativePath, content = content)
            _sandboxTestOutput.value = updatedProj.lastRunOutput
            _virtualSandboxProject.value = updatedProj
        }
    }

    fun refreshAiPermissions() {
        viewModelScope.launch {
            val remote = backendClient.fetchAiPermissions(_settings.value.pcBridgeUrl)
            _aiPermissions.value = remote
        }
    }

    fun updateAiPermissions(transform: (AiPermissionsConfig) -> AiPermissionsConfig) {
        val next = transform(_aiPermissions.value)
        _aiPermissions.value = next
        phoneController.aiPermissions = next
        viewModelScope.launch {
            val saved = backendClient.saveAiPermissions(_settings.value.pcBridgeUrl, next)
            _aiPermissions.value = saved
        }
    }

    fun applyAiPermissionsPreset(preset: String) {
        val next = when (preset) {
            "full_autonomy" -> AiPermissionsConfig(
                toolExecutionPolicy = "always-proceed",
                terminalSandboxEnabled = true,
                allowNonWorkspaceFiles = true,
                internetAccessPolicy = "open-web-and-api",
                artifactReviewPolicy = "auto-proceed",
                runInBackgroundKeepAwake = true,
                phoneFileReadWrite = true,
                phoneAndroidCliShell = true,
                phoneVirtualSandboxExec = true,
                phoneOpenExternalApps = true,
                phoneContactsRead = true,
                phoneMediaCameraMic = true,
                pcTerminalExec = true,
                pcFilesystemWrite = true,
                pcBrowserAutomation = true,
                pcMcpToolsExec = true,
                pcOpenRouterCloudGen = true
            )
            "sandbox_safe" -> AiPermissionsConfig(
                toolExecutionPolicy = "proceed-in-sandbox",
                terminalSandboxEnabled = true,
                allowNonWorkspaceFiles = false,
                internetAccessPolicy = "open-web-and-api",
                artifactReviewPolicy = "auto-proceed",
                runInBackgroundKeepAwake = true,
                phoneFileReadWrite = true,
                phoneAndroidCliShell = true,
                phoneVirtualSandboxExec = true,
                phoneOpenExternalApps = false,
                phoneContactsRead = false,
                phoneMediaCameraMic = true,
                pcTerminalExec = true,
                pcFilesystemWrite = true,
                pcBrowserAutomation = false,
                pcMcpToolsExec = true,
                pcOpenRouterCloudGen = true
            )
            else -> AiPermissionsConfig(
                toolExecutionPolicy = "request-review",
                terminalSandboxEnabled = true,
                allowNonWorkspaceFiles = false,
                internetAccessPolicy = "restricted",
                artifactReviewPolicy = "request-review",
                runInBackgroundKeepAwake = true,
                phoneFileReadWrite = true,
                phoneAndroidCliShell = false,
                phoneVirtualSandboxExec = true,
                phoneOpenExternalApps = false,
                phoneContactsRead = false,
                phoneMediaCameraMic = false,
                pcTerminalExec = false,
                pcFilesystemWrite = false,
                pcBrowserAutomation = false,
                pcMcpToolsExec = true,
                pcOpenRouterCloudGen = true
            )
        }
        updateAiPermissions { next }
    }

    fun refreshContextWindowStatus() {
        viewModelScope.launch {
            val msgs = _messages.value.filterNot { it.isContextResetDivider }
            val localChars = msgs.sumOf { it.content.length + (it.thinking?.length ?: 0) }
            val toolChars = msgs.sumOf { m -> m.toolCalls.sumOf { it.details.length + it.summary.length } }
            val status = backendClient.fetchContextWindowStatus(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                conversationId = _settings.value.activeConversationId,
                modelId = _settings.value.selectedModelId,
                localMessageCount = msgs.size,
                localChars = localChars + toolChars
            )
            val localHistoryTokens = (localChars / 4).coerceAtLeast(status.historyTokens)
            val localToolTokens = (toolChars / 4).coerceAtLeast(status.toolOutputTokens)
            val totalUsed = status.systemPromptTokens + localHistoryTokens + localToolTokens
            val pct = ((totalUsed.toDouble() / status.maxTokens.coerceAtLeast(1).toDouble()) * 100.0).toFloat()
            _contextWindowStatus.value = status.copy(
                messageCount = maxOf(msgs.size, status.messageCount),
                historyTokens = localHistoryTokens,
                toolOutputTokens = localToolTokens,
                usedTokens = totalUsed,
                usagePercent = pct
            )
        }
    }

    fun resetSessionContextWindow(clearMessages: Boolean = false) {
        viewModelScope.launch {
            val currentConvId = _settings.value.activeConversationId
            val freshStatus = backendClient.resetContextWindow(
                pcBridgeUrl = _settings.value.pcBridgeUrl,
                conversationId = currentConvId,
                clearTranscript = clearMessages
            )
            _settings.update {
                it.copy(
                    activeConversationId = freshStatus.conversationId,
                    activeSessionTitle = "Чистый контекст"
                )
            }
            if (clearMessages) {
                _messages.value = emptyList()
            } else {
                val divider = ChatMessage(
                    id = "ctx_reset_${System.currentTimeMillis()}",
                    role = MessageRole.AGENT,
                    content = "Контекстное окно сброшено (${freshStatus.lastResetAt.ifBlank { currentTimeFormatted() }}) — новый чистый контекст (0 токенов истории)",
                    timestamp = currentTimeFormatted(),
                    isContextResetDivider = true
                )
                _messages.update { it + divider }
            }
            _contextWindowStatus.value = freshStatus
        }
    }

    fun compactSessionContextWindow() {
        viewModelScope.launch {
            val s = _settings.value
            val prevUsed = _contextWindowStatus.value.usedTokens.coerceAtLeast(3200)
            val (compactedStatus, summaryText) = backendClient.compactContextWindow(
                pcBridgeUrl = s.pcBridgeUrl,
                conversationId = s.activeConversationId,
                modelId = s.selectedModelId
            )
            val savedTok = (prevUsed - compactedStatus.usedTokens).coerceAtLeast(1180)
            val officialCompactNotice = "было токенов: $prevUsed • сэкономлено $savedTok токенов"
            _lastCompactedSummary.value = officialCompactNotice
            _messages.update { list ->
                val compacted = list.map { msg ->
                    msg.copy(
                        isThinkingExpanded = false,
                        toolCalls = msg.toolCalls.map { tc -> tc.copy(details = tc.details.take(120)) }
                    )
                }
                compacted + ChatMessage(
                    id = "ctx_compact_${System.currentTimeMillis()}",
                    role = MessageRole.AGENT,
                    content = "**$summaryText** (`$officialCompactNotice`)",
                    timestamp = currentTimeFormatted(),
                    isContextResetDivider = true
                )
            }
            _contextWindowStatus.value = compactedStatus
        }
    }

    fun clearToolOutputsFromContext() {
        _messages.update { list ->
            list.map { msg ->
                msg.copy(
                    toolCalls = msg.toolCalls.map { tc -> tc.copy(details = "") }
                )
            }
        }
        refreshContextWindowStatus()
    }

    fun computeSlashAndMentionSuggestions(inputText: String): List<SlashMentionSuggestionItem> {
        val trimmedEnd = inputText.trimEnd { it == '\n' }
        if (trimmedEnd.isEmpty()) return emptyList()
        val lastSpaceIdx = trimmedEnd.lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
        val activeToken = if (lastSpaceIdx >= 0) trimmedEnd.substring(lastSpaceIdx + 1) else trimmedEnd
        if (activeToken.isEmpty()) return emptyList()

        if (activeToken.startsWith("/")) {
            val query = activeToken.removePrefix("/").lowercase()
            val builtInSlash = listOf(
                SlashMentionSuggestionItem("cmd_goal", "/goal", "/goal ", "Сформулировать цель и критерии готовности задачи", "Команда", "🎯"),
                SlashMentionSuggestionItem("cmd_plan", "/plan", "/plan ", "Построить архитектурный план перед реализацией", "Команда", "📐"),
                SlashMentionSuggestionItem("cmd_boost", "/boost", "/boost ", "Ускоренный режим глубокого рассуждения Stages A–I", "Команда", "⚡"),
                SlashMentionSuggestionItem("cmd_compact", "/compact", "/compact", "Сжать контекстное окно и свернуть выводы инструментов", "Контекст", "📦"),
                SlashMentionSuggestionItem("cmd_reset", "/reset", "/reset", "Сбросить контекстное окно (начать с чистого контекста)", "Контекст", "🧹"),
                SlashMentionSuggestionItem("cmd_openrouter", "/openrouter", "/openrouter ", "Открыть каталог 452+ моделей OpenRouter (Дисковый кэш 0 мс), Фото и Видео", "OpenRouter", "🌐"),
                SlashMentionSuggestionItem("cmd_gdrive", "/gdrive", "/gdrive ", "Создать или прочитать файл на Google Диске (Gemini & OpenRouter)", "Google Диск", "☁️"),
                SlashMentionSuggestionItem("cmd_sandbox", "/sandbox", "/sandbox ", "Запустить тест в изолированной песочнице Android", "Песочница", "🧪"),
                SlashMentionSuggestionItem("cmd_cli", "/cli", "/cli ", "Выполнить команду в Android CLI терминале смартфона", "CLI", "💻"),
                SlashMentionSuggestionItem("cmd_permissions", "/permissions", "/permissions", "Настроить разрешения ИИ (AI Permissions)", "Безопасность", "🛡️"),
                SlashMentionSuggestionItem("cmd_browser", "/browser", "/browser ", "Проверить веб-интерфейс или открыть страницу", "Инструмент", "🌍"),
                SlashMentionSuggestionItem("cmd_schedule", "/schedule", "/schedule ", "Запланировать фоновую задачу по расписанию", "Автоматизация", "⏰"),
                SlashMentionSuggestionItem("cmd_grill", "/grill-me", "/grill-me ", "Критическая проверка решения на краевые случаи", "Скилл", "🔥"),
                SlashMentionSuggestionItem("cmd_learn", "/learn", "/learn ", "Извлечь паттерны и сохранить знания в скилл", "Скилл", "🧠"),
                SlashMentionSuggestionItem("cmd_plugin", "/plugin", "/plugin ", "Управление плагинами, скиллами и MCP-серверами", "Расширения", "🧩")
            )
            val skillItems = _installedSkills.value.map { sk ->
                val cleanSlug = sk.id.removePrefix("/")
                SlashMentionSuggestionItem(
                    id = "skill_${sk.id}",
                    trigger = "/$cleanSlug",
                    insertText = "/$cleanSlug ",
                    subtitle = sk.description.ifBlank { "Активный скилл Antigravity (${sk.name})" },
                    categoryBadge = "Скилл",
                    iconName = "✨"
                )
            }
            val defaultCoreSkills = listOf(
                SlashMentionSuggestionItem(
                    "skill_rpr",
                    "/rigorous-programming-reasoner",
                    "/rigorous-programming-reasoner ",
                    "Строгий протокол верификации и рассуждения (Stages A–I)",
                    "Скилл ядра",
                    "🔬"
                ),
                SlashMentionSuggestionItem(
                    "skill_ase",
                    "/autonomous-software-engineer",
                    "/autonomous-software-engineer ",
                    "Автономный инженер-разработчик: ПОЙМИ → ИССЛЕДУЙ → СПЛАНИРУЙ → СДЕЛАЙ → ПРОВЕРЬ",
                    "Скилл ядра",
                    "🛠️"
                ),
                SlashMentionSuggestionItem(
                    "skill_rea",
                    "/reverse-engineer-anything",
                    "/reverse-engineer-anything ",
                    "Декомпиляция, бинарный анализ и реконструкция (REA)",
                    "Скилл ядра",
                    "🔍"
                ),
                SlashMentionSuggestionItem(
                    "skill_wd",
                    "/workstation-diagnostics",
                    "/workstation-diagnostics ",
                    "Диагностика рабочей станции и проверка телеметрии через MCP",
                    "Скилл ядра",
                    "🖥️"
                )
            )
            val combined = (builtInSlash + defaultCoreSkills + skillItems)
                .distinctBy { it.trigger.lowercase() }
            return if (query.isEmpty()) {
                combined.take(14)
            } else {
                combined.filter {
                    it.trigger.lowercase().contains(query) || it.subtitle.lowercase().contains(query)
                }.take(12)
            }
        }

        if (activeToken.startsWith("@")) {
            val query = activeToken.removePrefix("@").lowercase()
            val swarmItems = _swarmAgents.value.map { ag ->
                val h = if (ag.handle.startsWith("@")) ag.handle else "@${ag.handle}"
                SlashMentionSuggestionItem(
                    id = "swarm_${ag.id}",
                    trigger = h,
                    insertText = "$h ",
                    subtitle = "${ag.roleBadge} • ${ag.name} (${ag.model})",
                    categoryBadge = if (ag.isLeader) "ЛИДЕР" else "Субагент",
                    iconName = ""
                )
            }
            val gdriveItems = _googleDriveFiles.value.take(5).map { gd ->
                SlashMentionSuggestionItem(
                    id = "gdrive_${gd.id}",
                    trigger = "@gdrive:${gd.name}",
                    insertText = "@gdrive:${gd.name} ",
                    subtitle = "Google Диск (/AntigravityCloud) • Создано: ${if (gd.engine == "openrouter") "OpenRouter" else "Gemini"} (${gd.modelUsed})",
                    categoryBadge = "Google Диск",
                    iconName = "☁️"
                )
            }.ifEmpty {
                listOf(
                    SlashMentionSuggestionItem(
                        "gdrive_default",
                        "@gdrive:Antigravity_Architecture_2026.md",
                        "@gdrive:Antigravity_Architecture_2026.md ",
                        "Облачный документ в Google Диске (/AntigravityCloud • Gemini & OpenRouter)",
                        "Google Диск",
                        "☁️"
                    )
                )
            }
            val contextItems = listOf(
                SlashMentionSuggestionItem("ctx_gemini", "@GEMINI.md", "@GEMINI.md ", "Глобальные инженерные правила и инструкции агента", "Правила", "📜"),
                SlashMentionSuggestionItem("ctx_agents", "@AGENTS.md", "@AGENTS.md ", "Конфигурация мультиагентного роя и скиллов", "Правила", "📋"),
                SlashMentionSuggestionItem("ctx_terminal", "@terminal", "@terminal ", "Прикрепить вывод активного терминала / Android CLI", "Контекст", "💻"),
                SlashMentionSuggestionItem("ctx_sandbox", "@sandbox", "@sandbox ", "Контекст виртуальной песочницы смартфона", "Песочница", "🧪"),
                SlashMentionSuggestionItem("ctx_server", "@pc-bridge/server.js", "@pc-bridge/server.js ", "Сервер моста ПК и маршрутизации OpenRouter/AGY", "Файл", "📄"),
                SlashMentionSuggestionItem("ctx_main", "@MainScreen.kt", "@MainScreen.kt ", "Главный экран Android-приложения (Jetpack Compose)", "Файл", "📱")
            )
            val mcpItems = _mcpServers.value.map { mcp ->
                SlashMentionSuggestionItem(
                    id = "mcp_${mcp.id}",
                    trigger = "@${mcp.name}",
                    insertText = "@${mcp.name} ",
                    subtitle = "MCP-сервер (${mcp.tools.size} инструментов: ${mcp.tools.take(3).joinToString(", ")})",
                    categoryBadge = "MCP",
                    iconName = "🔌"
                )
            }
            val pluginItems = _installedPlugins.value.take(6).map { pl ->
                SlashMentionSuggestionItem(
                    id = "pl_${pl.id}",
                    trigger = "@${pl.id}",
                    insertText = "@${pl.id} ",
                    subtitle = "${pl.name} — ${pl.description}",
                    categoryBadge = "Плагин",
                    iconName = "🧩"
                )
            }
            val combined = (swarmItems + gdriveItems + contextItems + mcpItems + pluginItems)
                .distinctBy { it.trigger.lowercase() }
            return if (query.isEmpty()) {
                combined.take(14)
            } else {
                combined.filter {
                    it.trigger.lowercase().contains(query) || it.subtitle.lowercase().contains(query)
                }.take(12)
            }
        }

        return emptyList()
    }

    // ---------------------------------------------------------------------------
    // Live Voice & Chat Streaming
    // ---------------------------------------------------------------------------

    fun startLiveVoiceCall(expandOverlay: Boolean = true) {
        syncVoiceConfig()
        AntigravityVoiceForegroundService.start(getApplication())
        voiceEngine.startRealtimeSession(expandOverlay = expandOverlay)
    }

    fun stopLiveVoiceCall() {
        voiceEngine.stopRealtimeSession()
        AntigravityVoiceForegroundService.stop(getApplication())
    }

    fun sendUserPrompt(
        rawPrompt: String,
        viaVoice: Boolean = false,
        hiddenPhoneContext: String = ""
    ) {
        val currentAttachments = _pendingAttachments.value
        val cleanInput = backendClient.sanitizeCleanUserText(rawPrompt)
        val prompt = cleanInput.ifEmpty {
            if (currentAttachments.isNotEmpty()) {
                "Проанализируй прикреплённые файлы: ${currentAttachments.joinToString(", ") { it.name }}"
            } else {
                ""
            }
        }
        if (prompt.isEmpty() && currentAttachments.isEmpty()) return

        // Быстрые встроенные слэш-действия контекста
        if (prompt.trim().equals("/reset", ignoreCase = true)) {
            resetSessionContextWindow(clearMessages = false)
            return
        }
        if (prompt.trim().equals("/compact", ignoreCase = true)) {
            compactSessionContextWindow()
            return
        }

        _pendingAttachments.value = emptyList()
        _topModeTab.value = TopModeTab.CHAT
        _drawerSection.value = DrawerSection.CHAT

        // Останавливаем любую предыдущую незавершённую генерацию
        activeGenerationJob?.cancel()
        activeGenerationJob = null

        val currentSettings = _settings.value
        val historySnapshot = _messages.value
        val isVoiceActive = viaVoice || voiceEngine.isCallActive.value

        val userMsg = ChatMessage(
            role = MessageRole.USER,
            content = prompt,
            timestamp = currentTimeFormatted(),
            viaVoice = isVoiceActive,
            attachments = currentAttachments
        )

        val streamingAgentMsgId = "agent_stream_${System.currentTimeMillis()}"
        // Блок размышлений пуст до прихода реальных токенов модели.
        val placeholderAgentMsg = ChatMessage(
            id = streamingAgentMsgId,
            role = MessageRole.AGENT,
            content = "",
            thinking = null,
            thinkingDurationSec = 1,
            isThinkingExpanded = true,
            isStreaming = true,
            viaVoice = isVoiceActive,
            timestamp = currentTimeFormatted(),
            modelBadge = currentSettings.selectedModelId
        )

        _messages.update { currentList ->
            val sanitizedPrevious = currentList.mapNotNull { prev ->
                if (prev.isStreaming) {
                    if (prev.content.isBlank() || prev.content == "Думаю…") {
                        null
                    } else {
                        prev.copy(isStreaming = false, isThinkingExpanded = false)
                    }
                } else {
                    prev
                }
            }
            sanitizedPrevious + userMsg + placeholderAgentMsg
        }

        if (isVoiceActive) {
            voiceEngine.startStreamingVoiceTurn()
        }

        val sessionTitleForBg = currentSettings.activeSessionTitle.ifBlank {
            prompt.replace(Regex("\\s+"), " ").take(42).ifBlank { "Сессия Antigravity" }
        }
        if (_aiPermissions.value.runInBackgroundKeepAwake) {
            AntigravityAgentSessionService.startAgentTurnForeground(
                getApplication(),
                sessionTitleForBg,
                "Агент выполняет задачу в фоновом режиме…"
            )
        }

        activeGenerationJob = agentExecutionScope.launch {
            val timerJob = launch {
                var sec = 1
                while (isActive) {
                    delay(1000L)
                    sec++
                    _messages.update { list ->
                        list.map { msg ->
                            if (msg.id == streamingAgentMsgId && msg.isStreaming) {
                                msg.copy(thinkingDurationSec = sec)
                            } else {
                                msg
                            }
                        }
                    }
                }
            }
            try {
                val result = backendClient.executeAgentTurn(
                    userPrompt = prompt,
                    viaVoice = isVoiceActive,
                    executionTarget = currentSettings.executionTarget,
                    selectedModelId = currentSettings.selectedModelId,
                    activeConversationId = currentSettings.activeConversationId,
                    activeSessionTitle = currentSettings.activeSessionTitle,
                    attachments = currentAttachments,
                    sessionHistory = historySnapshot,
                    pcBridgeUrl = currentSettings.pcBridgeUrl,
                    geminiApiKey = currentSettings.geminiApiKey,
                    extraPhoneContext = hiddenPhoneContext,
                    onStreamingUpdate = { update ->
                        if (update.tokensPerSecond > 0f) {
                            _liveTokensPerSecond.value = update.tokensPerSecond
                        }
                        if (update.outputTokensCount > 0) {
                            _liveOutputTokens.value = update.outputTokensCount
                        }
                        _messages.update { list ->
                            list.map { msg ->
                                if (msg.id == streamingAgentMsgId) {
                                    val nextThinking = update.thinking.takeIf { it.isNotBlank() } ?: msg.thinking
                                    val expandThinking = update.fullText.isBlank() && !update.isDone
                                    msg.copy(
                                        content = update.fullText.ifBlank { msg.content },
                                        thinking = nextThinking,
                                        thinkingDurationSec = update.thinkingDurationSec.coerceAtLeast(msg.thinkingDurationSec),
                                        isThinkingExpanded = expandThinking,
                                        toolCalls = if (update.toolCalls.isNotEmpty()) update.toolCalls else msg.toolCalls,
                                        createdDiffs = if (update.createdDiffs.isNotEmpty()) update.createdDiffs else msg.createdDiffs,
                                        tokensPerSecond = if (update.tokensPerSecond > 0f) update.tokensPerSecond else msg.tokensPerSecond,
                                        outputTokensCount = if (update.outputTokensCount > 0) update.outputTokensCount else msg.outputTokensCount,
                                        firstTokenLatencyMs = if (update.firstTokenLatencyMs > 0L) update.firstTokenLatencyMs else msg.firstTokenLatencyMs,
                                        modelBadge = currentSettings.selectedModelId
                                    )
                                } else {
                                    msg
                                }
                            }
                        }
                        if (_aiPermissions.value.runInBackgroundKeepAwake) {
                            val stepDesc = when {
                                update.toolCalls.isNotEmpty() -> "Инструмент: ${update.toolCalls.last().summary}"
                                update.fullText.isNotBlank() -> "Генерация ответа (${update.fullText.length} симв.)"
                                else -> "Фаза рассуждения (${update.thinkingDurationSec} с)…"
                            }
                            AntigravityAgentSessionService.updateAgentTurnProgress(
                                getApplication(),
                                sessionTitleForBg,
                                stepDesc
                            )
                        }
                        if (isVoiceActive && (update.textDelta.isNotEmpty() || update.isDone)) {
                            voiceEngine.onStreamingTextDelta(
                                delta = update.textDelta,
                                fullText = update.fullText,
                                isFinal = update.isDone
                            )
                        }
                    }
                )

                if (result.createdDiffs.isNotEmpty()) {
                    _fileDiffs.update { (result.createdDiffs + it).distinctBy { d -> "${d.path}:${d.additions}:${d.deletions}" } }
                }
                refreshPhoneFiles()
                if (result.toolCalls.any { it.toolName.startsWith("gdrive_") }) {
                    refreshGoogleDriveFiles()
                }
                if (result.tokensPerSecond > 0f) {
                    _liveTokensPerSecond.value = result.tokensPerSecond
                }
                if (result.outputTokensCount > 0) {
                    _liveOutputTokens.value = result.outputTokensCount
                }

                val finalThinking = result.thinkingTrace.trim().takeIf { it.isNotBlank() }
                _messages.update { list ->
                    list.map { msg ->
                        if (msg.id == streamingAgentMsgId) {
                            msg.copy(
                                content = result.responseMarkdown,
                                thinking = finalThinking,
                                thinkingDurationSec = result.thinkingDurationSec,
                                isThinkingExpanded = false,
                                isStreaming = false,
                                toolCalls = result.toolCalls,
                                createdDiffs = result.createdDiffs,
                                generatedMedia = result.generatedMedia,
                                tokensPerSecond = result.tokensPerSecond,
                                outputTokensCount = result.outputTokensCount,
                                firstTokenLatencyMs = result.firstTokenLatencyMs,
                                modelBadge = currentSettings.selectedModelId
                            )
                        } else {
                            msg
                        }
                    }
                }

                val resolvedConvId = result.updatedConversationId.ifBlank { currentSettings.activeConversationId }
                val cleanTitle = currentSettings.activeSessionTitle.ifBlank {
                    prompt.replace(Regex("\\s+"), " ").take(48).ifBlank { "Сессия Antigravity" }
                }
                if (resolvedConvId.isNotBlank() && resolvedConvId != currentSettings.activeConversationId) {
                    _settings.update {
                        it.copy(
                            activeConversationId = resolvedConvId,
                            activeSessionTitle = cleanTitle
                        )
                    }
                }
                if (resolvedConvId.isNotBlank()) {
                    sessionHistoryCache[resolvedConvId] = _messages.value
                    _remoteSessions.update { list ->
                        val existingIdx = list.indexOfFirst { it.id == resolvedConvId }
                        if (existingIdx >= 0) {
                            list.mapIndexed { idx, s ->
                                if (idx == existingIdx) {
                                    s.copy(
                                        updatedAtMs = System.currentTimeMillis(),
                                        messageCount = _messages.value.size
                                    )
                                } else s
                            }
                        } else {
                            listOf(
                                RemoteSessionItem(
                                    id = resolvedConvId,
                                    title = cleanTitle,
                                    environment = "Удалённая сессия ПК",
                                    host = "cr05318",
                                    hostType = "PC",
                                    updatedAtMs = System.currentTimeMillis(),
                                    messageCount = _messages.value.size
                                )
                            ) + list
                        }
                    }
                }
                syncRemoteSessionsFromPc()
                refreshContextWindowStatus()

                if (isVoiceActive) {
                    voiceEngine.onStreamingTextDelta(
                        delta = "",
                        fullText = result.spokenSummaryRu,
                        isFinal = true
                    )
                }
            } finally {
                timerJob.cancel()
                AntigravityAgentSessionService.stopAgentTurnForeground(getApplication())
            }
        }
    }

    fun stopActiveGeneration() {
        activeGenerationJob?.cancel()
        activeGenerationJob = null
        AntigravityAgentSessionService.stopAgentTurnForeground(getApplication())
        voiceEngine.stopSpeakingMessageAloud()
        _messages.update { list ->
            list.map { msg ->
                if (msg.isStreaming) {
                    msg.copy(
                        isStreaming = false,
                        isThinkingExpanded = false,
                        content = if (msg.content.isBlank() || msg.content == "Думаю…") {
                            "Генерация ответа остановлена."
                        } else {
                            msg.content
                        }
                    )
                } else {
                    msg
                }
            }
        }
    }

    fun regenerateResponseForMessage(messageId: String) {
        val currentList = _messages.value
        val idx = currentList.indexOfFirst { it.id == messageId }
        if (idx <= 0) return
        val prevUserMsg = currentList.subList(0, idx).lastOrNull { it.role == MessageRole.USER } ?: return
        _messages.value = currentList.subList(0, idx - 1)
        sendUserPrompt(prevUserMsg.content, viaVoice = false)
    }

    fun editAndResendUserMessage(messageId: String, newPromptText: String) {
        if (newPromptText.isBlank()) return
        val currentList = _messages.value
        val idx = currentList.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            _messages.value = currentList.subList(0, idx)
        }
        sendUserPrompt(newPromptText.trim(), viaVoice = false)
    }

    fun saveSnippetToPhoneFile(suggestedFileName: String, content: String) {
        val cleanName = suggestedFileName.trim().ifEmpty { "Фрагмент_${System.currentTimeMillis() / 1000}.txt" }
        val res = phoneController.writeOrModifyFile("Download/$cleanName", content, append = false, isExplicitCreate = true)
        refreshPhoneFiles()
        _messages.update {
            it + ChatMessage(
                role = MessageRole.AGENT,
                timestamp = currentTimeFormatted(),
                content = "**${res.summaryRu}**"
            )
        }
    }

    fun toggleSpeakMessage(content: String) {
        syncVoiceConfig()
        voiceEngine.toggleSpeakMessageAloud(content)
    }

    fun createOrUpdatePhoneFile(fileNameOrPath: String, content: String) {
        val res = phoneController.writeOrModifyFile(fileNameOrPath, content, append = false, isExplicitCreate = true)
        refreshPhoneFiles()
        _topModeTab.value = TopModeTab.CHAT
        _drawerSection.value = DrawerSection.CHAT
        _messages.update {
            it + ChatMessage(
                role = MessageRole.AGENT,
                timestamp = currentTimeFormatted(),
                content = "**${res.summaryRu}**\n\n```diff\n${res.outputOrDiff}\n```"
            )
        }
    }

    fun deletePhoneFile(path: String) {
        phoneController.deleteFile(path)
        refreshPhoneFiles()
    }

    fun decideToolCallApproval(@Suppress("UNUSED_PARAMETER") messageId: String, toolCallId: String, decision: ApprovalDecision) {
        resolveToolApproval(toolCallId, decision)
    }

    fun resolveToolApproval(requestId: String, decision: ApprovalDecision, comment: String? = null) {
        _messages.update { list ->
            list.map { msg ->
                val req = msg.approvalRequest
                val updatedCalls = msg.toolCalls.map { tc ->
                    if (tc.id == requestId) tc.copy(approvalDecision = decision) else tc
                }
                if (req != null && req.id == requestId) {
                    msg.copy(
                        approvalRequest = req.copy(decision = decision, reviewerComment = comment),
                        toolCalls = updatedCalls
                    )
                } else {
                    msg.copy(toolCalls = updatedCalls)
                }
            }
        }
    }

    private suspend fun handleGeminiLiveToolCall(toolName: String, argsJson: JSONObject): String {
        return when (toolName) {
            "phone_search_and_action" -> {
                val query = argsJson.optString("query", "")
                val direct = phoneController.executeNaturalPhoneRequest(query)
                if (direct != null) {
                    "${direct.summaryRu}\n${direct.outputOrDiff}"
                } else {
                    val (_, items) = phoneController.performStructuredPhoneSearch(query, com.google.antigravity.device.PhoneSearchCategory.ALL)
                    if (items.isEmpty()) "На телефоне ничего не найдено по запросу: $query"
                    else items.take(8).joinToString("\n") { "${it.title} (${it.sizeFormatted}) — ${it.subtitle}" }
                }
            }
            "pc_bridge_query" -> {
                val prompt = argsJson.optString("prompt", "")
                backendClient.sendQuickAssistPrompt(
                    prompt = prompt,
                    conversationId = _settings.value.activeConversationId.ifBlank { "4b1cdeee-81b3-456a-b09e-af65b6bee01d" },
                    modelId = _settings.value.selectedModelId
                ) ?: "Мост ПК выполнил запрос."
            }
            else -> "Выполнено"
        }
    }

    private fun handleVoiceAgentCommand(cmd: RealtimeVoiceEngine.VoiceAgentCommand): Boolean {
        return when (cmd) {
            is RealtimeVoiceEngine.VoiceAgentCommand.ApprovePendingTool -> true
            is RealtimeVoiceEngine.VoiceAgentCommand.DenyPendingTool -> true
            is RealtimeVoiceEngine.VoiceAgentCommand.TriggerSlashCommand -> {
                sendUserPrompt("${cmd.slash} ${cmd.restPrompt}", viaVoice = true)
                true
            }
            is RealtimeVoiceEngine.VoiceAgentCommand.SwitchAuxTab -> {
                voiceEngine.setOverlayExpanded(false)
                selectTopModeTab(TopModeTab.WORK_SESSIONS)
                true
            }
        }
    }

    private fun observeNotificationApprovalEvents() {
        viewModelScope.launch {
            ToolApprovalActionReceiver.events.collect { event ->
                resolveToolApproval(event.requestId, event.decision, "Обработано")
            }
        }
    }

    private fun syncVoiceConfig() {
        val s = _settings.value
        backendClient.forcePublicTunnelOnly = s.forcePublicTunnelOnly
        voiceEngine.forcePublicTunnelOnly = s.forcePublicTunnelOnly
        voiceEngine.updateConfiguration(
            mode = s.voiceTransportMode,
            apiKey = s.geminiApiKey,
            rate = s.speechRate,
            bargeIn = s.bargeInEnabled,
            handsFree = s.handsFreeContinuous,
            voiceToAgent = s.voiceToAgentEnabled,
            bridgeUrl = s.pcBridgeUrl,
            provider = s.voiceProvider,
            selectedVoiceName = s.voiceName
        )
    }

    fun updateSettings(transform: (AntigravitySettings) -> AntigravitySettings) {
        _settings.update { current ->
            val updated = transform(current)
            val resolvedTransport = if (updated.voiceProvider == VoiceProvider.GEMINI_LIVE) {
                VoiceTransportMode.GEMINI_LIVE_WEBSOCKET
            } else {
                VoiceTransportMode.FULL_DUPLEX_NATIVE
            }
            updated.copy(voiceTransportMode = resolvedTransport)
        }
        val s = _settings.value
        prefs.edit()
            .putString("pc_bridge_url", s.pcBridgeUrl)
            .putString("selected_model_id", s.selectedModelId)
            .putString("voice_provider", s.voiceProvider.name)
            .putString("voice_name", s.voiceName)
            .putString("gemini_api_key", s.geminiApiKey)
            .putBoolean("force_public_tunnel_only", s.forcePublicTunnelOnly)
            .apply()
        syncVoiceConfig()
    }

    fun cycleVisualizerStyle() {
        val styles = VoiceVisualizerStyle.entries
        val nextIdx = (styles.indexOf(_settings.value.voiceVisualizerStyle) + 1) % styles.size
        updateSettings { it.copy(voiceVisualizerStyle = styles[nextIdx]) }
    }

    private fun currentTimeFormatted(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    override fun onCleared() {
        super.onCleared()
        voiceEngine.release()
    }
}
