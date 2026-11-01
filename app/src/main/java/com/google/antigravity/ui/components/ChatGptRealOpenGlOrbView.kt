package com.google.antigravity.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.antigravity.R
import com.google.antigravity.model.VoiceSessionStatus
import com.google.antigravity.model.VoiceVisualizerStyle
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Настоящий OpenGL ES 3.0 рендерер голосового шара из оригинального APK OpenAI ChatGPT 1.2026.293:
 *
 * Выполняет на GPU двухпроходный шейдерный конвейер OpenAI:
 * - Pass 1 (FBO 512x512): `res/raw/voice_vert.fsh` + `res/raw/voice_prepass.fsh` + `res/drawable-nodpi/noise_watercolor.webp`
 * - Pass 2 (Screen SDF):  `res/raw/voice_vert.fsh` + `res/raw/voice_main.fsh` + `res/drawable-nodpi/flowmap_outward_clockwise.webp`
 * - Режим Orbit 3D:       `res/raw/orbit_animation_frag.glsl` + `res/raw/orbit_palette.json`
 */
@Composable
fun ChatGptRealOpenGlOrbView(
    status: VoiceSessionStatus,
    visualizerStyle: VoiceVisualizerStyle,
    audioLevel: Float,
    spectrumBands: FloatArray,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember { ChatGptVoiceGlRenderer(context.applicationContext) }

    renderer.updateLiveState(
        status = status,
        style = visualizerStyle,
        audioLevel = audioLevel,
        bands = spectrumBands
    )

    val glView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    DisposableEffect(glView) {
        glView.onResume()
        onDispose {
            glView.onPause()
        }
    }

    AndroidView(
        factory = { glView },
        modifier = modifier
    )
}

private class ChatGptVoiceGlRenderer(
    private val context: Context
) : GLSurfaceView.Renderer {

    @Volatile
    private var currentStatus: VoiceSessionStatus = VoiceSessionStatus.LISTENING

    @Volatile
    private var currentStyle: VoiceVisualizerStyle = VoiceVisualizerStyle.ORBIT_ROSETTE

    @Volatile
    private var targetMicLevel: Float = 0.05f

    @Volatile
    private var band0: Float = 0.1f

    @Volatile
    private var band1: Float = 0.1f

    @Volatile
    private var band2: Float = 0.1f

    @Volatile
    private var band3: Float = 0.1f

    private var startTimeNs: Long = System.nanoTime()
    private var lastFrameSec: Float = 0f

    private var smoothMic: Float = 0.05f
    private var smoothListen: Float = 1.0f
    private var smoothThink: Float = 0.0f
    private var smoothSpeak: Float = 0.0f
    private var smoothHalt: Float = 0.0f

    private var listenTimestamp: Float = 0.0f
    private var thinkTimestamp: Float = 0.0f
    private var speakTimestamp: Float = 0.0f
    private var haltTimestamp: Float = 0.0f
    private var lastKnownStatus: VoiceSessionStatus = VoiceSessionStatus.INACTIVE

    private val cumulativeAudio = FloatArray(4) { 0f }

    private var surfaceWidth: Int = 512
    private var surfaceHeight: Int = 512

    private var vaoId: Int = 0
    private var prepassProgram: Int = 0
    private var mainProgram: Int = 0
    private var orbit3dProgram: Int = 0

    private var prepassFbo: Int = 0
    private var prepassTex: Int = 0
    private val prepassSize = 512

    private var noiseWatercolorTex: Int = 0
    private var flowmapThinkingTex: Int = 0

    fun updateLiveState(
        status: VoiceSessionStatus,
        style: VoiceVisualizerStyle,
        audioLevel: Float,
        bands: FloatArray
    ) {
        currentStatus = status
        currentStyle = style
        targetMicLevel = audioLevel.coerceIn(0f, 1f)
        if (bands.size >= 16) {
            band0 = ((bands[0] + bands[1] + bands[2] + bands[3]) * 0.25f).coerceIn(0f, 1f)
            band1 = ((bands[4] + bands[5] + bands[6] + bands[7]) * 0.25f).coerceIn(0f, 1f)
            band2 = ((bands[8] + bands[9] + bands[10] + bands[11]) * 0.25f).coerceIn(0f, 1f)
            band3 = ((bands[12] + bands[13] + bands[14] + bands[15]) * 0.25f).coerceIn(0f, 1f)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vaoId = vaos[0]
        GLES30.glBindVertexArray(vaoId)

        val vertSrc = readRawShader(R.raw.voice_vert)
        val prepassFragSrc = readRawShader(R.raw.voice_prepass)
        val mainFragSrc = readRawShader(R.raw.voice_main)
        val orbitFragSrc = readRawShader(R.raw.orbit_animation_frag)

        prepassProgram = buildShaderProgram(vertSrc, prepassFragSrc)
        mainProgram = buildShaderProgram(vertSrc, mainFragSrc)
        orbit3dProgram = buildShaderProgram(vertSrc, orbitFragSrc)

        noiseWatercolorTex = loadTextureFromDrawable(R.drawable.noise_watercolor, repeat = true)
        flowmapThinkingTex = loadTextureFromDrawable(R.drawable.flowmap_outward_clockwise, repeat = false)

        setupPrepassFramebuffer()
        startTimeNs = System.nanoTime()
        lastFrameSec = 0f
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = max(1, width)
        surfaceHeight = max(1, height)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val nowSec = ((System.nanoTime() - startTimeNs) / 1_000_000_000.0).toFloat()
        val dt = (nowSec - lastFrameSec).coerceIn(0.001f, 0.1f)
        lastFrameSec = nowSec

        val st = currentStatus
        if (st != lastKnownStatus) {
            when (st) {
                VoiceSessionStatus.LISTENING, VoiceSessionStatus.USER_SPEAKING -> listenTimestamp = nowSec
                VoiceSessionStatus.THINKING -> thinkTimestamp = nowSec
                VoiceSessionStatus.AI_SPEAKING -> speakTimestamp = nowSec
                VoiceSessionStatus.MUTED -> haltTimestamp = nowSec
                else -> {}
            }
            lastKnownStatus = st
        }

        val targetListen = if (st == VoiceSessionStatus.LISTENING || st == VoiceSessionStatus.USER_SPEAKING || st == VoiceSessionStatus.CONNECTING) 1f else 0f
        val targetThink = if (st == VoiceSessionStatus.THINKING) 1f else 0f
        val targetSpeak = if (st == VoiceSessionStatus.AI_SPEAKING) 1f else 0f
        val targetHalt = if (st == VoiceSessionStatus.MUTED) 1f else 0f

        val lerpSpeed = (dt * 7.5f).coerceIn(0.05f, 1.0f)
        smoothListen += (targetListen - smoothListen) * lerpSpeed
        smoothThink += (targetThink - smoothThink) * lerpSpeed
        smoothSpeak += (targetSpeak - smoothSpeak) * lerpSpeed
        smoothHalt += (targetHalt - smoothHalt) * lerpSpeed
        smoothMic += (targetMicLevel - smoothMic) * (dt * 12f).coerceIn(0.08f, 1.0f)

        val b0 = band0 + (if (st == VoiceSessionStatus.AI_SPEAKING) 0.35f * (sin(nowSec * 6.5f) * 0.5f + 0.5f) else 0f)
        val b1 = band1 + (if (st == VoiceSessionStatus.AI_SPEAKING) 0.42f * (sin(nowSec * 8.2f + 1.1f) * 0.5f + 0.5f) else 0f)
        val b2 = band2 + (if (st == VoiceSessionStatus.AI_SPEAKING) 0.38f * (sin(nowSec * 10.4f + 2.2f) * 0.5f + 0.5f) else 0f)
        val b3 = band3 + (if (st == VoiceSessionStatus.AI_SPEAKING) 0.30f * (sin(nowSec * 12.0f + 3.1f) * 0.5f + 0.5f) else 0f)

        val speedFactor = if (st == VoiceSessionStatus.AI_SPEAKING || st == VoiceSessionStatus.USER_SPEAKING) 2.6f else 0.7f
        cumulativeAudio[0] += (0.18f + b0) * dt * speedFactor
        cumulativeAudio[1] += (0.18f + b1) * dt * speedFactor
        cumulativeAudio[2] += (0.18f + b2) * dt * speedFactor
        cumulativeAudio[3] += (0.18f + b3) * dt * speedFactor

        GLES30.glBindVertexArray(vaoId)

        if (currentStyle == VoiceVisualizerStyle.ANTIGRAVITY_CORE && orbit3dProgram != 0) {
            renderOrbit3dShader(nowSec, smoothMic)
            return
        }

        val useNewBloop = if (currentStyle == VoiceVisualizerStyle.ORBIT_ROSETTE) 1 else 0

        // Палитра оригинального голубо-белого облачного шара ChatGPT Advanced Voice
        val mainR = 0.89f
        val mainG = 0.96f
        val mainB = 1.00f

        val lowR = 0.01f
        val lowG = 0.69f
        val lowB = 1.00f

        val midR = 0.27f
        val midG = 0.47f
        val midB = 1.00f

        val highR = 1.00f
        val highG = 1.00f
        val highB = 1.00f

        // =========================================================================
        // PASS 1: `voice_prepass.fsh` -> рендеринг акварельного FBM/Perlin облака в FBO 512x512
        // =========================================================================
        if (useNewBloop == 1 && prepassProgram != 0 && prepassFbo != 0) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prepassFbo)
            GLES30.glViewport(0, 0, prepassSize, prepassSize)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            GLES30.glUseProgram(prepassProgram)
            setUniform1f(prepassProgram, "time", nowSec)
            setUniform4f(prepassProgram, "avgMag", b0, b1, b2, b3)
            setUniform4f(
                prepassProgram,
                "cumulativeAudio",
                cumulativeAudio[0],
                cumulativeAudio[1],
                cumulativeAudio[2],
                cumulativeAudio[3]
            )
            setUniform1i(prepassProgram, "isNewBloop", 1)
            setUniform3f(prepassProgram, "bloopColorsMain", mainR, mainG, mainB)
            setUniform3f(prepassProgram, "bloopColorsLow", lowR, lowG, lowB)
            setUniform3f(prepassProgram, "bloopColorsMid", midR, midG, midB)
            setUniform3f(prepassProgram, "bloopColorsHigh", highR, highG, highB)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseWatercolorTex)
            setUniform1i(prepassProgram, "noiseTexture", 0)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        }

        // =========================================================================
        // PASS 2: `voice_main.fsh` -> финальный SDF шар ChatGPT с пружинной физикой и flowmap
        // =========================================================================
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        if (mainProgram != 0) {
            GLES30.glUseProgram(mainProgram)
            setUniform1f(mainProgram, "time", nowSec)
            setUniform1f(mainProgram, "micLevel", smoothMic)
            setUniform1f(mainProgram, "stateListen", smoothListen)
            setUniform1f(mainProgram, "listenTimestamp", listenTimestamp)
            setUniform1f(mainProgram, "stateThink", smoothThink)
            setUniform1f(mainProgram, "thinkTimestamp", thinkTimestamp)
            setUniform1f(mainProgram, "stateSpeak", smoothSpeak)
            setUniform1f(mainProgram, "speakTimestamp", speakTimestamp)
            setUniform1f(mainProgram, "readyTimestamp", 0.0f)
            setUniform1f(mainProgram, "stateHalt", smoothHalt)
            setUniform1f(mainProgram, "haltTimestamp", haltTimestamp)
            setUniform1f(mainProgram, "stateFailedToConnect", 0.0f)
            setUniform1f(mainProgram, "failedToConnectTimestamp", 0.0f)

            val avgOverall = (b0 + b1 + b2 + b3) * 0.25f
            setUniform4f(mainProgram, "avgMag", b0, b1, b2, b3)
            setUniform1f(mainProgram, "avgMagOverall", avgOverall)
            setUniform4f(
                mainProgram,
                "cumulativeAudio",
                cumulativeAudio[0],
                cumulativeAudio[1],
                cumulativeAudio[2],
                cumulativeAudio[3]
            )
            setUniform1f(mainProgram, "silenceAmount", if (avgOverall < 0.12f) 0.4f else 0.0f)
            setUniform1f(mainProgram, "silenceTimestamp", 0.0f)
            setUniform1i(mainProgram, "isNewBloop", useNewBloop)

            setUniform3f(mainProgram, "baseColor", 1.0f, 1.0f, 1.0f)
            setUniform3f(mainProgram, "bloopColorsMain", mainR, mainG, mainB)
            setUniform3f(mainProgram, "bloopColorsLow", lowR, lowG, lowB)
            setUniform3f(mainProgram, "bloopColorsMid", midR, midG, midB)
            setUniform3f(mainProgram, "bloopColorsHigh", highR, highG, highB)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prepassTex)
            setUniform1i(mainProgram, "prepassTexture", 0)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, flowmapThinkingTex)
            setUniform1i(mainProgram, "flowmapThinking", 1)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        }
    }

    private fun renderOrbit3dShader(nowSec: Float, mic: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUseProgram(orbit3dProgram)
        setUniform2f(orbit3dProgram, "resolution", surfaceWidth.toFloat(), surfaceHeight.toFloat())
        setUniform1f(orbit3dProgram, "pixelRatio", 2.75f)
        setUniform1f(orbit3dProgram, "scale", 0.86f + mic * 0.12f)
        setUniform1f(orbit3dProgram, "eyeEdge", 0.72f - mic * 0.14f)

        val dirX = sin(nowSec * 1.1f) * 0.45f
        val dirY = cos(nowSec * 0.85f) * 0.32f
        val dirZ = 0.82f
        setUniform3f(orbit3dProgram, "eyeDirection", dirX, dirY, dirZ)
        setUniform3f(orbit3dProgram, "bodyColor", 0.08f, 0.38f, 0.98f)
        setUniform1f(orbit3dProgram, "form", if (currentStatus == VoiceSessionStatus.THINKING) 0.85f else 0.0f)
        setUniform3f(orbit3dProgram, "rotationX", cos(nowSec), 0f, sin(nowSec))
        setUniform3f(orbit3dProgram, "rotationY", 0f, 1f, 0f)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
    }

    private fun setupPrepassFramebuffer() {
        val fbos = IntArray(1)
        val texs = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        GLES30.glGenTextures(1, texs, 0)
        prepassFbo = fbos[0]
        prepassTex = texs[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prepassTex)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            prepassSize,
            prepassSize,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prepassFbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            prepassTex,
            0
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun loadTextureFromDrawable(resId: Int, repeat: Boolean): Int {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)

        val wrap = if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)

        try {
            val opts = BitmapFactory.Options().apply { inScaled = false }
            val bmp = BitmapFactory.decodeResource(context.resources, resId, opts)
            if (bmp != null) {
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                bmp.recycle()
            }
        } catch (_: Throwable) {
        }
        return texId
    }

    private fun readRawShader(resId: Int): String {
        return try {
            context.resources.openRawResource(resId).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun buildShaderProgram(vertexSrc: String, fragmentSrc: String): Int {
        if (vertexSrc.isBlank() || fragmentSrc.isBlank()) return 0
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vs == 0 || fs == 0) return 0
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            GLES30.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun setUniform1f(prog: Int, name: String, v: Float) {
        val loc = GLES30.glGetUniformLocation(prog, name)
        if (loc >= 0) GLES30.glUniform1f(loc, v)
    }

    private fun setUniform1i(prog: Int, name: String, v: Int) {
        val loc = GLES30.glGetUniformLocation(prog, name)
        if (loc >= 0) GLES30.glUniform1i(loc, v)
    }

    private fun setUniform2f(prog: Int, name: String, x: Float, y: Float) {
        val loc = GLES30.glGetUniformLocation(prog, name)
        if (loc >= 0) GLES30.glUniform2f(loc, x, y)
    }

    private fun setUniform3f(prog: Int, name: String, x: Float, y: Float, z: Float) {
        val loc = GLES30.glGetUniformLocation(prog, name)
        if (loc >= 0) GLES30.glUniform3f(loc, x, y, z)
    }

    private fun setUniform4f(prog: Int, name: String, x: Float, y: Float, z: Float, w: Float) {
        val loc = GLES30.glGetUniformLocation(prog, name)
        if (loc >= 0) GLES30.glUniform4f(loc, x, y, z, w)
    }
}
