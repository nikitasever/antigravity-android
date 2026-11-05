package com.google.antigravity.voice

import com.google.antigravity.model.VoiceSessionStatus
import kotlin.math.atan
import kotlin.math.sin

/** zr31.c: time-based atan/sin easing, normal transition duration 0.65 s.
 * Project statuses map onto the shader's listen/think/speak/halt channels.
 * Interrupted transitions continue from the current weights without snapping.
 */
internal class VoiceOrbMotion {
    val weights = FloatArray(4)
    val timestamps = FloatArray(4)
    private var startWeights = FloatArray(4)
    private var target = 0
    private var changedAt = 0f

    init { reset() }

    fun reset() {
        weights.fill(0f)
        weights[0] = 1f
        timestamps.fill(0f)
        startWeights = weights.copyOf()
        target = 0
        changedAt = 0f
    }

    fun advance(status: VoiceSessionStatus, timeSeconds: Float) {
        val elapsed = ((timeSeconds - changedAt) / 0.65f).coerceIn(0f, 1f)
        val progress = ((atan(4.0 * sin((elapsed - 0.5) * Math.PI)) / atan(4.0)) * 0.5 + 0.5).toFloat()
        for (i in weights.indices) {
            val end = if (i == target) 1f else 0f
            weights[i] = startWeights[i] + (end - startWeights[i]) * progress
        }
        val next = when (status) {
            VoiceSessionStatus.LISTENING, VoiceSessionStatus.USER_SPEAKING,
            VoiceSessionStatus.CONNECTING, VoiceSessionStatus.RECONNECTING -> 0
            VoiceSessionStatus.THINKING -> 1
            VoiceSessionStatus.AI_SPEAKING -> 2
            VoiceSessionStatus.MUTED -> 3
            VoiceSessionStatus.INACTIVE -> -1
        }
        if (next != target) {
            startWeights = weights.copyOf()
            target = next
            changedAt = timeSeconds
            if (next >= 0) timestamps[next] = timeSeconds
        }
    }
}
