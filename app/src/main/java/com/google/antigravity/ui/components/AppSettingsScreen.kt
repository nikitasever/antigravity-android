package com.google.antigravity.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.BuildConfig
import com.google.antigravity.model.*
import com.google.antigravity.viewmodel.AntigravityViewModel

private val SettingsMuted = Color(0xFF9E9E9E)
private val SettingsSurface = Color(0xFF212121)

@Composable
fun AppSettingsScreen(
    viewModel: AntigravityViewModel, settings: AntigravitySettings,
    googleEmail: String, pcInstanceName: String,
    onUpdate: ((AntigravitySettings) -> AntigravitySettings) -> Unit,
    onOpenGoogleWeb: () -> Unit, onSignOut: () -> Unit, onBack: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf("Настройки") }
    val root = page == "Настройки"
    val context = LocalContext.current
    val bridge by viewModel.pcBridgeStatus.collectAsState()
    val verifying by viewModel.isVerifyingApiKey.collectAsState()
    val verification by viewModel.apiKeyVerificationMessage.collectAsState()
    val verified by viewModel.apiKeyVerificationOk.collectAsState()
    val mic by viewModel.voiceEngine.micCheckState.collectAsState()
    val voiceError by viewModel.voiceEngine.voiceError.collectAsState()
    var verificationCandidate by remember { mutableStateOf<String?>(null) }
    val back = { if (root) onBack() else page = "Настройки" }
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
    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) {
                Icon(if (root) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                    if (root) "Закрыть настройки" else "Назад к настройкам", tint = Color.White)
            }
            Text(page, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
        key(page) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (root) {
                    SettingsGroup("Аккаунт") {
                        SettingsLink("Google", googleEmail.ifBlank { "Вход не выполнен" }) { page = "Аккаунт" }
                    }
                    SettingsGroup("Приложение") {
                        SettingsLink("Голос", voiceTitle(settings.voiceProvider)) { page = "Голос" }
                        SettingsRule()
                        SettingsLink("Модели и ключи", "Gemini API и работа через ПК") { page = "Модели и ключи" }
                        SettingsRule()
                        SettingsLink("Помощник Android", "Вызов из других приложений") { page = "Помощник Android" }
                    }
                    SettingsGroup("Подключения") {
                        SettingsLink("Подключение к ПК", connection) { page = "Подключение к ПК" }
                    }
                    SettingsGroup("Данные и доступ") {
                        SettingsLink("Разрешения помощника", "Файлы, приложения и инструменты") { onOpenPermissions() }
                    }
                    SettingsNote("Обычные чаты сохраняются на этом телефоне. Временные чаты остаются только до закрытия.")
                    Text("Antigravity · ${BuildConfig.VERSION_NAME}", color = SettingsMuted, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
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
                            OutlinedTextField(value = settings.pcBridgeUrl,
                                onValueChange = { value -> onUpdate { it.copy(pcBridgeUrl = value.trim()) } },
                                label = { Text("Адрес моста") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
                if (page == "Аккаунт") {
                    SettingsGroup("Google") { Text(googleEmail.ifBlank { "Вход не выполнен" }, color = Color.White, modifier = Modifier.padding(16.dp)) }
                    OutlinedButton(onClick = onOpenGoogleWeb, modifier = Modifier.fillMaxWidth()) { Text("Открыть веб-сессию") }
                    TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Сменить аккаунт") }
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
@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = SettingsMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
        Surface(color = SettingsSurface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Column(content = content) }
    }
}
@Composable private fun SettingsLink(title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(detail, color = SettingsMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = SettingsMuted, modifier = Modifier.padding(start = 8.dp).size(20.dp))
    }
}
@Composable private fun SettingsToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(detail, color = SettingsMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}
@Composable private fun SettingsRule() { HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(horizontal = 16.dp)) }
@Composable private fun SettingsNote(text: String) { Text(text, color = SettingsMuted, fontSize = 14.sp, lineHeight = 21.sp) }
