package com.google.antigravity

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.model.AgentExecutionTarget
import com.google.antigravity.notifications.SubagentNotifier
import com.google.antigravity.viewmodel.AntigravityViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Requires the two local QA bridges, adb reverse and URL A set through the normal settings UI. */
@RunWith(AndroidJUnit4::class)
class SubagentCancellationIntegrationTest {
    @get:org.junit.Rule val bridgeFixture = LocalBridgeFixtureRule(8810, 8811)
    private val a = "http://127.0.0.1:8810"
    private val b = "http://127.0.0.1:8811"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()

    private fun state(url: String): JSONObject = http.newCall(Request.Builder().url("$url/qa/state").build())
        .execute().use { response ->
            check(response.isSuccessful)
            JSONObject(response.body!!.string())
        }
    private fun action(): PendingIntent? = context.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.id == SubagentNotifier.NOTIFICATION_ID }?.notification?.actions?.firstOrNull()?.actionIntent
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(80)
        }
        fail(label)
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun post(url: String, path: String) = http.newCall(Request.Builder()
        .url("$url$path").post("{}".toRequestBody()).build()).execute().use { check(it.isSuccessful) }

    /** Start a fresh fixture with --hold-dispatch-a before this scenario. */
    @Test(timeout = 60000) fun cancellationBeforeDispatchAcceptanceIsReplayedForItsOriginalBatch() {
        assertTrue("Owned Android emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertTrue("Start a fresh gated fixture", state(a).getBoolean("holdDispatch"))
        if (Build.VERSION.SDK_INT >= 33) assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val store = ViewModelStore()
        val observers = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var vm: AntigravityViewModel
        var initialized = false
        var originalTarget: AgentExecutionTarget? = null
        try {
            main {
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(
                    context.applicationContext as Application))[AntigravityViewModel::class.java]
                initialized = true
                originalTarget = vm.settings.value.executionTarget
            }
            assertEquals(a, vm.settings.value.pcBridgeUrl.trimEnd('/'))
            observers.launch { vm.swarmAgents.collect {} }
            main {
                vm.startNewChat()
                vm.updateSettings { it.copy(executionTarget = AgentExecutionTarget.PC_REMOTE_SESSION) }
                vm.dispatchSwarmTask("QA held dispatch A")
            }
            val idA = vm.currentSubagentTaskId
            await("A dispatch must reach the gate before any worker starts") {
                state(a).getInt("dispatchReceived") == 1 && state(a).getInt("started") == 0
            }
            main { vm.cancelSubagentAnalysis() }
            await("Early cancellation must reach A before its dispatch is accepted") {
                state(a).getJSONArray("cancels").length() == 1
            }
            main {
                vm.startNewChat()
                vm.updateSettings { it.copy(pcBridgeUrl = b) }
                vm.dispatchSwarmTask("QA independent batch B")
            }
            val idB = vm.currentSubagentTaskId
            await("B must run before A is released") {
                state(b).optString("batchId") == idB && state(b).getInt("active") == 2 && action() != null
            }
            val notificationB = action()
            val idsB = vm.swarmAgents.value.map { it.id }.toSet()
            post(a, "/qa/release-dispatch")
            await("Early cancellation must be replayed after A is accepted, even when B owns the UI") {
                val s = state(a)
                s.getInt("started") == 2 && s.getInt("aborted") == 2 && s.getInt("active") == 0
            }
            SystemClock.sleep(1900)
            val cancelsA = state(a).getJSONArray("cancels")
            assertTrue(cancelsA.length() >= 2)
            assertTrue((0 until cancelsA.length()).all { cancelsA.getString(it) == idA })
            assertEquals(0, state(b).getJSONArray("cancels").length())
            assertEquals(2, state(b).getInt("active"))
            assertEquals(idsB, vm.swarmAgents.value.map { it.id }.toSet())
            assertTrue(vm.swarmAgents.value.all { it.isInProgress })
            assertEquals(notificationB, action())
            main {
                vm.updateSettings { it.copy(pcBridgeUrl = a) }
                vm.cancelSubagentAnalysis()
            }
            await("B must settle independently on its original bridge") {
                state(b).getInt("active") == 0 && state(b).getInt("aborted") == 2
            }
        } finally {
            runCatching { post(a, "/qa/cleanup") }
            runCatching { post(b, "/qa/cleanup") }
            observers.cancel()
            if (initialized) main {
                originalTarget?.let { target -> vm.updateSettings { it.copy(pcBridgeUrl = a, executionTarget = target) } }
                store.clear()
            }
            SubagentNotifier.cancel(context)
        }
    }

    @Test(timeout = 60000) fun notificationCancellationUsesOwnerBridgeAndCannotCancelANewerBatch() {
        assertTrue("Owned Android emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        if (Build.VERSION.SDK_INT >= 33) assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val store = ViewModelStore()
        val observers = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var vm: AntigravityViewModel
        var initialized = false
        var originalTarget: AgentExecutionTarget? = null
        var oldAction: PendingIntent? = null
        var newAction: PendingIntent? = null
        try {
            main {
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(
                    context.applicationContext as Application))[AntigravityViewModel::class.java]
                initialized = true
                originalTarget = vm.settings.value.executionTarget
            }
            assertEquals("Configure only the local fixture before running", a, vm.settings.value.pcBridgeUrl.trimEnd('/'))
            observers.launch { vm.swarmAgents.collect {} }
            main {
                vm.startNewChat()
                vm.updateSettings { it.copy(executionTarget = AgentExecutionTarget.PC_REMOTE_SESSION) }
                vm.dispatchSwarmTask("QA notification cancellation A")
            }
            val idA = vm.currentSubagentTaskId
            await("Batch A and its native notification must start") {
                state(a).optString("batchId") == idA && vm.swarmAgents.value.size == 2 && action() != null
            }
            oldAction = action()!!
            main {
                vm.startNewChat() // Explicitly cancels A; its response is delayed by the fixture.
                vm.updateSettings { it.copy(pcBridgeUrl = b) }
                vm.dispatchSwarmTask("QA notification cancellation B")
            }
            val idB = vm.currentSubagentTaskId
            assertNotEquals(idA, idB)
            await("Batch B must start with a distinct notification") {
                state(b).optString("batchId") == idB && action() != null && action() != oldAction
            }
            newAction = action()!!
            // Let the late A cancellation response arrive after B has been accepted.
            SystemClock.sleep(1900)
            val idsB = state(b).getJSONObject("swarm").getJSONArray("agents").let { agents ->
                (0 until agents.length()).map { agents.getJSONObject(it).getString("id") }.toSet()
            }
            assertEquals(idsB, vm.swarmAgents.value.map { it.id }.toSet())
            assertTrue(vm.swarmAgents.value.all { it.isInProgress })
            val cancelCountB = state(b).getJSONArray("cancels").length()
            oldAction.send()
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
            assertEquals(cancelCountB, state(b).getJSONArray("cancels").length())
            assertEquals(2, state(b).getInt("active"))
            assertEquals(newAction, action())
            // Current settings now point to A; the B notification must still cancel on B.
            main { vm.updateSettings { it.copy(pcBridgeUrl = a) } }
            newAction.send()
            await("The real bridge workers must receive abort and settle") {
                val snapshot = state(b)
                snapshot.getInt("active") == 0 && snapshot.getInt("aborted") == 2
            }
            await("ViewModel must receive terminal cancellation") {
                vm.swarmAgents.value.size == 2 && vm.swarmAgents.value.all { it.status == "cancelled" && !it.isSettling }
            }
            assertEquals(idB, state(b).getJSONArray("cancels").getString(cancelCountB))
            assertEquals(cancelCountB + 1, state(b).getJSONArray("cancels").length())
            val cancelledA = state(a).getJSONArray("cancels")
            assertEquals(1, cancelledA.length())
            assertEquals(idA, cancelledA.getString(0))
            assertEquals(0, state(a).getInt("active"))
        } finally {
            observers.cancel()
            if (initialized) main {
                originalTarget?.let { target -> vm.updateSettings { it.copy(pcBridgeUrl = a, executionTarget = target) } }
                store.clear()
            }
            oldAction?.cancel(); newAction?.cancel()
            SubagentNotifier.cancel(context)
        }
    }
}
