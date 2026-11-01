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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.antigravity.MainActivity
import com.google.antigravity.R
import com.google.antigravity.receiver.ToolApprovalActionReceiver

/**
 * Непрерывный фоновый сервис выполнения задач ИИ (`AntigravityAgentSessionService` / `AntigravityAgentForegroundService`).
 *
 * Гарантирует, что при сворачивании приложения, выключении экрана или переключении в другие окна
 * выполнение длительных задач, потоковый приём шагов и фоновый опрос `/api/chat/turn-status`
 * НЕ прерываются (удерживается `PARTIAL_WAKE_LOCK` и активное уведомление прогресса).
 */
class AntigravityAgentSessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        // Promote immediately, even when a short turn finishes before onStartCommand.
        try {
            val notification = buildProgressNotification(this, "Antigravity", "Подготовка задачи…", ongoing = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(PROGRESS_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("AgentSessionService", "Cannot enter foreground", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedAction = intent?.action ?: ACTION_START_PROGRESS
        // A very short turn may stop before Android delivers its start intent.
        // onCreate has already fulfilled startForegroundService's promotion.
        val action = if (!progressRequested) ACTION_STOP_PROGRESS else requestedAction
        when (action) {
            ACTION_START_PROGRESS, ACTION_UPDATE_PROGRESS -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Antigravity ИИ выполняет задачу"
                val step = intent?.getStringExtra(EXTRA_STEP) ?: "Синхронизация с рабочей сессией…"
                val notification = buildProgressNotification(this, title, step, ongoing = true)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(
                            PROGRESS_NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } else {
                        startForeground(PROGRESS_NOTIFICATION_ID, notification)
                    }
                } catch (_: Throwable) {
                    releaseWakeLock()
                    stopSelf()
                    return START_NOT_STICKY
                }
                acquireWakeLockIfNeeded()
            }
            ACTION_STOP_PROGRESS -> {
                if (progressRequested) return START_NOT_STICKY // An immediately queued next turn superseded this stop.
                releaseWakeLock()
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Throwable) {
                }
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLockIfNeeded() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Antigravity::AgentExecutionWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L) // до 10 минут непрерывного хода
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    companion object {
        @Volatile private var progressRequested = false
        const val APPROVAL_CHANNEL_ID = "antigravity_ccr_approval_channel"
        const val PROGRESS_CHANNEL_ID = "antigravity_agent_progress_channel"
        const val APPROVAL_NOTIFICATION_ID = 4202
        const val PROGRESS_NOTIFICATION_ID = 4203

        const val ACTION_START_PROGRESS = "com.google.antigravity.action.START_AGENT_PROGRESS"
        const val ACTION_UPDATE_PROGRESS = "com.google.antigravity.action.UPDATE_AGENT_PROGRESS"
        const val ACTION_STOP_PROGRESS = "com.google.antigravity.action.STOP_AGENT_PROGRESS"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_STEP = "extra_step"

        private fun ensureChannels(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val approvalChannel = NotificationChannel(
                APPROVAL_CHANNEL_ID,
                context.getString(R.string.agent_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Запросы подтверждения выполнения команд и патчей Antigravity Agent"
            }
            val progressChannel = NotificationChannel(
                PROGRESS_CHANNEL_ID,
                "Фоновое выполнение задач ИИ",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Непрерывная работа агента Antigravity в фоновом режиме"
                setShowBadge(false)
            }
            nm.createNotificationChannel(approvalChannel)
            nm.createNotificationChannel(progressChannel)
        }

        private fun buildProgressNotification(
            context: Context,
            title: String,
            stepText: String,
            ongoing: Boolean
        ): Notification {
            ensureChannels(context)
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                context,
                201,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(stepText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(stepText))
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openPending)
                .build()
        }

        fun startAgentTurnForeground(context: Context, title: String, stepText: String) {
            progressRequested = true
            try {
                val intent = Intent(context, AntigravityAgentSessionService::class.java).apply {
                    action = ACTION_START_PROGRESS
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_STEP, stepText)
                }
                context.startForegroundService(intent)
            } catch (_: Throwable) {
                progressRequested = false
            }
        }

        fun updateAgentTurnProgress(context: Context, title: String, stepText: String) {
            try {
                ensureChannels(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                nm.notify(PROGRESS_NOTIFICATION_ID, buildProgressNotification(context, title, stepText, ongoing = true))
            } catch (_: Throwable) {
            }
        }

        fun stopAgentTurnForeground(context: Context) {
            if (!progressRequested) return
            progressRequested = false
            try {
                // Do not remove a pending foreground-service start before onCreate.
                // Deliver stop through the same service lifecycle instead.
                context.startService(Intent(context, AntigravityAgentSessionService::class.java).apply {
                    action = ACTION_STOP_PROGRESS
                })
            } catch (_: Throwable) {
            }
        }

        fun postToolApprovalNotification(
            context: Context,
            requestId: String,
            toolName: String,
            summary: String,
            commandOrPath: String
        ) {
            try {
                ensureChannels(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val openPending = PendingIntent.getActivity(
                    context,
                    200,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val approveIntent = Intent(context, ToolApprovalActionReceiver::class.java).apply {
                    action = ToolApprovalActionReceiver.ACTION_APPROVE_TOOL
                    putExtra(ToolApprovalActionReceiver.EXTRA_REQUEST_ID, requestId)
                }
                val approvePending = PendingIntent.getBroadcast(
                    context,
                    requestId.hashCode(),
                    approveIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val denyIntent = Intent(context, ToolApprovalActionReceiver::class.java).apply {
                    action = ToolApprovalActionReceiver.ACTION_DENY_TOOL
                    putExtra(ToolApprovalActionReceiver.EXTRA_REQUEST_ID, requestId)
                }
                val denyPending = PendingIntent.getBroadcast(
                    context,
                    requestId.hashCode() + 1,
                    denyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, APPROVAL_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Antigravity запрашивает разрешение: $toolName")
                    .setContentText("$summary ($commandOrPath)")
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText("$summary\nЦель: $commandOrPath\nВы также можете сказать голосом: «Одобри команду».")
                    )
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(openPending)
                    .addAction(android.R.drawable.checkbox_on_background, "Одобрить (Approve)", approvePending)
                    .addAction(android.R.drawable.ic_delete, "Отклонить (Deny)", denyPending)
                    .build()

                nm.notify(APPROVAL_NOTIFICATION_ID, notification)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
