package com.google.antigravity

import com.google.antigravity.voice.MicCheckResult
import com.google.antigravity.voice.VoiceDiagnostics
import org.junit.Assert.*
import org.junit.Test

class VoiceDiagnosticsTest {
    @Test fun wsReconnectDelayGrowsExponentiallyAndCapsAt30s() {
        assertEquals(1000L, VoiceDiagnostics.wsReconnectDelayMs(0))
        assertEquals(2000L, VoiceDiagnostics.wsReconnectDelayMs(1))
        assertEquals(4000L, VoiceDiagnostics.wsReconnectDelayMs(2))
        assertEquals(8000L, VoiceDiagnostics.wsReconnectDelayMs(3))
        assertEquals(16000L, VoiceDiagnostics.wsReconnectDelayMs(4))
        assertEquals(30_000L, VoiceDiagnostics.wsReconnectDelayMs(5))
        assertEquals(30_000L, VoiceDiagnostics.wsReconnectDelayMs(50))
        assertEquals(1000L, VoiceDiagnostics.wsReconnectDelayMs(-3))
    }

    @Test fun fatalSpeechErrorsStopDictationRetryLoop() {
        // 9 = ERROR_INSUFFICIENT_PERMISSIONS, 5 = ERROR_CLIENT,
        // 12/13 = язык не поддерживается — повтор бессмыслен.
        assertTrue(VoiceDiagnostics.isFatalSpeechRecognizerError(5))
        assertTrue(VoiceDiagnostics.isFatalSpeechRecognizerError(9))
        assertTrue(VoiceDiagnostics.isFatalSpeechRecognizerError(12))
        assertTrue(VoiceDiagnostics.isFatalSpeechRecognizerError(13))
        // Временные ошибки (сеть, занятость, тишина) продолжают повтор.
        assertFalse(VoiceDiagnostics.isFatalSpeechRecognizerError(2))
        assertFalse(VoiceDiagnostics.isFatalSpeechRecognizerError(3))
        assertFalse(VoiceDiagnostics.isFatalSpeechRecognizerError(6))
        assertFalse(VoiceDiagnostics.isFatalSpeechRecognizerError(7))
        assertFalse(VoiceDiagnostics.isFatalSpeechRecognizerError(8))
    }

    @Test fun pcmRmsLevelIsNormalizedAndClamped() {
        assertEquals(0.03f, VoiceDiagnostics.normalizedPcmRmsLevel(0.0), 1e-6f)
        assertEquals(0.45f, VoiceDiagnostics.normalizedPcmRmsLevel(0.1), 1e-6f)
        assertEquals(1.0f, VoiceDiagnostics.normalizedPcmRmsLevel(1.0), 1e-6f)
    }

    @Test fun micCheckMessageDistinguishesHardwareAndSilence() {
        assertTrue(VoiceDiagnostics.micCheckMessage(false, 0f, 0).contains("недоступен"))
        assertTrue(VoiceDiagnostics.micCheckMessage(true, 0f, 0).contains("не читаются"))
        assertTrue(VoiceDiagnostics.micCheckMessage(true, 0.001f, 500).contains("сигнал не обнаружен"))
        val okMessage = VoiceDiagnostics.micCheckMessage(true, 0.25f, 500)
        assertTrue(okMessage.contains("работает"))
        assertTrue(okMessage.contains("25%"))
    }

    @Test fun micCheckResultDefaultsAreSafe() {
        val result = MicCheckResult(ok = false, hardwareOk = false, peakLevel = 0f,
            averageLevel = 0f, samplesRead = 0, message = "")
        assertFalse(result.ok)
        assertFalse(result.hardwareOk)
    }
}
