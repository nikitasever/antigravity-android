package com.google.antigravity.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Base64
import androidx.core.content.ContextCompat
import com.google.antigravity.R
import com.google.antigravity.model.VoiceProvider
import com.google.antigravity.model.VoiceSessionStatus
import com.google.antigravity.model.VoiceTransportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Полнодуплексный голосовой движок реального времени (Antigravity Realtime Voice Engine 3.0).
 *
 * Поддерживает 4 режима живого нейросетевого голоса:
 * 1. `GEMINI_LIVE`: Прямой двусторонний аудио-поток по WebSocket
 *    `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`
 *    с моделями `gemini-2.5-flash-native-audio-preview-12-2025` / `gemini-2.0-flash-live-001`,
 *    PCM16 16 кГц (вход) / 24 кГц (выход), перебиванием голосом (barge-in), выбором голоса
 *    (`Aoede`, `Kore`, `Puck`, `Charon`, `Fenrir`) и вызовом функций (`tools`) телефона и ПК.
 * 2. `GEMINI_TTS`: Облачный синтез `gemini-2.5-flash-preview-tts` (`prebuiltVoiceConfig.voiceName`)
 *    для кнопки «Озвучить» и голосовых ответов при наличии API-ключа.
 * 3. `NEURAL_BRIDGE` (по умолчанию без ключа): Живой русский нейроголос Microsoft Edge Neural
 *    (`ru-RU-SvetlanaNeural` / `ru-RU-DmitryNeural`) через `/api/tts` моста ПК с разбивкой по предложениям
 *    и предзагрузкой следующего фрагмента (первый байт аудио < 1 с).
 * 4. `ON_DEVICE`: Локальный `TextToSpeech` с приоритетным выбором нейросетевого голоса Google `*-network`.
 */
class RealtimeVoiceEngine(
    private val context: Context,
    private val onUserUtteranceFinalized: (String) -> Unit,
    private val onAgentVoiceCommandDetected: (VoiceAgentCommand) -> Boolean,
    private val onLiveToolCallRequested: (suspend (toolName: String, argsJson: JSONObject) -> String)? = null
) {
    sealed class VoiceAgentCommand {
        data object ApprovePendingTool : VoiceAgentCommand()
        data object DenyPendingTool : VoiceAgentCommand()
        data class TriggerSlashCommand(val slash: String, val restPrompt: String) : VoiceAgentCommand()
        data class SwitchAuxTab(val tabIndex: Int) : VoiceAgentCommand()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(VoiceSessionStatus.INACTIVE)
    val status: StateFlow<VoiceSessionStatus> = _status.asStateFlow()

    private val _isCallActive = MutableStateFlow(false)
    val isCallActive: StateFlow<Boolean> = _isCallActive.asStateFlow()

    private val _isOverlayExpanded = MutableStateFlow(false)
    val isOverlayExpanded: StateFlow<Boolean> = _isOverlayExpanded.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerphone = MutableStateFlow(true)
    val isSpeakerphone: StateFlow<Boolean> = _isSpeakerphone.asStateFlow()

    private val _isDictatingInline = MutableStateFlow(false)
    val isDictatingInline: StateFlow<Boolean> = _isDictatingInline.asStateFlow()

    private val _isSpeakingMessageAloud = MutableStateFlow(false)
    val isSpeakingMessageAloud: StateFlow<Boolean> = _isSpeakingMessageAloud.asStateFlow()

    private val _livePartialTranscript = MutableStateFlow("")
    val livePartialTranscript: StateFlow<String> = _livePartialTranscript.asStateFlow()

    private val _liveAiSubtitle = MutableStateFlow("")
    val liveAiSubtitle: StateFlow<String> = _liveAiSubtitle.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _spectrumBands = MutableStateFlow(FloatArray(16) { 0.08f })
    val spectrumBands: StateFlow<FloatArray> = _spectrumBands.asStateFlow()

    private val _bargeInCount = MutableStateFlow(0)
    val bargeInCount: StateFlow<Int> = _bargeInCount.asStateFlow()

    private val _micCheckState = MutableStateFlow<MicCheckResult?>(null)
    val micCheckState: StateFlow<MicCheckResult?> = _micCheckState.asStateFlow()

    /** Пользовательски видимая ошибка голосового режима (микрофон, диктовка, связь). */
    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var activeSpeechMediaPlayer: MediaPlayer? = null
    private var neuralSpeechJob: Job? = null
    private var streamingTtsConsumerJob: Job? = null
    private val streamingSentenceBuffer = StringBuilder()
    private val streamingSentenceQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile
    private var isStreamingTurnDone = false
    @Volatile
    private var streamedAtLeastOneSentenceInTurn = false

    var onBargeInInterruptCallback: (() -> Unit)? = null
    @Volatile
    var forcePublicTunnelOnly: Boolean = false

    private var isTtsReady = false
    private var isRuTtsSupported = false

    @Volatile
    private var isAiSpeakingOrThinking = false

    private var inlineDictationCallback: ((String) -> Unit)? = null
    @Volatile
    private var committedDictationText: String = ""
    @Volatile
    private var currentPartialDictation: String = ""
    @Volatile
    private var pendingVoiceUtteranceBuffer: String = ""
    private var voiceUtteranceDebounceJob: Job? = null

    private var transportMode: VoiceTransportMode = VoiceTransportMode.FULL_DUPLEX_NATIVE
    private var voiceProvider: VoiceProvider = VoiceProvider.NEURAL_BRIDGE
    private var voiceName: String = "Aoede"
    private var geminiApiKey: String = ""
    private var pcBridgeUrl: String = ""
    private var speechRate: Float = 1.05f
    private var bargeInEnabled: Boolean = true
    private var handsFreeContinuous: Boolean = true
    private var voiceToAgentEnabled: Boolean = true

    private var liveWebSocket: WebSocket? = null
    private var pcmRecordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    // Восстановление живого WebSocket: счётчик попыток и отложенное переподключение.
    private var wsReconnectAttempts = 0
    private var wsReconnectJob: Job? = null
    private var wsModelName: String = ""
    private var wsFallbackUsed = false

    /** Поколение сессии: события устаревших сокетов не запускают переподключение. */
    private var wsSessionGeneration = 0

    private var visualizerTickerJob: Job? = null
    private var callTimerJob: Job? = null
    private var phaseAngle = 0f

    private val speechAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    init {
        initTextToSpeech()
        startVisualizerTicker()
    }

    private fun initTextToSpeech() {
        mainHandler.postDelayed({
            try {
                textToSpeech = TextToSpeech(context.applicationContext, { initStatus ->
                    if (initStatus == TextToSpeech.SUCCESS) {
                        isTtsReady = true
                        val tts = textToSpeech ?: return@TextToSpeech
                        tts.setAudioAttributes(speechAudioAttributes)
                        val ruLocale = Locale("ru", "RU")
                        val result = tts.setLanguage(ruLocale)
                        isRuTtsSupported =
                            result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                        if (!isRuTtsSupported) {
                            tts.setLanguage(Locale.getDefault())
                        }
                        selectBestRussianNetworkVoice(tts)
                        tts.setSpeechRate(speechRate)
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                isAiSpeakingOrThinking = true
                                _isSpeakingMessageAloud.value = true
                                if (_isCallActive.value && !_isMuted.value) {
                                    _status.value = VoiceSessionStatus.AI_SPEAKING
                                }
                            }

                            override fun onDone(utteranceId: String?) {
                                if (utteranceId?.endsWith("_last") == true) {
                                    onAiSpeechFinished()
                                }
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                if (utteranceId?.endsWith("_last") == true) {
                                    onAiSpeechFinished()
                                }
                            }
                        })
                    }
                }, "com.google.android.tts")
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }, 600L)
    }

    /**
     * Выбирает лучший доступный русский нейросетевой голос Google (`*-network`) в системном TTS.
     */
    private fun selectBestRussianNetworkVoice(tts: TextToSpeech) {
        try {
            val allVoices = tts.voices ?: return
            val ruVoices = allVoices.filter { v ->
                v.locale?.language?.equals("ru", ignoreCase = true) == true
            }
            if (ruVoices.isEmpty()) return

            val wantsMale = voiceName.equals("Puck", ignoreCase = true) ||
                voiceName.equals("Charon", ignoreCase = true) ||
                voiceName.equals("Fenrir", ignoreCase = true)

            val preferredVoice: Voice? = ruVoices
                .sortedWith(
                    compareByDescending<Voice> { it.name.contains("network", ignoreCase = true) }
                        .thenByDescending { it.quality }
                        .thenBy { it.latency }
                        .thenByDescending { v ->
                            if (wantsMale) {
                                v.name.contains("rud", ignoreCase = true) || v.name.contains("ruf", ignoreCase = true)
                            } else {
                                v.name.contains("ruc", ignoreCase = true) || v.name.contains("rue", ignoreCase = true)
                            }
                        }
                )
                .firstOrNull()

            if (preferredVoice != null) {
                tts.voice = preferredVoice
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
    }

    private fun onAiSpeechFinished() {
        isAiSpeakingOrThinking = false
        _isSpeakingMessageAloud.value = false
        abandonSpeechAudioFocus()
        if (_isCallActive.value) {
            if (_isMuted.value) {
                _status.value = VoiceSessionStatus.MUTED
            } else {
                _status.value = VoiceSessionStatus.LISTENING
                if (handsFreeContinuous && !isUsingGeminiLiveWebSocket()) {
                    scheduleStartListening(180L)
                }
            }
        } else {
            _status.value = VoiceSessionStatus.INACTIVE
        }
    }

    private fun isUsingGeminiLiveWebSocket(): Boolean {
        return (voiceProvider == VoiceProvider.GEMINI_LIVE ||
            transportMode == VoiceTransportMode.GEMINI_LIVE_WEBSOCKET) &&
            geminiApiKey.isNotBlank() &&
            liveWebSocket != null
    }

    fun updateConfiguration(
        mode: VoiceTransportMode,
        apiKey: String,
        rate: Float,
        bargeIn: Boolean,
        handsFree: Boolean,
        voiceToAgent: Boolean,
        bridgeUrl: String = "",
        provider: VoiceProvider = VoiceProvider.NEURAL_BRIDGE,
        selectedVoiceName: String = "Aoede"
    ) {
        transportMode = mode
        voiceProvider = provider
        voiceName = selectedVoiceName.ifBlank { "Aoede" }
        geminiApiKey = apiKey.trim()
        if (bridgeUrl.isNotBlank()) {
            pcBridgeUrl = bridgeUrl.trim().trimEnd('/')
        }
        speechRate = rate
        bargeInEnabled = bargeIn
        handsFreeContinuous = handsFree
        voiceToAgentEnabled = voiceToAgent
        textToSpeech?.let {
            it.setSpeechRate(speechRate)
            selectBestRussianNetworkVoice(it)
        }
    }

    /**
     * Реальная проверка ключа Gemini API (вызывается по кнопке «Проверить ключ» в Настройках → Голос).
     */
    suspend fun verifyGeminiApiKey(candidateKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanKey = candidateKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext false to "Введите ключ Gemini API из Google AI Studio"
        }
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$cleanKey&pageSize=20"
            val req = Request.Builder().url(url).get().build()
            okHttpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errMsg = try {
                        JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                    } catch (failure: Throwable) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure;
                        ""
                    }
                    return@withContext false to "Ключ не прошёл проверку (HTTP ${resp.code})${if (errMsg.isNotBlank()) ": $errMsg" else ""}"
                }
                val modelsArr = JSONObject(raw).optJSONArray("models")
                val count = modelsArr?.length() ?: 0
                return@withContext true to "Ключ активен • Доступны Gemini Live и Gemini 2.5 TTS (моделей: $count)"
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e;
            return@withContext false to "Ошибка сети при проверке ключа: ${e.localizedMessage ?: "неизвестная ошибка"}"
        }
    }

    /**
     * Открывает официальное приложение Google Gemini (`com.google.android.apps.bard`) в режиме голосового ассистента
     * или страницу Gemini Live, если приложение не установлено.
     */
    fun openOfficialGeminiLiveApp(initialPrompt: String = ""): Boolean {
        return try {
            val pm = context.packageManager
            val bardIntent = pm.getLaunchIntentForPackage("com.google.android.apps.bard")
            if (bardIntent != null) {
                bardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (initialPrompt.isNotBlank()) {
                    bardIntent.putExtra(Intent.EXTRA_TEXT, initialPrompt)
                }
                context.startActivity(bardIntent)
                true
            } else {
                val assistIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                    setPackage("com.google.android.googlequicksearchbox")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (assistIntent.resolveActivity(pm) != null) {
                    context.startActivity(assistIntent)
                    true
                } else {
                    val webIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://gemini.google.com/app")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webIntent)
                    false
                }
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            false
        }
    }

    /**
     * Запускает непрерывную накопительную голосовую диктовку текста в поле ввода чата (кнопка `🎤`).
     * Запись НЕ выключается сама на паузах или вдохах — сегменты накапливаются в `committedDictationText`
     * до явного нажатия пользователем кнопки «Стоп / Готово» или «Отправить».
     */
    fun startInlineDictation(initialText: String = "", onDictatedText: (String) -> Unit) {
        if (_isDictatingInline.value) {
            stopInlineDictation()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _voiceError.value = "Нет разрешения на запись с микрофона — диктовка недоступна"
            return
        }
        _voiceError.value = null
        stopActiveAiSpeechPlayback()
        committedDictationText = initialText.trim()
        currentPartialDictation = ""
        inlineDictationCallback = onDictatedText
        _isDictatingInline.value = true
        playVoiceSoundCue(R.raw.dictation_start)
        mainHandler.postDelayed({
            if (_isDictatingInline.value) {
                startAndroidSpeechRecognizer(isInlineDictation = true)
            }
        }, 100L)
    }

    fun stopInlineDictation() {
        if (!_isDictatingInline.value) return
        _isDictatingInline.value = false
        currentPartialDictation = ""
        playVoiceSoundCue(R.raw.dictation_end)
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
    }

    fun clearVoiceError() {
        _voiceError.value = null
    }

    fun clearMicrophoneCheck() {
        _micCheckState.value = null
    }

    /**
     * Полноценная проверка микрофона: реальная запись ~1.2 секунды с измерением
     * пикового и среднего уровней сигнала. Запускается из настроек голоса.
     */
    fun runMicrophoneCheck() {
        scope.launch(Dispatchers.IO) {
            _micCheckState.value = MicCheckResult(
                ok = false, hardwareOk = false, peakLevel = 0f, averageLevel = 0f,
                samplesRead = 0, message = "Проверка микрофона…"
            )
            _micCheckState.value = performMicrophoneCheck()
        }
    }

    @SuppressLint("MissingPermission")
    private fun performMicrophoneCheck(): MicCheckResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return MicCheckResult(false, false, 0f, 0f, 0, "Нет разрешения на запись с микрофона")
        }
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            null
        } ?: return MicCheckResult(false, false, 0f, 0f, 0,
            VoiceDiagnostics.micCheckMessage(false, 0f, 0))
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return MicCheckResult(false, false, 0f, 0f, 0,
                    VoiceDiagnostics.micCheckMessage(false, 0f, 0))
            }
            record.startRecording()
            val buffer = ShortArray(1600)
            var samplesRead = 0
            var peak = 0.0
            var sumSq = 0.0
            val deadline = System.currentTimeMillis() + 1200L
            while (System.currentTimeMillis() < deadline) {
                val count = record.read(buffer, 0, buffer.size)
                if (count <= 0) break
                for (i in 0 until count) {
                    val sample = buffer[i].toInt()
                    val abs = kotlin.math.abs(sample)
                    if (abs > peak) peak = abs.toDouble()
                    sumSq += (sample * sample).toDouble()
                }
                samplesRead += count
            }
            val peakLevel = (peak / 32768.0).toFloat().coerceIn(0f, 1f)
            val averageLevel = if (samplesRead > 0) {
                kotlin.math.sqrt(sumSq / samplesRead).toFloat() / 32768f
            } else 0f
            val hardwareOk = samplesRead > 0
            val ok = hardwareOk && peakLevel >= VoiceDiagnostics.MIC_CHECK_SILENCE_THRESHOLD
            return MicCheckResult(
                ok = ok, hardwareOk = hardwareOk, peakLevel = peakLevel,
                averageLevel = averageLevel.coerceIn(0f, 1f), samplesRead = samplesRead,
                message = VoiceDiagnostics.micCheckMessage(hardwareOk, peakLevel, samplesRead)
            )
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            return MicCheckResult(false, false, 0f, 0f, 0, "Ошибка проверки микрофона: ${failure.message}")
        } finally {
            try {
                record.stop()
                record.release()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
    }

    /**
     * Принудительно отправляет накопленную голосовую фразу в живом режиме (`OrbitVoiceOverlay`) без ожидания таймера паузы.
     */
    fun flushPendingVoiceUtteranceNow() {
        voiceUtteranceDebounceJob?.cancel()
        voiceUtteranceDebounceJob = null
        val fullText = pendingVoiceUtteranceBuffer.trim()
        pendingVoiceUtteranceBuffer = ""
        if (fullText.isNotEmpty() && _isCallActive.value && !isAiSpeakingOrThinking) {
            _livePartialTranscript.value = fullText
            handleRecognizedUtterance(fullText)
        }
    }

    /**
     * Запускает полнодуплексную голосовую сессию в реальном времени.
     */
    fun startRealtimeSession(expandOverlay: Boolean = true) {
        if (_isDictatingInline.value) {
            _isDictatingInline.value = false
        }
        if (_isCallActive.value) {
            if (expandOverlay) _isOverlayExpanded.value = true
            return
        }
        isAiSpeakingOrThinking = false
        _isCallActive.value = true
        _isOverlayExpanded.value = expandOverlay
        _isMuted.value = false
        _callDurationSeconds.value = 0L
        _livePartialTranscript.value = ""
        _liveAiSubtitle.value = ""
        _status.value = VoiceSessionStatus.LISTENING
        wsReconnectAttempts = 0
        wsFallbackUsed = false

        configureAudioRouting(speakerphone = _isSpeakerphone.value)
        playVoiceSoundCue(R.raw.listening_start_v2)
        startCallTimer()

        val shouldUseGeminiLive = (voiceProvider == VoiceProvider.GEMINI_LIVE ||
            transportMode == VoiceTransportMode.GEMINI_LIVE_WEBSOCKET) && geminiApiKey.isNotBlank()

        if (shouldUseGeminiLive) {
            startGeminiLiveWebSocketSession(
                apiKey = geminiApiKey,
                modelName = "models/gemini-2.5-flash-native-audio-preview-12-2025",
                allowFallbackModel = true
            )
        } else {
            _status.value = VoiceSessionStatus.LISTENING
            scheduleStartListening(200L)
        }
    }

    /**
     * Останавливает полнодуплексную голосовую сессию.
     */
    fun stopRealtimeSession() {
        if (_isCallActive.value) {
            playVoiceSoundCue(R.raw.hangup_v2)
        }
        isAiSpeakingOrThinking = false
        _isCallActive.value = false
        _isOverlayExpanded.value = false
        _isDictatingInline.value = false
        _isSpeakingMessageAloud.value = false
        _status.value = VoiceSessionStatus.INACTIVE
        _livePartialTranscript.value = ""
        _liveAiSubtitle.value = ""
        _audioLevel.value = 0f

        callTimerJob?.cancel()
        callTimerJob = null

        stopGeminiLiveWebSocketSession()
        stopActiveAiSpeechPlayback()

        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
            speechRecognizer = null
        }
    }

    fun setOverlayExpanded(expanded: Boolean) {
        _isOverlayExpanded.value = expanded
    }

    fun toggleMute() {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        if (newMuted) {
            playVoiceSoundCue(R.raw.button_up_off_v2)
            _status.value = VoiceSessionStatus.MUTED
            mainHandler.post {
                try {
                    speechRecognizer?.cancel()
                } catch (failure: Throwable) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure;
                }
            }
        } else if (_isCallActive.value) {
            playVoiceSoundCue(R.raw.button_up_on_v2)
            isAiSpeakingOrThinking = false
            _status.value = VoiceSessionStatus.LISTENING
            if (!isUsingGeminiLiveWebSocket()) {
                scheduleStartListening(150L)
            }
        }
    }

    fun toggleSpeakerphone() {
        val next = !_isSpeakerphone.value
        _isSpeakerphone.value = next
        playVoiceSoundCue(R.raw.button_down_v2)
        configureAudioRouting(next)
    }

    private fun playVoiceSoundCue(resId: Int) {
        mainHandler.post {
            try {
                val mp = MediaPlayer.create(context.applicationContext, resId) ?: return@post
                mp.setOnCompletionListener { player ->
                    try {
                        player.release()
                    } catch (failure: Throwable) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure;
                    }
                }
                mp.start()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
    }

    /**
     * Принудительный Barge-In (перебивание ИИ голосом или касанием сферы).
     */
    fun interruptAiAndListenNow() {
        performBargeInInterrupt()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
        if (_isCallActive.value && !_isMuted.value) {
            _status.value = VoiceSessionStatus.LISTENING
            _liveAiSubtitle.value = ""
            if (!isUsingGeminiLiveWebSocket()) {
                scheduleStartListening(100L)
            }
        }
    }

    /**
     * Общая часть прерывания: останавливает озвучку, отменяет текущую генерацию
     * ответа (через `onBargeInInterruptCallback`) и сбрасывает счётчик barge-in.
     * Вызывается из всех путей перебивания: распознаватель речи, уровень PCM,
     * касание сферы и прямой вызов.
     */
    private fun performBargeInInterrupt() {
        val wasSpeakingOrThinking = _status.value == VoiceSessionStatus.AI_SPEAKING ||
            _status.value == VoiceSessionStatus.THINKING ||
            _isSpeakingMessageAloud.value ||
            (textToSpeech?.isSpeaking == true)
        stopActiveAiSpeechPlayback()
        try {
            onBargeInInterruptCallback?.invoke()
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
        if (wasSpeakingOrThinking) {
            _bargeInCount.value += 1
        }
        isAiSpeakingOrThinking = false
    }

    private fun stopActiveAiSpeechPlayback() {
        neuralSpeechJob?.cancel()
        neuralSpeechJob = null
        streamingTtsConsumerJob?.cancel()
        streamingTtsConsumerJob = null
        synchronized(streamingSentenceBuffer) {
            streamingSentenceBuffer.setLength(0)
        }
        streamingSentenceQueue.clear()
        isStreamingTurnDone = true
        mainHandler.post {
            try {
                activeSpeechMediaPlayer?.stop()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
            try {
                activeSpeechMediaPlayer?.release()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
            activeSpeechMediaPlayer = null
            try {
                textToSpeech?.stop()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
        _isSpeakingMessageAloud.value = false
    }

    /**
     * Подготавливает потоковую очередь предложений перед началом генерации ответа ИИ в голосовом режиме.
     */
    fun startStreamingVoiceTurn() {
        if (!_isCallActive.value || isUsingGeminiLiveWebSocket()) return
        stopActiveAiSpeechPlayback()
        synchronized(streamingSentenceBuffer) {
            streamingSentenceBuffer.setLength(0)
        }
        streamingSentenceQueue.clear()
        isStreamingTurnDone = false
        streamedAtLeastOneSentenceInTurn = false
        isAiSpeakingOrThinking = true
        _status.value = VoiceSessionStatus.THINKING
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
    }

    /**
     * Получает очередной фрагмент текста `text_delta` из SSE-потока `/api/chat/stream`,
     * режет его по границам предложений (`.`, `!`, `?`, `;`, `\n`) и немедленно ставит в очередь синтеза речи `/api/tts`,
     * не дожидаясь окончания всего ответа модели.
     */
    fun onStreamingTextDelta(delta: String, fullText: String, isFinal: Boolean) {
        if (!_isCallActive.value || isUsingGeminiLiveWebSocket()) return

        if (fullText.isNotBlank()) {
            _liveAiSubtitle.value = sanitizeMarkdownForSpeech(fullText).take(320)
        }

        val readySentences = mutableListOf<String>()
        synchronized(streamingSentenceBuffer) {
            if (delta.isNotEmpty()) {
                streamingSentenceBuffer.append(delta)
            }
            while (true) {
                val bufStr = streamingSentenceBuffer.toString()
                val match = Regex("[.!?;\n]+\\s+").find(bufStr)
                if (match != null && match.range.last >= 12) {
                    val splitEnd = match.range.last + 1
                    val rawSentence = bufStr.substring(0, splitEnd).trim()
                    streamingSentenceBuffer.delete(0, splitEnd)
                    val cleanSentence = sanitizeMarkdownForSpeech(rawSentence)
                    if (cleanSentence.length >= 2) {
                        readySentences.add(cleanSentence)
                    }
                } else {
                    break
                }
            }
            if (isFinal) {
                val leftover = sanitizeMarkdownForSpeech(streamingSentenceBuffer.toString())
                streamingSentenceBuffer.setLength(0)
                if (leftover.isNotBlank()) {
                    readySentences.addAll(splitIntoSpeechChunks(leftover, maxChunkLen = 200))
                } else if (!streamedAtLeastOneSentenceInTurn && readySentences.isEmpty() && fullText.isNotBlank()) {
                    val fullClean = sanitizeMarkdownForSpeech(fullText)
                    if (fullClean.isNotBlank()) {
                        readySentences.addAll(splitIntoSpeechChunks(fullClean, maxChunkLen = 200))
                    }
                }
                isStreamingTurnDone = true
            }
        }

        if (readySentences.isNotEmpty()) {
            streamedAtLeastOneSentenceInTurn = true
            readySentences.forEach { streamingSentenceQueue.offer(it) }
            ensureStreamingTtsConsumerRunning()
        } else if (isFinal && streamingSentenceQueue.isEmpty() && streamingTtsConsumerJob?.isActive != true) {
            mainHandler.post { onAiSpeechFinished() }
        }
    }

    private fun ensureStreamingTtsConsumerRunning() {
        if (streamingTtsConsumerJob?.isActive == true) return
        requestSpeechAudioFocus()
        ensureAudibleMediaVolume()

        streamingTtsConsumerJob = scope.launch(Dispatchers.IO) {
            var nextPrefetchSentence: String? = null
            var nextPrefetchDeferred: Deferred<ByteArray?>? = null

            while (isActive && isAiSpeakingOrThinking) {
                val sentence = nextPrefetchSentence ?: streamingSentenceQueue.poll()
                if (sentence == null) {
                    if (isStreamingTurnDone) {
                        break
                    }
                    delay(45L)
                    continue
                }

                _isSpeakingMessageAloud.value = true
                if (_isCallActive.value && !_isMuted.value) {
                    _status.value = VoiceSessionStatus.AI_SPEAKING
                }

                val currentAudioBytes = if (nextPrefetchSentence != null && nextPrefetchDeferred != null) {
                    nextPrefetchSentence = null
                    val def = nextPrefetchDeferred
                    nextPrefetchDeferred = null
                    try {
                        def.await()
                    } catch (failure: Throwable) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure;
                        null
                    }
                } else {
                    fetchBridgeNeuralTtsMp3Bytes(sentence)
                }

                val peekNext = streamingSentenceQueue.poll()
                if (peekNext != null && isActive && isAiSpeakingOrThinking) {
                    nextPrefetchSentence = peekNext
                    nextPrefetchDeferred = async(Dispatchers.IO) { fetchBridgeNeuralTtsMp3Bytes(peekNext) }
                }

                if (currentAudioBytes != null && currentAudioBytes.size > 256) {
                    playMp3BytesBlocking(currentAudioBytes)
                } else if (isActive && isAiSpeakingOrThinking) {
                    speakChunkedOnDeviceTts(listOf(sentence))
                    break
                }
            }

            if (isActive && isAiSpeakingOrThinking) {
                mainHandler.post { onAiSpeechFinished() }
            }
        }
    }

    /**
     * Озвучивает любое сообщение в чате по нажатию на иконку 🔊 («Озвучить» / «Стоп»).
     */
    fun toggleSpeakMessageAloud(markdownText: String) {
        if (_isSpeakingMessageAloud.value) {
            stopActiveAiSpeechPlayback()
            isAiSpeakingOrThinking = false
            return
        }
        speakAgentResponse(markdownText, flushExisting = true, forceSpeakEvenOutsideCall = true)
    }

    fun stopSpeakingMessageAloud() {
        stopActiveAiSpeechPlayback()
        isAiSpeakingOrThinking = false
    }

    /**
     * Озвучивает ответ агента в голосовом режиме или в чате:
     * - Если выбран `GEMINI_TTS` (или есть ключ Gemini API и выбран Gemini-провайдер) → синтезирует речь через
     *   `gemini-2.5-flash-preview-tts` с выбранным `voiceName`.
     * - Без ключа (`NEURAL_BRIDGE`) → потоково загружает и воспроизводит живой русский голос
     *   `ru-RU-SvetlanaNeural` / `ru-RU-DmitryNeural` через `/api/tts` моста ПК с предзагрузкой следующего предложения.
     * - При полном отсутствии сети → переключается на локальный `TextToSpeech` с выбором `*-network` голоса.
     */
    fun speakAgentResponse(
        markdownText: String,
        flushExisting: Boolean = true,
        forceSpeakEvenOutsideCall: Boolean = false
    ) {
        val cleanText = sanitizeMarkdownForSpeech(markdownText)
        if (cleanText.isBlank()) return

        _liveAiSubtitle.value = cleanText.take(320)

        if (!_isCallActive.value && !forceSpeakEvenOutsideCall) return
        if (_isCallActive.value && isUsingGeminiLiveWebSocket() && !forceSpeakEvenOutsideCall) {
            return
        }

        isAiSpeakingOrThinking = true
        _isSpeakingMessageAloud.value = true
        if (_isCallActive.value) {
            _status.value = VoiceSessionStatus.AI_SPEAKING
        }

        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }

        requestSpeechAudioFocus()
        ensureAudibleMediaVolume()

        val chunks = splitIntoSpeechChunks(cleanText, maxChunkLen = 200)
        if (chunks.isEmpty()) {
            onAiSpeechFinished()
            return
        }

        if (flushExisting) {
            stopActiveAiSpeechPlayback()
            isAiSpeakingOrThinking = true
            _isSpeakingMessageAloud.value = true
            if (_isCallActive.value) {
                _status.value = VoiceSessionStatus.AI_SPEAKING
            }
        }

        if (voiceProvider == VoiceProvider.ON_DEVICE) {
            speakChunkedOnDeviceTts(chunks)
            return
        }

        neuralSpeechJob = scope.launch(Dispatchers.IO) {
            var playedAtLeastOneChunk = false

            // 1. Если есть ключ и выбран Gemini TTS или Gemini Live (для кнопки «Озвучить»)
            val tryGeminiCloudTts = geminiApiKey.isNotBlank() &&
                (voiceProvider == VoiceProvider.GEMINI_TTS || voiceProvider == VoiceProvider.GEMINI_LIVE)

            if (tryGeminiCloudTts) {
                for (chunk in chunks) {
                    if (!isActive || !isAiSpeakingOrThinking) break
                    val ok = synthesizeAndPlayGeminiCloudTtsChunk(chunk, geminiApiKey, voiceName)
                    if (ok) {
                        playedAtLeastOneChunk = true
                    } else {
                        break
                    }
                }
            }

            // 2. Потоковый нейроголос Microsoft Edge Neural через мост ПК (работает сразу без ключа)
            if (!playedAtLeastOneChunk && isActive && isAiSpeakingOrThinking) {
                var nextPrefetch: Deferred<ByteArray?>? = if (chunks.isNotEmpty()) {
                    async(Dispatchers.IO) { fetchBridgeNeuralTtsMp3Bytes(chunks[0]) }
                } else {
                    null
                }

                for (i in chunks.indices) {
                    if (!isActive || !isAiSpeakingOrThinking) break
                    val currentBytes = try {
                        nextPrefetch?.await()
                    } catch (failure: Throwable) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure;
                        null
                    }
                    nextPrefetch = if (i + 1 < chunks.size && isActive && isAiSpeakingOrThinking) {
                        val nextText = chunks[i + 1]
                        async(Dispatchers.IO) { fetchBridgeNeuralTtsMp3Bytes(nextText) }
                    } else {
                        null
                    }

                    if (currentBytes != null && currentBytes.size > 256) {
                        val ok = playMp3BytesBlocking(currentBytes)
                        if (ok) {
                            playedAtLeastOneChunk = true
                        } else {
                            break
                        }
                    } else {
                        break
                    }
                }
            }

            // 3. Последний резерв (офлайн) — системный TextToSpeech с *-network голосом
            if (!playedAtLeastOneChunk && isActive && isAiSpeakingOrThinking) {
                speakChunkedOnDeviceTts(chunks)
            } else if (isActive) {
                mainHandler.post { onAiSpeechFinished() }
            }
        }
    }

    /**
     * Озвучивает фрагмент через официальный `gemini-2.5-flash-preview-tts` REST API.
     */
    private suspend fun synthesizeAndPlayGeminiCloudTtsChunk(
        chunkText: String,
        apiKey: String,
        selectedVoice: String
    ): Boolean {
        return try {
            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=$apiKey"
            val payload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", chunkText)
                    }))
                }))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", selectedVoice.ifBlank { "Aoede" })
                            })
                        })
                    })
                })
            }

            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            var pcmBytes: ByteArray? = null
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val bodyStr = resp.body?.string().orEmpty()
                val root = JSONObject(bodyStr)
                val candidates = root.optJSONArray("candidates") ?: return false
                val first = candidates.optJSONObject(0) ?: return false
                val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: return false
                for (i in 0 until parts.length()) {
                    val inlineData = parts.optJSONObject(i)?.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val b64 = inlineData.optString("data", "")
                        if (b64.isNotBlank()) {
                            pcmBytes = Base64.decode(b64, Base64.DEFAULT)
                            break
                        }
                    }
                }
            }

            val validPcm = pcmBytes ?: return false
            playRawPcm24kHzBlocking(validPcm)
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            false
        }
    }

    /**
     * Воспроизводит PCM16 24 кГц буфер от Gemini TTS через `AudioTrack`.
     */
    private suspend fun playRawPcm24kHzBlocking(pcmBytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (!isAiSpeakingOrThinking || pcmBytes.isEmpty()) return@withContext false
        val sampleRate = 24000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(speechAudioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf * 2, pcmBytes.size.coerceAtMost(96000)))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            return@withContext false
        }

        return@withContext try {
            track.play()
            var offset = 0
            val step = 4800
            while (offset < pcmBytes.size && isAiSpeakingOrThinking) {
                val len = min(step, pcmBytes.size - offset)
                val written = track.write(pcmBytes, offset, len)
                if (written <= 0) break
                offset += written
            }
            val durationMs = ((pcmBytes.size / 2L) * 1000L) / sampleRate
            delay(durationMs.coerceIn(100L, 15000L))
            true
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            false
        } finally {
            try {
                track.stop()
                track.release()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
    }

    /**
     * Загружает MP3-аудио от `/api/tts` моста ПК (Microsoft Edge Neural `ru-RU-SvetlanaNeural` / `ru-RU-DmitryNeural`).
     * Автоматически пробует локальный мост эмулятора (`10.0.2.2:8787`, `127.0.0.1:8787`) и туннель `pcBridgeUrl`.
     */
    private fun fetchBridgeNeuralTtsMp3Bytes(chunkText: String): ByteArray? {
        val encoded = try {
            URLEncoder.encode(chunkText, "UTF-8")
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            return null
        }

        val cleanBridge = pcBridgeUrl.trim().trimEnd('/')
        if (cleanBridge.contains("offline", ignoreCase = true) || cleanBridge.contains("invalid", ignoreCase = true)) {
            return null
        }

        val candidateBaseUrls = buildList {
            if (!forcePublicTunnelOnly) {
                add("http://10.0.2.2:8787")
                add("http://127.0.0.1:8787")
            }
            if (cleanBridge.isNotBlank() && (!forcePublicTunnelOnly || (!cleanBridge.contains("10.0.2.2") && !cleanBridge.contains("127.0.0.1")))) {
                add(cleanBridge)
            }
            addAll(com.google.antigravity.network.AntigravityBackendClient.DEFAULT_PUBLIC_BRIDGE_URLS)
        }.distinct()

        for (base in candidateBaseUrls) {
            val url = "$base/api/tts?voice=${URLEncoder.encode(voiceName, "UTF-8")}&rate=$speechRate&text=$encoded"
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "audio/mpeg")
                    .get()
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.size > 256) {
                            return bytes
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
        return null
    }

    private suspend fun playMp3BytesBlocking(validBytes: ByteArray): Boolean {
        val tempFile = try {
            File(context.cacheDir, "agy_neural_${System.currentTimeMillis()}_${validBytes.size}.mp3").apply {
                FileOutputStream(this).use { it.write(validBytes) }
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
            return false
        }

        val completionSignal = kotlinx.coroutines.CompletableDeferred<Boolean>()
        mainHandler.post {
            if (!isAiSpeakingOrThinking) {
                tempFile.delete()
                completionSignal.complete(false)
                return@post
            }
            try {
                val mp = MediaPlayer()
                activeSpeechMediaPlayer = mp
                mp.setAudioAttributes(speechAudioAttributes)
                mp.setDataSource(tempFile.absolutePath)
                mp.setVolume(1.0f, 1.0f)
                mp.setOnCompletionListener { player ->
                    try {
                        player.release()
                    } catch (failure: Throwable) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure;
                    }
                    if (activeSpeechMediaPlayer === player) {
                        activeSpeechMediaPlayer = null
                    }
                    tempFile.delete()
                    completionSignal.complete(true)
                }
                mp.setOnErrorListener { player, _, _ ->
                    try {
                        player.release()
                    } catch (failure: Throwable) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure;
                    }
                    if (activeSpeechMediaPlayer === player) {
                        activeSpeechMediaPlayer = null
                    }
                    tempFile.delete()
                    completionSignal.complete(false)
                    true
                }
                mp.prepare()
                mp.start()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
                tempFile.delete()
                completionSignal.complete(false)
            }
        }

        return try {
            completionSignal.await()
        } finally {
            try {
                tempFile.delete()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }
    }

    private fun speakChunkedOnDeviceTts(chunks: List<String>) {
        mainHandler.post {
            val tts = textToSpeech
            if (!isTtsReady || tts == null) {
                onAiSpeechFinished()
                return@post
            }
            selectBestRussianNetworkVoice(tts)
            tts.setAudioAttributes(speechAudioAttributes)
            tts.setSpeechRate(speechRate)
            if (_isCallActive.value) {
                _status.value = VoiceSessionStatus.AI_SPEAKING
            }

            chunks.forEachIndexed { idx, sentence ->
                val queueMode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val isLast = idx == chunks.lastIndex
                val uttId = if (isLast) "ag_utt_${idx}_last" else "ag_utt_$idx"
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                }
                tts.speak(sentence, queueMode, params, uttId)
            }
        }
    }

    private fun splitIntoSpeechChunks(cleanText: String, maxChunkLen: Int): List<String> {
        val rawSentences = cleanText
            .split(Regex("(?<=[.!?।\\n;])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val out = mutableListOf<String>()
        for (s in rawSentences) {
            if (s.length <= maxChunkLen) {
                out.add(s)
            } else {
                var remaining = s
                while (remaining.length > maxChunkLen) {
                    val splitAt = remaining.lastIndexOf(' ', maxChunkLen).takeIf { it > 40 } ?: maxChunkLen
                    out.add(remaining.substring(0, splitAt).trim())
                    remaining = remaining.substring(splitAt).trim()
                }
                if (remaining.isNotEmpty()) out.add(remaining)
            }
        }
        return out.take(8)
    }

    fun simulateOrInjectVoiceUtterance(utterance: String) {
        if (utterance.isBlank()) return
        if (_status.value == VoiceSessionStatus.AI_SPEAKING || _isSpeakingMessageAloud.value) {
            interruptAiAndListenNow()
        }
        _livePartialTranscript.value = utterance
        handleRecognizedUtterance(utterance)
    }

    private fun scheduleStartListening(delayMs: Long) {
        mainHandler.postDelayed({
            if (_isCallActive.value &&
                !_isMuted.value &&
                !isAiSpeakingOrThinking &&
                _status.value != VoiceSessionStatus.AI_SPEAKING &&
                _status.value != VoiceSessionStatus.THINKING &&
                !isUsingGeminiLiveWebSocket()
            ) {
                startAndroidSpeechRecognizer(isInlineDictation = false)
            }
        }, delayMs)
    }

    private fun scheduleVoiceUtteranceDebounce(delayMs: Long = 2200L) {
        voiceUtteranceDebounceJob?.cancel()
        voiceUtteranceDebounceJob = scope.launch {
            delay(delayMs)
            val fullText = pendingVoiceUtteranceBuffer.trim()
            if (fullText.isNotEmpty() && _isCallActive.value && !_isMuted.value && !isAiSpeakingOrThinking) {
                pendingVoiceUtteranceBuffer = ""
                mainHandler.post {
                    _livePartialTranscript.value = fullText
                    handleRecognizedUtterance(fullText)
                }
            }
        }
    }

    private fun startAndroidSpeechRecognizer(isInlineDictation: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _liveAiSubtitle.value = "Готов к голосовому диалогу"
            return
        }

        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                    setRecognitionListener(createRecognitionListener())
                }
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    5000L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    4000L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    30000L
                )
            }
            speechRecognizer?.startListening(intent)
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (_isCallActive.value && !_isMuted.value && !isAiSpeakingOrThinking) {
                    _status.value = VoiceSessionStatus.LISTENING
                }
            }

            override fun onBeginningOfSpeech() {
                if (_isDictatingInline.value) return
                if (!_isCallActive.value || _isMuted.value) return
                if (bargeInEnabled && (isAiSpeakingOrThinking || _status.value == VoiceSessionStatus.AI_SPEAKING)) {
                    // Речь пользователя прерывает и озвучку, и текущую генерацию ответа.
                    performBargeInInterrupt()
                }
                if (pendingVoiceUtteranceBuffer.isNotBlank()) {
                    scheduleVoiceUtteranceDebounce(2600L)
                }
                _status.value = VoiceSessionStatus.USER_SPEAKING
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (_isMuted.value) {
                    _audioLevel.value = 0f
                    return
                }
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1.0f)
                _audioLevel.value = normalized
                if (!_isDictatingInline.value && rmsdB > 3.5f && pendingVoiceUtteranceBuffer.isNotBlank()) {
                    scheduleVoiceUtteranceDebounce(2200L)
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (_isDictatingInline.value) {
                    return
                }
                if (_isCallActive.value && !_isMuted.value && !isAiSpeakingOrThinking) {
                    _status.value = VoiceSessionStatus.LISTENING
                }
            }

            override fun onError(error: Int) {
                // КРИТИЧНО: В режиме диктовки НЕ выключаем микрофон при паузах/таймаутах, а перезапускаем слушание!
                if (_isDictatingInline.value) {
                    if (VoiceDiagnostics.isFatalSpeechRecognizerError(error)) {
                        // Фатальная ошибка: бесконечный повтор бессмыслен — честно останавливаемся.
                        _isDictatingInline.value = false
                        currentPartialDictation = ""
                        _voiceError.value = when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на запись с микрофона — диктовка остановлена"
                            SpeechRecognizer.ERROR_CLIENT -> "Служба распознавания речи недоступна — диктовка остановлена"
                            else -> "Распознавание речи не поддерживается для выбранного языка"
                        }
                        return
                    }
                    val delayMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 450L else 120L
                    mainHandler.postDelayed({
                        if (_isDictatingInline.value) {
                            startAndroidSpeechRecognizer(isInlineDictation = true)
                        }
                    }, delayMs)
                    return
                }
                if (!_isCallActive.value || _isMuted.value || isAiSpeakingOrThinking) return
                if (pendingVoiceUtteranceBuffer.isNotBlank()) {
                    scheduleVoiceUtteranceDebounce(1400L)
                }
                if (handsFreeContinuous) {
                    val retryDelay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 700L else 300L
                    _status.value = VoiceSessionStatus.LISTENING
                    scheduleStartListening(retryDelay)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val bestText = matches?.firstOrNull()?.trim().orEmpty()

                // 1. Непрерывная накопительная диктовка (Continuous Multi-Segment Dictation):
                if (_isDictatingInline.value) {
                    if (bestText.isNotEmpty()) {
                        committedDictationText = if (committedDictationText.isBlank()) {
                            bestText
                        } else {
                            "$committedDictationText $bestText"
                        }
                        currentPartialDictation = ""
                        inlineDictationCallback?.invoke(committedDictationText)
                    }
                    // Мгновенно (через 60 мс) перезапускаем слушание для следующей фразы, пока пользователь сам не нажал «Стоп / Отправить»!
                    mainHandler.postDelayed({
                        if (_isDictatingInline.value) {
                            startAndroidSpeechRecognizer(isInlineDictation = true)
                        }
                    }, 60L)
                    return
                }

                // 2. Живой голосовой режим (OrbitVoiceOverlay) с умным окном паузы 2200 мс (Utterance Grace Debounce):
                if (bestText.isNotEmpty()) {
                    pendingVoiceUtteranceBuffer = if (pendingVoiceUtteranceBuffer.isBlank()) {
                        bestText
                    } else {
                        "$pendingVoiceUtteranceBuffer $bestText"
                    }
                    _livePartialTranscript.value = pendingVoiceUtteranceBuffer
                    // Перезапускаем фоновое слушание на случай, если пользователь сделал короткий вдох и продолжает говорить
                    scheduleStartListening(80L)
                    scheduleVoiceUtteranceDebounce(2200L)
                } else if (_isCallActive.value && !_isMuted.value && !isAiSpeakingOrThinking && handsFreeContinuous) {
                    if (pendingVoiceUtteranceBuffer.isNotBlank()) {
                        scheduleVoiceUtteranceDebounce(1500L)
                    }
                    scheduleStartListening(240L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = partials?.firstOrNull()?.trim().orEmpty()
                if (partialText.isNotEmpty()) {
                    if (_isDictatingInline.value) {
                        currentPartialDictation = partialText
                        val combined = if (committedDictationText.isBlank()) {
                            partialText
                        } else {
                            "$committedDictationText $partialText"
                        }
                        inlineDictationCallback?.invoke(combined)
                        return
                    }
                    if (!isAiSpeakingOrThinking) {
                        _status.value = VoiceSessionStatus.USER_SPEAKING
                        val combinedLive = if (pendingVoiceUtteranceBuffer.isBlank()) {
                            partialText
                        } else {
                            "$pendingVoiceUtteranceBuffer $partialText"
                        }
                        _livePartialTranscript.value = combinedLive
                        if (pendingVoiceUtteranceBuffer.isNotBlank()) {
                            scheduleVoiceUtteranceDebounce(2200L)
                        }
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun handleRecognizedUtterance(utterance: String) {
        isAiSpeakingOrThinking = true
        _status.value = VoiceSessionStatus.THINKING

        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            }
        }

        if (voiceToAgentEnabled) {
            val lower = utterance.lowercase(Locale.getDefault())
            when {
                lower.contains("одобри") || lower.contains("разреши") || lower.contains("примени патч") -> {
                    if (onAgentVoiceCommandDetected(VoiceAgentCommand.ApprovePendingTool)) {
                        speakAgentResponse("Действие подтверждено голосом. Выполняю операцию.")
                        return
                    }
                }
                lower.contains("отклони") || lower.contains("запрети") || lower.contains("отмени действие") -> {
                    if (onAgentVoiceCommandDetected(VoiceAgentCommand.DenyPendingTool)) {
                        speakAgentResponse("Действие отменено по вашей команде.")
                        return
                    }
                }
                lower.startsWith("составь план") || lower.startsWith("план ") || lower.contains("режим планирования") -> {
                    onAgentVoiceCommandDetected(VoiceAgentCommand.TriggerSlashCommand("/plan", utterance))
                    return
                }
                lower.contains("глубокий анализ") || lower.contains("режим буст") -> {
                    onAgentVoiceCommandDetected(VoiceAgentCommand.TriggerSlashCommand("/boost", utterance))
                    return
                }
                lower.contains("покажи сесси") || lower.contains("открой сесси") -> {
                    onAgentVoiceCommandDetected(VoiceAgentCommand.SwitchAuxTab(0))
                    speakAgentResponse("Открываю список рабочих сессий.")
                    return
                }
            }
        }

        onUserUtteranceFinalized(utterance)
    }

    /**
     * Подключается к официальному Gemini Live API (`v1beta.GenerativeService.BidiGenerateContent`)
     * с поддержкой `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName` и вызовом функций (`tools`) телефона и ПК.
     */
    @SuppressLint("MissingPermission")
    private fun startGeminiLiveWebSocketSession(
        apiKey: String,
        modelName: String,
        allowFallbackModel: Boolean
    ) {
        stopGeminiLiveWebSocketSession()
        val generation = ++wsSessionGeneration
        wsModelName = modelName

        val wsUrl =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        setupPcmAudioPlayout24kHz()

        liveWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsReconnectAttempts = 0
                val setupJson = JSONObject().apply {
                    put("setup", JSONObject().apply {
                        put("model", modelName)
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", JSONArray().put("AUDIO"))
                            put("speechConfig", JSONObject().apply {
                                put("voiceConfig", JSONObject().apply {
                                    put("prebuiltVoiceConfig", JSONObject().apply {
                                        put("voiceName", voiceName.ifBlank { "Aoede" })
                                    })
                                })
                            })
                        })
                        put("systemInstruction", JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().apply {
                                put(
                                    "text",
                                    "Ты голосовой ИИ-ассистент Google Antigravity на Android. " +
                                        "Отвечай естественно, живо и лаконично на русском языке. " +
                                        "При необходимости используй инструменты phone_search_and_action для поиска и работы с файлами на телефоне " +
                                        "и pc_bridge_query для обращения к рабочей сессии на компьютере."
                                )
                            }))
                        })
                        put("tools", JSONArray().put(JSONObject().apply {
                            put("functionDeclarations", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("name", "phone_search_and_action")
                                    put(
                                        "description",
                                        "Поиск файлов, фото, документов, контактов, приложений на телефоне или создание/чтение файла."
                                    )
                                    put("parameters", JSONObject().apply {
                                        put("type", "OBJECT")
                                        put("properties", JSONObject().apply {
                                            put("query", JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Поисковый запрос или команда на русском языке")
                                            })
                                        })
                                        put("required", JSONArray().put("query"))
                                    })
                                })
                                put(JSONObject().apply {
                                    put("name", "pc_bridge_query")
                                    put(
                                        "description",
                                        "Запрос к удалённой сессии Antigravity на компьютере (проверка статуса, чтение проектов, запуск задачи)."
                                    )
                                    put("parameters", JSONObject().apply {
                                        put("type", "OBJECT")
                                        put("properties", JSONObject().apply {
                                            put("prompt", JSONObject().apply {
                                                put("type", "STRING")
                                                put("description", "Текст запроса к сессии на компьютере")
                                            })
                                        })
                                        put("required", JSONArray().put("prompt"))
                                    })
                                })
                            })
                        }))
                    })
                }
                webSocket.send(setupJson.toString())
                _status.value = VoiceSessionStatus.LISTENING
                _liveAiSubtitle.value = "Слушаю вас…"
                startPcm16kHzCaptureLoop(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleGeminiLiveServerMessage(webSocket, text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Сервер закрыл соединение — восстанавливаем его, пока звонок активен.
                handleLiveConnectionLost(generation, apiKey, modelName, allowFallbackModel)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleLiveConnectionLost(generation, apiKey, modelName, allowFallbackModel)
            }
        })
    }

    /**
     * Восстановление живого соединения: одна попытка запасной модели, затем
     * переподключение с экспоненциальной паузой (см. VoiceDiagnostics).
     * События устаревших сокетов игнорируются по номеру поколения.
     */
    private fun handleLiveConnectionLost(
        generation: Int,
        apiKey: String,
        modelName: String,
        allowFallbackModel: Boolean
    ) {
        if (generation != wsSessionGeneration) return
        liveWebSocket = null
        if (!_isCallActive.value) return
        if (allowFallbackModel && !wsFallbackUsed) {
            wsFallbackUsed = true
            startGeminiLiveWebSocketSession(
                apiKey = apiKey,
                modelName = "models/gemini-2.0-flash-live-001",
                allowFallbackModel = false
            )
            return
        }
        scheduleWsReconnect(apiKey, modelName)
    }

    private fun scheduleWsReconnect(apiKey: String, modelName: String) {
        if (!_isCallActive.value) return
        val exhausted = wsReconnectAttempts >= VoiceDiagnostics.WS_MAX_RECONNECT_ATTEMPTS
        _status.value = VoiceSessionStatus.RECONNECTING
        _liveAiSubtitle.value = if (exhausted) {
            "Связь потеряна — повторная попытка…"
        } else {
            "Переподключение… (попытка ${wsReconnectAttempts + 1})"
        }
        val delayMs = if (exhausted) {
            30_000L // после исчерпания быстрых попыток — редкие повторы, пока звонок не завершён
        } else {
            VoiceDiagnostics.wsReconnectDelayMs(wsReconnectAttempts)
        }
        wsReconnectAttempts += 1
        wsReconnectJob?.cancel()
        wsReconnectJob = scope.launch {
            delay(delayMs)
            if (_isCallActive.value && liveWebSocket == null) {
                startGeminiLiveWebSocketSession(apiKey, modelName, allowFallbackModel = false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPcm16kHzCaptureLoop(webSocket: WebSocket) {
        pcmRecordJob?.cancel()
        pcmRecordJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
                null
            } ?: return@launch

            audioRecord = record
            attachHardwareWebRtcApmEffects(record.audioSessionId)

            val shortBuffer = ShortArray(1600)
            try {
                record.startRecording()
                while (isActive && _isCallActive.value) {
                    if (_isMuted.value) {
                        delay(80)
                        continue
                    }
                    val readCount = record.read(shortBuffer, 0, shortBuffer.size)
                    if (readCount > 0) {
                        var sumSq = 0.0
                        val byteBuffer = ByteArray(readCount * 2)
                        for (i in 0 until readCount) {
                            val sample = shortBuffer[i].toInt()
                            sumSq += (sample * sample).toDouble()
                            byteBuffer[i * 2] = (sample and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                        }
                        val rms = sqrt(sumSq / readCount) / 32768.0
                        val normLevel = (rms * 4.5).toFloat().coerceIn(0.03f, 1.0f)
                        _audioLevel.value = normLevel

                        if (bargeInEnabled && normLevel > 0.42f && _status.value == VoiceSessionStatus.AI_SPEAKING) {
                            // Речь пользователя прерывает и озвучку, и текущую генерацию ответа.
                            performBargeInInterrupt()
                            audioTrack?.pause()
                            audioTrack?.flush()
                            audioTrack?.play()
                            _status.value = VoiceSessionStatus.USER_SPEAKING
                        }

                        val b64Pcm = Base64.encodeToString(byteBuffer, Base64.NO_WRAP)
                        val chunkPayload = JSONObject().apply {
                            put("realtimeInput", JSONObject().apply {
                                put("mediaChunks", JSONArray().put(JSONObject().apply {
                                    put("mimeType", "audio/pcm;rate=16000")
                                    put("data", b64Pcm)
                                }))
                            })
                        }
                        webSocket.send(chunkPayload.toString())
                    }
                }
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure;
            } finally {
                try {
                    record.stop()
                    record.release()
                } catch (failure: Throwable) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure;
                }
            }
        }
    }

    private fun setupPcmAudioPlayout24kHz() {
        try {
            val sampleRate = 24000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4800)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(speechAudioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .apply { play() }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
    }

    private fun handleGeminiLiveServerMessage(webSocket: WebSocket, rawJson: String) {
        try {
            val root = JSONObject(rawJson)

            // Обработка вызова инструментов (toolCall) в режиме Gemini Live
            val toolCall = root.optJSONObject("toolCall")
            if (toolCall != null) {
                val functionCalls = toolCall.optJSONArray("functionCalls")
                if (functionCalls != null && functionCalls.length() > 0) {
                    scope.launch(Dispatchers.IO) {
                        val responsesArray = JSONArray()
                        for (i in 0 until functionCalls.length()) {
                            val fc = functionCalls.optJSONObject(i) ?: continue
                            val callId = fc.optString("id", "call_$i")
                            val fnName = fc.optString("name", "")
                            val argsObj = fc.optJSONObject("args") ?: JSONObject()
                            _status.value = VoiceSessionStatus.THINKING
                            val toolResult = try {
                                onLiveToolCallRequested?.invoke(fnName, argsObj)
                                    ?: "Инструмент выполнен успешно"
                            } catch (e: Throwable) {
                                if (e is kotlinx.coroutines.CancellationException) throw e;
                                "Ошибка выполнения: ${e.localizedMessage ?: "неизвестно"}"
                            }
                            responsesArray.put(JSONObject().apply {
                                put("id", callId)
                                put("name", fnName)
                                put("response", JSONObject().apply {
                                    put("result", toolResult)
                                })
                            })
                        }
                        val toolResponsePayload = JSONObject().apply {
                            put("toolResponse", JSONObject().apply {
                                put("functionResponses", responsesArray)
                            })
                        }
                        webSocket.send(toolResponsePayload.toString())
                    }
                }
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return
            if (serverContent.optBoolean("interrupted", false)) {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.play()
                _status.value = VoiceSessionStatus.LISTENING
                return
            }
            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val text = part.optString("text", "")
                    if (text.isNotEmpty()) {
                        _liveAiSubtitle.value = text
                    }
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val b64 = inlineData.optString("data", "")
                        if (b64.isNotEmpty()) {
                            val pcmBytes = Base64.decode(b64, Base64.DEFAULT)
                            _status.value = VoiceSessionStatus.AI_SPEAKING
                            audioTrack?.write(pcmBytes, 0, pcmBytes.size)
                        }
                    }
                }
            }
            if (serverContent.optBoolean("turnComplete", false)) {
                _status.value = VoiceSessionStatus.LISTENING
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
    }

    private fun attachHardwareWebRtcApmEffects(audioSessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
    }

    private fun stopGeminiLiveWebSocketSession() {
        wsSessionGeneration++ // события закрываемого сокета не должны переподключать его снова
        wsReconnectJob?.cancel()
        wsReconnectJob = null
        pcmRecordJob?.cancel()
        pcmRecordJob = null
        try {
            liveWebSocket?.close(1000, "Session closed")
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
        liveWebSocket = null

        try {
            aec?.release()
            ns?.release()
            agc?.release()
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
        aec = null
        ns = null
        agc = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
        audioTrack = null
    }

    private var activeFocusRequest: AudioFocusRequest? = null

    private fun requestSpeechAudioFocus() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.mode = AudioManager.MODE_NORMAL
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(speechAudioAttributes)
                .build()
            activeFocusRequest = req
            am.requestAudioFocus(req)
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
    }

    private fun abandonSpeechAudioFocus() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            activeFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
    }

    private fun ensureAudibleMediaVolume() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (curVol < (maxVol * 0.25f).toInt()) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.55f).toInt(), 0)
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
    }

    private fun configureAudioRouting(speakerphone: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speakerphone
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure;
        }
    }

    private fun startCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = scope.launch {
            while (isActive && _isCallActive.value) {
                delay(1000L)
                _callDurationSeconds.value += 1L
            }
        }
    }

    private fun startVisualizerTicker() {
        visualizerTickerJob?.cancel()
        visualizerTickerJob = scope.launch {
            while (isActive) {
                delay(16L)
                phaseAngle += 0.11f
                val currentStatus = _status.value

                val targetBaseLevel = when {
                    currentStatus == VoiceSessionStatus.AI_SPEAKING || _isSpeakingMessageAloud.value -> {
                        0.48f + 0.36f * abs(sin(phaseAngle * 1.35f)) * cos(phaseAngle * 0.72f)
                    }
                    currentStatus == VoiceSessionStatus.USER_SPEAKING || _isDictatingInline.value -> {
                        max(_audioLevel.value, 0.30f + 0.25f * abs(sin(phaseAngle * 1.9f)))
                    }
                    currentStatus == VoiceSessionStatus.THINKING -> {
                        0.28f + 0.14f * abs(sin(phaseAngle * 2.4f))
                    }
                    currentStatus == VoiceSessionStatus.LISTENING -> {
                        max(_audioLevel.value * 0.6f, 0.10f + 0.05f * abs(sin(phaseAngle * 0.8f)))
                    }
                    else -> 0.04f
                }

                val smoothed = _audioLevel.value * 0.65f + targetBaseLevel * 0.35f
                _audioLevel.value = min(1f, max(0.03f, smoothed))

                val bands = FloatArray(16)
                for (i in 0 until 16) {
                    val harmonic = abs(sin(phaseAngle * (1.0f + i * 0.17f) + i * 0.45f))
                    bands[i] = (_audioLevel.value * (0.45f + 0.55f * harmonic)).coerceIn(0.05f, 1.0f)
                }
                _spectrumBands.value = bands
            }
        }
    }

    private fun sanitizeMarkdownForSpeech(markdown: String): String {
        return markdown
            .replace(Regex("```[\\s\\S]*?```"), " Подробности и код приведены в чате. ")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), " ссылка в чате ")
            .replace(Regex("[#*>_~|•]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun release() {
        stopRealtimeSession()
        visualizerTickerJob?.cancel()
        scope.cancel()
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
            textToSpeech?.shutdown()
            textToSpeech = null
        }
    }
}
