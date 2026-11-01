package com.google.antigravity.voice

/**
 * Результат реальной проверки микрофона (запись с уровнями сигнала).
 * `hardwareOk` — запись инициализирована и прочитаны сэмплы; `ok` дополнительно
 * требует ненулевой уровень сигнала.
 */
data class MicCheckResult(
    val ok: Boolean,
    val hardwareOk: Boolean,
    val peakLevel: Float,
    val averageLevel: Float,
    val samplesRead: Int,
    val message: String
)

/**
 * Чистые диагностические функции голосового режима (без Android-зависимостей):
 * паузы переподключения, классификация ошибок распознавания, нормировка уровня.
 */
object VoiceDiagnostics {
    /**
     * Пауза перед попыткой переподключения живого WebSocket: экспоненциально
     * 1с, 2с, 4с… с потолком 30с. Отрицательный номер попытки трактуется как 0.
     */
    fun wsReconnectDelayMs(attempt: Int): Long {
        val n = attempt.coerceIn(0, 5)
        return (1000L shl n).coerceAtMost(30_000L)
    }

    /** Максимум автоматических попыток переподключения подряд до паузы 30с. */
    const val WS_MAX_RECONNECT_ATTEMPTS = 5

    /** Коды SpeechRecognizer, после которых бесконечный повтор диктовки бессмыслен. */
    // 5 = ERROR_CLIENT, 9 = ERROR_INSUFFICIENT_PERMISSIONS,
    // 12 = ERROR_LANGUAGE_NOT_SUPPORTED, 13 = ERROR_LANGUAGE_UNAVAILABLE
    private val FATAL_SPEECH_ERRORS = setOf(5, 9, 12, 13)

    fun isFatalSpeechRecognizerError(error: Int): Boolean = error in FATAL_SPEECH_ERRORS

    /** Нормировка RMS PCM16-буфера к 0..1 (как в цикле захвата живого режима). */
    fun normalizedPcmRmsLevel(rms: Double): Float =
        (rms * 4.5).toFloat().coerceIn(0.03f, 1.0f)

    /** Порог «сигнала» для проверки микрофона: ниже — тишина или заглушен вход. */
    const val MIC_CHECK_SILENCE_THRESHOLD = 0.004f

    fun micCheckMessage(hardwareOk: Boolean, peakLevel: Float, samplesRead: Int): String = when {
        !hardwareOk -> "Микрофон недоступен: запись не инициализирована"
        samplesRead <= 0 -> "Микрофон недоступен: данные не читаются"
        peakLevel < MIC_CHECK_SILENCE_THRESHOLD ->
            "Микрофон записывает, но сигнал не обнаружен — проверьте, не заглушен ли вход"
        else -> "Микрофон работает: пиковый уровень ${(peakLevel * 100).toInt()}%"
    }
}
