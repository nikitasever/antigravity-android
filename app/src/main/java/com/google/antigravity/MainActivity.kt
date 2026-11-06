package com.google.antigravity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.antigravity.model.DrawerSection
import com.google.antigravity.ui.design.DesignColors
import com.google.antigravity.ui.MainScreen
import com.google.antigravity.viewmodel.AntigravityViewModel

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_SHORTCUT_NEW_CHAT = "com.google.antigravity.ACTION_SHORTCUT_NEW_CHAT"
        const val ACTION_SHORTCUT_VOICE = "com.google.antigravity.ACTION_SHORTCUT_VOICE"
        const val ACTION_SHORTCUT_PHONE_SEARCH = "com.google.antigravity.ACTION_SHORTCUT_PHONE_SEARCH"
    }

    private val viewModel: AntigravityViewModel by viewModels()
    private var voiceLaunchRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        Thread { publishDynamicAppShortcuts() }.start()
        if (isVoiceLaunchIntent(intent)) voiceLaunchRequest++
        handleIncomingIntent(intent, isInitialCreate = true)

        setContent {
            val colorScheme = darkColorScheme(
                primary = DesignColors.AccentBlue,
                secondary = DesignColors.AccentBlue,
                tertiary = DesignColors.AccentBlue,
                background = DesignColors.Bg,
                surface = DesignColors.Surface,
                onPrimary = Color.White,
                onSecondary = Color.White,
                onBackground = Color(0xFFECECEC),
                onSurface = Color(0xFFECECEC),
                error = DesignColors.AccentCoral
            )

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { _ ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.startLiveVoiceCall(expandOverlay = true)
                }
            }

            val requestVoiceAndStart: () -> Unit = {
                val neededPermissions = mutableListOf<String>()
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    neededPermissions.add(Manifest.permission.RECORD_AUDIO)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }

                if (neededPermissions.isEmpty()) {
                    viewModel.startLiveVoiceCall(expandOverlay = true)
                } else {
                    permissionLauncher.launch(neededPermissions.toTypedArray())
                }
            }

            LaunchedEffect(voiceLaunchRequest) {
                if (voiceLaunchRequest > 0) {
                    requestVoiceAndStart()
                }
            }

            com.google.antigravity.ui.design.FixedDarkAppearance {
            MaterialTheme(colorScheme = colorScheme) {
                MainScreen(
                    viewModel = viewModel,
                    onRequestVoicePermissionAndStart = requestVoiceAndStart
                )
            }
            }
        }
    }

    private var isFirstResume = true

    override fun onResume() {
        super.onResume()
        if (isFirstResume) {
            isFirstResume = false
            return
        }
        viewModel.refreshPermissionState()
        viewModel.syncRemoteSessionsFromPc()
        // OAuth MCP: возврат из браузера — обновить статус авторизации и инструменты.
        viewModel.onAppResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isVoiceLaunchIntent(intent)) voiceLaunchRequest++
        handleIncomingIntent(intent, isInitialCreate = false)
    }

    private fun isVoiceLaunchIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == Intent.ACTION_ASSIST ||
            action == Intent.ACTION_VOICE_COMMAND ||
            action == ACTION_SHORTCUT_VOICE
    }

    /** Отчёт об использовании ярлыка (ранжирование ярлыков Android). */
    private fun reportShortcutUsedIfAvailable(shortcutId: String) {
        try {
            getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(shortcutId)
        } catch (_: Throwable) {
        }
    }

    private fun handleIncomingIntent(intent: Intent?, @Suppress("UNUSED_PARAMETER") isInitialCreate: Boolean) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "com.google.antigravity") {
            intent.data?.let { viewModel.completeMcpOAuth(it) }
            intent.data = null
            return
        }
        when (intent.action) {
            ACTION_SHORTCUT_NEW_CHAT -> {
                intent.action = Intent.ACTION_MAIN
                reportShortcutUsedIfAvailable("shortcut_new_chat")
                viewModel.selectDrawerSection(DrawerSection.CHAT)
                viewModel.createNewRemoteSessionOnPc()
            }
            ACTION_SHORTCUT_PHONE_SEARCH -> {
                intent.action = Intent.ACTION_MAIN
                reportShortcutUsedIfAvailable("shortcut_phone_search")
                viewModel.selectDrawerSection(DrawerSection.PHONE_EXPLORER)
            }
            ACTION_SHORTCUT_VOICE -> {
                intent.action = Intent.ACTION_MAIN
                reportShortcutUsedIfAvailable("shortcut_voice_mode")
                viewModel.selectDrawerSection(DrawerSection.CHAT)
            }
            Intent.ACTION_SEND -> {
                val sharedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                val collectedUris = mutableListOf<Uri>()
                if (sharedUri != null) collectedUris.add(sharedUri)
                val clip = intent.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        val u = clip.getItemAt(i)?.uri
                        if (u != null && u !in collectedUris) collectedUris.add(u)
                    }
                }
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                intent.action = Intent.ACTION_MAIN
                viewModel.handleSharedContentFromExternalApp(collectedUris, sharedText)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
                val collectedUris = uris.toMutableList()
                val clip = intent.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        val u = clip.getItemAt(i)?.uri
                        if (u != null && u !in collectedUris) collectedUris.add(u)
                    }
                }
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                intent.action = Intent.ACTION_MAIN
                viewModel.handleSharedContentFromExternalApp(collectedUris, sharedText)
            }
        }

        // Test-only controls must never be reachable through exported release intents.
        if (!BuildConfig.DEBUG) return

        if (intent.hasExtra("auto_sign_in")) {
            val signIn = intent.getBooleanExtra("auto_sign_in", true)
            intent.removeExtra("auto_sign_in")
            if (signIn) {
                viewModel.signInWithGoogleCompleted(
                    targetMode = com.google.antigravity.model.AgentExecutionTarget.PC_REMOTE_SESSION,
                    openWebConsoleFirst = false
                )
            } else {
                viewModel.signOutGoogle()
            }
        }

        if (intent.hasExtra("force_public_tunnel_only")) {
            val forcePublic = intent.getBooleanExtra("force_public_tunnel_only", false)
            intent.removeExtra("force_public_tunnel_only")
            viewModel.setForcePublicTunnelOnly(forcePublic)
        }

        val bridgeUrlOverride = intent.getStringExtra("pc_bridge_url")
        if (!bridgeUrlOverride.isNullOrBlank()) {
            intent.removeExtra("pc_bridge_url")
            viewModel.updateSettings { it.copy(pcBridgeUrl = bridgeUrlOverride.trim()) }
        }

        val modelOverride = intent.getStringExtra("select_model_id")
        if (!modelOverride.isNullOrBlank()) {
            intent.removeExtra("select_model_id")
            viewModel.setSelectedModel(modelOverride.trim())
        }

        val drawerSec = intent.getStringExtra("select_drawer_section")
        if (!drawerSec.isNullOrBlank()) {
            intent.removeExtra("select_drawer_section")
            runCatching { DrawerSection.valueOf(drawerSec.trim().uppercase()) }
                .onSuccess { viewModel.selectDrawerSection(it) }
        }

        val openSheet = intent.getStringExtra("open_sheet")
        if (!openSheet.isNullOrBlank()) {
            intent.removeExtra("open_sheet")
            viewModel.setDebugSheet(openSheet.trim())
        }

        val connectSessionId = intent.getStringExtra("connect_session_id")
        if (!connectSessionId.isNullOrBlank()) {
            intent.removeExtra("connect_session_id")
            val title = intent.getStringExtra("connect_session_title") ?: connectSessionId
            val existing = viewModel.remoteSessions.value.firstOrNull { it.id == connectSessionId }
            val sessionItem = existing ?: com.google.antigravity.model.RemoteSessionItem(
                id = connectSessionId,
                title = title,
                hostType = if (connectSessionId == "phone-local-storage") "PHONE" else "PC",
                workspacePath = if (connectSessionId == "phone-local-storage") "/storage/emulated/0" else ""
            )
            viewModel.connectToExistingRemoteSession(sessionItem)
        }

        val extAction = intent.getStringExtra("extension_action")
        if (!extAction.isNullOrBlank()) {
            intent.removeExtra("extension_action")
            when (extAction.trim()) {
                "refresh" -> viewModel.refreshExtensionsFromPc()
                "add_demo_mcp" -> {
                    viewModel.addMcpServerOnPc(
                        name = intent.getStringExtra("mcp_name") ?: "ag-workstation-info",
                        command = intent.getStringExtra("mcp_cmd") ?: "C:\\Users\\st811210\\AppData\\Local\\Programs\\nodejs\\node.exe",
                        argsRaw = intent.getStringExtra("mcp_args") ?: "C:\\Users\\st811210\\.gemini\\antigravity\\scratch\\antigravity-android\\pc-bridge\\mcp_sysinfo_server.js"
                    )
                }
                "delete_mcp" -> {
                    val name = intent.getStringExtra("mcp_name") ?: "ag-workstation-info"
                    viewModel.deleteMcpServerOnPc(name)
                }
                "add_demo_skill" -> {
                    viewModel.addSkillOnPc(
                        name = intent.getStringExtra("skill_name") ?: "workstation-diagnostics",
                        description = "Диагностика рабочей станции и проверка статуса туннелей",
                        instructions = "# Диагностика ПК\nИспользуй системные команды для точной проверки процессов."
                    )
                }
            }
        }

        val toggleThinkingLast = intent.getBooleanExtra("toggle_last_thinking", false)
        if (toggleThinkingLast) {
            intent.removeExtra("toggle_last_thinking")
            val lastAgent = viewModel.messages.value.lastOrNull { it.role == com.google.antigravity.model.MessageRole.AGENT }
            if (lastAgent != null) {
                viewModel.toggleThinkingExpanded(lastAgent.id)
            }
        }

        val voiceAction = intent.getStringExtra("voice_action")
        if (!voiceAction.isNullOrBlank()) {
            intent.removeExtra("voice_action")
            when (voiceAction.trim()) {
                "start" -> viewModel.startLiveVoiceCall(expandOverlay = true)
                "barge_in" -> viewModel.voiceEngine.interruptAiAndListenNow()
                "stop" -> viewModel.stopLiveVoiceCall()
                "utterance" -> {
                    val b64 = intent.getStringExtra("voice_utterance_b64").orEmpty()
                    val text = if (b64.isNotBlank()) {
                        String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    } else {
                        intent.getStringExtra("voice_utterance").orEmpty()
                    }
                    if (text.isNotBlank()) {
                        viewModel.startLiveVoiceCall(expandOverlay = true)
                        viewModel.sendUserPrompt(text, viaVoice = true)
                    }
                }
            }
        }

        val promptB64 = intent.getStringExtra("send_prompt_b64")
        val promptPlain = intent.getStringExtra("send_prompt")
        if (!promptB64.isNullOrBlank() || !promptPlain.isNullOrBlank()) {
            intent.removeExtra("send_prompt_b64")
            intent.removeExtra("send_prompt")
            val decoded = if (!promptB64.isNullOrBlank()) {
                String(android.util.Base64.decode(promptB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } else {
                promptPlain.orEmpty()
            }
            val viaVoice = intent.getBooleanExtra("via_voice", false)
            if (decoded.isNotBlank()) {
                viewModel.sendUserPrompt(decoded, viaVoice = viaVoice)
            }
        }
    }

    /**
     * Регистрирует динамические ярлыки приложения (App Shortcuts, Раздел 5.6d):
     * «Новый чат», «Голосовой режим», «Поиск по телефону».
     */
    private fun publishDynamicAppShortcuts() {
        try {
            val shortcutManager = getSystemService(ShortcutManager::class.java) ?: return
            val icon = Icon.createWithResource(this, R.mipmap.ic_launcher)

            val newChatShortcut = ShortcutInfo.Builder(this, "shortcut_new_chat")
                .setShortLabel(getString(R.string.shortcut_new_chat_short))
                .setLongLabel(getString(R.string.shortcut_new_chat_long))
                .setIcon(icon)
                .setRank(0)
                .setIntent(
                    Intent(ACTION_SHORTCUT_NEW_CHAT, null, this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
                .build()

            val voiceShortcut = ShortcutInfo.Builder(this, "shortcut_voice_mode")
                .setShortLabel(getString(R.string.shortcut_voice_short))
                .setLongLabel(getString(R.string.shortcut_voice_long))
                .setIcon(icon)
                .setRank(1)
                .setIntent(
                    Intent(ACTION_SHORTCUT_VOICE, null, this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
                .build()

            val phoneSearchShortcut = ShortcutInfo.Builder(this, "shortcut_phone_search")
                .setShortLabel(getString(R.string.shortcut_phone_search_short))
                .setLongLabel(getString(R.string.shortcut_phone_search_long))
                .setIcon(icon)
                .setRank(2)
                .setIntent(
                    Intent(ACTION_SHORTCUT_PHONE_SEARCH, null, this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
                .build()

            shortcutManager.dynamicShortcuts = listOf(newChatShortcut, voiceShortcut, phoneSearchShortcut)
        } catch (_: Throwable) {
        }
    }
}
