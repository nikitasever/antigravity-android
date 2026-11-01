package com.google.antigravity.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Полнодуплексный голосовой экран 1:1 в стиле ChatGPT Android:
 * - Ровно ОДНА органичная голубая сфера по центру экрана, плавно реагирующая на голос пользователя и ИИ.
 * - Под сферой — живые субтитры речи пользователя и ответа ИИ на русском языке.
 * - Внизу — только две крупные круглые кнопки: микрофон (вкл/выкл) и закрытие (`✕`).
 * - Полное отсутствие любых технических/отладочных надписей.
 */
@Composable
fun OrbitVoiceOverlay(
    voiceEngine: RealtimeVoiceEngine,
    visualizerStyle: VoiceVisualizerStyle,
    transportMode: VoiceTransportMode,
    onCycleVisualizerStyle: () -> Unit,
    onEndCall: () -> Unit
) {
    val isCallActive by voiceEngine.isCallActive.collectAsState()
    val isExpanded by voiceEngine.isOverlayExpanded.collectAsState()
    val status by voiceEngine.status.collectAsState()
    val isMuted by voiceEngine.isMuted.collectAsState()
    val partialUserText by voiceEngine.livePartialTranscript.collectAsState()
    val aiSubtitle by voiceEngine.liveAiSubtitle.collectAsState()
    val audioLevel by voiceEngine.audioLevel.collectAsState()
    val spectrumBands by voiceEngine.spectrumBands.collectAsState()

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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Верхний ряд: лаконичная кнопка сворачивания в чат
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1A1A1A))
                            .clickable { voiceEngine.setOverlayExpanded(false) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Свернуть в чат",
                            tint = Color(0xFFE5E5E5),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Центральная область: ровно ОДНА органичная голубая сфера + живые субтитры
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(288.dp)
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
                        ChatGptVolumetricCloudCanvas(
                            status = status,
                            audioLevel = smoothAudio,
                            spectrumBands = spectrumBands,
                            visualizerStyle = visualizerStyle,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Живые субтитры пользователя (накапливают сегменты через паузы 2.2 с без обрыва на полуслове)
                    if (partialUserText.isNotBlank() && !isMuted) {
                        Text(
                            text = "«$partialUserText»",
                            color = Color(0xFFE0E0E0),
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (status == VoiceSessionStatus.LISTENING || status == VoiceSessionStatus.USER_SPEAKING) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xFF1F2937))
                                    .clickable { voiceEngine.flushPendingVoiceUtteranceNow() }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Отправить фразу ↑",
                                    color = Color(0xFF60A5FA),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    val mainHeadline = when {
                        isMuted -> "Микрофон выключен (Пауза)"
                        status == VoiceSessionStatus.THINKING -> "Думаю…"
                        status == VoiceSessionStatus.AI_SPEAKING && aiSubtitle.isNotBlank() -> aiSubtitle
                        aiSubtitle.isNotBlank() -> aiSubtitle
                        status == VoiceSessionStatus.USER_SPEAKING -> "Слушаю вас (говорите без спешки)…"
                        else -> "Слушаю…"
                    }

                    Text(
                        text = mainHeadline,
                        color = Color.White,
                        fontSize = if (mainHeadline.length > 65) 16.sp else 19.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 25.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val subHint = when (status) {
                        VoiceSessionStatus.AI_SPEAKING -> "Нажмите на сферу или начните говорить, чтобы прервать"
                        VoiceSessionStatus.MUTED -> "Микрофон на паузе — нажмите внизу, чтобы продолжить"
                        VoiceSessionStatus.THINKING -> "Формирую голосовой ответ…"
                        else -> "Можно делать паузы на вдох — фраза отправится после 2.2 с тишины"
                    }

                    Text(
                        text = subHint,
                        color = Color(0xFF8E8E8E),
                        fontSize = 12.5.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Нижний ряд: ТОЛЬКО кнопка микрофона (вкл/выкл) и кнопка закрытия (✕)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Левая кнопка: Вкл/Выкл микрофон (68.dp)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(if (isMuted) Color(0xFFEF4444) else Color(0xFF212121))
                            .clickable { voiceEngine.toggleMute() }
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (isMuted) "Включить микрофон" else "Выключить микрофон",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Правая кнопка: Завершить голосовой режим ✕ (68.dp)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF212121))
                            .clickable { onEndCall() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Выйти из голосового режима",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
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
        color = Color(0xFF1E1E1E)
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(26.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
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
                        .background(Color(0xFF3B82F6))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Голосовой режим • ${formatDuration(durationSec)} • ${
                        when (status) {
                            VoiceSessionStatus.AI_SPEAKING -> "Говорит"
                            VoiceSessionStatus.THINKING -> "Думает…"
                            VoiceSessionStatus.MUTED -> "Выкл. микр."
                            else -> "Слушает…"
                        }
                    }",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onEndCall,
                modifier = Modifier.size(28.dp)
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
                brush = Brush.radialGradient(
                    colors = when (visualizerStyle) {
                        VoiceVisualizerStyle.ORBIT_ROSETTE -> listOf(
                            Color(0xFFF6FCFF),
                            Color(0xFFC2EBFF),
                            Color(0xFF47B8FF),
                            Color(0xFF0A78F2),
                            Color(0xFF044BB8)
                        )
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

