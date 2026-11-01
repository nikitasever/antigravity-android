package com.google.antigravity.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.antigravity.MainActivity
import com.google.antigravity.R

/**
 * Фоновая служба полнодуплексного голосового общения в реальном времени.
 *
 * Синтезирована по образцу:
 * - `com.openai.voice.service.VoiceModeForegroundService` & `AgentVoiceForegroundService` (ChatGPT)
 * - `com.anthropic.claude.HandsFreeMode.BellModeService` (Claude)
 */
class AntigravityVoiceForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "antigravity_realtime_voice_channel"
        const val NOTIFICATION_ID = 4101
        const val ACTION_START = "com.google.antigravity.voice.ACTION_START"
        const val ACTION_STOP = "com.google.antigravity.voice.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, AntigravityVoiceForegroundService::class.java).apply {
                action = ACTION_START
            }
            try {
                context.startForegroundService(intent)
            } catch (_: Throwable) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AntigravityVoiceForegroundService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildVoiceNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("VoiceForegroundService", "Cannot enter foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.voice_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Полнодуплексный голосовой канал Antigravity Live (Orbit + Bell Mode)"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildVoiceNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AntigravityVoiceForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Antigravity Live Voice (Full-Duplex)")
            .setContentText("Режим реального времени активен • Barge-In и управление агентом включены")
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Завершить звонок",
                stopPending
            )
            .build()
    }
}
