package com.google.antigravity

import android.opengl.EGL14
import android.opengl.GLES30
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.model.VoiceSessionStatus
import com.google.antigravity.model.VoiceVisualizerStyle
import com.google.antigravity.ui.components.ChatGptVoiceGlRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** Runs the actual two-pass shader on a device, independently of Compose/SurfaceView. */
@RunWith(AndroidJUnit4::class)
class VoiceOrbRenderTest {
    @Test(timeout = 30_000) fun listeningOrbHasVisibleCenterAndTransparentExterior() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40, // ES 3
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
        ), 0, configs, 0, 1, count, 0))
        assertTrue(count[0] > 0)
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 512, EGL14.EGL_HEIGHT, 512, EGL14.EGL_NONE), 0)
        try {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
            GLES30.glClearColor(1f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            val clear = ByteBuffer.allocateDirect(4)
            GLES30.glReadPixels(256, 256, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, clear)
            assertEquals("Pbuffer readback must work before probing the renderer", 255, clear.get(0).toInt() and 255)
            var initializations = 0
            val renderer = ChatGptVoiceGlRenderer(InstrumentationRegistry.getInstrumentation().targetContext,
                onInitializing = { initializations++ }) {}
            renderer.updateLiveState(VoiceSessionStatus.LISTENING, VoiceVisualizerStyle.ORBIT_ROSETTE,
                0.15f, FloatArray(16) { 0.1f })
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 512, 512)
            val deadline = SystemClock.uptimeMillis() + 1_200L
            // The first draw can compile the driver's pipeline and exceed the deadline.
            // Always draw a subsequent frame at a settled animation time as well.
            do { renderer.onDrawFrame(null); SystemClock.sleep(33L) }
            while (SystemClock.uptimeMillis() < deadline)
            renderer.onDrawFrame(null)
            val center = ByteBuffer.allocateDirect(4)
            GLES30.glReadPixels(256, 256, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, center)
            val diagnostic = renderer.inspectFrame()
            android.util.Log.i("VoiceOrbTest", diagnostic)
            assertTrue(diagnostic, (0..2).sumOf { center.get(it).toInt() and 255 } > 64)
            assertTrue(diagnostic, (center.get(3).toInt() and 255) > 128)
            val corner = ByteBuffer.allocateDirect(4)
            GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, corner)
            assertEquals("The transparent orb must not occlude Compose outside its silhouette", 0,
                corner.get(3).toInt() and 255)
            renderer.updateLiveState(VoiceSessionStatus.AI_SPEAKING, VoiceVisualizerStyle.ORBIT_ROSETTE,
                0.3f, FloatArray(16) { 0.2f })
            renderer.onDrawFrame(null)
            SystemClock.sleep(700L)
            renderer.onDrawFrame(null)
            // The same renderer can receive a new surface after background/context loss.
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 512, 512)
            renderer.updateLiveState(VoiceSessionStatus.LISTENING, VoiceVisualizerStyle.ORBIT_ROSETTE,
                0.1f, FloatArray(16) { 0.1f })
            repeat(6) { renderer.onDrawFrame(null); SystemClock.sleep(200L) }
            GLES30.glReadPixels(256, 256, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, center)
            assertEquals(2, initializations)
            assertTrue("Recreated surface must produce a visible center", (center.get(3).toInt() and 255) > 128)
            assertTrue((0..2).sumOf { center.get(it).toInt() and 255 } > 64)
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

}
