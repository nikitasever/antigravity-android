package com.google.antigravity.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.google.antigravity.MainActivity
import com.google.antigravity.device.PhoneResourceController
import com.google.antigravity.network.AntigravityBackendClient
import com.google.antigravity.voice.RealtimeVoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Компактная всплывающая панель цифрового помощника поверх любого приложения (Раздел 5.6a):
 * Вызывается по системному жесту ассистента (`ACTION_ASSIST`), кнопке голосового вызова (`VOICE_COMMAND`)
 * или из плитки быстрых настроек (`AntigravityQuickTileService`).
 */
class AntigravityAssistActivity : ComponentActivity() {

    private var capturedScreenSummary by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extraContext = intent?.getStringExtra("assist_screen_context").orEmpty()
        if (extraContext.isNotBlank()) {
            capturedScreenSummary = extraContext
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF3B82F6),
                    background = Color.Transparent,
                    surface = Color(0xFF171717),
                    onSurface = Color.White
                )
            ) {
                AssistOverlaySheetContent(
                    screenContextSummary = capturedScreenSummary,
                    onDismiss = { finish() },
                    onOpenFullApp = { promptToPass, openVoice, openPhoneSearch ->
                        val launchIntent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            if (openVoice) {
                                action = MainActivity.ACTION_SHORTCUT_VOICE
                            } else if (openPhoneSearch) {
                                action = MainActivity.ACTION_SHORTCUT_PHONE_SEARCH
                            } else if (promptToPass.isNotBlank()) {
                                action = Intent.ACTION_SEND
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, promptToPass)
                            }
                        }
                        startActivity(launchIntent)
                        finish()
                    }
                )
            }
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent?) {
        super.onProvideAssistContent(outContent)
    }

    fun updateCapturedAssistStructure(structure: AssistStructure?) {
        if (structure == null) return
        val texts = mutableListOf<String>()
        try {
            val count = structure.windowNodeCount
            for (i in 0 until count) {
                val win = structure.getWindowNodeAt(i)
                collectNodeText(win.rootViewNode, texts)
            }
            if (texts.isNotEmpty()) {
                capturedScreenSummary = texts.distinct().take(25).joinToString(" • ")
            }
        } catch (_: Throwable) {
        }
    }

    private fun collectNodeText(node: AssistStructure.ViewNode?, out: MutableList<String>) {
        if (node == null || out.size >= 30) return
        val txt = node.text?.toString()?.trim().orEmpty()
        if (txt.length in 2..140) {
            out.add(txt)
        }
        val childCount = node.childCount
        for (i in 0 until childCount) {
            collectNodeText(node.getChildAt(i), out)
        }
    }
}

@Composable
private fun AssistOverlaySheetContent(
    screenContextSummary: String,
    onDismiss: () -> Unit,
    onOpenFullApp: (prompt: String, openVoice: Boolean, openPhoneSearch: Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val phoneController = remember { PhoneResourceController(context.applicationContext) }
    val backendClient = remember { AntigravityBackendClient(phoneController) }
    val voiceEngine = remember {
        RealtimeVoiceEngine(
            context = context.applicationContext,
            onUserUtteranceFinalized = {},
            onAgentVoiceCommandDetected = { false }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceEngine.release()
        }
    }

    var inputPrompt by remember { mutableStateOf("") }
    var includeScreenContext by remember { mutableStateOf(true) }
    var isThinking by remember { mutableStateOf(false) }
    var aiAnswer by remember { mutableStateOf("") }

    fun submitQuickQuery(rawText: String) {
        val clean = rawText.trim()
        if (clean.isBlank() || isThinking) return
        isThinking = true
        aiAnswer = ""
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                // Сначала проверяем, можно ли мгновенно выполнить действие на телефоне
                val localAction = phoneController.executeNaturalPhoneRequest(clean)
                if (localAction != null) {
                    return@withContext "${localAction.summaryRu}\n\n${localAction.outputOrDiff}".trim()
                }
                val finalPrompt = if (includeScreenContext && screenContextSummary.isNotBlank()) {
                    "$clean\n\n[Контекст текущего экрана: $screenContextSummary]"
                } else {
                    clean
                }
                val bridgeReply = backendClient.sendQuickAssistPrompt(
                    prompt = finalPrompt,
                    conversationId = "4b1cdeee-81b3-456a-b09e-af65b6bee01d",
                    modelId = "gemini-4-argon-high"
                )
                bridgeReply?.takeIf { it.isNotBlank() }
                    ?: "Ассистент Antigravity готов. Нажмите «Открыть в чате» для развёрнутой работы."
            }
            isThinking = false
            aiAnswer = reply
            voiceEngine.speakAgentResponse(reply, flushExisting = true, forceSpeakEvenOutsideCall = true)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = Color(0xFF171717),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFF3B82F6), Color(0xFF60A5FA), Color(0xFF2E2E2E))
                    ),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                )
                .navigationBarsPadding()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                // Индикатор шторки
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(38.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF3A3A3A))
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Шапка оверлея ассистента
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2563EB))
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Помощник Antigravity",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Быстрый вызов поверх приложений",
                                color = Color(0xFF9E9E9E),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onOpenFullApp(inputPrompt, false, false) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "В полный чат",
                                tint = Color(0xFFB4B4B4),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = Color(0xFFB4B4B4),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Переключатель согласия на передачу контекста экрана
                Surface(
                    color = Color(0xFF212121),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                tint = Color(0xFF60A5FA),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Учитывать контекст текущего экрана",
                                color = Color(0xFFE3E3E3),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = includeScreenContext,
                            onCheckedChange = { includeScreenContext = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF2563EB)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Чипсы быстрых действий
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistActionChip(
                        icon = Icons.Default.AutoAwesome,
                        label = "Что на экране",
                        onClick = {
                            val q = "Кратко объясни, что сейчас открыто на экране, и предложи полезные действия."
                            inputPrompt = q
                            submitQuickQuery(q)
                        }
                    )
                    AssistActionChip(
                        icon = Icons.Default.Search,
                        label = "Поиск по телефону",
                        onClick = { onOpenFullApp("", false, true) }
                    )
                    AssistActionChip(
                        icon = Icons.Default.GraphicEq,
                        label = "Голосовой режим",
                        onClick = { onOpenFullApp("", true, false) }
                    )
                }

                // Блок ответа ИИ
                AnimatedVisibility(visible = isThinking || aiAnswer.isNotBlank()) {
                    Surface(
                        color = Color(0xFF212121),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (isThinking) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF60A5FA),
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Думаю…",
                                        color = Color(0xFFB4B4B4),
                                        fontSize = 13.sp
                                    )
                                }
                            } else {
                                Text(
                                    text = aiAnswer,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                voiceEngine.toggleSpeakMessageAloud(aiAnswer)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.VolumeUp,
                                            contentDescription = "Озвучить",
                                            tint = Color(0xFF60A5FA),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Озвучить",
                                            color = Color(0xFF60A5FA),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    Text(
                                        text = "Открыть в чате →",
                                        color = Color(0xFFB4B4B4),
                                        fontSize = 12.sp,
                                        modifier = Modifier.clickable {
                                            onOpenFullApp(inputPrompt.ifBlank { aiAnswer }, false, false)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Поле ввода и микрофон
                Surface(
                    color = Color(0xFF2F2F2F),
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (inputPrompt.isEmpty()) {
                                Text(
                                    text = "Спросите Antigravity…",
                                    color = Color(0xFF9E9E9E),
                                    fontSize = 15.sp
                                )
                            }
                            BasicTextField(
                                value = inputPrompt,
                                onValueChange = { inputPrompt = it },
                                textStyle = TextStyle(
                                    color = Color.White,
                                    fontSize = 15.sp
                                ),
                                cursorBrush = SolidColor(Color.White),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { submitQuickQuery(inputPrompt) }),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        IconButton(
                            onClick = {
                                voiceEngine.startInlineDictation { dictated ->
                                    inputPrompt = dictated
                                    submitQuickQuery(dictated)
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Голосовой ввод",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (inputPrompt.isNotBlank()) Color.White else Color(0xFF424242))
                                .clickable(enabled = inputPrompt.isNotBlank()) {
                                    submitQuickQuery(inputPrompt)
                                }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Отправить",
                                tint = if (inputPrompt.isNotBlank()) Color.Black else Color(0xFF8E8E8E),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        color = Color(0xFF242424),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF60A5FA),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = Color(0xFFE3E3E3),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
