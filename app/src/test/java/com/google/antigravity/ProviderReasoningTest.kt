package com.google.antigravity

import com.google.antigravity.network.PhoneReasoningAccumulator
import com.google.antigravity.network.parseGeminiReply
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderReasoningTest {
    @Test fun geminiThoughtPartsNeverBecomeAnswerOrPhoneActions() {
        val reply = parseGeminiReply(JSONObject("""{"candidates":[{"content":{"parts":[
            {"text":"Check files. ","thought":true},
            {"text":"Then answer.","thought":true,"thoughtSignature":"opaque-secret"},
            {"text":"Actual answer"},{"thoughtSignature":"opaque-secret"}
        ]}}]}"""))
        assertEquals("Actual answer", reply.text)
        assertEquals("Check files. Then answer.", reply.thinking)
        assertFalse(reply.toString().contains("opaque-secret"))
    }

    @Test fun modelsWithoutSummariesHaveNoInventedThinking() {
        assertEquals("", parseGeminiReply(JSONObject("""{"candidates":[{"content":{"parts":[{"text":"Answer"}]}}]}""")).thinking)
        assertEquals("", parseGeminiReply(JSONObject()).text)
    }

    @Test fun snapshotsReplaceCurrentStepAndSurviveLaterToolSteps() {
        val progress = PhoneReasoningAccumulator()
        progress.beginStep()
        progress.accept("Check")
        progress.accept("Check files.")
        progress.accept("")
        assertEquals("Check files.", progress.text)
        progress.finishStep()
        progress.beginStep()
        progress.accept("Verify results.")
        assertEquals("Check files.\n\nVerify results.", progress.text)
        progress.finishStep()
        assertEquals("Check files.\n\nVerify results.", progress.text)
    }
}
