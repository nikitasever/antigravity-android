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
    /** Издатель позиции; пусто — «не записано» (мост может не отдавать поле). */
    val publisher: String = "",
    /** URL официального источника; пусто — «не записано». */
    val sourceUrl: String = "",
    /** Сценарий проверки при переносе; пусто — «не записано». */
    val testScenario: String = "",
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
 * Запись права инструмента MCP (ответ tool_permissions моста, «как у них»):
 * permission — allow/ask/deny; source — уровень правила (tool/server/policy).
 * comment — комментарий пользователя к запрету («Deny with comment»); пустая
 * строка означает «не записано» (мост не прислал комментарий — ничего не
 * выдумывается).
 */
data class McpToolPermissionEntry(
    val tool: String = "",
    val permission: String = "",
    val source: String = "",
    val comment: String = ""
)

/**
 * Состояние прав инструментов одного MCP-сервера (секция «Права инструментов»).
 * Приоритет прав (как у них): per-tool > «все инструменты сервера» > глобальная
 * политика (source показывает уровень). defaultPermission = null — мост не
 * прислал уровень сервера (не записано, ничего не выдумывается).
 */
data class McpToolPermissionsState(
    val serverId: String = "",
    val defaultPermission: String? = null,
    val tools: List<McpToolPermissionEntry> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String = ""
) {
    /** Запрос к мосту отправлен (честное состояние загрузки). */
    fun onLoading(): McpToolPermissionsState = copy(loading = true, error = "")

    /** Успешный ответ tool_permissions — список только из живого ответа. */
    fun onLoaded(
        defaultPermission: String?,
        tools: List<McpToolPermissionEntry>
    ): McpToolPermissionsState = copy(
        defaultPermission = defaultPermission,
        tools = tools,
        loaded = true,
        loading = false,
        error = ""
    )

    /** Отказ сервера/связи: ошибка видима текстом, успех не выдумывается. */
    fun onError(message: String): McpToolPermissionsState =
        copy(loading = false, error = message)

    /**
     * Успешная смена права одного инструмента (set_tool_permission).
     * comment — комментарий к запрету («Deny with comment»); у allow/ask он не
     * действует и не хранится, а у deny без комментария прежний очищается
     * (повторный deny без комментария = «не записано»).
     */
    fun onToolPermissionChanged(
        tool: String,
        permission: String,
        source: String = "tool",
        comment: String = ""
    ): McpToolPermissionsState =
        copy(
            tools = tools.map {
                if (it.tool == tool) {
                    it.copy(
                        permission = permission,
                        source = source,
                        comment = toolPermissionComment(permission, comment)
                    )
                } else {
                    it
                }
            },
            error = ""
        )

    /**
     * Успешная смена прав всех инструментов (set_all_tools_permissions) —
     * уровень «все инструменты сервера». Инструменты с собственным правом
     * (source = tool) сохраняют его: приоритет per-tool выше. comment
     * действует только при deny («Deny with comment»), у остальных прав
     * очищается вместе с правом.
     */
    fun onAllToolsPermissionChanged(
        permission: String,
        source: String = "server",
        comment: String = ""
    ): McpToolPermissionsState =
        copy(
            defaultPermission = permission,
            tools = tools.map {
                if (it.source == "tool") it else it.copy(
                    permission = permission,
                    source = source,
                    comment = toolPermissionComment(permission, comment)
                )
            },
            error = ""
        )
}

/**
 * Деталь навыка (ответ skills detail моста): полный текст SKILL.md (content).
 * enabled = null — мост не прислал признак (не записано).
 */
data class SkillDetailInfo(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val enabled: Boolean? = null,
    val source: String = "",
    val path: String = "",
    val content: String = ""
)

/** Состояние загрузки детали навыка (загрузка / успех / честная ошибка). */
data class SkillDetailState(
    val skillId: String = "",
    val detail: SkillDetailInfo? = null,
    val loading: Boolean = false,
    val error: String = ""
) {
    fun onLoading(): SkillDetailState = copy(loading = true, error = "")
    fun onLoaded(detail: SkillDetailInfo): SkillDetailState =
        copy(detail = detail, loading = false, error = "")

    fun onError(message: String): SkillDetailState = copy(loading = false, error = message)
}

/**
 * Результат правки или удаления навыка (ответ update/remove /api/skills):
 * ok + message при успехе, error + code при отказе (skill_name_taken,
 * skill_read_only). skills — свежий список навыков из ответа моста; null —
 * мост его не прислал («не записано»), значения не выдумываются.
 */
data class SkillEditResult(
    val ok: Boolean = false,
    val message: String = "",
    /** Код отказа моста (например skill_name_taken / skill_read_only). */
    val errorCode: String? = null,
    /** Текст отказа моста без маскировки. */
    val error: String? = null,
    val skills: List<PcSkillItem>? = null
)

/**
 * Честный текст отказа правки навыка: для skill_name_taken — ярлык «Навык с
 * именем «X» уже существует.», для остальных — текст ошибки моста как есть
 * (ничего не выдумывается).
 */
fun skillEditErrorLabel(result: SkillEditResult, attemptedName: String): String =
    when (result.errorCode) {
        "skill_name_taken" -> "Навык с именем «$attemptedName» уже существует."
        else -> result.error?.takeIf { it.isNotBlank() }
            ?: result.message.takeIf { it.isNotBlank() }
            ?: "Не удалось изменить навык"
    }

/**
 * Состояние редактора навыка («как у них»: Edit skill): загрузка черновика /
 * редактор / сохранение / честная ошибка. nameDraft/contentDraft — черновик
 * имени и полного текста SKILL.md. Встроенный навык (source = builtin) —
 * только чтение: мост отклоняет правку кодом skill_read_only.
 */
data class SkillEditState(
    val skillId: String = "",
    /** Черновик имени навыка (поле «Имя навыка»); пустой — newName не отправляется. */
    val nameDraft: String = "",
    /** Черновик полного текста SKILL.md. */
    val contentDraft: String = "",
    /** Источник навыка (из detail); пусто — «не записано». */
    val source: String = "",
    /** Загрузка черновика (detail) выполняется. */
    val loading: Boolean = false,
    /** Редактор открыт (черновик загружен). */
    val editorOpen: Boolean = false,
    /** Сохранение выполняется — кнопки заблокированы. */
    val saving: Boolean = false,
    /** Текст честной ошибки (загрузки или сохранения). */
    val error: String = "",
    /** Код ошибки моста последнего отказа (например skill_name_taken). */
    val errorCode: String? = null
) {
    /** Встроенный навык — только чтение. */
    val readOnly: Boolean get() = source == "builtin"

    /** Причина, по которой сохранение недоступно ("" — можно сохранять). */
    val saveBlockReason: String
        get() = when {
            readOnly -> "встроенный навык — только чтение"
            contentDraft.isBlank() -> "Содержимое навыка не может быть пустым."
            else -> ""
        }

    /** Сохранение доступно: редактор открыт, не загружается, не сохраняется. */
    val canSave: Boolean
        get() = editorOpen && !loading && !saving && saveBlockReason.isEmpty()

    /** Запрос к мосту отправлен (честное состояние загрузки черновика). */
    fun onLoading(): SkillEditState =
        copy(loading = true, editorOpen = false, saving = false, error = "", errorCode = null)

    /** Успешный detail — редактор открыт с черновиками имени и SKILL.md. */
    fun onLoaded(detail: SkillDetailInfo): SkillEditState = copy(
        nameDraft = detail.name,
        contentDraft = detail.content,
        source = detail.source,
        loading = false,
        editorOpen = true,
        saving = false,
        error = "",
        errorCode = null
    )

    /** Отказ загрузки черновика: ошибка видима текстом, редактор не открывается. */
    fun onError(message: String): SkillEditState =
        copy(loading = false, saving = false, error = message)

    /** Правка имени в черновике; прежняя ошибка сбрасывается при новой правке. */
    fun onNameChanged(name: String): SkillEditState =
        copy(nameDraft = name, error = "", errorCode = null)

    /** Правка SKILL.md в черновике; прежняя ошибка сбрасывается при новой правке. */
    fun onContentChanged(content: String): SkillEditState =
        copy(contentDraft = content, error = "", errorCode = null)

    /** Запрос update отправлен — кнопки заблокированы до ответа. */
    fun onSaving(): SkillEditState = copy(saving = true, error = "", errorCode = null)

    /** Успех сохранения — редактор закрывается. */
    fun onSaved(): SkillEditState =
        copy(saving = false, editorOpen = false, error = "", errorCode = null)

    /**
     * Отказ сохранения: редактор НЕ закрывается (в том числе skill_name_taken),
     * текст ошибки виден честно (ярлык для известного кода, иначе текст моста).
     */
    fun onSaveError(result: SkillEditResult, attemptedName: String): SkillEditState = copy(
        saving = false,
        editorOpen = true,
        error = skillEditErrorLabel(result, attemptedName),
        errorCode = result.errorCode
    )

    /** Редактор закрыт пользователем («Отмена») без сохранения. */
    fun onClose(): SkillEditState =
        copy(editorOpen = false, saving = false, error = "", errorCode = null)
}

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

/**
 * Человекочитаемая метка права инструмента (как у них): allow — «Всегда
 * разрешать», ask — «Спрашивать», deny — «Запретить». Пустое значение —
 * «не записано»; неизвестные значения показываются как есть, без выдумывания.
 */
fun describeToolPermission(permission: String): String = when (permission.trim().lowercase()) {
    "allow" -> "Всегда разрешать"
    "ask" -> "Спрашивать"
    "deny" -> "Запретить"
    "" -> "не записано"
    else -> permission
}

/**
 * Метка права с учётом комментария к запрету («Deny with comment»): у deny с
 * записанным комментарием — честная подпись «Запретить с комментарием»; у
 * остальных прав комментарий не приписывается (он действует только при deny).
 */
fun describeToolPermission(permission: String, comment: String): String =
    if (permission.trim().lowercase() == "deny" && comment.isNotBlank()) {
        "Запретить с комментарием"
    } else {
        describeToolPermission(permission)
    }

/**
 * Подпись комментария к запрету («Deny with comment»): записанный текст — как
 * есть; пустой/незаписанный — «не записано», ничего не выдумывается.
 */
fun describeDenyComment(comment: String): String =
    comment.trim().ifBlank { "не записано" }

/**
 * Комментарий, сохраняемый в строке права («Deny with comment»): действует
 * только при deny — у allow/ask он не применяется и не хранится. Пустой
 * комментарий у deny допустим и означает «не записано» (повторный deny без
 * комментария очищает прежний).
 */
fun toolPermissionComment(permission: String, comment: String): String =
    if (permission.trim().lowercase() == "deny") comment else ""

/**
 * Комментарий для запроса мосту («Deny with comment»): отправляется только с
 * deny и только непустым (контракт set_tool_permission /
 * set_all_tools_permissions); null — поле comment не отправляется вовсе
 * (в том числе повторный deny без комментария — мост очищает прежний).
 */
fun denyCommentForRequest(permission: String, comment: String): String? =
    if (permission.trim().lowercase() == "deny" && comment.isNotBlank()) comment else null

/**
 * Состояние диалога «Запретить с комментарием» («Deny with comment»): цель
 * запрета (инструмент либо «все инструменты») и текст комментария. Подпись
 * кнопки подтверждения честно отражает действие: пустой комментарий —
 * «просто Запретить», непустой — «Запретить с комментарием».
 */
data class DenyCommentDialogState(
    /** Инструмент-цель; пусто при цели «все инструменты». */
    val tool: String = "",
    /** true — запрет для всех инструментов сервера (set_all_tools_permissions). */
    val isAllTools: Boolean = false,
    /** Текст комментария пользователя (может быть пустым — это допустимо). */
    val comment: String = ""
) {
    /** Подпись кнопки подтверждения диалога. */
    val confirmLabel: String
        get() = if (comment.isBlank()) "Запретить" else "Запретить с комментарием"
}

/**
 * Скоуп права инструмента MCP («как у них»: «Always allow for this
 * project/session/task/website»). Семантика — наша честная модель по мотивам
 * Claude; точная семантика референса не подтверждена («требует проверки»).
 * project — сохраняется на ПК (permissions-config.json); session/task —
 * только память моста (до его перезапуска); website — сохраняется по origin
 * HTTP-подключения.
 */
enum class McpToolPermissionScope(
    /** Значение поля scope в запросе моста. */
    val wire: String,
    /** Подпись варианта (честная, по конвенции проекта). */
    val label: String,
    /** Короткая честная подсказка под подписью. */
    val hint: String
) {
    PROJECT(
        "project",
        "Для этого проекта (сохраняется на ПК)",
        "Правило сохраняется в файле прав на ПК."
    ),
    SESSION(
        "session",
        "Для этой сессии (до перезапуска моста)",
        "Хранится только в памяти моста — до его перезапуска."
    ),
    TASK(
        "task",
        "Для этой задачи (до перезапуска моста)",
        "Хранится только в памяти моста — до его перезапуска."
    ),
    WEBSITE(
        "website",
        "Для этого сайта (только HTTP-подключения)",
        "только для HTTP-подключений; у stdio-серверов сайта нет"
    );

    /** Источник строки tool_permissions для правила этого скоупа (как у них). */
    val rowSource: String get() = if (wire == "project") "tool" else wire
}

/**
 * Контекст для отправки права со скоупом: sessionId текущего разговора,
 * taskId текущей/последней пачки субагентов (пусто — «нет активной задачи»),
 * origin HTTP-подключения сервера (пусто — у подключения сайта нет).
 */
data class McpToolScopeContext(
    val sessionId: String = "",
    val taskId: String = "",
    val serverOrigin: String = ""
)

/** Результат выбора скоупа: параметры запроса либо честный отказ до отправки. */
sealed interface McpToolScopeRequest {
    /** Запрос готов: scope всегда, scopeId/origin — только если нужны скоупу. */
    data class Ready(
        val scope: String,
        val scopeId: String? = null,
        val origin: String? = null
    ) : McpToolScopeRequest

    /** Отказ до отправки (нет scopeId/origin) — как отказал бы мост, без выдумок. */
    data class Refused(val message: String) : McpToolScopeRequest
}

/**
 * Подготовка параметров скоупа для set_tool_permission /
 * set_all_tools_permissions. Пустые scopeId/origin — честный отказ ДО
 * отправки (мост отклоняет такие запросы): ничего не выдумывается.
 */
fun resolveMcpToolScope(
    scope: McpToolPermissionScope,
    context: McpToolScopeContext
): McpToolScopeRequest = when (scope) {
    McpToolPermissionScope.PROJECT ->
        McpToolScopeRequest.Ready(scope = scope.wire)

    McpToolPermissionScope.SESSION ->
        if (context.sessionId.isBlank()) {
            McpToolScopeRequest.Refused(
                "Для скоупа «${scope.label}» нужен sessionId — нет активного разговора."
            )
        } else {
            McpToolScopeRequest.Ready(scope = scope.wire, scopeId = context.sessionId)
        }

    McpToolPermissionScope.TASK ->
        if (context.taskId.isBlank()) {
            McpToolScopeRequest.Refused(
                "Для скоупа «${scope.label}» нужен taskId — нет активной задачи."
            )
        } else {
            McpToolScopeRequest.Ready(scope = scope.wire, scopeId = context.taskId)
        }

    McpToolPermissionScope.WEBSITE ->
        if (context.serverOrigin.isBlank()) {
            McpToolScopeRequest.Refused(
                "Скоуп «для этого сайта» доступен только для HTTP-подключений; у stdio-серверов сайта нет."
            )
        } else {
            McpToolScopeRequest.Ready(scope = scope.wire, origin = context.serverOrigin)
        }
}

/**
 * Человекочитаемая подпись источника права (tool_permissions): скоупы
 * session/task/website и уровни tool/server/policy. Пусто — «не записано»;
 * неизвестные значения показываются как есть, без выдумывания.
 */
fun describeMcpPermissionSource(source: String): String = when (source.trim().lowercase()) {
    "task" -> "для этой задачи"
    "session" -> "для этой сессии"
    "tool" -> "для этого проекта"
    "website" -> "для этого сайта"
    "server" -> "все инструменты сервера"
    "policy" -> "глобальная политика"
    "" -> "не записано"
    else -> source
}

/**
 * Приоритет источника права («как у них»): task > session > tool (project) >
 * website > server > policy. Неизвестные источники ниже всех известных.
 */
fun mcpPermissionSourcePriority(source: String): Int = when (source.trim().lowercase()) {
    "task" -> 6
    "session" -> 5
    "tool" -> 4
    "website" -> 3
    "server" -> 2
    "policy" -> 1
    else -> 0
}

/**
 * Режим разрешений MCP («как у них»: default/plan/auto/acceptEdits/dontAsk/
 * bypassPermissions). Состав режимов — как в Claude; точная семантика их
 * Auto-режима (модельная оценка рисков и prompt injection) НЕ воспроизводится
 * — «требует проверки». Наше «Автоматически» — эвристика по именам
 * инструментов и аргументам.
 */
enum class McpPermissionMode(
    /** Значение поля mode в запросе моста. */
    val wire: String,
    /** Подпись варианта (честная, по конвенции проекта). */
    val label: String,
    /** Короткая честная подсказка под подписью. */
    val hint: String
) {
    DEFAULT(
        "default",
        "Обычный (по правам инструментов)",
        "Действуют права инструментов (allow/ask/deny)."
    ),
    PLAN(
        "plan",
        "План (без изменений и выполнения)",
        "Изменения и выполнение не производятся."
    ),
    AUTO(
        "auto",
        "Автоматически (наша эвристическая оценка риска — не их Auto-режим)",
        "Оценка риска — эвристика по именам инструментов и аргументам; рискованные действия требуют подтверждения. Модельная проверка рисков и prompt injection не воспроизводится — «требует проверки»."
    ),
    ACCEPT_EDITS(
        "acceptEdits",
        "Принимать правки (правки файлов автоматически)",
        "Правки файлов выполняются автоматически; выполнение и разрушительные действия — с подтверждения."
    ),
    DONT_ASK(
        "dontAsk",
        "Не спрашивать",
        "Всё выполняется без вопросов, кроме явных запретов."
    ),
    BYPASS_PERMISSIONS(
        "bypassPermissions",
        "Обход разрешений",
        "Всё выполняется без вопросов, кроме явных запретов; deny прав никогда не обходится."
    );

    companion object {
        /**
         * Режим из wire-значения моста; неизвестное значение — null
         * («не записано», режим не выдумывается).
         */
        fun fromWire(value: String): McpPermissionMode? =
            entries.firstOrNull { it.wire == value.trim() }
    }
}

/**
 * Состояние режима разрешений (загрузка / успех / честная ошибка). mode =
 * null — мост не прислал режим или прислал неизвестное значение («не
 * записано», не выдумывается).
 */
data class McpPermissionModeState(
    val mode: McpPermissionMode? = null,
    /** Wire-значения допустимых режимов из ответа моста (пусто — «не записано»). */
    val validModes: List<String> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String = ""
) {
    /** Запрос к мосту отправлен (честное состояние загрузки). */
    fun onLoading(): McpPermissionModeState = copy(loading = true, error = "")

    /** Успешный ответ моста: режим (или null — «не записано») и список допустимых. */
    fun onLoaded(
        mode: McpPermissionMode?,
        validModes: List<String>
    ): McpPermissionModeState = copy(
        mode = mode,
        validModes = validModes,
        loaded = true,
        loading = false,
        error = ""
    )

    /** Отказ сервера/связи: ошибка видима текстом, успех не выдумывается. */
    fun onError(message: String): McpPermissionModeState =
        copy(loading = false, error = message)

    /** Успешная смена режима (set_permission_mode). */
    fun onModeChanged(mode: McpPermissionMode): McpPermissionModeState =
        copy(mode = mode, loading = false, error = "")
}

/**
 * Origin HTTP-подключения сервера (scheme://host[:port]) для скоупа «для
 * этого сайта». У stdio-серверов и без записанного URL — пусто («не
 * записано»): сайта у такого подключения нет, ничего не выдумывается.
 */
fun mcpServerOrigin(server: PcMcpServerItem): String {
    val raw = server.commandOrUrl.ifBlank { server.command }.trim()
    val match = Regex("https?://\\S+").find(raw) ?: return ""
    return runCatching {
        val uri = java.net.URI(match.value)
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
            ""
        } else {
            buildString {
                append(uri.scheme).append("://").append(uri.host)
                if (uri.port != -1) append(":").append(uri.port)
            }
        }
    }.getOrDefault("")
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

class ToolApprovalHandle(val requestId: String, val token: String, val turnId: String,
    val conversationId: String, val bridgeUrl: String, val preview: String) {
    override fun toString() = "ToolApprovalHandle(redacted)"
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
    val leadingText: String = "",
    // Transient capability: never serialize it into chat history.
    val approvalHandle: ToolApprovalHandle? = null,
    val approvalSending: Boolean = false,
    val approvalError: String = ""
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
    val mimeType: String = "",
    val resolution: String = "",
    val width: Int = 0,
    val height: Int = 0,
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
        "Голос через ПК",
        "Озвучка через PC-Bridge; требуется подключение к ПК",
        false
    ),
    GEMINI_LIVE(
        "Gemini Live",
        "Двусторонний голосовой разговор через Gemini API",
        true
    ),
    GEMINI_TTS(
        "Gemini TTS",
        "Озвучка ответов через Gemini API",
        true
    ),
    ON_DEVICE(
        "Голос Android",
        "Озвучка средствами устройства",
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
    val priceLabel: String = "Тариф OpenRouter",
    val generationCapabilitiesJson: String = "{}"
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
    val isSettling: Boolean = false,
    // «Как у них» (Claude: «Running in the shared directory… consider worktree
    // mode to isolate changes»): режим рабочего каталога задачи —
    // "isolated" (изолированная рабочая копия) или "shared" (общий каталог).
    // В старых ответах моста полей нет — null, прежний вид панели.
    val workspaceMode: String? = null,
    val sharedDirectoryWarning: String? = null
) {
    val isRunning: Boolean get() = status in setOf("running", "active", "working")
    val isInProgress: Boolean get() = isSettling || status == "queued" || isRunning
}

typealias SwarmSubAgentItem = SwarmAgentNode

// Снимок роя «как у них» (Claude: «%1$d running task(s)», «No background
// tasks», «Your computer is busy with other sessions…»). Все новые поля
// отсутствуют в старых ответах моста — тогда null и прежнее поведение.
data class SwarmCounts(
    val queued: Int = 0,
    val running: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0
)

data class SwarmSnapshot(
    val agents: List<SwarmAgentNode> = emptyList(),
    val counts: SwarmCounts? = null,
    val runningTasks: Int? = null,
    val busy: Boolean? = null,
    val busyMessage: String? = null,
    val batchId: String = "",
    val swarmActive: Boolean = false,
    val strategy: String = ""
)

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

/**
 * Тип фишки упоминания в композиторе («как у них»: их TokenType
 * SYSTEM_HINT/GIZMO/SKILL/TARGETED_REPLY НЕ воспроизводится дословно —
 * «требует проверки»). Типы — наши честные категории по categoryBadge
 * существующих предложений SlashMentionSuggestionItem.
 */
enum class ComposerTokenType(val label: String) {
    SUBAGENT("Субагент"),
    SKILL("Навык"),
    MCP("MCP"),
    FILE("Файл"),
    DRIVE("Google Диск"),
    PLUGIN("Плагин"),
    CONTEXT("Контекст"),
    OTHER("Прочее");

    companion object {
        /**
         * Тип по categoryBadge предложения. Неизвестная категория — «Прочее»
         * (ничего не выдумывается).
         */
        fun fromCategoryBadge(categoryBadge: String): ComposerTokenType {
            val badge = categoryBadge.trim()
            return when {
                badge.contains("Субагент", ignoreCase = true) ||
                    badge.contains("ЛИДЕР", ignoreCase = true) -> SUBAGENT
                badge.contains("Скилл", ignoreCase = true) ||
                    badge.contains("Навык", ignoreCase = true) -> SKILL
                badge.contains("MCP", ignoreCase = true) -> MCP
                badge.contains("Диск", ignoreCase = true) -> DRIVE
                badge.contains("Плагин", ignoreCase = true) -> PLUGIN
                badge.contains("Файл", ignoreCase = true) -> FILE
                badge.contains("Контекст", ignoreCase = true) -> CONTEXT
                else -> OTHER
            }
        }
    }
}

/**
 * Фишка упоминания в композиторе («как у них»: токены их composer'а).
 * Действия: INSERTED при добавлении, REMOVED при удалении. insertText — тот же
 * текстовый insert, что и раньше (@handle в черновике): фишка —
 * ДОПОЛНИТЕЛЬНЫЙ механизм, текстовое упоминание работает как раньше.
 */
data class ComposerToken(
    val id: String,
    val label: String,
    val insertText: String,
    val typeLabel: String
) {
    companion object {
        /** Фишка из предложения палитры (INSERTED). */
        fun fromSuggestion(item: SlashMentionSuggestionItem): ComposerToken = ComposerToken(
            id = item.id,
            label = item.title.ifBlank { item.trigger },
            insertText = item.insertText,
            typeLabel = ComposerTokenType.fromCategoryBadge(item.categoryBadge).label
        )
    }
}

/**
 * Логика фишек упоминаний композитора (чистая, без Android-контекста):
 * добавление (INSERTED) без дубликатов по id, удаление (REMOVED), очистка и
 * композиция промпта — тексты фишек (insertText) одной строкой через пробел
 * ПЕРЕД текстом черновика.
 */
data class ComposerTokensState(
    val tokens: List<ComposerToken> = emptyList()
) {
    /** Добавление фишки (INSERTED); дубликат по id не добавляется. */
    fun onAdded(token: ComposerToken): ComposerTokensState =
        if (tokens.any { it.id == token.id }) this else copy(tokens = tokens + token)

    /** Удаление фишки (REMOVED); остальные сохраняются в порядке добавления. */
    fun onRemoved(id: String): ComposerTokensState =
        copy(tokens = tokens.filterNot { it.id == id })

    /** Очистка всех фишек (после отправки). */
    fun onCleared(): ComposerTokensState = copy(tokens = emptyList())

    /**
     * Итоговый текст хода: insertText фишек одной строкой через пробел ПЕРЕД
     * текстом черновика. Пустой черновик не мешает отправке — уходят только
     * фишки; пустые части не добавляют лишних пробелов.
     */
    fun composePrompt(draft: String): String {
        val tokenLine = tokens
            .map { it.insertText.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return listOf(tokenLine, draft.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    /** Есть ли содержимое для отправки (фишки или черновик). */
    fun hasContent(draft: String): Boolean = composePrompt(draft).isNotBlank()
}

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
    val responseFont: ResponseFont = ResponseFont.SERIF
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

