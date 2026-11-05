package com.google.antigravity.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.model.VoiceSessionStatus
import com.google.antigravity.model.VoiceTransportMode
import com.google.antigravity.model.VoiceVisualizerStyle
import com.google.antigravity.voice.RealtimeVoiceEngine
import com.google.antigravity.ui.design.DesignColors
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Голосовой экран по живому референсу ChatGPT (091536):
 * - Ровно ОДНА органичная голубая сфера по центру экрана, плавно реагирующая на голос пользователя и ИИ.
 * - Субтитры включаются в меню, вместо постоянного крупного текста под сферой.
 * - Внизу — ввод текста, микрофон и белая кнопка завершения разговора.
 * - Полное отсутствие любых технических/отладочных надписей.
 */
@Composable
fun OrbitVoiceOverlay(
    voiceEngine: RealtimeVoiceEngine,
    visualizerStyle: VoiceVisualizerStyle,
    transportMode: VoiceTransportMode,
    onCycleVisualizerStyle: () -> Unit,
    onEndCall: () -> Unit,
    textDraft: String = "",
    onTextDraftChange: (String) -> Unit = {},
    onSendText: (String) -> Unit = {},
    onAddAttachment: () -> Unit = {},
    voiceLabel: String = "Системный",
    onOpenVoiceSettings: () -> Unit = {}
) {
    val isCallActive by voiceEngine.isCallActive.collectAsState()
    val isExpanded by voiceEngine.isOverlayExpanded.collectAsState()
    val status by voiceEngine.status.collectAsState()
    val isMuted by voiceEngine.isMuted.collectAsState()
    val partialUserText by voiceEngine.livePartialTranscript.collectAsState()
    val aiSubtitle by voiceEngine.liveAiSubtitle.collectAsState()
    val audioLevel by voiceEngine.audioLevel.collectAsState()
    val spectrumBands by voiceEngine.spectrumBands.collectAsState()

    var showCaptions by rememberSaveable { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    var sheetTopPx by remember { mutableStateOf<Float?>(null) }
    var contentTopPx by remember { mutableStateOf(0f) }
    var shaderFailed by remember { mutableStateOf(false) }
    var shaderReady by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val supportsGles3 = remember(context) {
        context.getSystemService(android.app.ActivityManager::class.java)
            ?.deviceConfigurationInfo?.reqGlEsVersion?.let { it >= 0x30000 } == true
    }
    LaunchedEffect(isCallActive, isExpanded) {
        if (!isCallActive || !isExpanded) {
            optionsOpen = false
            sheetTopPx = null
            shaderReady = false
        }
        if (!isCallActive) shaderFailed = false
    }
    BackHandler(enabled = isCallActive && isExpanded && !optionsOpen) {
        voiceEngine.setOverlayExpanded(false)
    }
    if (optionsOpen && isCallActive && isExpanded) VoiceConversationSettingsSheet(
        voiceLabel = voiceLabel,
        onOpenVoiceSettings = { optionsOpen = false; onOpenVoiceSettings() },
        showCaptions = showCaptions,
        onCaptionsChange = { showCaptions = it },
        onCycleVisualizerStyle = onCycleVisualizerStyle,
        onDismiss = { optionsOpen = false; sheetTopPx = null },
        onSheetTopChanged = { sheetTopPx = it }
    )
    LaunchedEffect(isCallActive, isExpanded, visualizerStyle, shaderReady, lifecycleState) {
        if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && isCallActive && isExpanded && supportsGles3 &&
            visualizerStyle == VoiceVisualizerStyle.ORBIT_ROSETTE && !shaderReady) {
            kotlinx.coroutines.delay(8_000L)
            shaderFailed = true
        }
    }

    AnimatedVisibility(
        visible = isCallActive && isExpanded,
        enter = fadeIn(animationSpec = tween(220)),
        exit = fadeOut(animationSpec = tween(180))
    ) {
        val smoothAudio by animateFloatAsState(
            targetValue = audioLevel,
            animationSpec = tween(durationMillis = 60),
            label = "smooth_audio"
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .onGloballyPositioned {
                    val top = it.positionOnScreen().y
                    if (top.isFinite()) contentTopPx = top
                }
        ) {
            val density = LocalDensity.current
            // Dialog and Activity have independent window origins after recreation.
            // Compare screen coordinates, ignoring the sheet's unplaced initial frame.
            val sheetBoundary = sheetTopPx?.takeIf { it.isFinite() && it > contentTopPx }
            val availableHeight = if (optionsOpen && sheetBoundary != null) {
                with(density) { (sheetBoundary - contentTopPx).toDp() }
                    .coerceIn(0.dp, maxHeight)
            } else maxHeight
            val voiceHeight by animateDpAsState(availableHeight, tween(220), label = "voiceAboveSettings")
            Column(
                modifier = Modifier.fillMaxWidth().height(voiceHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Верхний ряд: лаконичная кнопка сворачивания в чат
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (optionsOpen) 0f else 1f)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF303030))
                            .clickable { voiceEngine.setOverlayExpanded(false) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Свернуть в чат",
                            tint = Color(0xFFE5E5E5),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Box {
                        IconButton(onClick = { optionsOpen = true }, modifier = Modifier.size(48.dp)
                            .clip(CircleShape).background(Color(0xFF303030))) {
                            Icon(Icons.Default.Tune, "Настройки разговора", tint = Color.White)
                        }
                    }
                }

                // Центральная область: ровно ОДНА органичная голубая сфера + живые субтитры
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f)
                ) {
                    BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    val orbSize = minOf(330.dp, maxWidth, maxHeight)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(orbSize).align(Alignment.Center)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (status == VoiceSessionStatus.AI_SPEAKING) {
                                    voiceEngine.interruptAiAndListenNow()
                                } else if (partialUserText.isNotBlank()) {
                                    voiceEngine.flushPendingVoiceUtteranceNow()
                                } else {
                                    voiceEngine.interruptAiAndListenNow()
                                }
                            }
                    ) {
                        if (!shaderReady || shaderFailed || !supportsGles3 ||
                            visualizerStyle != VoiceVisualizerStyle.ORBIT_ROSETTE) {
                            ChatGptVolumetricCloudCanvas(status, smoothAudio, spectrumBands, visualizerStyle,
                                modifier = Modifier.fillMaxSize())
                        }
                        if (supportsGles3 && !shaderFailed && visualizerStyle == VoiceVisualizerStyle.ORBIT_ROSETTE) {
                            ChatGptRealOpenGlOrbView(status, visualizerStyle, smoothAudio, spectrumBands,
                                onRendererInitializing = {
                                    if (isCallActive && isExpanded) {
                                        shaderReady = false
                                        shaderFailed = false
                                    }
                                },
                                modifier = Modifier.fillMaxSize(), onRendererReady = {
                                    // The exit animation still owns its surface briefly.
                                    // A warm-up callback must not undo the collapse reset.
                                    if (isCallActive && isExpanded) {
                                        shaderReady = it
                                        shaderFailed = !it
                                    }
                                })
                        }
                    }
                    }

                    if (showCaptions) {
                        val caption = partialUserText.takeIf { it.isNotBlank() && !isMuted }
                            ?: aiSubtitle.takeIf { it.isNotBlank() }
                        if (caption != null) Text(caption, color = Color(0xFFE0E0E0),
                            fontSize = 16.sp, lineHeight = 24.sp, textAlign = TextAlign.Center,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 24.dp))
                    }
                    if (status == VoiceSessionStatus.RECONNECTING) Text("Восстанавливаем соединение…",
                        color = Color(0xFFC4C3CB), fontSize = 14.sp,
                        modifier = Modifier.padding(top = 16.dp))
                }

                Row(Modifier.fillMaxWidth().alpha(if (optionsOpen) 0f else 1f).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom) {
                    Surface(color = Color(0xFF303030), shape = RoundedCornerShape(30.dp),
                        modifier = Modifier.weight(1f)) {
                        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onAddAttachment) {
                                Icon(Icons.Default.Add, "Добавить вложение", tint = Color.White)
                            }
                            BasicTextField(value = textDraft, onValueChange = onTextDraftChange,
                                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                                maxLines = 3, textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                                cursorBrush = SolidColor(Color.White), decorationBox = { field ->
                                    if (textDraft.isEmpty()) Text("Спросить…", color = Color(0xFFC4C3CB), fontSize = 16.sp)
                                    field()
                                })
                            if (textDraft.isNotBlank()) IconButton(onClick = { onSendText(textDraft) }) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Отправить сообщение", tint = Color.White)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    IconButton(onClick = { voiceEngine.toggleMute() }, modifier = Modifier.size(56.dp)
                        .clip(CircleShape).background(Color(0xFF303030))) {
                        Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            if (isMuted) "Включить микрофон" else "Выключить микрофон", tint = Color.White)
                    }
                    IconButton(onClick = onEndCall, modifier = Modifier.size(56.dp)
                        .clip(CircleShape).background(Color.White)) {
                        Icon(Icons.Default.Close, "Завершить разговор", tint = Color.Black)
                    }
                }

            }
        }
    }
}

/**
 * Компактная плавающая капсула активного голосового режима, когда пользователь свернул голос в чат.
 */
@Composable
fun FloatingVoiceCallPill(
    voiceEngine: RealtimeVoiceEngine,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCallActive by voiceEngine.isCallActive.collectAsState()
    val isExpanded by voiceEngine.isOverlayExpanded.collectAsState()
    val status by voiceEngine.status.collectAsState()
    val durationSec by voiceEngine.callDurationSeconds.collectAsState()

    if (!isCallActive || isExpanded) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { voiceEngine.setOverlayExpanded(true) },
        shape = RoundedCornerShape(26.dp),
        color = DesignColors.SurfaceHigh
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, DesignColors.Border, RoundedCornerShape(26.dp))
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(DesignColors.AccentBlue)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                Text("Голосовой разговор · ${formatDuration(durationSec)}", color = DesignColors.TextPrimary,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text =
                        when (status) {
                            VoiceSessionStatus.AI_SPEAKING -> "Говорит"
                            else -> status.labelRu
                        },
                    color = DesignColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                }
            }

            IconButton(
                onClick = onEndCall,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Завершить",
                    tint = Color(0xFFB0B0B0),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

@Composable
private fun ChatGptVolumetricCloudCanvas(
    status: VoiceSessionStatus,
    audioLevel: Float,
    spectrumBands: FloatArray,
    visualizerStyle: VoiceVisualizerStyle,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "cloud_orb_transition")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_phase"
    )
    val secondaryPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_phase_2"
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val hoverY = size.minDimension * 0.01f * sin(phase)
        val cy = (size.height / 2f) - hoverY
        val baseRadius = size.minDimension * 0.37f
        val pulseBoost = when (status) {
            VoiceSessionStatus.AI_SPEAKING -> 1.04f + audioLevel * 0.14f
            VoiceSessionStatus.USER_SPEAKING -> 1.0f + audioLevel * 0.12f
            VoiceSessionStatus.THINKING -> 0.95f + 0.04f * sin(secondaryPhase * 2f)
            else -> 0.99f + 0.025f * sin(phase)
        }
        val r = (baseRadius * pulseBoost).coerceAtMost(size.minDimension * 0.43f)
        val center = Offset(cx, cy)

        // Внешнее мягкое атмосферное свечение, полностью затухающее внутри холста без обрезки
        val outerGlowRadius = (r * 1.15f).coerceAtMost(size.minDimension * 0.49f)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to Color(0x382596FF),
                    0.78f to Color(0x180077FF),
                    0.92f to Color(0x060055CC),
                    1.0f to Color.Transparent
                ),
                center = center,
                radius = outerGlowRadius
            ),
            radius = outerGlowRadius,
            center = center
        )

        // Идеально гладкий круговой SDF-силуэт сферы (как в voice_main.fsh: length(adjusted_st) - radius)
        val spherePath = Path().apply {
            addOval(Rect(center = center, radius = r))
        }

        clipPath(spherePath) {
            // Базовый акварельный слой (bloopColorsMain #E3F5FF, bloopColorsLow #03B0FF, bloopColorsMid #4578FF)
            val cloudCenterOffset = Offset(
                x = cx - r * 0.18f * cos(phase),
                y = cy - r * 0.24f * sin(phase * 0.8f)
            )
            drawCircle(
                brush = if (visualizerStyle == VoiceVisualizerStyle.ORBIT_ROSETTE) {
                    // Live ChatGPT 091536: blue upper hemisphere, pale lower hemisphere.
                    Brush.verticalGradient(listOf(Color(0xFF4D7CEE), Color(0xFFBACBF9), Color(0xFFEEF2FE)),
                        startY = cy - r, endY = cy + r)
                } else Brush.radialGradient(
                    colors = when (visualizerStyle) {
                        VoiceVisualizerStyle.ORBIT_ROSETTE -> listOf(Color(0xFF4D7CEE), Color(0xFFEEF2FE))
                        VoiceVisualizerStyle.ANTIGRAVITY_CORE -> listOf(
                            Color(0xFFF5F0FF),
                            Color(0xFFC4B5FD),
                            Color(0xFF60A5FA),
                            Color(0xFF3B82F6),
                            Color(0xFF1D4ED8)
                        )
                        VoiceVisualizerStyle.BELL_WAVE -> listOf(
                            Color(0xFFFFFFFF),
                            Color(0xFFBAE6FD),
                            Color(0xFF38BDF8),
                            Color(0xFF0284C7)
                        )
                    },
                    center = cloudCenterOffset,
                    radius = r * 1.45f
                ),
                radius = r * 1.05f,
                center = center
            )

            // Акварельные облачные слои (FBM волны sn2 / sn2Bis / sn2Third из voice_prepass.fsh)
            for (lobe in 0 until 4) {
                val bandVal = spectrumBands.getOrElse(lobe * 3) { audioLevel }
                val lobeAngle = phase * (if (lobe % 2 == 0) 1f else -0.8f) + lobe * 1.57f
                val distFactor = 0.22f + 0.12f * sin(secondaryPhase + lobe)
                val lobeCenter = Offset(
                    x = cx + r * distFactor * cos(lobeAngle),
                    y = cy + r * (distFactor * 0.9f) * sin(lobeAngle + secondaryPhase * 0.6f)
                )
                val lobeRadius = r * (0.68f + bandVal * 0.22f + audioLevel * 0.14f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = if (lobe % 2 == 0) {
                            listOf(
                                Color(0xD8FFFFFF),
                                Color(0x88CCF0FF),
                                Color.Transparent
                            )
                        } else {
                            listOf(
                                Color(0xB88CE0FF),
                                Color(0x551DA1FF),
                                Color.Transparent
                            )
                        },
                        center = lobeCenter,
                        radius = lobeRadius
                    ),
                    radius = lobeRadius,
                    center = lobeCenter
                )
            }

            // Мягкое сферическое объёмное затенение у краёв без жёсткой рамки
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.76f to Color.Transparent,
                        0.93f to Color(0x22023896),
                        1.0f to Color(0x4401256B)
                    ),
                    center = center,
                    radius = r
                ),
                radius = r,
                center = center
            )
        }
    }
}

