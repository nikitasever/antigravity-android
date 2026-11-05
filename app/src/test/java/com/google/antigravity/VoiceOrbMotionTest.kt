package com.google.antigravity

import com.google.antigravity.model.VoiceSessionStatus.*
import com.google.antigravity.voice.VoiceOrbMotion
import org.junit.Assert.*
import org.junit.Test

class VoiceOrbMotionTest {
    @Test fun elapsedTimeControlsTransitionRegardlessOfFrameRate() {
        fun sample(fps: Int): FloatArray {
            val m = VoiceOrbMotion()
            m.advance(THINKING, 0f)
            for (i in 1..fps) m.advance(THINKING, i * 0.325f / fps)
            return m.weights
        }
        assertArrayEquals(sample(15), sample(120), 0.00001f)
        assertEquals(0.5f, sample(30)[1], 0.00001f)
    }

    @Test fun bargeInRetargetsFromCurrentShapeWithoutJump() {
        val m = VoiceOrbMotion()
        m.advance(AI_SPEAKING, 0f)
        m.advance(AI_SPEAKING, 0.3f)
        val before = m.weights.copyOf()
        m.advance(USER_SPEAKING, 0.3f)
        assertArrayEquals(before, m.weights, 0f)
        m.advance(LISTENING, 1f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), m.weights, 0.00001f)
        assertEquals(0.3f, m.timestamps[0], 0f)
    }

    @Test fun contextRestartResetsOldTimelineAndEquivalentListenStatesKeepTimestamp() {
        val m = VoiceOrbMotion()
        m.advance(MUTED, 80f)
        m.advance(MUTED, 81f)
        m.reset()
        m.advance(LISTENING, 0f)
        m.advance(USER_SPEAKING, 0.1f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), m.weights, 0f)
        assertEquals(0f, m.timestamps[3], 0f)
        assertEquals(0f, m.timestamps[0], 0f)
    }
}
