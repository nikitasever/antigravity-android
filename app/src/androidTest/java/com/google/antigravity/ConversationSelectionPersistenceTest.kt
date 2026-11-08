package com.google.antigravity

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.model.*
import com.google.antigravity.viewmodel.AntigravityViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConversationSelectionPersistenceTest {
    @Test(timeout = 60_000) fun openingHistoryPersistsSelectionBeforeViewModelIsCleared() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Owned QA emulator only" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("antigravity_prefs_v4", Context.MODE_PRIVATE)
        val keys = listOf("active_conversation_id", "active_session_title", "active_execution_target")
        val original = keys.associateWith { prefs.getString(it, null) }
        // A real local history entry; no inference, credentials or paid API calls.
        val id = "qa-selection-persistence-20261108"
        val title = "QA selection persistence"
        val history = ChatSessionStore(File(context.filesDir, "chat_history"))
        history.save(SavedChat(id, title, AgentExecutionTarget.PHONE_LOCAL_RESOURCES,
            listOf(ChatMessage(id = "qa-selection-message", role = MessageRole.USER,
                content = "Local QA history", timestamp = "12:00"))))
        val store = ViewModelStore()
        lateinit var model: AntigravityViewModel
        try {
            val reset = prefs.edit()
            keys.forEach { reset.remove(it) }
            assertTrue(reset.commit())
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AntigravityViewModel(context.applicationContext as Application) as T
                })[AntigravityViewModel::class.java]
            }
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (model.remoteSessions.value.none { it.id == id } && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            assertTrue("Local history loaded", model.remoteSessions.value.any { it.id == id })
            instrumentation.runOnMainSync {
                model.connectToExistingRemoteSession(model.remoteSessions.value.first { it.id == id })
                assertEquals(id, model.settings.value.activeConversationId)
                assertEquals(id, prefs.getString("active_conversation_id", null))
                assertEquals(title, prefs.getString("active_session_title", null))
                assertEquals(AgentExecutionTarget.PHONE_LOCAL_RESOURCES.name,
                    prefs.getString("active_execution_target", null))
                // The previous defect saved only in onCleared/next navigation, too late for process death.
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            val editor = prefs.edit()
            original.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
            assertTrue(editor.commit())
            // Keep the clearly labelled fixture in this QA emulator; never delete unrelated history.
        }
    }
}
