package com.google.antigravity.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.antigravity.ui.design.DesignColors
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.BuildConfig
import com.google.antigravity.model.*
import com.google.antigravity.viewmodel.AntigravityViewModel

private val SettingsMuted = Color(0xFF9E9E9E)
private val SettingsSurface = DesignColors.Surface

@Composable
fun AppSettingsScreen(
    viewModel: AntigravityViewModel, settings: AntigravitySettings,
    googleEmail: String, pcInstanceName: String,
    onUpdate: ((AntigravitySettings) -> AntigravitySettings) -> Unit,
    onOpenGoogleWeb: () -> Unit, onSignOut: () -> Unit, onBack: () -> Unit,
    onOpenPermissions: () -> Unit, onOpenDrawer: () -> Unit = onBack,
    initialPage: String = "Настройки"
) {
    var page by rememberSaveable(initialPage) { mutableStateOf(initialPage) }
    var search by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    val root = page == "Настройки"
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() {
        search = ""
        searchVisible = false
        focus.clearFocus()
        keyboard?.hide()
    }
    LaunchedEffect(root, searchVisible) {
        if (root && searchVisible) searchFocus.requestFocus()
    }
    var notificationsEnabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val bridge by viewModel.pcBridgeStatus.collectAsState()
    val verifying by viewModel.isVerifyingApiKey.collectAsState()
    val verification by viewModel.apiKeyVerificationMessage.collectAsState()
    val verified by viewModel.apiKeyVerificationOk.collectAsState()
    val mic by viewModel.voiceEngine.micCheckState.collectAsState()
    val voiceError by viewModel.voiceEngine.voiceError.collectAsState()
    var verificationCandidate by remember { mutableStateOf<String?>(null) }
    val back: () -> Unit = {
        when {
            root && searchVisible -> closeSearch()
            root -> onBack()
            else -> { focus.clearFocus(); keyboard?.hide(); page = "Настройки" }
        }
    }
    BackHandler(onBack = back)
    fun open(uri: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
            .onFailure { android.widget.Toast.makeText(context, "Не удалось открыть ссылку", android.widget.Toast.LENGTH_SHORT).show() }
    }
    val connection = when {
        !bridge.checked -> "Соединение ещё не проверено"
        bridge.connected -> "Подключено · ${bridge.instanceName.ifBlank { pcInstanceName }}"
        else -> "Нет подключения"
    }
    Column(Modifier.fillMaxSize().background(DesignColors.Bg).systemBarsPadding().imePadding()) {
        ReferencePageHeader(if (page == "Аккаунт") "Профиль" else page,
            navigationIcon = {
                IconButton(onClick = if (root) onOpenDrawer else back) {
                    Icon(if (root) Icons.Default.Menu else Icons.AutoMirrored.Filled.ArrowBack,
                        if (root) "Открыть меню" else "Назад к настройкам")
                }
            }, actions = {
                if (root) IconButton(onClick = { if (searchVisible) closeSearch() else searchVisible = true }) {
                    Icon(if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                        if (searchVisible) "Закрыть поиск настроек" else "Поиск настроек")
                }
            })
        key(page) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (root) {
                    if (searchVisible || search.isNotBlank()) OutlinedTextField(value = search, onValueChange = { search = it },
                        label = { Text("Поиск по настройкам") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus), shape = RoundedCornerShape(16.dp),
                        trailingIcon = {
                            if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                                Icon(Icons.Default.Close, "Очистить поиск")
                            }
                        })
                    if (search.trim().isNotEmpty()) {
                        val entries = listOf(
                            Triple("Внешний вид", "Размер текста и шрифт ответов", "размер текст шрифт чтение крупный мелкий font appearance"),
                            Triple("Профиль", "Google и веб-сессия", "профиль вход почта account google"),
                            Triple("Уведомления", "Фоновые задачи и разрешения Android", "notifications оповещения уведомления задачи"),
                            Triple("Голос", "Источник, тембр и микрофон", "voice микрофон звук озвучка речь перебивать live tts"),
                            Triple("Модели и ключи", "Gemini API и работа через ПК", "gemini api ключ модель argon flash"),
                            Triple("Подключение к ПК", "Адрес моста и соединение", "bridge мост адрес туннель интернет https компьютер"),
                            Triple("Помощник Android", "Вызов из других приложений", "android ассистент по умолчанию жест"),
                            Triple("Разрешения помощника", "Файлы, приложения и инструменты", "доступ данные поиск файлы permissions инструменты")
                        ).filter { (title, detail, aliases) ->
                            "$title $detail $aliases".contains(search.trim(), ignoreCase = true)
                        }
                        if (entries.isEmpty()) SettingsNote("Ничего не найдено. Попробуйте «голос», «ключ» или «мост».")
                        else SettingsGroup("Результаты · ${entries.size}") {
                            entries.forEachIndexed { index, (title, detail, _) ->
                                if (index > 0) SettingsRule()
                                SettingsLink(title, detail) {
                                    closeSearch()
                                    if (title == "Разрешения помощника") onOpenPermissions() else page = title
                                }
                            }
                        }
                    } else {
                    Surface(color = SettingsSurface, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().clickable { page = "Профиль" }) {
                        Column(Modifier.padding(18.dp)) {
                            Text(googleEmail.ifBlank { "Профиль" }, color = Color.White, fontSize = 18.sp,
                                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(if (googleEmail.isBlank()) "Вход не выполнен" else "Google и веб-сессия",
                                color = SettingsMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    SettingsGroup("") {
                        SettingsLink("Внешний вид", "", icon = Icons.Default.Palette) { page = "Внешний вид" }
                        SettingsRule()
                        SettingsLink("Голос", voiceTitle(settings.voiceProvider), icon = Icons.Default.MicNone) { page = "Голос" }
                        SettingsRule()
                        SettingsLink("Уведомления", "", icon = Icons.Default.NotificationsNone) { page = "Уведомления" }
                    }
                    SettingsGroup("") {
                        SettingsLink("Модели и ключи", "", icon = Icons.Default.Tune) { page = "Модели и ключи" }
                        SettingsRule()
                        SettingsLink("Подключение к ПК", if (bridge.connected) "Подключено" else "", icon = Icons.Default.Computer) { page = "Подключение к ПК" }
                    }
                    SettingsGroup("") {
                        SettingsLink("Помощник Android", "", icon = Icons.Default.Android) { page = "Помощник Android" }
                        SettingsRule()
                        SettingsLink("Разрешения помощника", "", icon = Icons.Default.Security) { onOpenPermissions() }
                    }
                    Text("Antigravity · ${BuildConfig.VERSION_NAME}", color = SettingsMuted, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
                if (page == "Внешний вид") {
                    SettingsGroup("Размер текста в чате") {
                        ChatTextSize.entries.forEach { size ->
                            Row(Modifier.fillMaxWidth().selectable(selected = settings.chatTextSize == size,
                                role = Role.RadioButton, onClick = { onUpdate { it.copy(chatTextSize = size) } })
                                .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = settings.chatTextSize == size, onClick = null)
                                Text(size.titleRu, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                    SettingsGroup("Шрифт ответов") {
                        ResponseFont.entries.forEach { font ->
                            Row(Modifier.fillMaxWidth().selectable(selected = settings.responseFont == font,
                                role = Role.RadioButton, onClick = { onUpdate { it.copy(responseFont = font) } })
                                .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = settings.responseFont == font, onClick = null)
                                Text(font.titleRu, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                    SettingsGroup("Предпросмотр") {
                        ChatGptMarkdownText(
                            markdown = "## Удобно читать\nРазмер текста меняется сразу, а **важное** остаётся заметным.\n\n```kotlin\nval message = \"Привет!\"\n```",
                            textSize = settings.chatTextSize, responseFont = settings.responseFont,
                            modifier = Modifier.padding(16.dp))
                    }
                    SettingsNote("Размер применяется к сообщениям и ответам. Шрифт с засечками — только к ответам; код остаётся моноширинным. Изменения сохраняются автоматически. Масштаб текста Android учитывается дополнительно.")
                }
                if (page == "Голос") {
                    SettingsGroup("Источник голоса") {
                        VoiceProvider.entries.forEachIndexed { index, provider ->
                            if (index > 0) SettingsRule()
                            Row(Modifier.fillMaxWidth().selectable(selected = settings.voiceProvider == provider, role = Role.RadioButton, onClick = {
                                onUpdate { it.copy(voiceProvider = provider) }
                            }).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = settings.voiceProvider == provider, onClick = null)
                                Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp)) {
                                    Text(voiceTitle(provider), color = Color.White, fontSize = 16.sp)
                                    Text(voiceDescription(provider), color = SettingsMuted, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    if (settings.voiceProvider != VoiceProvider.ON_DEVICE) SettingsGroup("Голос") {
                        AVAILABLE_VOICE_NAMES.forEach { voice ->
                            Row(Modifier.fillMaxWidth().selectable(selected = settings.voiceName == voice.id, role = Role.RadioButton, onClick = {
                                onUpdate { it.copy(voiceName = voice.id) }
                            }).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = settings.voiceName == voice.id, onClick = null)
                                Text(voice.displayNameRu, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                    SettingsGroup("Разговор") {
                        SettingsToggle("Перебивать голосом", "Останавливать ответ, когда вы начинаете говорить", settings.bargeInEnabled) {
                            onUpdate { old -> old.copy(bargeInEnabled = it) }
                        }
                    }
                    OutlinedButton(onClick = { viewModel.voiceEngine.runMicrophoneCheck() }, modifier = Modifier.fillMaxWidth()) { Text("Проверить микрофон") }
                    mic?.let { SettingsNote(it.message); TextButton(onClick = { viewModel.voiceEngine.clearMicrophoneCheck() }) { Text("Сбросить результат проверки") } }
                    voiceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { page = "Модели и ключи" }) { Text("Настроить ключ Gemini API") }
                    TextButton(onClick = { viewModel.openOfficialGeminiLiveApp() }) { Text("Открыть Gemini Live") }
                }
                if (page == "Модели и ключи") {
                    SettingsNote("Gemini 4 Argon и Gemini 3.8 работают через Antigravity на ПК. Для них нужен мост, ключ API не требуется. API-модели могут работать без ПК.")
                    SettingsGroup("Gemini API") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            var reveal by remember { mutableStateOf(false) }
                            OutlinedTextField(value = settings.geminiApiKey,
                                onValueChange = { value -> onUpdate { it.copy(geminiApiKey = value.trim()) } },
                                label = { Text("Ключ API") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Скрыть" else "Показать", fontSize = 12.sp) } })
                            Button(onClick = {
                                verificationCandidate = settings.geminiApiKey
                                viewModel.verifyGeminiApiKeyNow(settings.geminiApiKey)
                            },
                                enabled = !verifying && settings.geminiApiKey.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Text(if (verifying) "Проверка…" else "Проверить ключ")
                            }
                            if (verificationCandidate == settings.geminiApiKey && verification.isNotBlank()) Text(verification,
                                color = if (verified == true) Color.White else MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            TextButton(onClick = { open("https://aistudio.google.com/apikey") }) { Text("Открыть Google AI Studio") }
                        }
                    }
                    SettingsNote("Изменения сохраняются автоматически. Ключи и модели OpenRouter настраиваются в каталоге OpenRouter в меню выбора модели.")
                }
                if (page == "Подключение к ПК") {
                    SettingsGroup("Antigravity на компьютере") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(connection, color = Color.White, fontSize = 16.sp)
                            BridgeAddressField(value = settings.pcBridgeUrl,
                                onChange = { value -> onUpdate { it.copy(pcBridgeUrl = value.trim()) } })
                        }
                        SettingsRule()
                        SettingsToggle("Подключаться через интернет", "Использовать только публичный HTTPS-адрес, без локальной сети", settings.forcePublicTunnelOnly) {
                            viewModel.setForcePublicTunnelOnly(it)
                        }
                    }
                    SettingsNote("Для CLI-моделей компьютер и PC-Bridge должны быть включены. Поиск и запись файлов телефона выполняются Android-приложением.")
                    OutlinedButton(onClick = onOpenGoogleWeb, modifier = Modifier.fillMaxWidth()) { Text("Открыть веб-сессию") }
                }
                if (page == "Помощник Android") {
                    SettingsNote("Выберите Antigravity помощником по умолчанию. Способ вызова зависит от устройства и настроек Android.")
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                            .recoverCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                            .onFailure { android.widget.Toast.makeText(context, "Откройте раздел приложений по умолчанию в настройках Android", android.widget.Toast.LENGTH_LONG).show() }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Открыть настройки Android") }
                }
                if (page == "Профиль" || page == "Аккаунт") {
                    SettingsGroup("Google") { Text(googleEmail.ifBlank { "Вход не выполнен" }, color = Color.White, modifier = Modifier.padding(16.dp)) }
                    OutlinedButton(onClick = onOpenGoogleWeb, modifier = Modifier.fillMaxWidth()) { Text("Открыть веб-сессию") }
                    TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Сменить аккаунт") }
                    SettingsGroup("На этом устройстве") {
                        Text("Antigravity · ${BuildConfig.VERSION_NAME}", color = DesignColors.TextPrimary, modifier = Modifier.padding(16.dp))
                    }
                    SettingsNote("История обычных чатов сохраняется на телефоне. Настройки чтения и голоса сохраняются автоматически.")
                }
                if (page == "Уведомления") {
                    SettingsGroup("Статус") {
                        Text(if (notificationsEnabled) "Уведомления разрешены" else "Уведомления отключены",
                            color = DesignColors.TextPrimary, modifier = Modifier.padding(16.dp))
                    }
                    SettingsNote("Antigravity сообщает о выполнении фоновых задач. Звук, показ на экране блокировки и отдельные категории настраиваются в Android.")
                    Button(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }.onFailure {
                            android.widget.Toast.makeText(context, "Не удалось открыть настройки уведомлений", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Настроить уведомления в Android") }
                }
            }
        }
    }
}

private fun voiceTitle(provider: VoiceProvider) = when (provider) {
    VoiceProvider.NEURAL_BRIDGE -> "Голос через ПК"
    VoiceProvider.GEMINI_LIVE -> "Gemini Live"
    VoiceProvider.GEMINI_TTS -> "Озвучка Gemini"
    VoiceProvider.ON_DEVICE -> "Голос Android"
}
private fun voiceDescription(provider: VoiceProvider) = when (provider) {
    VoiceProvider.NEURAL_BRIDGE -> "Озвучка Edge Neural через мост"
    VoiceProvider.GEMINI_LIVE -> "Голосовой разговор · нужен ключ API"
    VoiceProvider.GEMINI_TTS -> "Чтение ответов · нужен ключ API"
    VoiceProvider.ON_DEVICE -> "Системный синтез речи устройства"
}
/** Echo IME edits synchronously; settings/connection updates must not reset the active edit. */
@Composable private fun BridgeAddressField(value: String, onChange: (String) -> Unit) {
    var edit by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(value)) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val save by rememberUpdatedState(onChange)
    var pending by rememberSaveable { mutableStateOf(false) }
    fun flush() {
        if (pending) { pending = false; save(edit.text) }
    }
    LaunchedEffect(edit.text, edit.composition, pending) {
        if (pending && edit.composition == null) {
            kotlinx.coroutines.delay(250)
            flush()
        }
    }
    DisposableEffect(Unit) { onDispose { flush() } }
    LaunchedEffect(value, focused) {
        if (!focused && !pending && edit.text != value) edit = TextFieldValue(value, TextRange(value.length))
    }
    OutlinedTextField(value = edit,
        onValueChange = { next ->
            val changed = edit.text != next.text
            edit = next
            if (changed) pending = true
        },
        label = { Text("Адрес моста") },
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (focused && !it.isFocused) flush()
            focused = it.isFocused
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }))
}

@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title.isNotBlank()) Text(title, color = SettingsMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
        Surface(color = SettingsSurface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Column(content = content) }
    }
}
@Composable private fun SettingsLink(title: String, detail: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 64.dp).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, tint = DesignColors.TextPrimary, modifier = Modifier.padding(end = 16.dp).size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 18.sp)
            if (detail.isNotBlank()) Text(detail, color = SettingsMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (icon == null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = SettingsMuted, modifier = Modifier.padding(start = 8.dp).size(20.dp))
    }
}
@Composable private fun SettingsToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(detail, color = SettingsMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Switch(colors = com.google.antigravity.ui.design.designSwitchColors(), checked = checked, onCheckedChange = null)
    }
}
@Composable private fun SettingsRule() { HorizontalDivider(color = DesignColors.Bg, thickness = 2.dp) }
@Composable private fun SettingsNote(text: String) { Text(text, color = SettingsMuted, fontSize = 14.sp, lineHeight = 21.sp) }
