package com.google.antigravity.model

import java.util.UUID

enum class MessageRole {
    USER,
    AGENT,
    SYSTEM
}

enum class ToolCallStatus {
    RUNNING,
    AWAITING_APPROVAL,
    APPROVED,
    DENIED,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class ApprovalDecision {
    PENDING,
    APPROVED,
    DENIED,
    DENIED_WITH_COMMENT
}

/**
 * Верхний переключатель на главном экране (`Чат | Работа`)
 */
enum class TopModeTab {
    CHAT,
    WORK_SESSIONS
}

/**
 * Фильтр окружений на экране удалённых сессий (`Все | ☁️ Облако | • 💻 ПК | • 💻 WIN-11 | • 📱 Телефон`)
 */
enum class RemoteEnvFilter(val label: String, val hostTag: String) {
    ALL("Все", "ALL"),
    CLOUD("Облако", "CLOUD"),
    PC_CR05318("Компьютер", "cr05318"),
    PC_WIN11("Рабочая станция", "WIN-11"),
    PHONE_LOCAL("Телефон", "PHONE")
}

/**
 * Модель уже созданной удалённой сессии (синхронизируется с ПК `C:\Users\st811210\.gemini\antigravity\brain\`)
 */
data class RemoteSessionItem(
    val id: String,
    val title: String,
    val environment: String = "Основное окружение",
    val host: String = "cr05318",
    val hostType: String = "PC", // PC, CLOUD, PHONE
    val workspacePath: String = "C:\\Users\\st811210\\.gemini\\antigravity\\scratch\\antigravity-android",
    val updatedAtMs: Long = System.currentTimeMillis(),
    val messageCount: Int = 0
)

/**
 * Прикреплённый файл или фото с телефона (через кнопку `+` в поле ввода или системное меню «Поделиться»)
 */
data class AttachedFileItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val uriString: String = "",
    val sizeBytes: Long = 0L,
    val mimeType: String = "application/octet-stream",
    val textPreview: String = "",
    val base64Data: String = "",
    val pcUploadedPath: String = ""
)

/**
 * Проект из рабочей директории ПК (`C:\Users\st811210\.gemini\antigravity\scratch\`)
 */
data class ScratchProjectItem(
    val name: String,
    val path: String,
    val host: String = "Компьютер",
    val fileCount: Int = 0,
    val sampleFiles: List<String> = emptyList()
)

/**
 * Запланированная задача / автоматизация (раздел `Запланировано`)
 */
data class ScheduledAutomationItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val cronSchedule: String,
    val targetHost: String,
    val enabled: Boolean = true,
    val promptCommand: String
)

/**
 * Плагин / Навык / Интеграция Antigravity (раздел `Плагины`)
 */
data class InstalledPluginItem(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val enabled: Boolean = true,
    val version: String = "1.0.0",
    val source: String = "antigravity",
    val path: String = "",
    /** Этап 5/6: проверенный путь компонентов либо конкретный блокер. */
    val composition: ExtensionComposition = ExtensionComposition()
)

/**
 * Запись адаптера компонента (Этап 5, `composition.records` в /api/plugins):
 * стратегия preserve/convert/own-executor и статус verified/blocked/
 * requires-check/not-applicable с конкретным блокером.
 */
data class ComponentAdapterRecord(
    val componentType: String,
    val strategy: String = "preserve",
    val status: String = "requires-check",
    val blocker: String = "",
    val verification: String = ""
)

/**
 * Состав пакета расширения: «у каждого компонента есть проверенный путь
 * исполнения либо конкретный блокер»; «полностью» — только при проверенном
 * составе И праве переиспользования/распространения.
 */
data class ExtensionComposition(
    val supportStatus: String = "unknown",
    val fullSupportAllowed: Boolean = false,
    val records: List<ComponentAdapterRecord> = emptyList(),
    val licenseRight: String = "unknown",
    val licenseNote: String = "",
    val blockers: List<String> = emptyList()
)

/**
 * Позиция каталога расширений (инвентарь Этапа 0 / матрица Этапа 5).
 * Каталог — наблюдения источников: не показываются работающими.
 */
data class ExtensionCatalogItem(
    val id: String,
    val name: String,
    /** Источник (продукт-источник позиции). */
    val sourceProduct: String,
    /** Категория (первый тип компонента позиции). */
    val category: String,
    val availability: String = "unknown",
    /** Совместимость: supportStatus матрицы адаптеров. */
    val compatibility: String = "requires-check",
    val blocker: String = "",
    val licenseNote: String = "",
    /** Устанавливается только из локального проверенного источника. */
    val installable: Boolean = false,
    val installPath: String = ""
)

data class PcSkillItem(
    val id: String,
    val name: String,
    val description: String,
    val instruction: String = "",
    val instructionsPreview: String = instruction,
    val enabled: Boolean = true,
    val path: String = ""
)

/**
 * QA-дефект Medium #1: счётчик поколений запросов расширений. Каждая операция
 * обновления/переключения/проверки получает свой номер; запоздавший ответ
 * более старого запроса (например, старого refresh) не перезаписывает свежее
 * состояние и не сбрасывает индикатор загрузки.
 */
class ExtLoadSequencer {
    private val seq = java.util.concurrent.atomic.AtomicInteger(0)

    /** Начать новое поколение запроса (новее всех предыдущих). */
    fun next(): Int = seq.incrementAndGet()

    /** Применим ли ответ: с момента старта не появилось более нового запроса. */
    fun isCurrent(generation: Int): Boolean = generation == seq.get()
}

data class PcMcpServerItem(
    val id: String,
    val name: String,
    val transport: String = "stdio",
    val scope: String = "user",
    val command: String = "",
    val args: List<String> = emptyList(),
    val commandOrUrl: String = "$command ${args.joinToString(" ")}".trim(),
    val enabled: Boolean = true,
    val status: String = "enabled",
    val tools: List<String> = emptyList(),
    val error: String = "",
    /** Время последнего реального MCP-рукопожатия (этап 3), ISO-8601. */
    val probedAt: String = "",
    /** QA-дефект Medium #2: код ошибки последней операции (errorCode ответа). */
    val errorCode: String? = null,
    /** QA-дефект Medium #2: serverInfo успешного рукопожатия (имя/версия сервера). */
    val serverInfo: String? = null,
    /** OAuth 2.1: вход выполнен (производная от authStatus == "authorized"). */
    val oauthAuthorized: Boolean = false,
    /** OAuth 2.1: адрес страницы входа (authUrl из ответа моста), если требуется. */
    val authUrl: String = "",
    /**
     * OAuth 2.1: нормализованный статус авторизации
     * (authorized/not_authorized/pending/denied/failed/expired/revoked; "" — неизвестен).
     */
    val authStatus: String = ""
)

/**
 * Человекочитаемый статус MCP-сервера (Этап 3). «connected» означает только
 * реальное успешное рукопожатие initialize → initialized → tools/list;
 * «configured» — включён в конфиге, но ещё не проверялся.
 */
fun describeMcpStatus(status: String): String = when (status.trim().lowercase()) {
    "connected" -> "подключён (рукопожатие выполнено)"
    "configured" -> "настроен (не проверен)"
    "failed" -> "проверка не пройдена"
    "disabled" -> "отключён"
    else -> status
}

enum class AgentExecutionTarget(val shortLabel: String, val titleRu: String, val subtitleRu: String) {
    PC_REMOTE_SESSION(
        "Сессия на компьютере",
        "Удалённая сессия на компьютере",
        "Подключение к рабочим сессиям на ПК и синхронизация истории"
    ),
    PHONE_LOCAL_RESOURCES(
        "Память телефона",
        "Работа с файлами и приложениями телефона",
        "Умный поиск, чтение и создание файлов в памяти смартфона"
    ),
    GOOGLE_WEB_CONSOLE(
        "Веб-синхронизация",
        "Веб-интерфейс Google Antigravity",
        "Живая синхронизация чата между телефоном, браузером и ПК"
    )
}

data class GoogleAccountProfile(
    val isSignedIn: Boolean = false,
    val displayName: String = "st811210",
    val email: String = "st811210@gmail.com",
    val pcInstanceName: String = "Рабочий компьютер",
    val authSource: String = "Аккаунт Google"
)

data class ToolApprovalRequest(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val commandOrPath: String,
    val summary: String,
    val riskLevel: String = "MEDIUM",
    val unifiedDiff: String? = null,
    val decision: ApprovalDecision = ApprovalDecision.PENDING,
    val reviewerComment: String? = null
)

/**
 * Переводит внутренние коды инструментов в понятные русские названия без технического мусора.
 */
fun humanizeToolNameRu(rawToolName: String): String {
    val normalized = rawToolName.trim().lowercase()
    return when {
        normalized == "delegate_task" -> "Делегирование"
        normalized.contains("create_file") || normalized.contains("write_file") || normalized.contains("write_to_file") -> "Создание файла"
        normalized.contains("patch_file") || normalized.contains("edit_file") || normalized.contains("replace_file") || normalized.contains("modify") -> "Точечное изменение файла"
        normalized.contains("append_file") -> "Дозапись в файл"
        normalized.contains("read_file") || normalized.contains("view_file") -> "Чтение файла"
        normalized.contains("search_media") || normalized.contains("media") -> "Поиск фото и медиа"
        normalized.contains("search") || normalized.contains("find") || normalized.contains("grep") -> "Поиск файлов"
        normalized.contains("list_dir") || normalized.contains("list_files") -> "Просмотр папки"
        normalized.contains("contacts") || normalized.contains("contact") -> "Поиск контактов"
        normalized.contains("share") -> "Отправка (Поделиться)"
        normalized.contains("phone_inspect") || normalized.contains("device_info") || normalized.contains("storage") -> "Анализ памяти телефона"
        normalized.contains("android_cli") -> "Терминал Android CLI"
        normalized.contains("sandbox") -> "Виртуальная песочница"
        normalized.contains("openrouter_generate_video") -> "Генерация MP4-видео (OpenRouter)"
        normalized.contains("openrouter_generate_image") -> "Генерация фото (OpenRouter)"
        normalized.contains("shell") || normalized.contains("command") || normalized.contains("terminal") -> "Выполнение команды"
        normalized.contains("open_app") || normalized.contains("launch") -> "Запуск приложения"
        normalized.contains("delete") || normalized.contains("remove") -> "Удаление файла"
        normalized.contains("mcp") -> "Вызов MCP-сервера"
        normalized.contains("skill") -> "Применение скилла"
        normalized.contains("agy") || normalized.contains("pc_bridge") || normalized.contains("bridge") -> "Запрос к сессии на ПК"
        normalized.contains("gemini") -> "Ответ модели Gemini"
        else -> "Инструмент: $rawToolName"
    }
}

data class ToolCallEvent(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val summary: String,
    val details: String,
    val status: ToolCallStatus = ToolCallStatus.COMPLETED,
    val durationMs: Long = 180L,
    val toolTitleRu: String = humanizeToolNameRu(toolName),
    val unifiedDiff: String? = details.takeIf { it.isNotBlank() },
    val requiresApproval: Boolean = false,
    val approvalDecision: ApprovalDecision = ApprovalDecision.PENDING,
    val parentTaskId: String? = null,
    val executionTarget: AgentExecutionTarget? = null,
    val leadingText: String = ""
)

data class ArtifactDocument(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val type: ArtifactType = ArtifactType.MARKDOWN,
    val summary: String,
    val content: String,
    val requestFeedback: Boolean = false,
    val updatedAt: String = "Сегодня",
    val versions: List<ArtifactVersion> = emptyList(),
    /** Время последнего изменения копии; метка для слияния с ПК. */
    val updatedAtMs: Long = 0L,
    /** Закрепление артефакта (как «Закрепить артефакт» в ChatGPT/Claude). */
    val pinned: Boolean = false
)

enum class ArtifactType(val label: String) {
    MARKDOWN("Документ / План"),
    CODE_DIFF("Изменения кода"),
    INTERACTIVE_WIDGET("Интерактивный виджет"),
    RE_REPORT("Отчёт анализа")
}

data class FileDiffItem(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val diffPatch: String,
    val language: String = "kotlin",
    val fullPath: String = path
)

data class GeneratedMediaAttachment(
    val id: String = UUID.randomUUID().toString(),
    val type: String = "video",
    val fileName: String,
    val mediaUrl: String,
    val localPath: String = "",
    val prompt: String = "",
    val modelId: String = "",
    val mimeType: String = "video/mp4",
    val resolution: String = "640x360",
    val width: Int = 640,
    val height: Int = 360,
    val durationSec: Int = 0,
    val sizeBytes: Long = 0L,
    val localPhonePath: String? = null
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: String,
    val thinking: String? = null,
    val thinkingDurationSec: Int = 2,
    val isThinkingExpanded: Boolean = false,
    val isStreaming: Boolean = false,
    val viaVoice: Boolean = false,
    val executionTargetLabel: String? = null,
    val attachments: List<AttachedFileItem> = emptyList(),
    val toolCalls: List<ToolCallEvent> = emptyList(),
    val createdDiffs: List<FileDiffItem> = emptyList(),
    val generatedMedia: List<GeneratedMediaAttachment> = emptyList(),
    val approvalRequest: ToolApprovalRequest? = null,
    val artifactId: String? = null,
    val isContextResetDivider: Boolean = false,
    /** Сводка сжатия контекста: входит в контекст модели как первый материал. */
    val isCompactionSummary: Boolean = false,
    val tokensPerSecond: Float = 0f,
    val outputTokensCount: Int = 0,
    val firstTokenLatencyMs: Long = 0L,
    val modelBadge: String = "",
    val versionState: MessageVersionState? = null
)

data class TerminalSessionItem(
    val id: String,
    val name: String,
    val workingDir: String,
    val isActive: Boolean,
    val outputLines: List<String>
)

enum class VoiceSessionStatus(val labelRu: String) {
    INACTIVE("Отключён"),
    CONNECTING("Подключение…"),
    RECONNECTING("Переподключение…"),
    LISTENING("Слушаю…"),
    USER_SPEAKING("Вы говорите…"),
    THINKING("Думаю…"),
    AI_SPEAKING("Нажмите на сферу или начните говорить, чтобы прервать"),
    MUTED("Микрофон выключен")
}

enum class VoiceVisualizerStyle(val title: String) {
    ORBIT_ROSETTE("Голубая сфера (Стиль ChatGPT)"),
    ANTIGRAVITY_CORE("Ядро Antigravity"),
    BELL_WAVE("Минималистичная волна")
}

enum class VoiceTransportMode(val title: String, val subtitle: String) {
    FULL_DUPLEX_NATIVE(
        "Живой диалог (Нейроголос + перебивание голосом)",
        "Мгновенный голосовой диалог в реальном времени без задержек"
    ),
    GEMINI_LIVE_WEBSOCKET(
        "Gemini Live Audio (Прямой поток по ключу)",
        "Двусторонний аудио-поток Gemini Live с вызовом функций телефона и ПК"
    )
}

enum class VoiceProvider(val titleRu: String, val subtitleRu: String, val requiresApiKey: Boolean) {
    NEURAL_BRIDGE(
        "Нейроголос без ключа (Edge Neural)",
        "Живой русский голос Светлана / Дмитрий через мост ПК, задержка < 1 с",
        false
    ),
    GEMINI_LIVE(
        "Gemini Live (Прямой аудио-поток по ключу)",
        "Двусторонний голосовой диалог с перебиванием и доступом к файлам телефона/ПК",
        true
    ),
    GEMINI_TTS(
        "Gemini 2.5 Flash TTS (По ключу)",
        "Студийная озвучка ответов голосами Aoede, Kore, Puck, Charon, Fenrir",
        true
    ),
    ON_DEVICE(
        "Системный голос Android (Офлайн)",
        "Локальный синтез речи Google с выбором нейросетевого пакета *-network",
        false
    )
}

data class VoiceNameOption(
    val id: String,
    val displayNameRu: String,
    val descriptionRu: String
)

val AVAILABLE_VOICE_NAMES = listOf(
    VoiceNameOption("Aoede", "Aoede • Светлана", "Мягкий и естественный женский голос"),
    VoiceNameOption("Kore", "Kore • Елена", "Чёткий и спокойный женский голос"),
    VoiceNameOption("Puck", "Puck • Дмитрий", "Живой и динамичный мужской голос"),
    VoiceNameOption("Charon", "Charon • Максим", "Глубокий и размеренный мужской голос"),
    VoiceNameOption("Fenrir", "Fenrir • Артём", "Уверенный и выразительный мужской голос")
)

enum class ToolExecutionPolicy(val code: String, val labelRu: String) {
    ALWAYS_PROCEED("always-proceed", "Выполнять действия автоматически"),
    REQUEST_REVIEW("request-review", "Запрашивать подтверждение перед изменениями"),
    PROCEED_IN_SANDBOX("proceed-in-sandbox", "Выполнять в изолированной песочнице"),
    STRICT("strict", "Строгий режим (ручное одобрение каждого шага)")
}

data class AntigravityModelOption(
    val id: String,
    val displayName: String,
    val badge: String
)

val AVAILABLE_AGY_MODELS = listOf(
    AntigravityModelOption("gemini-4-argon-high", "Gemini 4 Argon (High)", "Основная флагманская модель • Глубокое мышление и архитектура"),
    AntigravityModelOption("gemini-4-argon-medium", "Gemini 4 Argon (Medium)", "Оптимальный баланс скорости и глубины анализа"),
    AntigravityModelOption("gemini-4-argon-low", "Gemini 4 Argon (Fast)", "Быстрые ответы Gemini 4 для повседневных задач"),
    AntigravityModelOption("gemini-3.8-flash-high", "Gemini 3.8 Flash (High)", "Мгновенный отклик с расширенным анализом"),
    AntigravityModelOption("gemini-3.8-flash-medium", "Gemini 3.8 Flash (Medium)", "Универсальная быстрая модель для чата и голоса"),
    AntigravityModelOption("gemini-2.5-pro", "Gemini 2.5 Pro", "Прямой Gemini API • Сложное рассуждение и длинный контекст"),
    AntigravityModelOption("gemini-2.5-flash", "Gemini 2.5 Flash", "Прямой Gemini API • Сверхбыстрый мультимодальный режим")
)

fun mapToDirectGeminiModelId(selectedModelId: String): String {
    return selectedModelId.trim().removePrefix("models/")

}

enum class OpenRouterCategory(val code: String, val labelRu: String, val emoji: String) {
    ALL("ALL", "Новейшие / Все", "🌐"),
    REASONING("REASONING", "Reasoning / Код", "🧠"),
    VISION("VISION", "Vision / Мульти", "👁️"),
    IMAGE_GEN("IMAGE_GEN", "Фото", "🎨"),
    VIDEO_GEN("VIDEO_GEN", "Видео (MP4)", "🎬"),
    AUDIO("AUDIO", "Аудио", "🎙️"),
    FREE("FREE", "Бесплатные", "⚡")
}

data class OpenRouterModelItem(
    val id: String,
    val name: String,
    val createdTimestamp: Long = 0L,
    val created: Long = createdTimestamp,
    val createdDate: String = "",
    val description: String = "",
    val contextLength: Int = 128000,
    val modality: String = "text->text",
    val isFree: Boolean = false,
    val isReasoning: Boolean = false,
    val isVision: Boolean = false,
    val isImageGen: Boolean = false,
    val isVideoGen: Boolean = false,
    val isAudio: Boolean = false,
    val primaryCategory: String = "TEXT",
    val categories: List<OpenRouterCategory> = listOf(OpenRouterCategory.ALL),
    val pricingPrompt: String = "0",
    val pricingCompletion: String = "0",
    val priceLabel: String = "Бесплатно (Free)"
)

data class SwarmAgentNode(
    val id: String,
    val handle: String = "@leader",
    val mention: String = handle,
    val name: String,
    val roleBadge: String = "",
    val role: String = roleBadge,
    val isLeader: Boolean = false,
    val model: String = "gemini-4-argon-high",
    val status: String = "active",
    val currentTask: String = "",
    val currentStep: String = currentTask,
    val completedTasks: Int = 0,
    val stepLogs: List<String> = emptyList(),
    val logs: List<String> = stepLogs,
    val changeReview: String = "",
    val isSettling: Boolean = false
) {
    val isInProgress: Boolean get() = isSettling || status in setOf("queued", "running")
}

typealias SwarmSubAgentItem = SwarmAgentNode

data class AndroidCliCommandResult(
    val id: String = UUID.randomUUID().toString(),
    val command: String,
    val output: String = "",
    val stdout: String = output,
    val stderr: String = "",
    val exitCode: Int = 0,
    val durationMs: Long = 1L,
    val timestamp: String = ""
)

data class VirtualSandboxProject(
    val id: String = "default",
    val name: String,
    val rootPath: String,
    val status: String = "Готова",
    val files: List<String> = emptyList(),
    val htmlEntryPath: String = "",
    val previewHtmlPath: String = htmlEntryPath.removePrefix("file://"),
    val lastRunOutput: String = "",
    val lastTestSummary: String = "Проверки ещё не запускались",
    val snapshots: List<String> = emptyList(),
    val updatedAt: String = "Только что"
)

data class AiPermissionsConfig(
    val preset: String = "AUTOPILOT",
    val toolExecutionPolicy: String = "always-proceed",
    val terminalSandboxEnabled: Boolean = true,
    val terminalSandbox: Boolean = terminalSandboxEnabled,
    val allowNonWorkspaceFiles: Boolean = true,
    val nonWorkspaceFileAccess: String = "allow",
    val internetAccessPolicy: String = "open-web-and-api",
    val artifactReviewPolicy: String = "auto-proceed",
    val artifactReviewMode: String = artifactReviewPolicy,
    val runInBackgroundKeepAwake: Boolean = true,
    // Смартфон Android
    val phoneFileReadWrite: Boolean = true,
    val phoneAndroidCliShell: Boolean = true,
    val phoneVirtualSandboxExec: Boolean = true,
    val phoneOpenExternalApps: Boolean = true,
    val phoneContactsRead: Boolean = true,
    val phoneMediaCameraMic: Boolean = true,
    val phoneInstallPackagesAccess: Boolean = true,
    // Рабочая станция ПК
    val pcTerminalExec: Boolean = true,
    val pcFilesystemWrite: Boolean = true,
    val pcBrowserAutomation: Boolean = true,
    val pcMcpToolsExec: Boolean = true,
    val pcOpenRouterCloudGen: Boolean = true,
    val pcFullDiskAccess: Boolean = true
) {
    val phoneFileSystemAccess: Boolean get() = phoneFileReadWrite
    val phoneAndroidCliAccess: Boolean get() = phoneAndroidCliShell
    val phoneVirtualSandboxAccess: Boolean get() = phoneVirtualSandboxExec
    val phoneCameraMediaAccess: Boolean get() = phoneMediaCameraMic
    val phoneContactsCalendarSmsAccess: Boolean get() = phoneContactsRead
    val pcWorkspaceReadWrite: Boolean get() = pcFilesystemWrite
    val pcPowerShellExecution: Boolean get() = pcTerminalExec
    val pcBrowserAndNetworkAccess: Boolean get() = pcBrowserAutomation
    val pcPluginsMcpSkillsAccess: Boolean get() = pcMcpToolsExec
}

data class ContextWindowStatus(
    val conversationId: String = "",
    val model: String = "gemini-4-argon-high",
    val wasReset: Boolean = false,
    val maxTokens: Int = 1_000_000,
    val usedTokens: Int = 1_240,
    val maxContextTokens: Int = maxTokens,
    val usedContextTokens: Int = usedTokens,
    val systemPromptTokens: Int = 1_240,
    val historyTokens: Int = 0,
    val toolOutputTokens: Int = 0,
    val messageCount: Int = 0,
    val messagesCount: Int = messageCount,
    val usagePercent: Float = 0.12f,
    val lastResetAt: String = ""
)

data class SlashMentionSuggestionItem(
    val id: String,
    val trigger: String,
    val insertText: String,
    val subtitle: String,
    val categoryBadge: String,
    val iconName: String,
    val title: String = trigger,
    val badge: String = categoryBadge,
    val category: String = categoryBadge,
    val actionType: String = "INSERT"
)

data class AntigravitySettings(
    val selectedModelId: String = "gemini-4-argon-high",
    val executionTarget: AgentExecutionTarget = AgentExecutionTarget.PC_REMOTE_SESSION,
    val activeConversationId: String = "",
    val activeSessionTitle: String = "",
    val voiceVisualizerStyle: VoiceVisualizerStyle = VoiceVisualizerStyle.ORBIT_ROSETTE,
    val voiceTransportMode: VoiceTransportMode = VoiceTransportMode.FULL_DUPLEX_NATIVE,
    val voiceProvider: VoiceProvider = VoiceProvider.NEURAL_BRIDGE,
    val voiceName: String = "Aoede",
    val voiceToAgentEnabled: Boolean = true,
    val bargeInEnabled: Boolean = true,
    val handsFreeContinuous: Boolean = true,
    val speechRate: Float = 1.05f,
    val toolPolicy: ToolExecutionPolicy = ToolExecutionPolicy.ALWAYS_PROCEED,
    val geminiApiKey: String = "",
    val openRouterApiKey: String = "",
    val pcBridgeUrl: String = "https://2d0102ca0a9fe83d-79-137-206-221.serveousercontent.com",
    val antigravityGatewayUrl: String = "https://antigravity.google.com",
    val forcePublicTunnelOnly: Boolean = false,
    val chatTextSize: ChatTextSize = ChatTextSize.NORMAL,
    val responseFont: ResponseFont = ResponseFont.SYSTEM
) {
    val selectedModelDisplay: String
        get() = AVAILABLE_AGY_MODELS.find { it.id == selectedModelId }?.displayName
            ?: selectedModelId.removePrefix("openrouter/").substringAfterLast('/')
}

enum class DrawerSection(val titleRu: String) {
    CHAT("Чат"),
    REMOTE_SESSIONS("Рабочие сессии"),
    PROJECTS("Проекты"),
    LIBRARY("Библиотека"),
    IMAGES("Изображения"),
    SCHEDULED("Расписание"),
    PLUGINS("Навыки и плагины"),
    GOOGLE_WEB("Веб-синхронизация"),
    PHONE_EXPLORER("Поиск по телефону"),
    SETTINGS("Настройки")
}

data class GoogleDriveFileItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val folder: String = "/AntigravityCloud",
    val folderPath: String = folder,
    val engine: String = "Gemini",
    val modelId: String = "gemini-4-argon-high",
    val modelUsed: String = modelId,
    val providerBadge: String = "Gemini & OpenRouter",
    val mimeType: String = "text/markdown",
    val sizeBytes: Long = 0L,
    val updatedAt: String = "Только что",
    val webViewLink: String = "",
    val downloadUrl: String = "",
    val localUri: String = "",
    val previewSnippet: String = "",
    val content: String = "",
    val contentPreview: String = previewSnippet.ifBlank { content }
)

data class GoogleDriveStatus(
    val connected: Boolean = false,
    val accountEmail: String = "",
    val folderPath: String = "/AntigravityCloud",
    val rootFolder: String = folderPath,
    val filesCount: Int = 0,
    val usedBytes: Long = 0L,
    val quotaBytes: Long = 0L,
    val engines: List<String> = listOf("Gemini", "OpenRouter")
)

