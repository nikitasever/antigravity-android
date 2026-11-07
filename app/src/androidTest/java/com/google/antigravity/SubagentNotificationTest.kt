package com.google.antigravity

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.notifications.SubagentNotifier
import com.google.antigravity.receiver.SubagentActionReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real NotificationManager/PendingIntent/BroadcastReceiver; no Activity, model or PC calls. */
@RunWith(AndroidJUnit4::class)
class SubagentNotificationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun requireNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) assertEquals("Grant POST_NOTIFICATIONS on the owned test AVD first",
            PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun currentAction(): PendingIntent? = manager.activeNotifications
        .firstOrNull { it.id == SubagentNotifier.NOTIFICATION_ID }?.notification?.actions?.firstOrNull()?.actionIntent

    private fun awaitAction(previous: PendingIntent? = null): PendingIntent {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            currentAction()?.takeIf { it != previous }?.let { return it }
            SystemClock.sleep(50)
        }
        error("Expected a new subagent notification action")
    }

    @Test(timeout = 30000) fun oldCancelActionKeepsItsIdAndDoesNotDismissNewerNotification() = runBlocking {
        requireNotificationPermission()
        val idA = "qa-A-${UUID.randomUUID()}"
        val idB = "qa-B-${UUID.randomUUID()}"
        var actionA: PendingIntent? = null
        var actionB: PendingIntent? = null
        try {
            SubagentNotifier.cancel(context)
            SubagentNotifier.notifyStarted(context, 1, idA)
            actionA = awaitAction()
            SubagentNotifier.notifyStarted(context, 2, idB)
            actionB = awaitAction(actionA)
            assertNotEquals(actionA, actionB)
            val oldId = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5000) { SubagentActionReceiver.events.first() }
            }
            actionA.send()
            assertEquals(idA, oldId.await())
            instrumentation.waitForIdleSync()
            assertEquals("Old action must leave the new notification visible", actionB, currentAction())
            val newId = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5000) { SubagentActionReceiver.events.first() }
            }
            actionB.send()
            assertEquals(idB, newId.await())
            instrumentation.waitForIdleSync()
            assertEquals(actionB, currentAction())
        } finally {
            actionA?.cancel(); actionB?.cancel()
            SubagentNotifier.cancel(context)
        }
    }

    @Test(timeout = 30000) fun emptyCancelIdDoesNotEmitAnActionOrDismissCurrentNotification() = runBlocking {
        requireNotificationPermission()
        var current: PendingIntent? = null
        try {
            SubagentNotifier.cancel(context)
            SubagentNotifier.notifyStarted(context, 1, "qa-current-${UUID.randomUUID()}")
            current = awaitAction()
            val unexpected = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(800) { SubagentActionReceiver.events.first() }
            }
            context.sendBroadcast(Intent(context, SubagentActionReceiver::class.java).apply {
                action = SubagentActionReceiver.ACTION_CANCEL_SUBAGENT
                putExtra(SubagentActionReceiver.EXTRA_REQUEST_ID, "")
            })
            assertNull(unexpected.await())
            instrumentation.waitForIdleSync()
            assertEquals(current, currentAction())
        } finally {
            current?.cancel()
            SubagentNotifier.cancel(context)
        }
    }
}
