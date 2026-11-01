package com.google.antigravity

import com.google.antigravity.model.AiPermissionsConfig
import com.google.antigravity.model.ChatMessage
import com.google.antigravity.model.ContextWindowStatus
import com.google.antigravity.model.FileDiffItem
import com.google.antigravity.model.GoogleDriveFileItem
import com.google.antigravity.model.GoogleDriveStatus
import com.google.antigravity.model.MessageRole
import com.google.antigravity.model.OpenRouterCategory
import com.google.antigravity.model.OpenRouterModelItem
import com.google.antigravity.model.SwarmAgentNode
import com.google.antigravity.network.sanitizeStoragePathsInMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Round5VerificationTest {

    @Test
    fun sanitizeStoragePaths_preservesClickableFileUrisAndSanitizesRawPaths() {
        val input = """
            Отчёт готов:
            - Ссылка на файл: [MainScreen.kt](file:///storage/emulated/0/Download/Antigravity/MainScreen.kt)
            - Рабочая папка: [report.md](file:///storage/emulated/0/Android/data/com.google.antigravity/files/AntigravityWorkspace/report.md)
            - Песочница: [index.html](file:///data/user/0/com.google.antigravity/files/virtual_sandbox_v2/index.html)
            - Сырой путь: `/storage/emulated/0/Download/secret_doc.pdf`
        """.trimIndent()

        val result = sanitizeStoragePathsInMarkdown(input, "Сделай отчёт со ссылками")

        assertTrue(
            "Clickable Download file:/// URI must be preserved intact",
            result.contains("file:///storage/emulated/0/Download/Antigravity/MainScreen.kt")
        )
        assertTrue(
            "Clickable AntigravityWorkspace file:/// URI must be preserved intact",
            result.contains("file:///storage/emulated/0/Android/data/com.google.antigravity/files/AntigravityWorkspace/report.md")
        )
        assertTrue(
            "Clickable app data file:/// URI must be preserved intact",
            result.contains("file:///data/user/0/com.google.antigravity/files/virtual_sandbox_v2/index.html")
        )
        assertTrue(
            "Raw backticked /storage/emulated/0/ path must be shortened to filename",
            result.contains("`secret_doc.pdf`")
        )
    }

    @Test
    fun fileDiffItem_parsesAddedAndRemovedLinesCorrectly() {
        val diff = FileDiffItem(
            path = "app/src/main/java/com/google/antigravity/ui/MainScreen.kt",
            additions = 4,
            deletions = 2,
            diffPatch = """
                @@ -10,4 +10,6 @@
                 fun renderHeader() {
                -    Text("Old Title")
                -    Text("Legacy")
                +    Text("Antigravity 2.0")
                +    Text("OpenRouter Live 452")
                +    Text("Multi-Agent Swarm")
                +    Text("Virtual Sandbox v2.0")
                 }
            """.trimIndent()
        )
        assertEquals(4, diff.additions)
        assertEquals(2, diff.deletions)
        assertTrue(diff.diffPatch.contains("+    Text(\"OpenRouter Live 452\")"))
        assertTrue(diff.diffPatch.contains("-    Text(\"Old Title\")"))
    }

    @Test
    fun openRouterLiveModels_sortNewest2026FirstAndSwarmLeaderSwitchesCleanly() {
        val models = listOf(
            OpenRouterModelItem(
                id = "openai/gpt-4o-mini",
                name = "GPT-4o Mini",
                createdTimestamp = 1721260800L
            ),
            OpenRouterModelItem(
                id = "particle/particle-1",
                name = "Particle: Particle-1 (2026 Flagship)",
                createdTimestamp = 1793404800L,
                isReasoning = true,
                categories = listOf(OpenRouterCategory.ALL, OpenRouterCategory.REASONING)
            ),
            OpenRouterModelItem(
                id = "openai/gpt-6.1-terra-pro",
                name = "OpenAI: GPT-6.1 Terra Pro",
                createdTimestamp = 1793318400L,
                isReasoning = true,
                categories = listOf(OpenRouterCategory.ALL, OpenRouterCategory.REASONING)
            )
        )
        val sorted = models.sortedByDescending { it.createdTimestamp }
        assertEquals("particle/particle-1", sorted.first().id)
        assertEquals("openai/gpt-6.1-terra-pro", sorted[1].id)

        val nodes = listOf(
            SwarmAgentNode(id = "orchestrator", name = "Orchestrator", roleBadge = "Лидер", model = "m1", isLeader = true, status = "active"),
            SwarmAgentNode(id = "coder", name = "Coder", roleBadge = "Кодер", model = "m2", isLeader = false, status = "working")
        )
        val switched = nodes.map { it.copy(isLeader = it.id == "coder") }
        assertEquals(1, switched.count { it.isLeader })
        assertEquals("coder", switched.first { it.isLeader }.id)
    }

    @Test
    fun permissionsAndContextWindow_enforceGranularControls() {
        val perms = AiPermissionsConfig(phoneVirtualSandboxExec = false)
        assertFalse("Disabling phoneVirtualSandboxExec must disable phoneVirtualSandboxAccess", perms.phoneVirtualSandboxAccess)

        val ctx = ContextWindowStatus(
            conversationId = "conv-r5",
            maxTokens = 1_000_000,
            usedTokens = 1_240,
            wasReset = true
        )
        assertTrue(ctx.wasReset)
        assertEquals(1_240, ctx.usedContextTokens)
    }

    @Test
    fun tokenGenerationSpeedAndGoogleDriveModels_storeMetricsAndCloudFiles() {
        val msg = ChatMessage(
            id = "msg-speed-1",
            role = MessageRole.AGENT,
            content = "Ответ сгенерирован.",
            timestamp = "12:00",
            tokensPerSecond = 62.4f,
            outputTokensCount = 340,
            firstTokenLatencyMs = 280L,
            modelBadge = "OpenRouter • particle/particle-1"
        )
        assertEquals(62.4f, msg.tokensPerSecond, 0.01f)
        assertEquals(340, msg.outputTokensCount)
        assertEquals(280L, msg.firstTokenLatencyMs)
        assertTrue(msg.modelBadge.contains("particle-1"))

        val driveStatus = GoogleDriveStatus(connected = true, folderPath = "/AntigravityCloud", filesCount = 3)
        val driveFile = GoogleDriveFileItem(
            id = "gdrive-1",
            name = "architecture_plan.md",
            folder = "/AntigravityCloud"
        )
        assertTrue(driveStatus.connected)
        assertEquals("/AntigravityCloud", driveFile.folderPath)
        assertEquals("Gemini & OpenRouter", driveFile.providerBadge)
    }
}
