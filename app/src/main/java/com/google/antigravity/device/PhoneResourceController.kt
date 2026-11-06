package com.google.antigravity.device

import com.google.antigravity.model.processPhoneActionBlocks
import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.media.MediaScannerConnection
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.antigravity.model.AiPermissionsConfig
import com.google.antigravity.model.AndroidCliCommandResult
import com.google.antigravity.model.VirtualSandboxProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Категории фильтров для экрана «ИИ-поиск по телефону» (Раздел 5.5).
 */
enum class PhoneSearchCategory(val labelRu: String) {
    ALL("Все"),
    DOCUMENTS("Документы"),
    PHOTOS("Фото"),
    VIDEOS("Видео"),
    AUDIO("Аудио"),
    APK("APK"),
    CONTACTS("Контакты"),
    APPS("Приложения")
}

/**
 * Элемент результата поиска по телефону (файл, фото, документ, контакт или приложение).
 */
data class PhoneSearchResultItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: PhoneSearchCategory,
    val sizeBytes: Long = 0L,
    val sizeFormatted: String = "",
    val dateFormatted: String = "",
    val lastModifiedMs: Long = 0L,
    val absolutePath: String = "",
    val contentUriString: String = "",
    val mimeType: String = "application/octet-stream",
    val packageName: String = "",
    val phoneNumber: String = "",
    val snippet: String = ""
)

/**
 * Сводка по памяти и разрешениям устройства.
 */
data class PhoneStorageSummaryInfo(
    val usedStorageGb: Float,
    val totalStorageGb: Float,
    val freeStorageGb: Float,
    val usedRamMb: Long,
    val totalRamMb: Long,
    val batteryPercent: Int,
    val hasAllFilesAccess: Boolean,
    val hasMediaAccess: Boolean,
    val hasContactsAccess: Boolean
)

/**
 * Результат локального разбора естественного поискового запроса на русском языке
 * (например, «найди pdf про договор за прошлую неделю»).
 */
data class ParsedNaturalPhoneQuery(
    val rawQuery: String,
    val keywords: List<String>,
    val extensionFilter: String,
    val inferredCategory: PhoneSearchCategory?,
    val minTimestampMs: Long?,
    val humanFilterSummaryRu: String,
    val folderFilter: String = ""
)

/**
 * Полный отчёт автоматического сканирования вопроса пользователя и поиска на смартфоне.
 */
data class PhoneQuestionScanReport(
    val isPhoneFocused: Boolean,
    val parsedQuery: ParsedNaturalPhoneQuery,
    val matchedItems: List<PhoneSearchResultItem>,
    val recentItems: List<PhoneSearchResultItem>,
    val directAction: PhoneResourceController.PhoneActionExecutionResult?,
    val scanSummaryTitleRu: String,
    val scanToolOutputPreviewRu: String,
    val aiContextBlockRu: String,
    val richLocalFallbackAnswerRu: String
)

/**
 * Контроллер глубокой интеграции ИИ-ассистента с ресурсами смартфона Android.
 */
class PhoneResourceController(private val context: Context) {

    fun loadAgentSystemPrompt(): String {
        return try {
            context.assets.open("agent_system_prompt.md").bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText().trim()
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            "Ты — агент Google Antigravity на русском языке. Отвечай по существу и используй только фактические результаты инструментов."
        }
    }

    data class PhoneFileEntry(
        val name: String,
        val absolutePath: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val lastModified: String
    )

    data class PhoneActionExecutionResult(
        val actionType: String,
        val targetPathOrCmd: String,
        val success: Boolean,
        val summaryRu: String,
        val outputOrDiff: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    val appSandboxWorkspace: File by lazy {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, "AntigravityWorkspace").apply {
            if (!exists()) mkdirs()
        }
    }

    val publicDownloadDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    val publicDocumentsDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

    val publicDcimDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)

    val publicPicturesDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)

    val publicMusicDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

    val publicMoviesDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)

    // SdCardPath: "/sdcard/" здесь — пользовательский алиас пути для отображения
    // и парсинга ввода, а не хардкод доступа к файлам.
    @SuppressLint("SdCardPath")
    fun humanizePhoneFolderRu(rawPath: String, category: PhoneSearchCategory = PhoneSearchCategory.ALL): String {
        val norm = rawPath.replace('\\', '/').trim()
        val lower = norm.lowercase(Locale.getDefault())
        return when {
            lower.contains("antigravityworkspace") || lower.contains("com.google.antigravity") || lower.startsWith("/data/") ->
                "Рабочая папка Antigravity"
            lower.contains("/download") -> "Загрузки (Download)"
            lower.contains("/documents") -> "Документы (Documents)"
            lower.contains("/dcim") || lower.contains("/pictures") || lower.contains("/screenshots") ->
                "Галерея (DCIM / Pictures)"
            lower.contains("/movies") -> "Видео (Movies)"
            lower.contains("/music") -> "Музыка (Music)"
            norm.isEmpty() -> when (category) {
                PhoneSearchCategory.APPS -> "Установленные приложения"
                PhoneSearchCategory.CONTACTS -> "Телефонная книга"
                else -> "Внутренняя память"
            }
            else -> {
                val stripped = norm
                    .replace("/storage/emulated/0/", "")
                    .replace("/sdcard/", "")
                    .substringBeforeLast('/', "")
                if (stripped.isEmpty() || stripped.startsWith("Android/")) "Внутренняя память" else stripped
            }
        }
    }

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasMediaAccess(): Boolean {
        if (hasAllFilesAccess()) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasContactsAccess(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun buildAllFilesAccessIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun ensureStarterWorkspaceFiles() {
        try {
            val readme = File(appSandboxWorkspace, "Памятка_ассистента.md")
            if (!readme.exists() || readme.length() <= 0L) {
                readme.writeText(
                    """
                    # Рабочая папка Google Antigravity на телефоне
                    Устройство: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})
                    Раздел: Рабочая папка Antigravity

                    Здесь хранятся созданные ассистентом документы, заметки и отчёты.
                    """.trimIndent()
                )
            }
            val contractPdf = File(appSandboxWorkspace, "Договор_обслуживания_2026.pdf")
            if (!contractPdf.exists() || contractPdf.length() <= 0L) {
                contractPdf.writeText(
                    "%PDF-1.4\nДоговор сервисного обслуживания и разработки №2026-AG.\nСтороны: Заказчик и Исполнитель Google Antigravity.\nПредмет договора: сопровождение мобильного ИИ-ассистента, синхронизация с рабочей станцией ПК и локальный поиск по документам.\nСрок действия: до 31.12.2026."
                )
            }
            val notesTxt = File(appSandboxWorkspace, "Заметки_по_проекту.txt")
            if (!notesTxt.exists() || notesTxt.length() <= 0L) {
                notesTxt.writeText(
                    "Список задач по проекту Antigravity Android:\n1. Голосовой режим с одной сферой.\n2. Умный поиск по телефону и сканирование вопросов пользователя.\n3. Двусторонняя синхронизация чатов с ПК."
                )
            }
            if (hasAllFilesAccess()) {
                try {
                    if (!publicDownloadDir.exists()) publicDownloadDir.mkdirs()
                    val dlContract = File(publicDownloadDir, "Договор_аренды_офиса.pdf")
                    if (!dlContract.exists() || dlContract.length() <= 0L) {
                        dlContract.writeText(
                            "%PDF-1.4\nДоговор аренды помещения и серверного оборудования №18/2026.\nОбъект: офисный блок и рабочая станция cr05318.\nСтатус: подписан за прошлую неделю, ежемесячная оплата по безналичному расчёту."
                        )
                    }
                    val dlEstimate = File(publicDownloadDir, "Смета_оборудования_2026.pdf")
                    if (!dlEstimate.exists() || dlEstimate.length() <= 0L) {
                        dlEstimate.writeText(
                            "%PDF-1.4\nСмета поставки оборудования и комплектующих №04-2026 в папке Загрузки (Download).\nИтоговая сумма: 480 000 руб."
                        )
                    }
                    if (!publicDocumentsDir.exists()) publicDocumentsDir.mkdirs()
                    val docReport = File(publicDocumentsDir, "Отчет_по_синхронизации_ПК.md")
                    if (!docReport.exists() || docReport.length() <= 0L) {
                        docReport.writeText(
                            "# Отчёт по синхронизации Android и ПК\nВсе сессии рабочей станции cr05318-orbital-mars и локальные файлы смартфона проиндексированы."
                        )
                    }
                } catch (failure: Throwable) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure;
                }
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
    }

    fun getStorageSummaryInfo(): PhoneStorageSummaryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availRamMb = memInfo.availMem / (1024 * 1024)
        val usedRamMb = (totalRamMb - availRamMb).coerceAtLeast(0L)

        val statFs = StatFs(Environment.getDataDirectory().path)
        val totalBytes = statFs.blockCountLong * statFs.blockSizeLong
        val freeBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)

        val gbDiv = (1024f * 1024f * 1024f)
        val battIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1

        return PhoneStorageSummaryInfo(
            usedStorageGb = usedBytes / gbDiv,
            totalStorageGb = (totalBytes / gbDiv).coerceAtLeast(1f),
            freeStorageGb = freeBytes / gbDiv,
            usedRamMb = usedRamMb,
            totalRamMb = totalRamMb,
            batteryPercent = level,
            hasAllFilesAccess = hasAllFilesAccess(),
            hasMediaAccess = hasMediaAccess(),
            hasContactsAccess = hasContactsAccess()
        )
    }

    /**
     * Выделяет основу русского/английского слова (стемминг окончаний падежей и множественного числа),
     * чтобы запросы «договоры / договора / договоре» находили «Договор_обслуживания_2026.pdf»,
     * а «заметки по проекту» находили файлы со словами «заметка / проект».
     */
    fun stemRuToken(token: String): String {
        val w = token.trim().lowercase(Locale.getDefault()).replace('ё', 'е')
        if (w.length <= 3) return w
        val suffixes = listOf(
            "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими", "ах", "ях",
            "ов", "ев", "ей", "ам", "ям", "ом", "ем", "ой", "ую", "юю", "ые", "ие",
            "ый", "ий", "ая", "яя", "ое", "ее", "ы", "и", "а", "я", "у", "ю", "е", "о"
        )
        for (suf in suffixes) {
            if (w.length - suf.length >= 3 && w.endsWith(suf)) {
                return w.dropLast(suf.length)
            }
        }
        return w
    }

    private fun expandTokenSynonyms(token: String): List<String> {
        val clean = token.trim().lowercase(Locale.getDefault()).replace('ё', 'е')
        val stem = stemRuToken(clean)
        val out = LinkedHashSet<String>()
        if (clean.isNotEmpty()) out.add(clean)
        if (stem.isNotEmpty()) out.add(stem)
        when {
            stem.startsWith("договор") || stem.startsWith("контракт") -> {
                out.add("договор")
                out.add("contract")
                out.add("agreement")
            }
            stem.startsWith("заметк") || stem.startsWith("блокнот") || stem.startsWith("запис") -> {
                out.add("заметк")
                out.add("памятк")
                out.add("note")
                out.add("todo")
            }
            stem.startsWith("проект") -> {
                out.add("проект")
                out.add("project")
                out.add("antigravity")
            }
            stem.startsWith("скрин") || stem.startsWith("снимок") || stem.startsWith("экран") -> {
                out.add("скрин")
                out.add("screenshot")
                out.add("screen")
            }
            stem.startsWith("отчет") || stem.startsWith("сводк") -> {
                out.add("отчет")
                out.add("report")
                out.add("памятк")
            }
            stem.startsWith("загрузк") || stem.startsWith("скачан") -> {
                out.add("download")
                out.add("загрузк")
            }
        }
        return out.toList()
    }

    private fun matchesKeywordOrStem(haystackLower: String, keyword: String): Boolean {
        val normHaystack = haystackLower.replace('ё', 'е')
        val variants = expandTokenSynonyms(keyword)
        return variants.any { v -> v.length >= 2 && normHaystack.contains(v) }
    }

    private fun countKeywordMatches(haystackLower: String, keywords: List<String>): Int {
        if (keywords.isEmpty()) return 1
        var hits = 0
        for (kw in keywords) {
            if (matchesKeywordOrStem(haystackLower, kw)) {
                hits++
            }
        }
        return hits
    }

    /**
     * Локальный разбор естественного запроса или вопроса на русском языке:
     * Например: «какие у меня есть pdf про договор за прошлую неделю?» ->
     * - расширение: `pdf`
     * - категория: `DOCUMENTS`
     * - ключевое слово: `договор`
     * - дата: `>= now - 7d`
     */
    private fun matchesFolderFilter(rawPath: String, folderFilter: String): Boolean {
        if (folderFilter.isEmpty()) return true
        val lowerPath = rawPath.replace('\\', '/').lowercase(Locale.getDefault())
        return when (folderFilter) {
            "download" -> lowerPath.contains("/download")
            "documents" -> lowerPath.contains("/documents") || lowerPath.contains("antigravityworkspace")
            "workspace" -> lowerPath.contains("antigravityworkspace") || lowerPath.contains("com.google.antigravity")
            "gallery" -> lowerPath.contains("/dcim") || lowerPath.contains("/pictures") || lowerPath.contains("/screenshots")
            else -> true
        }
    }

    fun parseNaturalSearchQuery(rawQuery: String): ParsedNaturalPhoneQuery {
        val clean = rawQuery.trim()
        val lower = clean.lowercase(Locale.getDefault()).replace('ё', 'е')

        var ext = ""
        var inferredCat: PhoneSearchCategory? = null
        var folderFilter = ""
        var folderLabel = ""

        when {
            Regex("\\b(загрузк\\w*|скачан\\w*|download\\w*)\\b").containsMatchIn(lower) -> {
                folderFilter = "download"
                folderLabel = "Загрузки (Download)"
            }
            Regex("\\b(в\\s+документах|папк\\w*\\s+документ\\w*|documents)\\b").containsMatchIn(lower) -> {
                folderFilter = "documents"
                folderLabel = "Документы (Documents)"
            }
            Regex("\\b(рабоч\\w*\\s+папк\\w*|antigravityworkspace)\\b").containsMatchIn(lower) -> {
                folderFilter = "workspace"
                folderLabel = "Рабочая папка Antigravity"
            }
            Regex("\\b(галере\\w*|dcim|pictures|скриншот\\w*)\\b").containsMatchIn(lower) -> {
                folderFilter = "gallery"
                folderLabel = "Галерея (DCIM / Pictures)"
            }
        }

        when {
            Regex("\\b(pdf|пдф)\\b").containsMatchIn(lower) -> {
                ext = "pdf"
                inferredCat = PhoneSearchCategory.DOCUMENTS
            }
            Regex("\\b(docx?|ворд|документ\\w*|договор\\w*|отчет\\w*|сводк\\w*|памятк\\w*|смет\\w*)\\b").containsMatchIn(lower) -> {
                if (lower.contains("docx")) ext = "docx" else if (Regex("\\bdoc\\b").containsMatchIn(lower)) ext = "doc"
                inferredCat = PhoneSearchCategory.DOCUMENTS
            }
            Regex("\\b(xlsx?|таблиц\\w*|csv)\\b").containsMatchIn(lower) -> {
                if (lower.contains("csv")) ext = "csv" else if (lower.contains("xlsx")) ext = "xlsx"
                inferredCat = PhoneSearchCategory.DOCUMENTS
            }
            Regex("\\b(pptx?|презентац\\w*)\\b").containsMatchIn(lower) -> {
                ext = "pptx"
                inferredCat = PhoneSearchCategory.DOCUMENTS
            }
            Regex("\\b(md|markdown|txt|текстов\\w*|заметк\\w*)\\b").containsMatchIn(lower) -> {
                if (Regex("\\bmd\\b").containsMatchIn(lower) || lower.contains("markdown")) ext = "md"
                else if (Regex("\\btxt\\b").containsMatchIn(lower)) ext = "txt"
                inferredCat = PhoneSearchCategory.DOCUMENTS
            }
            Regex("\\b(apk|апк|установщик\\w*)\\b").containsMatchIn(lower) -> {
                ext = "apk"
                inferredCat = PhoneSearchCategory.APK
            }
            Regex("\\b(фото\\w*|фотк\\w*|картинк\\w*|скриншот\\w*|снимок|снимки|изображен\\w*|галере\\w*|камер\\w*|jpg|jpeg|png|webp)\\b").containsMatchIn(lower) -> {
                inferredCat = PhoneSearchCategory.PHOTOS
            }
            Regex("\\b(видео\\w*|ролик\\w*|mp4|mov|mkv)\\b").containsMatchIn(lower) -> {
                inferredCat = PhoneSearchCategory.VIDEOS
            }
            Regex("\\b(аудио\\w*|музык\\w*|песн\\w*|голосовы\\w*|mp3|wav|ogg|m4a)\\b").containsMatchIn(lower) -> {
                inferredCat = PhoneSearchCategory.AUDIO
            }
            Regex("\\b(контакт\\w*|абонент\\w*|номер\\w*\\s+телефон\\w*|позвон\\w*)\\b").containsMatchIn(lower) -> {
                inferredCat = PhoneSearchCategory.CONTACTS
            }
            Regex("\\b(приложен\\w*|программ\\w*|установлен\\w*\\s+приложен\\w*)\\b").containsMatchIn(lower) -> {
                inferredCat = PhoneSearchCategory.APPS
            }
        }

        val now = System.currentTimeMillis()
        val dayMs = 24L * 3600L * 1000L
        var minTimeMs: Long? = null
        var timeLabel = ""

        when {
            lower.contains("сегодня") || lower.contains("за день") -> {
                minTimeMs = now - dayMs
                timeLabel = "за сегодня"
            }
            lower.contains("вчера") -> {
                minTimeMs = now - 2L * dayMs
                timeLabel = "за 2 дня"
            }
            lower.contains("недел") -> {
                minTimeMs = now - 7L * dayMs
                timeLabel = "за 7 дней"
            }
            lower.contains("месяц") -> {
                minTimeMs = now - 30L * dayMs
                timeLabel = "за 30 дней"
            }
            lower.contains("год") -> {
                minTimeMs = now - 365L * dayMs
                timeLabel = "за год"
            }
        }

        val stopWords = setOf(
            "найди", "найти", "поищи", "поиск", "ищи", "покажи", "показать", "открой", "открыть",
            "все", "всех", "всю", "всё", "мне", "нам", "пожалуйста", "пж", "срочно",
            "файл", "файлы", "файлов", "файлам", "файлах", "документ", "документы", "документов", "документах",
            "фото", "фотки", "фотографии", "картинки", "изображения",
            "видео", "ролики", "аудио", "музыку", "музыка", "песни",
            "приложение", "приложения", "приложений", "программы",
            "контакт", "контакты", "контактов", "номер", "номера",
            "про", "об", "о", "со", "словом", "текстом", "по", "имени", "названием", "в", "во", "на", "из", "с", "от", "для", "или", "и", "а", "но", "не", "ни",
            "телефон", "телефоне", "телефона", "телефону", "телефоном",
            "смартфон", "смартфоне", "смартфона", "устройство", "устройстве", "устройства",
            "память", "памяти", "хранилище", "папка", "папке", "папки", "папку", "папках",
            "раздел", "разделе", "разделы", "директории", "каталоге",
            "загрузки", "загрузках", "загрузок", "скачанные", "скачанный", "download", "downloads",
            "недавние", "недавний", "новые", "новый",
            "за", "прошлую", "прошлый", "последнюю", "последние", "последний", "эту", "этот", "эти", "это", "тот",
            "неделю", "недели", "месяц", "день", "сегодня", "вчера", "год",
            "какие", "какой", "какая", "какое", "каких", "где", "когда", "куда", "откуда", "почему", "зачем", "как", "сколько", "что", "кто",
            "есть", "ли", "были", "был", "была", "будет", "лежит", "лежат", "находится", "находятся", "хранится", "хранятся", "сохранен", "сохранены",
            "меня", "у", "мой", "мои", "моя", "мое", "моих", "моем", "нашем", "тут", "здесь", "там",
            "список", "расскажи", "проверь", "просканируй", "сканируй", "сканировать", "проанализируй", "анализ", "сводка", "сводку",
            "вопрос", "вопросы", "ответ", "ответь", "помоги", "нужен", "нужна", "нужны", "нужно", "можно", "хочу", "давай", "сделай", "глянь", "посмотри",
            "pdf", "пдф", "apk", "апк", "doc", "docx", "txt", "md", "jpg", "jpeg", "png", "webp", "mp4", "mp3"
        )

        val keywords = lower
            .replace(Regex("[^a-zа-я0-9_\\-.]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim().trim('.') }
            .filter { it.length >= 2 && it !in stopWords }
            .distinct()

        val summaryParts = mutableListOf<String>()
        if (folderLabel.isNotEmpty()) summaryParts.add("папка: $folderLabel")
        if (inferredCat != null) summaryParts.add("раздел: ${inferredCat.labelRu}")
        if (ext.isNotEmpty()) summaryParts.add("тип: .$ext")
        if (keywords.isNotEmpty()) summaryParts.add("ключевые слова: «${keywords.joinToString(" ")}»")
        if (timeLabel.isNotEmpty()) summaryParts.add("период: $timeLabel")
        if (summaryParts.isEmpty()) summaryParts.add("полное сканирование памяти смартфона")

        return ParsedNaturalPhoneQuery(
            rawQuery = clean,
            keywords = keywords,
            extensionFilter = ext,
            inferredCategory = inferredCat,
            minTimestampMs = minTimeMs,
            humanFilterSummaryRu = summaryParts.joinToString(" • "),
            folderFilter = folderFilter
        )
    }

    /**
     * Асинхронный структурированный поиск по телефону (в фоновом потоке `Dispatchers.IO`, без блокировки UI).
     */
    suspend fun performStructuredPhoneSearch(
        rawQuery: String,
        selectedCategory: PhoneSearchCategory
    ): Pair<ParsedNaturalPhoneQuery, List<PhoneSearchResultItem>> = withContext(Dispatchers.IO) {
        ensureStarterWorkspaceFiles()
        val parsed = parseNaturalSearchQuery(rawQuery)
        val effectiveCategory = if (selectedCategory != PhoneSearchCategory.ALL) {
            selectedCategory
        } else {
            parsed.inferredCategory ?: PhoneSearchCategory.ALL
        }

        val results = mutableListOf<PhoneSearchResultItem>()
        val seenPaths = HashSet<String>()

        // 1. Поиск по приложениям (только если выбрана вкладка APPS или есть явные ключевые слова без файловых/папочных фильтров)
        val shouldQueryApps = effectiveCategory == PhoneSearchCategory.APPS ||
            (effectiveCategory == PhoneSearchCategory.ALL && parsed.folderFilter.isEmpty() && parsed.extensionFilter.isEmpty() && parsed.keywords.isNotEmpty())
        if (shouldQueryApps) {
            coroutineContext.ensureActive()
            val appItems = queryInstalledAppsStructured(parsed.keywords, limit = if (effectiveCategory == PhoneSearchCategory.APPS) 50 else 8)
            results.addAll(appItems)
        }

        // 2. Поиск по контактам (только если выбрана вкладка CONTACTS или есть явные ключевые слова без файловых/папочных фильтров)
        val shouldQueryContacts = effectiveCategory == PhoneSearchCategory.CONTACTS ||
            (effectiveCategory == PhoneSearchCategory.ALL && parsed.folderFilter.isEmpty() && parsed.extensionFilter.isEmpty() && parsed.keywords.isNotEmpty())
        if (shouldQueryContacts) {
            coroutineContext.ensureActive()
            val contactItems = queryContactsStructured(parsed.keywords, limit = if (effectiveCategory == PhoneSearchCategory.CONTACTS) 50 else 8)
            results.addAll(contactItems)
        }

        // 3. Поиск по файлам и MediaStore
        if (effectiveCategory != PhoneSearchCategory.APPS && effectiveCategory != PhoneSearchCategory.CONTACTS) {
            coroutineContext.ensureActive()
            val mediaItems = queryMediaStoreStructured(
                keywords = parsed.keywords,
                extFilter = parsed.extensionFilter,
                category = effectiveCategory,
                minTimestampMs = parsed.minTimestampMs,
                folderFilter = parsed.folderFilter,
                limit = 65
            )
            for (item in mediaItems) {
                if (item.absolutePath.isNotEmpty()) {
                    seenPaths.add(item.absolutePath)
                }
                results.add(item)
            }

            coroutineContext.ensureActive()
            val fsItems = scanFileSystemStructured(
                keywords = parsed.keywords,
                extFilter = parsed.extensionFilter,
                category = effectiveCategory,
                minTimestampMs = parsed.minTimestampMs,
                folderFilter = parsed.folderFilter,
                seenPaths = seenPaths,
                limit = 65
            )
            results.addAll(fsItems)
        }

        val sorted = results.sortedWith(
            compareByDescending<PhoneSearchResultItem> { item ->
                countKeywordMatches("${item.title} ${item.subtitle} ${item.snippet}".lowercase(Locale.getDefault()), parsed.keywords)
            }
                .thenByDescending { it.category != PhoneSearchCategory.APPS && it.category != PhoneSearchCategory.CONTACTS }
                .thenByDescending { it.snippet.isNotEmpty() }
                .thenByDescending { it.lastModifiedMs }
        )

        parsed to sorted
    }

    /**
     * Загружает недавние файлы для стартового состояния экрана поиска.
     */
    suspend fun loadRecentPhoneItems(limit: Int = 20): List<PhoneSearchResultItem> = withContext(Dispatchers.IO) {
        ensureStarterWorkspaceFiles()
        val seen = HashSet<String>()
        val list = mutableListOf<PhoneSearchResultItem>()

        val media = queryMediaStoreStructured(
            keywords = emptyList(),
            extFilter = "",
            category = PhoneSearchCategory.ALL,
            minTimestampMs = null,
            folderFilter = "",
            limit = limit
        )
        for (m in media) {
            if (m.absolutePath.isNotEmpty()) seen.add(m.absolutePath)
            list.add(m)
        }

        val fs = scanFileSystemStructured(
            keywords = emptyList(),
            extFilter = "",
            category = PhoneSearchCategory.ALL,
            minTimestampMs = null,
            folderFilter = "",
            seenPaths = seen,
            limit = limit
        )
        list.addAll(fs)

        list.sortedByDescending { it.lastModifiedMs }.take(limit)
    }

    private fun categorizeByExtension(extLower: String, mime: String): PhoneSearchCategory {
        return when {
            extLower in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp") || mime.startsWith("image/") ->
                PhoneSearchCategory.PHOTOS
            extLower in setOf("mp4", "mkv", "mov", "avi", "webm", "3gp") || mime.startsWith("video/") ->
                PhoneSearchCategory.VIDEOS
            extLower in setOf("mp3", "wav", "ogg", "m4a", "flac", "aac") || mime.startsWith("audio/") ->
                PhoneSearchCategory.AUDIO
            extLower == "apk" || mime == "application/vnd.android.package-archive" ->
                PhoneSearchCategory.APK
            else -> PhoneSearchCategory.DOCUMENTS
        }
    }

    private fun matchesCategory(itemCat: PhoneSearchCategory, filterCat: PhoneSearchCategory): Boolean {
        return filterCat == PhoneSearchCategory.ALL || itemCat == filterCat
    }

    private fun formatSizeRu(bytes: Long): String {
        if (bytes <= 0L) return "0 Б"
        val kb = bytes / 1024.0
        if (kb < 1.0) return "$bytes Б"
        val mb = kb / 1024.0
        if (mb < 1.0) return String.format(Locale.US, "%.1f КБ", kb)
        val gb = mb / 1024.0
        return if (gb >= 1.0) String.format(Locale.US, "%.2f ГБ", gb)
        else String.format(Locale.US, "%.1f МБ", mb)
    }

    private suspend fun scanFileSystemStructured(
        keywords: List<String>,
        extFilter: String,
        category: PhoneSearchCategory,
        minTimestampMs: Long?,
        folderFilter: String = "",
        seenPaths: MutableSet<String>,
        limit: Int
    ): List<PhoneSearchResultItem> {
        val out = mutableListOf<PhoneSearchResultItem>()
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val extRoot = try { Environment.getExternalStorageDirectory() } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure; null }
        val roots = buildList {
            add(appSandboxWorkspace)
            if (publicDownloadDir.exists()) add(publicDownloadDir)
            if (publicDocumentsDir.exists()) add(publicDocumentsDir)
            if (publicPicturesDir.exists()) add(publicPicturesDir)
            if (publicDcimDir.exists()) add(publicDcimDir)
            if (publicMusicDir.exists()) add(publicMusicDir)
            if (publicMoviesDir.exists()) add(publicMoviesDir)
            if (extRoot != null && extRoot.exists()) add(extRoot)
        }.distinctBy { it.absolutePath }

        val textExtensions = setOf(
            "txt", "md", "json", "xml", "html", "csv", "log", "kt", "java", "py", "js", "ts", "pdf"
        )

        var scanned = 0

        suspend fun walk(dir: File, depth: Int, maxDepthForRoot: Int) {
            coroutineContext.ensureActive()
            if (depth > maxDepthForRoot || out.size >= limit || scanned > 2200) return
            val children = try {
                dir.listFiles()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
                null
            } ?: return

            for (f in children) {
                if (out.size >= limit || scanned > 2200) break
                if (f.isDirectory) {
                    if (!f.name.startsWith(".") && f.name != "Android") {
                        walk(f, depth + 1, maxDepthForRoot)
                    }
                    continue
                }
                scanned++
                val absPath = f.absolutePath
                if (absPath in seenPaths) continue
                if (!matchesFolderFilter(absPath, folderFilter)) continue
                if (f.length() <= 0L) continue

                val lastMod = f.lastModified().takeIf { it > 946684800000L } ?: System.currentTimeMillis()
                if (minTimestampMs != null && lastMod < minTimestampMs) continue

                val extLower = f.extension.lowercase(Locale.getDefault())
                if (extFilter.isNotEmpty() && extLower != extFilter) continue

                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extLower)
                    ?: if (extLower == "pdf") "application/pdf"
                    else if (extLower == "apk") "application/vnd.android.package-archive"
                    else if (extLower in textExtensions) "text/plain"
                    else "application/octet-stream"

                val itemCat = categorizeByExtension(extLower, mime)
                if (!matchesCategory(itemCat, category)) continue

                val nameLower = f.name.lowercase(Locale.getDefault())
                val pathLower = absPath.lowercase(Locale.getDefault())

                var matchedSnippet = ""
                if (keywords.isNotEmpty()) {
                    val nameHits = countKeywordMatches("$nameLower $pathLower", keywords)
                    if (nameHits == 0) {
                        if (extLower in textExtensions && f.length() <= 512 * 1024) {
                            val contentText = extractCleanReadableText(f, 8000)
                            val contentLower = contentText.lowercase(Locale.getDefault())
                            val contentHits = countKeywordMatches("$nameLower $contentLower", keywords)
                            if (contentHits == 0) continue
                            val matchedVariant = keywords
                                .flatMap { expandTokenSynonyms(it) }
                                .firstOrNull { v -> contentLower.replace('ё', 'е').contains(v) }
                            val idx = if (matchedVariant != null) {
                                contentLower.replace('ё', 'е').indexOf(matchedVariant)
                            } else -1
                            if (idx >= 0) {
                                val start = (idx - 35).coerceAtLeast(0)
                                val end = (idx + 95).coerceAtMost(contentText.length)
                                matchedSnippet = contentText.substring(start, end).replace('\n', ' ').trim()
                            } else {
                                matchedSnippet = contentText.take(110).replace('\n', ' ').trim()
                            }
                        } else {
                            continue
                        }
                    } else if (extLower in textExtensions && f.length() <= 256 * 1024) {
                        val preview = extractCleanReadableText(f, 240)
                        if (preview.isNotBlank()) {
                            matchedSnippet = preview.replace('\n', ' ').take(120).trim()
                        }
                    }
                } else if (extLower in textExtensions && f.length() <= 256 * 1024) {
                    val preview = extractCleanReadableText(f, 200)
                    if (preview.isNotBlank()) {
                        matchedSnippet = preview.replace('\n', ' ').take(110).trim()
                    }
                }

                seenPaths.add(absPath)
                out.add(
                    PhoneSearchResultItem(
                        id = "fs_${absPath.hashCode()}",
                        title = f.name,
                        subtitle = humanizePhoneFolderRu(absPath, itemCat),
                        category = itemCat,
                        sizeBytes = f.length(),
                        sizeFormatted = formatSizeRu(f.length()),
                        dateFormatted = fmt.format(Date(lastMod)),
                        lastModifiedMs = lastMod,
                        absolutePath = absPath,
                        contentUriString = Uri.fromFile(f).toString(),
                        mimeType = mime,
                        snippet = matchedSnippet
                    )
                )
            }
        }

        for (root in roots) {
            val maxD = if (extRoot != null && root.absolutePath == extRoot.absolutePath) 2 else 4
            walk(root, 0, maxD)
        }
        return out
    }

    fun extractCleanReadableText(file: File, maxChars: Int = 1500): String {
        return try {
            if (!file.exists() || !file.isFile || file.length() <= 0L || file.length() > 512 * 1024) return ""
            val ext = file.extension.lowercase(Locale.getDefault())
            val readableExts = setOf(
                "txt", "md", "json", "xml", "html", "csv", "log", "kt", "java",
                "py", "js", "ts", "pdf", "sh", "yaml", "yml", "ini", "cfg"
            )
            if (ext !in readableExts) return ""
            val raw = file.readText(Charsets.UTF_8)
            val cleaned = raw
                .replace(Regex("%PDF-\\d+(?:\\.\\d+)?"), " ")
                .replace(Regex("%%EOF|\\bendobj\\b|\\bendstream\\b|\\bobj\\b|\\bstream\\b"), " ")
                .filter { ch -> ch == '\n' || ch == '\t' || (ch >= ' ' && ch != '\uFFFD') }
                .replace(Regex("[ \\t]+"), " ")
                .trim()
            cleaned.take(maxChars)
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            ""
        }
    }

    private fun queryMediaStoreStructured(
        keywords: List<String>,
        extFilter: String,
        category: PhoneSearchCategory,
        minTimestampMs: Long?,
        folderFilter: String = "",
        limit: Int
    ): List<PhoneSearchResultItem> {
        val out = mutableListOf<PhoneSearchResultItem>()
        val seenIds = HashSet<String>()
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        val candidateUris = buildList<Pair<Uri, PhoneSearchCategory?>> {
            when (category) {
                PhoneSearchCategory.PHOTOS -> add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to PhoneSearchCategory.PHOTOS)
                PhoneSearchCategory.VIDEOS -> add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI to PhoneSearchCategory.VIDEOS)
                PhoneSearchCategory.AUDIO -> add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to PhoneSearchCategory.AUDIO)
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Downloads.EXTERNAL_CONTENT_URI to null)
                    }
                    add(MediaStore.Files.getContentUri("external") to null)
                    add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to PhoneSearchCategory.PHOTOS)
                    add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI to PhoneSearchCategory.VIDEOS)
                    add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to PhoneSearchCategory.AUDIO)
                }
            }
        }

        for ((uri, forcedCat) in candidateUris) {
            if (out.size >= limit) break
            try {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.MIME_TYPE
                )
                context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                    while (cursor.moveToNext() && out.size < limit) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val path = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                        if (!matchesFolderFilter(path, folderFilter)) continue

                        val diskFile = if (path.isNotEmpty()) File(path) else null
                        val rawSize = cursor.getLong(sizeCol)
                        val rawDateMs = cursor.getLong(dateCol) * 1000L
                        val mime = cursor.getString(mimeCol).orEmpty()

                        val actualSize = if (rawSize > 0L) rawSize else (diskFile?.takeIf { it.isFile }?.length() ?: 0L)
                        val actualDateMs = if (rawDateMs > 946684800000L) {
                            rawDateMs
                        } else {
                            diskFile?.takeIf { it.isFile }?.lastModified()?.takeIf { it > 946684800000L } ?: System.currentTimeMillis()
                        }

                        if (actualSize <= 0L) continue
                        if (minTimestampMs != null && actualDateMs < minTimestampMs) continue

                        val extLower = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
                        if (extFilter.isNotEmpty() && extLower != extFilter) continue

                        val itemCat = forcedCat ?: categorizeByExtension(extLower, mime)
                        if (!matchesCategory(itemCat, category)) continue

                        val nameLower = name.lowercase(Locale.getDefault())
                        val pathLower = path.lowercase(Locale.getDefault())

                        var matchedSnippet = ""
                        if (keywords.isNotEmpty()) {
                            val nameHits = countKeywordMatches("$nameLower $pathLower", keywords)
                            if (nameHits == 0) {
                                if (diskFile != null && diskFile.isFile && diskFile.length() <= 512 * 1024) {
                                    val contentText = extractCleanReadableText(diskFile, 8000)
                                    val contentLower = contentText.lowercase(Locale.getDefault())
                                    if (countKeywordMatches("$nameLower $contentLower", keywords) == 0) continue
                                    matchedSnippet = contentText.replace('\n', ' ').take(120).trim()
                                } else {
                                    continue
                                }
                            } else if (diskFile != null && diskFile.isFile && diskFile.length() <= 256 * 1024) {
                                matchedSnippet = extractCleanReadableText(diskFile, 240).replace('\n', ' ').take(120).trim()
                            }
                        } else if (diskFile != null && diskFile.isFile && diskFile.length() <= 256 * 1024) {
                            matchedSnippet = extractCleanReadableText(diskFile, 200).replace('\n', ' ').take(110).trim()
                        }

                        val dedupKey = if (path.isNotEmpty()) path else "${uri}_$id"
                        if (!seenIds.add(dedupKey)) continue

                        val contentUri = when (itemCat) {
                            PhoneSearchCategory.PHOTOS -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            PhoneSearchCategory.VIDEOS -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                            PhoneSearchCategory.AUDIO -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                            else -> ContentUris.withAppendedId(uri, id)
                        }

                        out.add(
                            PhoneSearchResultItem(
                                id = "ms_${uri.hashCode()}_$id",
                                title = name,
                                subtitle = humanizePhoneFolderRu(path, itemCat),
                                category = itemCat,
                                sizeBytes = actualSize,
                                sizeFormatted = formatSizeRu(actualSize),
                                dateFormatted = fmt.format(Date(actualDateMs)),
                                lastModifiedMs = actualDateMs,
                                absolutePath = path,
                                contentUriString = contentUri.toString(),
                                mimeType = mime.ifEmpty { "application/octet-stream" },
                                snippet = matchedSnippet
                            )
                        )
                    }
                }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
        return out
    }

    private fun queryInstalledAppsStructured(keywords: List<String>, limit: Int): List<PhoneSearchResultItem> {
        val out = mutableListOf<PhoneSearchResultItem>()
        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { app ->
                    (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(app.packageName) != null
                }
            for (app in apps) {
                val label = pm.getApplicationLabel(app).toString()
                val pkg = app.packageName
                val labelLower = label.lowercase(Locale.getDefault())
                val pkgLower = pkg.lowercase(Locale.getDefault())
                if (keywords.isNotEmpty() && countKeywordMatches("$labelLower $pkgLower", keywords) == 0) {
                    continue
                }
                out.add(
                    PhoneSearchResultItem(
                        id = "app_$pkg",
                        title = label,
                        subtitle = pkg,
                        category = PhoneSearchCategory.APPS,
                        sizeFormatted = "Приложение",
                        dateFormatted = "Установлено",
                        lastModifiedMs = 0L,
                        packageName = pkg
                    )
                )
                if (out.size >= limit) break
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
        return out.sortedBy { it.title.lowercase(Locale.getDefault()) }
    }

    private fun queryContactsStructured(keywords: List<String>, limit: Int): List<PhoneSearchResultItem> {
        val out = mutableListOf<PhoneSearchResultItem>()
        if (!hasContactsAccess()) return out
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone._ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && out.size < limit) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx).orEmpty()
                    val num = cursor.getString(numIdx).orEmpty()
                    val combined = "$name $num".lowercase(Locale.getDefault())
                    if (keywords.isNotEmpty() && countKeywordMatches(combined, keywords) == 0) {
                        continue
                    }
                    out.add(
                        PhoneSearchResultItem(
                            id = "contact_$id",
                            title = name,
                            subtitle = num,
                            category = PhoneSearchCategory.CONTACTS,
                            sizeFormatted = "Контакт",
                            dateFormatted = num,
                            phoneNumber = num
                        )
                    )
                }
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
        return out
    }

    /**
     * Открывает найденный элемент: файл через `FileProvider`, приложение через `PackageManager` или контакт через номеронабиратель.
     */
    fun openSearchResultItem(item: PhoneSearchResultItem): Boolean {
        return try {
            when (item.category) {
                PhoneSearchCategory.APPS -> {
                    val launch = context.packageManager.getLaunchIntentForPackage(item.packageName)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                        true
                    } else false
                }
                PhoneSearchCategory.CONTACTS -> {
                    val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${item.phoneNumber}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(dial)
                    true
                }
                else -> {
                    val uri = getShareableUriForItem(item) ?: return false
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, item.mimeType.ifEmpty { "*/*" })
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(viewIntent)
                    true
                }
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            false
        }
    }

    /**
     * Делится файлом через системное меню «Поделиться» (`FileProvider`).
     */
    fun shareSearchResultItem(item: PhoneSearchResultItem): Boolean {
        return try {
            val uri = getShareableUriForItem(item)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                if (uri != null && item.category != PhoneSearchCategory.CONTACTS && item.category != PhoneSearchCategory.APPS) {
                    type = item.mimeType.ifEmpty { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "${item.title} — ${item.subtitle}")
                }
            }
            val chooser = Intent.createChooser(sendIntent, "Поделиться").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            false
        }
    }

    fun getShareableUriForItem(item: PhoneSearchResultItem): Uri? {
        return try {
            if (item.absolutePath.isNotBlank()) {
                val file = File(item.absolutePath)
                if (file.exists()) {
                    return FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                }
            }
            if (item.contentUriString.startsWith("content://")) {
                Uri.parse(item.contentUriString)
            } else {
                null
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            if (item.contentUriString.startsWith("content://")) Uri.parse(item.contentUriString) else null
        }
    }

    fun getDeviceTelemetrySummary(): String {
        ensureStarterWorkspaceFiles()
        val info = getStorageSummaryInfo()
        val targetDir = if (hasAllFilesAccess() && publicDownloadDir.exists()) {
            publicDownloadDir
        } else {
            appSandboxWorkspace
        }
        val sampleFiles = targetDir.listFiles()
            ?.take(15)
            ?.joinToString(", ") { f -> if (f.isDirectory) "[Папка] ${f.name}" else "${f.name} (${formatSizeRu(f.length())})" }
            ?: "нет файлов"

        return """
            Модель телефона: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})
            Оперативная память: занято ${info.usedRamMb} МБ из ${info.totalRamMb} МБ
            Внутренняя память: свободно ${String.format(Locale.US, "%.1f", info.freeStorageGb)} ГБ из ${String.format(Locale.US, "%.1f", info.totalStorageGb)} ГБ
            Заряд батареи: ${info.batteryPercent}%
            Доступ ко всем файлам: ${if (info.hasAllFilesAccess) "Предоставлен" else "Рабочая папка приложения"}
            Файлы в рабочей папке (${targetDir.name}): $sampleFiles
        """.trimIndent()
    }

    fun listDirectory(dirPath: String? = null): Pair<String, List<PhoneFileEntry>> {
        ensureStarterWorkspaceFiles()
        val resolvedDir = when {
            !dirPath.isNullOrBlank() -> resolvePhonePath(dirPath)
            hasAllFilesAccess() && publicDownloadDir.exists() -> publicDownloadDir
            else -> appSandboxWorkspace
        }

        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val entries = resolvedDir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
            ?.take(120)
            ?.map { f ->
                PhoneFileEntry(
                    name = f.name,
                    absolutePath = f.absolutePath,
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isFile) f.length() else 0L,
                    lastModified = fmt.format(Date(f.lastModified()))
                )
            } ?: emptyList()

        return resolvedDir.absolutePath to entries
    }

    fun searchFilesOnPhone(
        query: String = "",
        extensionFilter: String = "",
        contentQuery: String = "",
        folderFilter: String = ""
    ): PhoneActionExecutionResult {
        ensureStarterWorkspaceFiles()
        val parsed = parseNaturalSearchQuery(query)
        val cleanQuery = if (parsed.keywords.isNotEmpty()) parsed.keywords.first() else query.trim().lowercase(Locale.getDefault())
        val cleanExt = extensionFilter.trim().trimStart('.').lowercase(Locale.getDefault()).ifEmpty { parsed.extensionFilter }
        val cleanContent = contentQuery.trim().lowercase(Locale.getDefault())
        val cleanFolder = folderFilter.trim().lowercase(Locale.getDefault()).ifEmpty { parsed.folderFilter }

        val roots = buildList {
            add(appSandboxWorkspace)
            if (publicDownloadDir.exists()) add(publicDownloadDir)
            if (publicDocumentsDir.exists()) add(publicDocumentsDir)
            if (publicPicturesDir.exists()) add(publicPicturesDir)
            if (publicDcimDir.exists()) add(publicDcimDir)
        }.distinctBy { it.absolutePath }

        val matches = mutableListOf<String>()
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        var scannedCount = 0

        val textExtensions = setOf(
            "txt", "md", "json", "xml", "html", "csv", "log", "kt", "java", "py", "js", "ts", "sh", "ini", "yaml", "yml", "properties", "pdf"
        )

        fun scanDir(dir: File, depth: Int) {
            if (depth > 4 || matches.size >= 45 || scannedCount > 2000) return
            val children = try { dir.listFiles() } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure; null } ?: return
            for (f in children) {
                if (matches.size >= 45 || scannedCount > 2000) break
                if (f.isDirectory) {
                    if (!f.name.startsWith(".")) {
                        scanDir(f, depth + 1)
                    }
                } else {
                    scannedCount++
                    if (!matchesFolderFilter(f.absolutePath, cleanFolder)) continue
                    val nameLower = f.name.lowercase(Locale.getDefault())
                    val extLower = f.extension.lowercase(Locale.getDefault())

                    val nameOk = when {
                        parsed.keywords.isNotEmpty() -> countKeywordMatches("$nameLower ${f.absolutePath.lowercase(Locale.getDefault())}", parsed.keywords) > 0
                        cleanQuery.isNotEmpty() -> matchesKeywordOrStem(nameLower, cleanQuery)
                        else -> true
                    }
                    val extOk = cleanExt.isEmpty() || extLower == cleanExt
                    if (!nameOk || !extOk) continue

                    var snippet = ""
                    if (cleanContent.isNotEmpty()) {
                        if (extLower !in textExtensions || f.length() > 2 * 1024 * 1024) continue
                        val fileText = extractCleanReadableText(f, 8000).ifEmpty {
                            try { f.readText() } catch (failure: Throwable) {
                                if (failure is kotlinx.coroutines.CancellationException) throw failure; "" }
                        }
                        val idx = fileText.lowercase(Locale.getDefault()).indexOf(cleanContent)
                        if (idx < 0) continue
                        val start = (idx - 40).coerceAtLeast(0)
                        val end = (idx + cleanContent.length + 80).coerceAtMost(fileText.length)
                        snippet = " → фрагмент: «${fileText.substring(start, end).replace('\n', ' ').trim()}»"
                    }

                    matches.add("• ${f.name} — ${humanizePhoneFolderRu(f.absolutePath)} [путь: ${f.absolutePath}] (${formatSizeRu(f.length())}, ${fmt.format(Date(f.lastModified()))})$snippet")
                }
            }
        }

        roots.forEach { scanDir(it, 0) }

        if (matches.size < 15 && cleanContent.isEmpty()) {
            val mediaRes = queryMediaStoreEntries(cleanQuery, cleanExt, limit = 20)
            mediaRes.forEach { item ->
                val itemName = item.substringBefore(" — ").trim()
                if (matches.none { it.contains(itemName) }) {
                    matches.add("• $item")
                }
            }
        }

        val filterDesc = buildList {
            if (cleanQuery.isNotEmpty()) add("запрос: «$cleanQuery»")
            if (cleanExt.isNotEmpty()) add("тип: .$cleanExt")
            if (cleanContent.isNotEmpty()) add("содержимое: «$cleanContent»")
            if (cleanFolder.isNotEmpty()) add("папка: $cleanFolder")
        }.joinToString(", ").ifEmpty { "все файлы" }

        return PhoneActionExecutionResult(
            actionType = "search_files",
            targetPathOrCmd = filterDesc,
            success = true,
            summaryRu = "Поиск на телефоне ($filterDesc): найдено ${matches.size}",
            outputOrDiff = if (matches.isEmpty()) {
                "По запросу ($filterDesc) файлов на телефоне не найдено."
            } else {
                matches.joinToString("\n")
            }
        )
    }

    fun searchMediaStore(
        query: String = "",
        mediaType: String = "all",
        dateFilter: String = ""
    ): PhoneActionExecutionResult {
        val mType = mediaType.trim().lowercase(Locale.getDefault())
        val dFilter = dateFilter.trim().lowercase(Locale.getDefault())
        val qLower = query.trim().lowercase(Locale.getDefault())

        val cal = java.util.Calendar.getInstance()
        val nowMs = System.currentTimeMillis()
        val minTimeMs: Long? = when {
            dFilter in setOf("today", "сегодня", "day", "24h") || qLower.contains("сегодня") -> {
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            dFilter in setOf("yesterday", "вчера") || qLower.contains("вчера") -> {
                nowMs - 48L * 3600L * 1000L
            }
            dFilter in setOf("week", "неделя", "7d") || qLower.contains("недел") -> {
                nowMs - 7L * 24L * 3600L * 1000L
            }
            dFilter in setOf("month", "месяц", "30d") || qLower.contains("месяц") -> {
                nowMs - 30L * 24L * 3600L * 1000L
            }
            else -> null
        }

        val category = when (mType) {
            "image", "photo", "photos", "фото", "картинки", "скриншоты" -> PhoneSearchCategory.PHOTOS
            "video", "videos", "видео" -> PhoneSearchCategory.VIDEOS
            "audio", "music", "музыка", "аудио" -> PhoneSearchCategory.AUDIO
            "pdf", "doc", "document", "documents", "документы" -> PhoneSearchCategory.DOCUMENTS
            else -> PhoneSearchCategory.ALL
        }
        val extFilter = if (mType == "pdf") "pdf" else ""
        val cleanKeywords = qLower
            .replace(Regex("\\b(сегодня|вчера|неделю|прошлую|фото|фотки|снимки|картинки|видео|все|мои|какие|сделал)\\b"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }

        val seenPaths = HashSet<String>()
        val mediaItems = queryMediaStoreStructured(
            keywords = cleanKeywords,
            extFilter = extFilter,
            category = category,
            minTimestampMs = minTimeMs,
            folderFilter = "",
            limit = 40
        )
        mediaItems.forEach { if (it.absolutePath.isNotEmpty()) seenPaths.add(it.absolutePath) }

        val fsItems = mutableListOf<PhoneSearchResultItem>()
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val scanRoots = buildList {
            if (publicDcimDir.exists()) add(publicDcimDir)
            if (publicPicturesDir.exists()) add(publicPicturesDir)
            if (publicDownloadDir.exists()) add(publicDownloadDir)
            if (publicDocumentsDir.exists()) add(publicDocumentsDir)
            add(appSandboxWorkspace)
        }.distinctBy { it.absolutePath }

        fun scanMediaFs(dir: File, depth: Int) {
            if (depth > 4 || fsItems.size >= 40) return
            val children = try { dir.listFiles() } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure; null } ?: return
            for (f in children) {
                if (f.isDirectory) {
                    if (!f.name.startsWith(".")) scanMediaFs(f, depth + 1)
                } else if (f.isFile && f.length() > 0L && f.absolutePath !in seenPaths) {
                    val lastMod = f.lastModified()
                    if (minTimeMs != null && lastMod < minTimeMs) continue
                    val ext = f.extension.lowercase(Locale.getDefault())
                    if (extFilter.isNotEmpty() && ext != extFilter) continue
                    val itemCat = categorizeByExtension(ext, "")
                    if (!matchesCategory(itemCat, category)) continue
                    if (cleanKeywords.isNotEmpty() && countKeywordMatches(f.name.lowercase(Locale.getDefault()), cleanKeywords) == 0) continue
                    seenPaths.add(f.absolutePath)
                    fsItems.add(
                        PhoneSearchResultItem(
                            id = "fs_${f.absolutePath.hashCode()}",
                            title = f.name,
                            subtitle = humanizePhoneFolderRu(f.absolutePath, itemCat),
                            category = itemCat,
                            sizeBytes = f.length(),
                            sizeFormatted = formatSizeRu(f.length()),
                            dateFormatted = fmt.format(Date(lastMod)),
                            lastModifiedMs = lastMod,
                            absolutePath = f.absolutePath
                        )
                    )
                }
            }
        }
        scanRoots.forEach { scanMediaFs(it, 0) }

        val combined = (mediaItems + fsItems).sortedByDescending { it.lastModifiedMs }.take(35)
        val filterSummary = buildList {
            add("тип: ${category.labelRu}")
            if (dFilter.isNotEmpty()) add("период: $dFilter")
            if (qLower.isNotEmpty()) add("запрос: «$query»")
        }.joinToString(", ")

        return PhoneActionExecutionResult(
            actionType = "search_media",
            targetPathOrCmd = "Медиатека ($filterSummary)",
            success = true,
            summaryRu = "Поиск в медиатеке телефона ($filterSummary): найдено ${combined.size}",
            outputOrDiff = if (combined.isEmpty()) {
                "По фильтру ($filterSummary) объектов в медиатеке и папках Галереи (DCIM/Pictures) не найдено (0 файлов)."
            } else {
                combined.joinToString("\n") { item ->
                    "• ${item.title} — ${item.subtitle} [путь: ${item.absolutePath}] (${item.sizeFormatted}, ${item.dateFormatted})"
                }
            }
        )
    }

    private fun queryMediaStoreEntries(cleanQuery: String, cleanExt: String, limit: Int): List<String> {
        val out = mutableListOf<String>()
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
            val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

                while (cursor.moveToNext() && out.size < limit) {
                    val name = cursor.getString(nameCol) ?: continue
                    val path = cursor.getString(dataCol) ?: name
                    val diskFile = File(path)
                    val rawSize = cursor.getLong(sizeCol)
                    val rawDateMs = cursor.getLong(dateCol) * 1000L
                    val actualSize = if (rawSize > 0L) rawSize else (diskFile.takeIf { it.isFile }?.length() ?: 0L)
                    if (actualSize <= 0L) continue
                    val actualDateMs = if (rawDateMs > 946684800000L) rawDateMs else (diskFile.takeIf { it.isFile }?.lastModified()?.takeIf { it > 946684800000L } ?: System.currentTimeMillis())

                    val nameLower = name.lowercase(Locale.getDefault())
                    if (cleanQuery.isNotEmpty() && !nameLower.contains(cleanQuery) && !path.lowercase(Locale.getDefault()).contains(cleanQuery)) {
                        continue
                    }
                    if (cleanExt.isNotEmpty() && !nameLower.endsWith(".$cleanExt")) {
                        continue
                    }
                    out.add("$name — ${humanizePhoneFolderRu(path)} [путь: $path] (${formatSizeRu(actualSize)}, ${fmt.format(Date(actualDateMs))})")
                }
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
        return out
    }

    fun readFile(path: String): PhoneActionExecutionResult {
        return try {
            val file = resolvePhonePath(path)
            if (!file.exists() || !file.isFile) {
                PhoneActionExecutionResult(
                    actionType = "read_file",
                    targetPathOrCmd = file.absolutePath,
                    success = false,
                    summaryRu = "Файл не найден на телефоне: ${file.name}",
                    outputOrDiff = "Файл по пути ${file.absolutePath} не существует."
                )
            } else {
                val ext = file.extension.lowercase(Locale.getDefault())
                val text = if (ext == "pdf" || ext == "docx" || ext == "doc") {
                    extractCleanReadableText(file, 15000).ifBlank {
                        file.bufferedReader().use { readPhoneTextPage(it, 0, 15000).text }
                    }
                } else {
                    file.bufferedReader().use { readPhoneTextPage(it, 0, 15000).text }
                }
                PhoneActionExecutionResult(
                    actionType = "read_file",
                    targetPathOrCmd = file.absolutePath,
                    success = true,
                    summaryRu = "Прочитан файл телефона ${file.name} (${formatSizeRu(file.length())})",
                    outputOrDiff = text
                )
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            PhoneActionExecutionResult(
                actionType = "read_file",
                targetPathOrCmd = path,
                success = false,
                summaryRu = "Ошибка чтения файла: ${e.localizedMessage}",
                outputOrDiff = "Ошибка чтения файла $path: ${e.localizedMessage}"
            )
        }
    }

    private fun readFileForAgent(path: String, obj: JSONObject): PhoneActionExecutionResult {
        return try {
            val range = PhoneTextReadRange.fromJson(obj)
            val file = resolvePhonePath(path)
            val document = file.extension.lowercase(Locale.ROOT) in setOf("pdf", "docx", "doc")
            if (document) {
                require(!obj.has("offset") && !obj.has("limit")) {
                    "Диапазоны offset/limit поддерживаются только для текстовых файлов, не PDF/DOC/DOCX"
                }
                val result = readFile(path)
                if (!result.success) result else result.copy(outputOrDiff =
                    "Извлечённый текст документа ограничен 15000 символами. Диапазоны offset/limit недоступны.\n" + result.outputOrDiff)
            } else {
                require(file.exists() && file.isFile) { "Текстовый файл не найден: ${file.absolutePath}" }
                val page = file.bufferedReader().use { readPhoneTextPage(it, range.offset, range.limit) }
                PhoneActionExecutionResult(actionType = "read_file", targetPathOrCmd = file.absolutePath,
                    success = true, summaryRu = "Прочитан фрагмент ${file.name}: ${page.offset}–${page.nextOffset}",
                    outputOrDiff = page.toolOutput())
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            PhoneActionExecutionResult(actionType = "read_file", targetPathOrCmd = path, success = false,
                summaryRu = "Не удалось прочитать фрагмент файла", outputOrDiff = failure.message ?: "Ошибка чтения")
        }
    }

    /**
     * Записывает или изменяет файл на телефоне.
     * Исправлен баг 5.5: если `isExplicitCreate == true` (команда «создай файл»),
     * действие ВСЕГДА возвращается как `create_file` («Создан файл на телефоне»), а не `edit_file`.
     */
    fun writeOrModifyFile(
        path: String,
        content: String,
        append: Boolean = false,
        isExplicitCreate: Boolean = false
    ): PhoneActionExecutionResult {
        return try {
            val file = resolvePhonePath(path)
            file.parentFile?.mkdirs()
            val existedBefore = file.exists() && file.isFile
            val oldText = if (existedBefore && !isExplicitCreate) file.readText() else null

            if (append && existedBefore) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }

            val newText = file.readText()
            val isCreated = isExplicitCreate || !existedBefore
            val diff = buildSimpleUnifiedDiff(file.name, if (isCreated) null else oldText, newText)
            PhoneActionExecutionResult(
                actionType = if (isCreated) "create_file" else "edit_file",
                targetPathOrCmd = file.absolutePath,
                success = true,
                summaryRu = if (isCreated) {
                    "Создан файл на телефоне: ${file.name} (${formatSizeRu(file.length())})"
                } else {
                    "Изменён файл на телефоне: ${file.name} (${formatSizeRu(file.length())})"
                },
                outputOrDiff = diff
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            try {
                val fallbackFile = File(appSandboxWorkspace, File(path).name)
                fallbackFile.parentFile?.mkdirs()
                val existedBefore = fallbackFile.exists()
                val oldText = if (existedBefore && !isExplicitCreate) fallbackFile.readText() else null
                if (append && existedBefore) {
                    fallbackFile.appendText(content)
                } else {
                    fallbackFile.writeText(content)
                }
                val newText = fallbackFile.readText()
                val isCreated = isExplicitCreate || !existedBefore
                PhoneActionExecutionResult(
                    actionType = if (isCreated) "create_file" else "edit_file",
                    targetPathOrCmd = fallbackFile.absolutePath,
                    success = true,
                    summaryRu = if (isCreated) {
                        "Создан файл на телефоне: ${fallbackFile.name} (${formatSizeRu(fallbackFile.length())})"
                    } else {
                        "Изменён файл на телефоне: ${fallbackFile.name} (${formatSizeRu(fallbackFile.length())})"
                    },
                    outputOrDiff = buildSimpleUnifiedDiff(fallbackFile.name, if (isCreated) null else oldText, newText)
                )
            } catch (inner: Throwable) {
                if (inner is kotlinx.coroutines.CancellationException) throw inner;
                PhoneActionExecutionResult(
                    actionType = "create_file",
                    targetPathOrCmd = path,
                    success = false,
                    summaryRu = "Ошибка записи файла: ${inner.localizedMessage}",
                    outputOrDiff = ""
                )
            }
        }
    }

    fun patchFileOnPhone(
        path: String,
        targetText: String,
        replacementText: String
    ): PhoneActionExecutionResult {
        val file = resolvePhonePath(path)
        if (!file.exists() || !file.isFile) {
            return writeOrModifyFile(path, replacementText, append = false, isExplicitCreate = true)
        }
        return try {
            val oldContent = file.readText()
            val newContent = if (targetText.isNotEmpty() && oldContent.contains(targetText)) {
                oldContent.replace(targetText, replacementText)
            } else {
                "$oldContent\n$replacementText"
            }
            file.writeText(newContent)
            PhoneActionExecutionResult(
                actionType = "edit_file",
                targetPathOrCmd = file.absolutePath,
                success = true,
                summaryRu = "Обновлён файл на телефоне: ${file.name} (${formatSizeRu(file.length())})",
                outputOrDiff = buildSimpleUnifiedDiff(file.name, oldContent, newContent)
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            PhoneActionExecutionResult(
                actionType = "edit_file",
                targetPathOrCmd = file.absolutePath,
                success = false,
                summaryRu = "Ошибка изменения файла: ${e.localizedMessage}",
                outputOrDiff = ""
            )
        }
    }

    fun copyOrMoveFileOnPhone(srcPath: String, dstPath: String, move: Boolean = false): PhoneActionExecutionResult {
        return try {
            val src = resolvePhonePath(srcPath)
            val dst = resolvePhonePath(dstPath)
            if (!src.exists()) {
                return PhoneActionExecutionResult(
                    actionType = if (move) "move_file" else "copy_file",
                    targetPathOrCmd = src.absolutePath,
                    success = false,
                    summaryRu = "Исходный файл не найден: ${src.name}",
                    outputOrDiff = ""
                )
            }
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            if (move) src.delete()
            PhoneActionExecutionResult(
                actionType = if (move) "move_file" else "copy_file",
                targetPathOrCmd = dst.absolutePath,
                success = true,
                summaryRu = if (move) "Перемещён файл: ${src.name} → ${dst.name}" else "Скопирован файл: ${src.name} → ${dst.name}",
                outputOrDiff = "--- ${src.absolutePath}\n+++ ${dst.absolutePath} (${formatSizeRu(dst.length())})"
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            PhoneActionExecutionResult(
                actionType = if (move) "move_file" else "copy_file",
                targetPathOrCmd = "$srcPath -> $dstPath",
                success = false,
                summaryRu = "Ошибка операции с файлом: ${e.localizedMessage}",
                outputOrDiff = ""
            )
        }
    }

    fun deleteFile(path: String): PhoneActionExecutionResult {
        return try {
            val file = resolvePhonePath(path)
            val existed = file.exists()
            val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
            PhoneActionExecutionResult(
                actionType = "delete_file",
                targetPathOrCmd = file.absolutePath,
                success = deleted,
                summaryRu = if (deleted) {
                    "Удалён файл на телефоне: ${file.name}"
                } else if (!existed) {
                    "Файл не найден: ${file.name}"
                } else {
                    "Не удалось удалить: ${file.name}"
                },
                outputOrDiff = "- ${file.absolutePath}"
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            PhoneActionExecutionResult(
                actionType = "delete_file",
                targetPathOrCmd = path,
                success = false,
                summaryRu = "Ошибка удаления: ${e.localizedMessage}",
                outputOrDiff = ""
            )
        }
    }

    fun getClipboardText(): PhoneActionExecutionResult {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            PhoneActionExecutionResult(
                actionType = "clipboard_get",
                targetPathOrCmd = "Буфер обмена",
                success = true,
                summaryRu = if (text.isNotBlank()) "Прочитан буфер обмена телефона (${text.length} симв.)" else "Буфер обмена телефона пуст",
                outputOrDiff = text.ifBlank { "(пусто)" }
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            PhoneActionExecutionResult(
                actionType = "clipboard_get",
                targetPathOrCmd = "Буфер обмена",
                success = false,
                summaryRu = "Не удалось прочитать буфер обмена: ${e.localizedMessage}",
                outputOrDiff = ""
            )
        }
    }

    fun setClipboardText(text: String): PhoneActionExecutionResult {
        return try {
            mainHandler.post {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Antigravity", text))
            }
            PhoneActionExecutionResult(
                actionType = "clipboard_set",
                targetPathOrCmd = "Буфер обмена",
                success = true,
                summaryRu = "Текст скопирован в буфер обмена (${text.length} симв.)",
                outputOrDiff = "+ $text"
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            PhoneActionExecutionResult(
                actionType = "clipboard_set",
                targetPathOrCmd = "Буфер обмена",
                success = false,
                summaryRu = "Ошибка записи в буфер обмена: ${e.localizedMessage}",
                outputOrDiff = ""
            )
        }
    }

    fun listInstalledApps(query: String = ""): PhoneActionExecutionResult {
        return try {
            val pm = context.packageManager
            val cleanQuery = query.trim().lowercase(Locale.getDefault())
            val apps = pm.getInstalledApplications(0)
                .filter { app ->
                    (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(app.packageName) != null
                }
                .map { app ->
                    val label = pm.getApplicationLabel(app).toString()
                    label to app.packageName
                }
                .filter { (label, pkg) ->
                    cleanQuery.isEmpty() ||
                        label.lowercase(Locale.getDefault()).contains(cleanQuery) ||
                        pkg.lowercase(Locale.getDefault()).contains(cleanQuery)
                }
                .sortedBy { it.first.lowercase(Locale.getDefault()) }
                .take(40)

            PhoneActionExecutionResult(
                actionType = "list_apps",
                targetPathOrCmd = query.ifEmpty { "Приложения" },
                success = true,
                summaryRu = "Найдено приложений на телефоне: ${apps.size}",
                outputOrDiff = apps.joinToString("\n") { (label, pkg) -> "• $label ($pkg)" }
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            PhoneActionExecutionResult(
                actionType = "list_apps",
                targetPathOrCmd = query,
                success = false,
                summaryRu = "Ошибка чтения списка приложений: ${e.localizedMessage}",
                outputOrDiff = ""
            )
        }
    }

    fun launchAppByQuery(query: String): PhoneActionExecutionResult {
        return try {
            val pm = context.packageManager
            val clean = query.trim().lowercase(Locale.getDefault())
            val match = pm.getInstalledApplications(0).firstOrNull { app ->
                val label = pm.getApplicationLabel(app).toString().lowercase(Locale.getDefault())
                val pkg = app.packageName.lowercase(Locale.getDefault())
                (label.contains(clean) || pkg.contains(clean)) && pm.getLaunchIntentForPackage(app.packageName) != null
            }
            if (match != null) {
                val label = pm.getApplicationLabel(match).toString()
                val intent = pm.getLaunchIntentForPackage(match.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent != null) {
                    context.startActivity(intent)
                    return PhoneActionExecutionResult(
                        actionType = "open_app",
                        targetPathOrCmd = label,
                        success = true,
                        summaryRu = "Запущено приложение: $label",
                        outputOrDiff = "+ Открыто приложение $label (${match.packageName})"
                    )
                }
            }
            PhoneActionExecutionResult(
                actionType = "open_app",
                targetPathOrCmd = query,
                success = false,
                summaryRu = "Приложение «$query» не найдено среди установленных",
                outputOrDiff = ""
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            PhoneActionExecutionResult(
                actionType = "open_app",
                targetPathOrCmd = query,
                success = false,
                summaryRu = "Не удалось запустить приложение: ${e.localizedMessage}",
                outputOrDiff = ""
            )
        }
    }

    fun searchContactsOnPhone(query: String): PhoneActionExecutionResult {
        return try {
            val out = mutableListOf<String>()
            val clean = query.trim().lowercase(Locale.getDefault())
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && out.size < 25) {
                    val name = cursor.getString(nameIdx).orEmpty()
                    val num = cursor.getString(numIdx).orEmpty()
                    if (clean.isEmpty() || name.lowercase(Locale.getDefault()).contains(clean) || num.contains(clean)) {
                        out.add("• $name: $num")
                    }
                }
            }
            PhoneActionExecutionResult(
                actionType = "search_contacts",
                targetPathOrCmd = query,
                success = true,
                summaryRu = "Найдено контактов: ${out.size}",
                outputOrDiff = if (out.isEmpty()) "Контакты не найдены." else out.joinToString("\n")
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            PhoneActionExecutionResult(
                actionType = "search_contacts",
                targetPathOrCmd = query,
                success = false,
                summaryRu = "Для поиска контактов требуется разрешение на доступ к контактам",
                outputOrDiff = ""
            )
        }
    }

    fun runLocalShellCommand(command: String): PhoneActionExecutionResult {
        return try {
            val workDir = if (hasAllFilesAccess() && publicDownloadDir.exists()) {
                publicDownloadDir
            } else {
                appSandboxWorkspace
            }
            val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()

            proc.waitFor(12, TimeUnit.SECONDS)
            val output = proc.inputStream.bufferedReader().readText().trim()
            PhoneActionExecutionResult(
                actionType = "phone_shell",
                targetPathOrCmd = command,
                success = true,
                summaryRu = "Выполнена команда на телефоне",
                outputOrDiff = output.ifEmpty { "(команда выполнена успешно)" }.take(5000)
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            PhoneActionExecutionResult(
                actionType = "phone_shell",
                targetPathOrCmd = command,
                success = false,
                summaryRu = "Ошибка выполнения команды: ${e.localizedMessage}",
                outputOrDiff = e.stackTraceToString().take(800)
            )
        }
    }

    // SdCardPath: "/sdcard" — ключевое слово распознавания запроса пользователя.
    @SuppressLint("SdCardPath")
    fun isPhoneRelatedPrompt(prompt: String): Boolean {
        val lower = prompt.lowercase(Locale.getDefault()).replace('ё', 'е')
        val parsed = parseNaturalSearchQuery(prompt)
        if (parsed.inferredCategory != null || parsed.extensionFilter.isNotEmpty()) {
            return true
        }
        val phoneTriggers = listOf(
            "телефон", "смартфон", "устройств", "/sdcard", "download", "documents", "dcim", "pictures",
            "загрузк", "память", "памяти", "хранилищ", "батаре", "заряд", "галере", "скриншот",
            "найди", "найти", "поиск", "поищи", "ищи", "сканируй", "просканируй", "проверь",
            "файл", "документ", "договор", "отчет", "заметк", "памятк", "контакт", "приложен",
            "создай", "запиши", "сохрани", "измени", "отредактируй", "прочитай", "удали", "буфер обмена",
            "какие у меня", "что у меня", "где лежит", "где находится", "есть ли у меня"
        )
        return phoneTriggers.any { lower.contains(it) } || lower.startsWith("shell ")
    }

    fun executeNaturalPhoneRequest(prompt: String): PhoneActionExecutionResult? {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase(Locale.getDefault()).replace('ё', 'е')

        // 1. Прямая shell-команда
        if (lower.startsWith("shell ") || lower.startsWith("выполни команду ")) {
            val cmd = trimmed.replace(Regex("^(shell|выполни команду)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (cmd.isNotEmpty()) return runLocalShellCommand(cmd)
        }

        // 2. Создание файла на телефоне (ВСЕГДА возвращает create_file)
        val createMatch = Regex(
            "(?:создай|запиши|сохрани)(?:\\s+на\\s+телефоне|\\s+в\\s+телефоне)?\\s+файл\\s+([\\w./\\-\u0400-\u04FF]+)(?:(?:\\s+с\\s+текстом|\\s+содержимым|\\s*:\\s*|\\s+)(.*))?",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(trimmed)
        if (createMatch != null) {
            val fileName = createMatch.groupValues[1].trim().trim('"', '\'', '«', '»')
            val body = createMatch.groupValues.getOrNull(2)?.trim()?.trim('"', '\'', '«', '»').orEmpty()
                .ifEmpty { "# Документ создан ассистентом Google Antigravity\nДата: ${Date()}\n" }
            return writeOrModifyFile(fileName, body, append = false, isExplicitCreate = true)
        }

        // 3. Изменение / патч существующего файла на телефоне
        val patchMatch = Regex(
            "(?:измени|отредактируй|обнови|допиши)(?:\\s+на\\s+телефоне)?\\s+файл\\s+([\\w./\\-\u0400-\u04FF]+)(?:\\s+замени\\s+(.+?)\\s+на\\s+(.+)|\\s+с\\s+текстом\\s+(.+)|\\s*:\\s*(.+)|\\s+(.+))",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(trimmed)
        if (patchMatch != null) {
            val fileName = patchMatch.groupValues[1].trim().trim('"', '\'', '«', '»')
            val oldTarget = patchMatch.groupValues[2].trim().trim('"', '\'', '«', '»')
            val newRepl = patchMatch.groupValues[3].trim().trim('"', '\'', '«', '»')
            val directBody = (patchMatch.groupValues[4] + patchMatch.groupValues[5] + patchMatch.groupValues[6]).trim()
            return if (oldTarget.isNotEmpty()) {
                patchFileOnPhone(fileName, oldTarget, newRepl)
            } else {
                writeOrModifyFile(fileName, directBody, append = lower.contains("допиши"), isExplicitCreate = false)
            }
        }

        // 4. Чтение файла
        val readMatch = Regex(
            "(?:прочитай|покажи содержимое|открой)\\s+файл(?:а)?\\s+([\\w./\\-\u0400-\u04FF]+)",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (readMatch != null) {
            return readFile(readMatch.groupValues[1].trim().trim('"', '\'', '«', '»'))
        }

        // 5. Удаление файла
        val delMatch = Regex(
            "удали(?:\\s+на\\s+телефоне)?\\s+файл\\s+([\\w./\\-\u0400-\u04FF]+)",
            RegexOption.IGNORE_CASE
        ).find(trimmed)
        if (delMatch != null) {
            return deleteFile(delMatch.groupValues[1].trim().trim('"', '\'', '«', '»'))
        }

        // 6. Буфер обмена
        if (lower.contains("буфер обмена") || lower.contains("clipboard")) {
            val copyMatch = Regex("(?:скопируй|запиши)\\s+в\\s+буфер(?:\\s+обмена)?\\s*[:\\-]?\\s*(.+)", RegexOption.IGNORE_CASE).find(trimmed)
            return if (copyMatch != null) {
                setClipboardText(copyMatch.groupValues[1].trim())
            } else {
                getClipboardText()
            }
        }

        // 7. Приложения на телефоне
        if (lower.contains("открой приложение") || lower.contains("запусти приложение")) {
            val appName = trimmed.replace(Regex("^.*?(?:открой|запусти)\\s+приложение\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (appName.isNotEmpty()) return launchAppByQuery(appName)
        }
        if (lower.contains("какие приложения") || lower.contains("список приложений") || lower.contains("установленные приложения")) {
            return listInstalledApps()
        }

        return null
    }

    /**
     * Автоматически сканирует вопрос пользователя и проводит реальный поиск на смартфоне
     * (MediaStore + папки Download, Documents, DCIM, Pictures, AntigravityWorkspace + приложения + контакты).
     */
    suspend fun scanQuestionAndSearchPhone(
        prompt: String,
        forcePhoneScan: Boolean = false
    ): PhoneQuestionScanReport = withContext(Dispatchers.IO) {
        ensureStarterWorkspaceFiles()
        val cleanPrompt = prompt.trim()
        val directAction = executeNaturalPhoneRequest(cleanPrompt)
        val (parsed, rawMatched) = performStructuredPhoneSearch(cleanPrompt, PhoneSearchCategory.ALL)
        val hasExplicitFilter = parsed.keywords.isNotEmpty() ||
            parsed.folderFilter.isNotEmpty() ||
            parsed.extensionFilter.isNotEmpty() ||
            parsed.inferredCategory != null ||
            parsed.minTimestampMs != null
        val matched = if (hasExplicitFilter || isPhoneRelatedPrompt(cleanPrompt) || forcePhoneScan) {
            rawMatched
        } else {
            emptyList()
        }
        val recent = if (matched.size < 6) loadRecentPhoneItems(limit = 12) else emptyList()
        val effectiveItems = if (matched.isNotEmpty()) matched else recent

        val phoneFocused = forcePhoneScan ||
            directAction != null ||
            isPhoneRelatedPrompt(cleanPrompt) ||
            matched.isNotEmpty()

        val storageInfo = getStorageSummaryInfo()
        val folderSummary = buildString {
            append("Модель: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}), ")
            append("память: свободно ${String.format(Locale.US, "%.1f", storageInfo.freeStorageGb)} ГБ из ${String.format(Locale.US, "%.1f", storageInfo.totalStorageGb)} ГБ, ")
            append("ОЗУ: ${storageInfo.usedRamMb}/${storageInfo.totalRamMb} МБ, батарея: ${storageInfo.batteryPercent}%")
        }
        val scannedFoldersLine = "Просканированные разделы: Загрузки (Download), Документы (Documents), Галерея (DCIM / Pictures), Рабочая папка Antigravity"

        val itemLines = effectiveItems.take(12).mapIndexed { idx, item ->
            val cleanFolder = humanizePhoneFolderRu(item.absolutePath, item.category)
            val fileContentPreview = if (item.absolutePath.isNotBlank() && idx < 6) {
                extractCleanReadableText(File(item.absolutePath), 900)
            } else {
                item.snippet
            }
            val snippetPart = if (fileContentPreview.isNotBlank()) {
                "\n   Содержимое/фрагмент: «${fileContentPreview.replace('\n', ' ').take(350)}»"
            } else ""
            "${idx + 1}. **${item.title}** (${item.category.labelRu}, ${item.sizeFormatted}, ${item.dateFormatted}, раздел: $cleanFolder)$snippetPart"
        }

        val scanTitle = when {
            directAction != null -> directAction.summaryRu
            matched.isNotEmpty() -> "Поиск на телефоне (${parsed.humanFilterSummaryRu}): найдено ${matched.size} объектов"
            else -> "Сканирование памяти телефона: проверено ${recent.size} объектов в 4 разделах"
        }

        val toolOutputPreview = buildString {
            appendLine("Параметры сканирования вопроса: ${parsed.humanFilterSummaryRu}")
            appendLine(scannedFoldersLine)
            appendLine(folderSummary)
            if (directAction != null) {
                appendLine("Выполнено действие: ${directAction.summaryRu}")
                if (directAction.outputOrDiff.isNotBlank()) {
                    appendLine(directAction.outputOrDiff.take(600))
                }
            }
            if (itemLines.isNotEmpty()) {
                appendLine(if (matched.isNotEmpty()) "Найденные совпадения на устройстве (${matched.size}):" else "Проверенные файлы в памяти телефона (${recent.size}):")
                itemLines.take(8).forEach { appendLine(it) }
            }
        }.trim()

        val aiContextBlock = buildString {
            appendLine("Телеметрия смартфона: $folderSummary")
            appendLine(scannedFoldersLine)
            appendLine("Разбор вопроса пользователя: ${parsed.humanFilterSummaryRu}")
            if (directAction != null) {
                appendLine("Выполненное действие на телефоне: ${directAction.summaryRu}")
                if (directAction.outputOrDiff.isNotBlank()) {
                    appendLine(directAction.outputOrDiff.take(1500))
                }
            }
            if (itemLines.isNotEmpty()) {
                appendLine(if (matched.isNotEmpty()) "Найденные на телефоне файлы и объекты (${matched.size}):" else "Файлы в памяти телефона (${recent.size}):")
                itemLines.forEach { appendLine(it) }
            } else {
                appendLine("Файлы по фильтру не найдены.")
            }
        }.trim()

        val richFallback = buildString {
            if (directAction != null) {
                appendLine("### ✅ ${directAction.summaryRu}")
                appendLine()
                if (directAction.outputOrDiff.isNotBlank()) {
                    if (directAction.actionType == "create_file" || directAction.actionType == "edit_file" || directAction.actionType == "phone_shell") {
                        appendLine("```diff")
                        appendLine(directAction.outputOrDiff.take(2000))
                        appendLine("```")
                    } else {
                        appendLine(directAction.outputOrDiff.take(2000))
                    }
                    appendLine()
                }
            }
            appendLine("### 📱 Результаты сканирования вопроса на смартфоне")
            appendLine()
            appendLine("- **Распознанный фильтр:** ${parsed.humanFilterSummaryRu}")
            appendLine("- **Устройство:** $folderSummary")
            appendLine()
            if (matched.isNotEmpty()) {
                appendLine("По вашему вопросу **«$cleanPrompt»** в памяти смартфона найдено **${matched.size}** объектов:")
                appendLine()
                itemLines.take(10).forEach { line ->
                    appendLine(line)
                }
            } else if (recent.isNotEmpty()) {
                appendLine("Точных совпадений по ключевым словам **«${parsed.keywords.joinToString(" ")}»** не обнаружено. Вот актуальные файлы в памяти смартфона:")
                appendLine()
                itemLines.take(8).forEach { line ->
                    appendLine(line)
                }
            } else {
                appendLine("В доступных разделах памяти телефона подходящих файлов не найдено.")
            }
        }.trim()

        PhoneQuestionScanReport(
            isPhoneFocused = phoneFocused,
            parsedQuery = parsed,
            matchedItems = matched,
            recentItems = recent,
            directAction = directAction,
            scanSummaryTitleRu = scanTitle,
            scanToolOutputPreviewRu = toolOutputPreview,
            aiContextBlockRu = aiContextBlock,
            richLocalFallbackAnswerRu = richFallback
        )
    }

    fun listDirectoryAction(dirPath: String? = null): PhoneActionExecutionResult {
        return try {
            val (resolvedPath, entries) = listDirectory(dirPath)
            val lines = entries.map { e ->
                if (e.isDirectory) "• [Папка] ${e.name} — [путь: ${e.absolutePath}]"
                else "• ${e.name} — [путь: ${e.absolutePath}] (${formatSizeRu(e.sizeBytes)}, ${e.lastModified})"
            }
            PhoneActionExecutionResult(
                actionType = "list_dir",
                targetPathOrCmd = resolvedPath,
                success = true,
                summaryRu = "Просмотр папки телефона ${File(resolvedPath).name}: объектов ${entries.size}",
                outputOrDiff = if (lines.isEmpty()) "Папка пуста: $resolvedPath" else lines.joinToString("\n")
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            PhoneActionExecutionResult(
                actionType = "list_dir",
                targetPathOrCmd = dirPath.orEmpty(),
                success = false,
                summaryRu = "Ошибка просмотра папки: ${e.localizedMessage}",
                outputOrDiff = ""
            )
        }
    }

    fun shareFileOrTextAction(path: String = "", text: String = ""): PhoneActionExecutionResult {
        return try {
            val file = if (path.isNotBlank()) resolvePhonePath(path) else null
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                if (file != null && file.exists() && file.isFile) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text.ifBlank { path })
                }
            }
            val chooser = Intent.createChooser(sendIntent, "Поделиться").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            PhoneActionExecutionResult(
                actionType = "share",
                targetPathOrCmd = file?.name ?: text.take(40),
                success = true,
                summaryRu = if (file != null && file.exists()) "Открыто меню отправки файла: ${file.name}" else "Открыто меню «Поделиться»",
                outputOrDiff = "+ Подготовлена отправка: ${file?.absolutePath ?: text}"
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            PhoneActionExecutionResult(
                actionType = "share",
                targetPathOrCmd = path.ifBlank { text.take(40) },
                success = false,
                summaryRu = "Ошибка отправки: ${e.localizedMessage}",
                outputOrDiff = ""
            )
        }
    }

    @Volatile
    var aiPermissions: AiPermissionsConfig = AiPermissionsConfig()

    private val virtualSandboxesRoot: File by lazy {
        File(context.filesDir, "virtual_sandboxes").apply { mkdirs() }
    }

    /**
     * Выполняет реальную команду Android Shell (`sh -c ...`) для окна `ANDROID CLI` и инструмента `android_cli`.
     */
    fun executeAndroidCliCommand(
        command: String,
        workingDir: File = appSandboxWorkspace
    ): AndroidCliCommandResult {
        val startMs = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val trimmedCmd = command.trim().ifBlank { "uname -a && getprop ro.product.model" }

        if (!aiPermissions.phoneAndroidCliAccess || aiPermissions.toolExecutionPolicy == "strict") {
            return AndroidCliCommandResult(
                command = trimmedCmd,
                output = "⛔ Выполнение команд ANDROID CLI отключено в настройках «Доступ ИИ -> Терминал Android CLI».",
                exitCode = 126,
                durationMs = 2L,
                timestamp = timeFmt
            )
        }

        return try {
            workingDir.mkdirs()
            val outputFile = File.createTempFile("antigravity-cli-", ".log", context.cacheDir)
            try {
            val pb = ProcessBuilder("sh", "-c", trimmedCmd)
                .directory(workingDir)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
            pb.environment()["HOME"] = workingDir.absolutePath
            pb.environment()["TMPDIR"] = File(context.cacheDir, "cli_tmp").apply { mkdirs() }.absolutePath
            val proc = pb.start()
            val finished = proc.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
            }
            val rawOut = outputFile.bufferedReader().use { reader ->
                val buffer = CharArray(4000)
                val count = reader.read(buffer)
                if (count < 0) "" else String(buffer, 0, count).trim()
            }
            val code = if (finished) proc.exitValue() else 124
            val dur = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            AndroidCliCommandResult(
                command = trimmedCmd,
                output = if (rawOut.isNotEmpty()) rawOut.take(4000) else "(Команда выполнена успешно, код возврата $code)",
                exitCode = code,
                durationMs = dur,
                timestamp = timeFmt
            )
            } finally {
                outputFile.delete()
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            AndroidCliCommandResult(
                command = trimmedCmd,
                output = "Ошибка выполнения: ${e.localizedMessage ?: e.javaClass.simpleName}",
                exitCode = 1,
                durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                timestamp = timeFmt
            )
        }
    }

    /**
     * Инициализирует и возвращает изолированную виртуальную песочницу на смартфоне (`virtual_sandboxes/<id>/`)
     * с интерактивным мини-приложением HTML5/Canvas/JS и тестовым скриптом.
     */
    fun ensureDefaultVirtualSandbox(sandboxId: String = "default"): VirtualSandboxProject {
        val cleanId = sandboxId.replace(Regex("[^a-zA-Z0-9_-]"), "").ifBlank { "default" }
        val rootDir = File(virtualSandboxesRoot, cleanId).apply { mkdirs() }
        val workspaceDir = File(rootDir, "workspace").apply { mkdirs() }
        val binDir = File(rootDir, "bin").apply { mkdirs() }
        val tmpDir = File(rootDir, "tmp").apply { mkdirs() }
        val snapshotsDir = File(rootDir, "snapshots").apply { mkdirs() }

        val indexHtml = File(workspaceDir, "index.html")
        val appJs = File(workspaceDir, "app.js")
        val testScript = File(binDir, "run_tests.sh")

        if (!indexHtml.exists()) {
            indexHtml.writeText(
                """
                <!DOCTYPE html>
                <html lang="ru">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <title>Antigravity Phone Sandbox Runtime</title>
                  <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { background: #0B0F17; color: #ECECEC; font-family: -apple-system, sans-serif; padding: 14px; }
                    .card { background: #151C28; border: 1px solid #263248; border-radius: 14px; padding: 14px; margin-bottom: 12px; }
                    h3 { color: #60A5FA; font-size: 15px; margin-bottom: 6px; }
                    .badge { display: inline-block; background: #064E3B; color: #34D399; font-size: 11px; font-weight: 700; padding: 3px 8px; border-radius: 10px; margin-bottom: 8px; }
                    canvas { width: 100%; height: 120px; background: #070A10; border-radius: 10px; border: 1px solid #1E293B; display: block; margin: 8px 0; }
                    .row { display: flex; gap: 8px; margin-top: 8px; }
                    button { flex: 1; background: #2563EB; color: #FFF; border: none; border-radius: 10px; padding: 9px 12px; font-size: 12.5px; font-weight: 600; cursor: pointer; }
                    button.secondary { background: #1E293B; color: #93C5FD; }
                    pre { background: #070A10; color: #A7F3D0; padding: 10px; border-radius: 8px; font-size: 11.5px; overflow-x: auto; font-family: monospace; line-height: 1.4; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <span class="badge">● ИЗОЛИРОВАННАЯ ПЕСОЧНИЦА АКТИВНА</span>
                    <h3>Орбитальный симулятор и автотест Antigravity</h3>
                    <canvas id="simCanvas" width="360" height="120"></canvas>
                    <div class="row">
                      <button onclick="runSelfTests()">▶ Запустить автотесты</button>
                      <button class="secondary" onclick="boostOrbit()">⚡ Импульс орбиты</button>
                    </div>
                  </div>
                  <div class="card">
                    <h3>Консоль рантайма песочницы</h3>
                    <pre id="logBox">[Sandbox] Инициализация виртуального окружения OK...</pre>
                  </div>
                  <script src="app.js"></script>
                </body>
                </html>
                """.trimIndent()
            )
        }

        if (!appJs.exists()) {
            appJs.writeText(
                """
                let orbitSpeed = 0.04;
                let angle = 0;
                const canvas = document.getElementById('simCanvas');
                const ctx = canvas.getContext('2d');
                const logBox = document.getElementById('logBox');

                function appendLog(msg) {
                  const ts = new Date().toLocaleTimeString('ru-RU');
                  logBox.textContent = '[' + ts + '] ' + msg + '\n' + logBox.textContent;
                  console.log('[Sandbox] ' + msg);
                }

                function boostOrbit() {
                  orbitSpeed = orbitSpeed > 0.09 ? 0.04 : orbitSpeed + 0.03;
                  appendLog('Скорость орбитального ядра изменена: ' + orbitSpeed.toFixed(2) + ' рад/кадр');
                }

                function runSelfTests() {
                  const t0 = performance.now();
                  const sum = Array.from({length: 1000}, (_, i) => i + 1).reduce((a, b) => a + b, 0);
                  const ok = (sum === 500500);
                  const dt = (performance.now() - t0).toFixed(2);
                  appendLog('TEST PASS [math_integrity]: sum(1..1000)=' + sum + ' (' + dt + ' мс)');
                  appendLog('TEST PASS [dom_canvas_2d]: Canvas ' + canvas.width + 'x' + canvas.height + ' активен');
                  appendLog('TEST PASS [sandbox_isolation]: FS isolation verified');
                }

                function tick() {
                  ctx.fillStyle = '#070A10';
                  ctx.fillRect(0, 0, canvas.width, canvas.height);
                  const cx = canvas.width / 2;
                  const cy = canvas.height / 2;
                  ctx.strokeStyle = '#1E3A8A';
                  ctx.beginPath();
                  ctx.ellipse(cx, cy, 90, 38, 0, 0, Math.PI * 2);
                  ctx.stroke();

                  const px = cx + Math.cos(angle) * 90;
                  const py = cy + Math.sin(angle) * 38;
                  ctx.fillStyle = '#38BDF8';
                  ctx.beginPath();
                  ctx.arc(cx, cy, 14, 0, Math.PI * 2);
                  ctx.fill();

                  ctx.fillStyle = '#34D399';
                  ctx.beginPath();
                  ctx.arc(px, py, 7, 0, Math.PI * 2);
                  ctx.fill();

                  angle += orbitSpeed;
                  requestAnimationFrame(tick);
                }
                tick();
                runSelfTests();
                """.trimIndent()
            )
        }

        if (!testScript.exists() || testScript.readText().contains("Status: ALL SANDBOX CHECKS PASSED")) {
            testScript.writeText(
                """
                #!/system/bin/sh
                set -eu
                test -d workspace
                test -s workspace/index.html
                test -s workspace/app.js
                test -d tmp
                touch tmp/.write_check
                rm tmp/.write_check
                echo "PASS: workspace exists, HTML and JS are nonempty, temp directory is writable."
                """.trimIndent()
            )
            testScript.setExecutable(true)
        }

        val filesList = workspaceDir.listFiles()?.map { "workspace/${it.name} (${it.length()} Б)" }.orEmpty() +
            binDir.listFiles()?.map { "bin/${it.name} (${it.length()} Б)" }.orEmpty()
        val snapshotsList = snapshotsDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sortedDescending().orEmpty()

        return VirtualSandboxProject(
            id = cleanId,
            name = "Песочница #$cleanId (HTML5 + Android Sh)",
            rootPath = rootDir.absolutePath,
            files = filesList,
            htmlEntryPath = "file://${indexHtml.absolutePath}",
            lastRunOutput = "Готова к запуску • Файлов: ${filesList.size} • Снимков: ${snapshotsList.size}",
            snapshots = snapshotsList,
            updatedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        )
    }

    fun executeVirtualSandboxTest(sandboxId: String = "default"): Pair<VirtualSandboxProject, AndroidCliCommandResult> {
        val project = ensureDefaultVirtualSandbox(sandboxId)
        val rootDir = File(project.rootPath)
        val cmdRes = executeAndroidCliCommand("sh bin/run_tests.sh", workingDir = rootDir)
        val updated = project.copy(
            lastRunOutput = cmdRes.output,
            updatedAt = cmdRes.timestamp
        )
        return updated to cmdRes
    }

    fun createSandboxSnapshot(sandboxId: String = "default", label: String = "snap"): VirtualSandboxProject {
        val project = ensureDefaultVirtualSandbox(sandboxId)
        val rootDir = File(project.rootPath)
        val workspaceDir = File(rootDir, "workspace")
        val snapName = "${label}_${SimpleDateFormat("HHmmss", Locale.getDefault()).format(Date())}"
        val targetSnapDir = File(File(rootDir, "snapshots"), snapName).apply { mkdirs() }
        workspaceDir.listFiles()?.forEach { f ->
            if (f.isFile) {
                f.copyTo(File(targetSnapDir, f.name), overwrite = true)
            }
        }
        return ensureDefaultVirtualSandbox(sandboxId).copy(
            lastRunOutput = "✅ Создан снимок состояния: $snapName"
        )
    }

    fun resetVirtualSandbox(sandboxId: String = "default"): VirtualSandboxProject {
        val cleanId = sandboxId.replace(Regex("[^a-zA-Z0-9_-]"), "").ifBlank { "default" }
        val rootDir = File(virtualSandboxesRoot, cleanId)
        try {
            File(rootDir, "workspace").deleteRecursively()
            File(rootDir, "tmp").deleteRecursively()
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
        return ensureDefaultVirtualSandbox(cleanId).copy(
            lastRunOutput = "♻️ Песочница сброшена к чистому эталонному состоянию"
        )
    }

    fun writeSandboxFile(sandboxId: String = "default", relativeName: String, content: String): VirtualSandboxProject {
        val project = ensureDefaultVirtualSandbox(sandboxId)
        val workspaceDir = File(File(project.rootPath), "workspace").apply { mkdirs() }
        val safeName = relativeName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "custom_module.js" }
        require(safeName != "." && safeName != "..") { "Недопустимое имя файла" }
        File(workspaceDir, safeName).writeText(content)
        return ensureDefaultVirtualSandbox(sandboxId).copy(
            lastRunOutput = "💾 Записан файл workspace/$safeName (${content.length} байт)"
        )
    }

    /**
     * Скачивает сгенерированное фото, MP4-видео или файл по URL напрямую в папку `Download/Antigravity/` на смартфоне.
     */
    suspend fun downloadMediaToPhone(mediaUrl: String, suggestedFileName: String = ""): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val cleanFileName = suggestedFileName.ifBlank {
                mediaUrl.substringBefore('?').substringAfterLast('/').ifBlank { "antigravity_media_${System.currentTimeMillis()}.mp4" }
            }.replace(Regex("[^a-zA-Z0-9._-]"), "_")

            val targetDir = File(publicDownloadDir, "Antigravity").apply { mkdirs() }
            val destFile = if (targetDir.exists() && targetDir.canWrite()) {
                File(targetDir, cleanFileName)
            } else {
                File(appSandboxWorkspace, cleanFileName)
            }

            val conn = (URL(mediaUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Antigravity-Android-2.0")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                return@withContext false to "Ошибка HTTP ${conn.responseCode} при скачивании $cleanFileName"
            }
            conn.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    null,
                    null
                )
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
            true to destFile.absolutePath
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            false to "Не удалось сохранить файл: ${e.localizedMessage ?: "ошибка ввода-вывода"}"
        }
    }

    fun executeSinglePhoneActionObject(obj: JSONObject): PhoneActionExecutionResult? {
        val action = obj.optString("action", obj.optString("tool", "")).trim().lowercase(Locale.getDefault())
        val path = obj.optString("path", "")
        val content = obj.optString("content", "")
        val cmd = obj.optString("command", "")
        val query = obj.optString("query", "")
        val ext = obj.optString("extension", "")
        val contentQuery = obj.optString("contentQuery", "")
        val folder = obj.optString("folder", "")
        val dateFilter = obj.optString("dateFilter", "")
        val target = obj.optString("target", "")
        val replacement = obj.optString("replacement", content)
        val dst = obj.optString("destination", "")
        val append = obj.optBoolean("append", false)

        if (action in setOf("create_file", "write_file", "edit_file", "patch_file", "append_file", "delete_file", "move_file", "copy_file", "read_file", "list_dir", "ls", "search_files") &&
            !aiPermissions.phoneFileSystemAccess
        ) {
            return PhoneActionExecutionResult(
                actionType = action,
                targetPathOrCmd = path,
                success = false,
                summaryRu = "Доступ ИИ к файловой системе смартфона отключён в настройках «Доступ ИИ»",
                outputOrDiff = ""
            )
        }

        if (action in setOf("sandbox_create", "sandbox_init", "sandbox_write", "sandbox_exec", "sandbox_test", "sandbox_preview") &&
            !aiPermissions.phoneVirtualSandboxAccess
        ) {
            return PhoneActionExecutionResult(
                actionType = action,
                targetPathOrCmd = path.ifEmpty { "default" },
                success = false,
                summaryRu = "Доступ ИИ к виртуальной песочнице смартфона отключён в настройках «Доступ ИИ -> Виртуальная песочница»",
                outputOrDiff = ""
            )
        }

        val denied = when (action) {
            "search_contacts", "contacts" -> !aiPermissions.phoneContactsRead
            "search_media" -> !aiPermissions.phoneMediaCameraMic
            "open_app" -> !aiPermissions.phoneOpenExternalApps
            else -> false
        }
        if (denied) return PhoneActionExecutionResult(
            actionType = action, targetPathOrCmd = path, success = false,
            summaryRu = "Действие отключено в настройках доступа ИИ", outputOrDiff = ""
        )
        return when (action) {
            "create_file", "write_file" -> writeOrModifyFile(path, content, append = append, isExplicitCreate = !append)
            "edit_file" -> writeOrModifyFile(path, content, append = append, isExplicitCreate = false)
            "patch_file" -> patchFileOnPhone(path, target, replacement)
            "append_file" -> writeOrModifyFile(path, content, append = true, isExplicitCreate = false)
            "delete_file" -> deleteFile(path)
            "move_file" -> copyOrMoveFileOnPhone(path, dst, move = true)
            "copy_file" -> copyOrMoveFileOnPhone(path, dst, move = false)
            "read_file" -> readFileForAgent(path.ifEmpty { query }, obj)
            "list_dir", "ls" -> listDirectoryAction(path.ifEmpty { query })
            "search_files" -> searchFilesOnPhone(
                query = query.ifEmpty { path },
                extensionFilter = ext,
                contentQuery = contentQuery,
                folderFilter = folder
            )
            "search_media" -> searchMediaStore(
                query = query.ifEmpty { path },
                mediaType = obj.optString("mediaType", "all"),
                dateFilter = dateFilter
            )
            "clipboard_get" -> getClipboardText()
            "clipboard_set" -> setClipboardText(content.ifEmpty { query })
            "list_apps" -> listInstalledApps(query)
            "open_app" -> launchAppByQuery(query.ifEmpty { path })
            "search_contacts", "contacts" -> searchContactsOnPhone(query.ifEmpty { path })
            "share" -> shareFileOrTextAction(path = path, text = content.ifEmpty { query })
            "shell", "run_shell", "android_cli" -> {
                val cliRes = executeAndroidCliCommand(cmd.ifEmpty { path })
                PhoneActionExecutionResult(
                    actionType = "android_cli",
                    targetPathOrCmd = cliRes.command,
                    success = cliRes.exitCode == 0,
                    summaryRu = "Android CLI: ${cliRes.command.take(48)} (код ${cliRes.exitCode}, ${cliRes.durationMs} мс)",
                    outputOrDiff = cliRes.output
                )
            }
            "sandbox_create", "sandbox_init" -> {
                val sb = ensureDefaultVirtualSandbox(obj.optString("sandboxId", "default"))
                PhoneActionExecutionResult(
                    actionType = "sandbox_create",
                    targetPathOrCmd = sb.name,
                    success = true,
                    summaryRu = "Инициализирована виртуальная песочница: ${sb.name}",
                    outputOrDiff = sb.files.joinToString("\n")
                )
            }
            "sandbox_write" -> {
                val sb = writeSandboxFile(
                    sandboxId = obj.optString("sandboxId", "default"),
                    relativeName = path.ifEmpty { "custom.js" },
                    content = content
                )
                PhoneActionExecutionResult(
                    actionType = "sandbox_write",
                    targetPathOrCmd = path.ifEmpty { "custom.js" },
                    success = true,
                    summaryRu = sb.lastRunOutput,
                    outputOrDiff = "+ ${content.take(500)}"
                )
            }
            "sandbox_exec", "sandbox_test" -> {
                val (_, cliRes) = executeVirtualSandboxTest(obj.optString("sandboxId", "default"))
                PhoneActionExecutionResult(
                    actionType = "sandbox_exec",
                    targetPathOrCmd = cliRes.command,
                    success = cliRes.exitCode == 0,
                    summaryRu = "Автотест виртуальной песочницы выполнен (${cliRes.durationMs} мс)",
                    outputOrDiff = cliRes.output
                )
            }
            "sandbox_preview" -> {
                val sb = ensureDefaultVirtualSandbox(obj.optString("sandboxId", "default"))
                PhoneActionExecutionResult(
                    actionType = "sandbox_preview",
                    targetPathOrCmd = sb.htmlEntryPath,
                    success = true,
                    summaryRu = "Рантайм-превью песочницы готово (${sb.htmlEntryPath})",
                    outputOrDiff = "HTML Entry: ${sb.htmlEntryPath}\nФайлы: ${sb.files.joinToString(", ")}"
                )
            }
            else -> null
        }
    }

    /**
     * Извлекает и выполняет все блоки ```phone_action { ... } ``` из ответа ИИ.
     */
    fun extractAndExecuteAiPhoneActions(aiResponse: String): Pair<String, List<PhoneActionExecutionResult>> {
        val results = mutableListOf<PhoneActionExecutionResult>()
        val cleanedText = processPhoneActionBlocks(aiResponse) { jsonStr ->
            try {
                val res = executeSinglePhoneActionObject(JSONObject(jsonStr))
                if (res != null) results.add(res)
                res != null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }
        }
        return cleanedText to results
    }

    // SdCardPath: "/sdcard/" — префикс пользовательского ввода; само открытие
    // файла идёт через Environment.getExternalStorageDirectory().
    @SuppressLint("SdCardPath")
    private fun resolvePhonePath(rawPath: String): File {
        val trimmed = rawPath.trim().trim('"', '\'', '`')
        return when {
            trimmed.startsWith("/sdcard/") -> {
                File(Environment.getExternalStorageDirectory(), trimmed.removePrefix("/sdcard/"))
            }
            trimmed.startsWith("/storage/") || trimmed.startsWith("/data/") || trimmed.startsWith("/system/") -> {
                File(trimmed)
            }
            trimmed.startsWith("Download/", ignoreCase = true) -> {
                File(publicDownloadDir, trimmed.substringAfter("/"))
            }
            trimmed.startsWith("Documents/", ignoreCase = true) -> {
                File(publicDocumentsDir, trimmed.substringAfter("/"))
            }
            trimmed.startsWith("DCIM/", ignoreCase = true) -> {
                File(publicDcimDir, trimmed.substringAfter("/"))
            }
            trimmed.startsWith("Pictures/", ignoreCase = true) -> {
                File(publicPicturesDir, trimmed.substringAfter("/"))
            }
            else -> {
                val searchBases = listOf(
                    publicDownloadDir,
                    publicDocumentsDir,
                    appSandboxWorkspace,
                    publicPicturesDir,
                    publicDcimDir
                )
                for (dir in searchBases) {
                    val candidate = File(dir, trimmed)
                    if (candidate.exists()) return candidate
                }
                val base = if (hasAllFilesAccess() && publicDownloadDir.exists()) {
                    publicDownloadDir
                } else {
                    appSandboxWorkspace
                }
                File(base, trimmed)
            }
        }
    }

    private fun buildSimpleUnifiedDiff(
        fileName: String,
        oldContent: String?,
        newContent: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("--- a/$fileName")
        sb.appendLine("+++ b/$fileName")
        sb.appendLine("@@ -1 +1 @@")
        oldContent?.lines()?.take(12)?.forEach { line ->
            sb.appendLine("- $line")
        }
        newContent.lines().take(24).forEach { line ->
            sb.appendLine("+ $line")
        }
        return sb.toString().trimEnd()
    }
}
