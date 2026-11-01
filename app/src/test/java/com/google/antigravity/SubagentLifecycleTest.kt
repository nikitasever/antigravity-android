package com.google.antigravity

import com.google.antigravity.model.SwarmAgentNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentLifecycleTest {
    @Test fun failedWorkerStillWaitsForReviewWhileSettling() {
        assertTrue(SwarmAgentNode(id = "a", name = "Worker", status = "failed", isSettling = true).isInProgress)
        assertTrue(SwarmAgentNode(id = "a", name = "Worker", status = "cancelled", isSettling = true).isInProgress)
    }

    @Test fun terminalWorkerStopsPollingAfterReviewIsCollected() {
        for (status in listOf("failed", "completed", "cancelled")) {
            assertFalse(SwarmAgentNode(id = "a", name = "Worker", status = status).isInProgress)
        }
    }

    @Test fun queuedAndRunningWorkersRemainCompatibleWithOlderBridge() {
        for (status in listOf("queued", "running")) {
            assertTrue(SwarmAgentNode(id = "a", name = "Worker", status = status).isInProgress)
        }
    }
}
