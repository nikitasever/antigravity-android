package com.google.antigravity.assistant

import android.app.PendingIntent
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

/**
 * Системная служба голосового помощника (`VoiceInteractionService`),
 * позволяющая выбрать Antigravity цифровым помощником по умолчанию в Android (`android.app.role.ASSISTANT`).
 */
class AntigravityVoiceInteractionService : VoiceInteractionService()

class AntigravityVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AntigravityVoiceInteractionSession(this)
    }
}

class AntigravityVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        val summary = extractStructureSummary(state.assistStructure)
        launchAssistOverlay(summary)
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?
    ) {
        super.onHandleAssist(data, structure, content)
        val summary = extractStructureSummary(structure)
        launchAssistOverlay(summary)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        launchAssistOverlay("")
        hide()
    }

    private fun launchAssistOverlay(screenSummary: String) {
        try {
            val intent = Intent(context, AntigravityAssistActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (screenSummary.isNotBlank()) {
                    putExtra("assist_screen_context", screenSummary)
                }
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    private fun extractStructureSummary(structure: AssistStructure?): String {
        if (structure == null) return ""
        val out = mutableListOf<String>()
        try {
            val count = structure.windowNodeCount
            for (i in 0 until count) {
                val win = structure.getWindowNodeAt(i)
                collectText(win.rootViewNode, out)
            }
        } catch (_: Throwable) {
        }
        return out.distinct().take(20).joinToString(" • ")
    }

    private fun collectText(node: AssistStructure.ViewNode?, out: MutableList<String>) {
        if (node == null || out.size >= 25) return
        val t = node.text?.toString()?.trim().orEmpty()
        if (t.length in 2..120) out.add(t)
        for (i in 0 until node.childCount) {
            collectText(node.getChildAt(i), out)
        }
    }
}

class AntigravityRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

/**
 * Плитка в шторке быстрых настроек Android («Antigravity», Раздел 5.6c).
 * Нажатие открывает компактную панель ИИ-помощника поверх текущего экрана.
 */
class AntigravityQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "Antigravity"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = "ИИ-помощник"
            }
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, AntigravityAssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
