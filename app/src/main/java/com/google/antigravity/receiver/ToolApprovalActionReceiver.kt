package com.google.antigravity.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.antigravity.model.ApprovalDecision
import com.google.antigravity.service.AntigravityAgentSessionService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * BroadcastReceiver для мгновенной обработки решений пользователя по тулзам агента
 * прямо из шторки уведомлений Android (аналог `com.anthropic.claude.notification.CCRPermissionActionReceiver`:
 * `CCR_PERMISSION_APPROVE`, `CCR_PERMISSION_DENY`, `CCR_PERMISSION_DENY_WITH_COMMENT`).
 */
class ToolApprovalActionReceiver : BroadcastReceiver() {

    data class ApprovalActionEvent(
        val requestId: String,
        val decision: ApprovalDecision
    )

    companion object {
        const val ACTION_APPROVE_TOOL = "com.google.antigravity.ACTION_APPROVE_TOOL"
        const val ACTION_DENY_TOOL = "com.google.antigravity.ACTION_DENY_TOOL"
        const val ACTION_DENY_WITH_COMMENT = "com.google.antigravity.ACTION_DENY_WITH_COMMENT"
        const val EXTRA_REQUEST_ID = "extra_request_id"

        private val _events = MutableSharedFlow<ApprovalActionEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<ApprovalActionEvent> = _events.asSharedFlow()
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val reqId = intent?.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val decision = when (intent.action) {
            ACTION_APPROVE_TOOL -> ApprovalDecision.APPROVED
            ACTION_DENY_TOOL -> ApprovalDecision.DENIED
            ACTION_DENY_WITH_COMMENT -> ApprovalDecision.DENIED_WITH_COMMENT
            else -> return
        }

        _events.tryEmit(ApprovalActionEvent(requestId = reqId, decision = decision))

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(AntigravityAgentSessionService.APPROVAL_NOTIFICATION_ID)
    }
}
