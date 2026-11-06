package com.google.antigravity

import com.google.antigravity.model.*
import com.google.antigravity.network.bridgeTurnFailed
import com.google.antigravity.network.mergeBridgeTool
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgeTurnStateTest {
    @Test fun approvalCapabilityIsBoundToTheActualBridgeAndRemovedOnCompletion() {
        val events=mutableListOf<ToolCallEvent>()
        val pending=JSONObject("""{"id":"ask","name":"write_file","status":"AWAITING_APPROVAL","requiresApproval":true,
            "approval":{"requestId":"request","token":"secret-capability","turnId":"turn","conversationId":"chat",
                "bridgeUrl":"https://untrusted.example","preview":"path: notes.txt"}}""")
        mergeBridgeTool(events,pending,"fallback","http://actual-bridge:8787")
        val handle=events.single().approvalHandle!!
        assertEquals("http://actual-bridge:8787",handle.bridgeUrl)
        assertEquals("request",handle.requestId)
        assertFalse(handle.toString().contains("secret-capability"))
        mergeBridgeTool(events,JSONObject("""{"id":"ask","status":"COMPLETED","output":"written","requiresApproval":false}"""),"fallback")
        assertNull(events.single().approvalHandle)
        assertFalse(events.single().approvalSending)
    }

    @Test fun partialApprovalPayloadDoesNotEnableLocalApproval() {
        val events=mutableListOf<ToolCallEvent>()
        mergeBridgeTool(events,JSONObject("""{"id":"ask","status":"AWAITING_APPROVAL","requiresApproval":true,
            "approval":{"requestId":"request","token":"token"}}"""),"fallback","http://bridge")
        assertNull(events.single().approvalHandle)
    }

    @Test fun explicitTurnFailuresAreNeverTransportSuccess() {
        listOf("{\"ok\":false}", "{\"isError\":true}", "{\"status\":\"FAILED\"}",
            "{\"cancelled\":true}", "{\"error\":\"provider failed\"}").forEach { assertTrue(bridgeTurnFailed(JSONObject(it))) }
        listOf("{}", "{\"ok\":true}", "{\"error\":null}", "{\"error\":\"\"}",
            "{\"ok\":null}", "{\"status\":\"completed\"}").forEach { assertFalse(bridgeTurnFailed(JSONObject(it))) }
    }

    @Test fun failedToolRemainsFailedInTerminalSnapshot() {
        val events=mutableListOf<ToolCallEvent>()
        mergeBridgeTool(events, JSONObject("""{"id":"read","name":"read_file","output":"denied","status":"FAILED"}"""), "fallback")
        mergeBridgeTool(events, JSONObject("""{"id":"read","output":"permission denied"}"""), "other")
        assertEquals(ToolCallStatus.FAILED,events.single().status)
        assertEquals("permission denied",events.single().details)
        assertNull(events.single().unifiedDiff)
    }

    @Test fun nestedEventsUpdateOneIdWithoutLosingParentOrNarration() {
        val events=mutableListOf<ToolCallEvent>()
        mergeBridgeTool(events, JSONObject("""{"type":"tool","tool":{"id":"read","name":"read_file","status":"running","parentTaskId":"child","leadingText":"Inspect files"}}"""), "fallback")
        mergeBridgeTool(events, JSONObject("""{"id":"read","status":"COMPLETED","output":"actual output"}"""), "other")
        assertEquals(1,events.size)
        assertEquals("read_file",events.single().toolName)
        assertEquals("child",events.single().parentTaskId)
        assertEquals("Inspect files",events.single().leadingText)
        assertEquals(ToolCallStatus.COMPLETED,events.single().status)
    }

    @Test fun distinctIdsWithSameToolAndSummaryAreSeparateInvocations() {
        val events=mutableListOf<ToolCallEvent>()
        for (id in listOf("first","second")) mergeBridgeTool(events,
            JSONObject().put("id",id).put("name","read_file").put("summary","Read").put("output","same"),"fallback")
        assertEquals(listOf("first","second"),events.map { it.id })
    }

    @Test fun statusesAndLegacyResultRemainCompatibleWithoutInventingDiffs() {
        val states=mapOf("running" to ToolCallStatus.RUNNING,"CANCELLED" to ToolCallStatus.CANCELLED,
            "awaiting_approval" to ToolCallStatus.AWAITING_APPROVAL,"approved" to ToolCallStatus.APPROVED,
            "denied" to ToolCallStatus.DENIED,"unknown-new-state" to ToolCallStatus.RUNNING)
        for ((wire,expected) in states) {
            val events=mutableListOf<ToolCallEvent>()
            mergeBridgeTool(events,JSONObject().put("status",wire).put("id","x").put("name","tool"),"fallback")
            assertEquals(expected,events.single().status)
        }
        val legacy=mutableListOf<ToolCallEvent>()
        mergeBridgeTool(legacy,JSONObject("""{"name":"read_file","output":"ordinary text"}"""),"legacy")
        assertEquals(ToolCallStatus.COMPLETED,legacy.single().status)
        assertNull(legacy.single().unifiedDiff)
        mergeBridgeTool(legacy,JSONObject("""{"id":"legacy","ok":false,"status":"COMPLETED"}"""),"fallback")
        assertEquals(ToolCallStatus.FAILED,legacy.single().status)
    }

    @Test fun failedTerminalPayloadRetainsPartialTraceAndCompletesUnfinishedActions() {
        val read=ToolCallEvent(id="read",toolName="read_file",summary="Read",details="actual content",unifiedDiff=null)
        val child=read.copy(id="child",toolName="delegate_task",details="",status=ToolCallStatus.RUNNING)
        val final=finishTurnTools(listOf(read,child), emptyList(),true,AgentExecutionTarget.PC_REMOTE_SESSION)
        assertEquals(2,final.size)
        assertEquals("actual content",final.first().details)
        assertEquals(ToolCallStatus.COMPLETED,final.first().status)
        assertEquals(ToolCallStatus.FAILED,final.last().status)
        assertFalse(final.any { it.status.isUnfinished })
    }

    @Test fun successfulPendingApprovalIsNotConvertedToFailureAndFinalUpdateReplacesSameId() {
        val pending=ToolCallEvent(id="write",toolName="write_file",summary="Write",details="",status=ToolCallStatus.AWAITING_APPROVAL)
        val target=AgentExecutionTarget.PC_REMOTE_SESSION
        assertEquals(ToolCallStatus.AWAITING_APPROVAL,finishTurnTools(listOf(pending),emptyList(),false,target).single().status)
        val done=pending.copy(status=ToolCallStatus.COMPLETED,details="Written")
        val merged=finishTurnTools(listOf(pending),listOf(done),false,target)
        assertEquals(1,merged.size)
        assertEquals("Written",merged.single().details)
        assertEquals(target,merged.single().executionTarget)
    }
}
