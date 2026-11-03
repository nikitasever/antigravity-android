package com.google.antigravity.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.antigravity.model.SubagentNotificationTexts
import com.google.antigravity.model.SwarmCounts
import com.google.antigravity.receiver.SubagentActionReceiver

/**
 * Уведомления о пачке субагентов «как у них» (Claude: notification-события
 * запуска/завершения с действиями SessionAnswer/SessionReply → наши честные
 * аналоги «Отменить»/«Открыть»). Тексты строятся только из реальных счётчиков
 * снимка; без разрешения POST_NOTIFICATIONS работа продолжается тихо — без
 * падений и без навязчивых запросов.
 */
object SubagentNotifier {

    const val CHANNEL_ID = "antigravity_subagent_tasks_channel"
    // Идентификаторы 4101 (голос) и 4202/4203 (сервис агента) заняты.
    const val NOTIFICATION_ID = 4204

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Фоновые задачи и субагенты",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Запуск и завершение пачек субагентов (панель фоновых задач)"
        }
        nm.createNotificationChannel(channel)
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun launchPendingIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Пачка запущена: «Выполняется N задач…», действие «Отменить». */
    fun notifyStarted(context: Context, runningTasks: Int, requestId: String) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val cancelIntent = Intent(context, SubagentActionReceiver::class.java).apply {
            action = SubagentActionReceiver.ACTION_CANCEL_SUBAGENT
            putExtra(SubagentActionReceiver.EXTRA_REQUEST_ID, requestId)
        }
        val cancelPending = PendingIntent.getBroadcast(
            context, 1, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(SubagentNotificationTexts.started(runningTasks))
            .setContentText("Задачи выполняются на ПК; результаты вернутся в переписку.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        launchPendingIntent(context)?.let { builder.setContentIntent(it) }
        builder.addAction(0, "Отменить", cancelPending)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * Пачка завершена. Статус заголовка по фактам: ошибки → «с ошибками»;
     * тело — только реальные счётчики (без данных — «не записано»).
     */
    fun notifyFinished(context: Context, counts: SwarmCounts?, hasFailures: Boolean) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(SubagentNotificationTexts.finishedTitle(hasFailures))
            .setContentText(SubagentNotificationTexts.finishedBody(counts))
            .setStyle(NotificationCompat.BigTextStyle().bigText(SubagentNotificationTexts.finishedBody(counts)))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        launchPendingIntent(context)?.let {
            builder.setContentIntent(it)
            builder.addAction(0, "Открыть", it)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    /** Пачка отменена пользователем (в т.ч. действием «Отменить» из шторки). */
    fun notifyCancelled(context: Context, counts: SwarmCounts?) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Задачи отменены")
            .setContentText(SubagentNotificationTexts.cancelledBody(counts))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        launchPendingIntent(context)?.let { builder.setContentIntent(it) }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(NOTIFICATION_ID)
    }
}
