package com.google.antigravity.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * BroadcastReceiver для действий из шторки уведомлений о пачке субагентов
 * («как у них»: их notification-действия SessionAnswerActionWorker /
 * SessionReplyActionWorker → наши честные аналоги «Отменить»/«Открыть» в
 * пределах нашей модели; переписки с сессиями у нас нет и не выдумывается).
 */
class SubagentActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CANCEL_SUBAGENT = "com.google.antigravity.ACTION_CANCEL_SUBAGENT"
        const val EXTRA_REQUEST_ID = "extra_request_id"

        private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)

        /** Идентификатор отменяемой пачки (requestId). */
        val events: SharedFlow<String> = _events.asSharedFlow()
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CANCEL_SUBAGENT) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: ""
        _events.tryEmit(requestId)
        // Уведомление о запущенной пачке закрывается сразу — статус дальше
        // показывает панель субагентов (без выдуманных фактов).
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(com.google.antigravity.notifications.SubagentNotifier.NOTIFICATION_ID)
    }
}
