package com.google.antigravity.ui

import com.google.antigravity.ui.design.DesignColors
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.antigravity.device.PhoneResourceController
import com.google.antigravity.model.AVAILABLE_AGY_MODELS
import com.google.antigravity.model.AVAILABLE_VOICE_NAMES
import com.google.antigravity.model.AgentExecutionTarget
import com.google.antigravity.model.matches
import com.google.antigravity.model.AntigravityModelOption
import com.google.antigravity.model.AntigravitySettings
import com.google.antigravity.model.ApprovalDecision
import com.google.antigravity.model.ArtifactDocument
import com.google.antigravity.model.AttachedFileItem
import com.google.antigravity.model.ChatMessage
import com.google.antigravity.model.ContextWindowStatus
import com.google.antigravity.model.DrawerSection
import com.google.antigravity.model.GeneratedMediaAttachment
import com.google.antigravity.model.InstalledPluginItem
import com.google.antigravity.model.MessageRole
import com.google.antigravity.model.OpenRouterCategory
import com.google.antigravity.model.PcMcpServerItem
import com.google.antigravity.model.PcSkillItem
import com.google.antigravity.model.describeMcpStatus
import com.google.antigravity.model.RemoteEnvFilter
import com.google.antigravity.model.RemoteSessionItem
import com.google.antigravity.model.ScratchProjectItem
import com.google.antigravity.model.ScheduledAutomationItem
import com.google.antigravity.model.SwarmAgentNode
import com.google.antigravity.model.SwarmSubAgentItem
import com.google.antigravity.model.ToolCallStatus
import com.google.antigravity.model.TopModeTab
import com.google.antigravity.model.VoiceProvider
import com.google.antigravity.model.VoiceTransportMode
import com.google.antigravity.ui.components.AiPermissionsSheet
import com.google.antigravity.ui.components.AndroidCliAndSandboxSheet
import com.google.antigravity.ui.components.AntigravityGoogleWebConsole
import com.google.antigravity.ui.components.ChatGptMarkdownText
import com.google.antigravity.ui.components.ChatGptStyleGoogleLoginScreen
import com.google.antigravity.ui.components.CodeBackgroundTasksEntryPoint
import com.google.antigravity.ui.components.ContextWindowBadge
import com.google.antigravity.ui.components.ContextWindowSheet
import com.google.antigravity.ui.components.ExtensionsScreen
import com.google.antigravity.ui.components.FloatingVoiceCallPill
import com.google.antigravity.ui.components.GeneratedMediaCard
import com.google.antigravity.ui.components.GoogleDriveSheet
import com.google.antigravity.ui.components.MultiAgentSwarmSheet
import com.google.antigravity.ui.components.OpenRouterHubSheet
import com.google.antigravity.ui.components.OrbitVoiceOverlay
import com.google.antigravity.ui.components.ReferenceDiffCard
import com.google.antigravity.ui.components.SlashAndMentionPopup
import com.google.antigravity.ui.phone.PhoneAssistantScreen
import com.google.antigravity.viewmodel.AntigravityViewModel
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    viewModel: AntigravityViewModel,
    onRequestVoicePermissionAndStart: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val googleProfile by viewModel.googleProfile.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val topModeTab by viewModel.topModeTab.collectAsState()
    val remoteEnvFilter by viewModel.remoteEnvFilter.collectAsState()
    val remoteSessions by viewModel.remoteSessions.collectAsState()
    val scratchProjects by viewModel.scratchProjects.collectAsState()
    val projectWorkspaces by viewModel.projectWorkspaces.collectAsState()
    val chatLibraryPreferences by viewModel.chatLibraryPreferences.collectAsState()
    val temporaryChatActive by viewModel.temporaryChatActive.collectAsState()
    val artifactsLibrary by viewModel.artifactsLibrary.collectAsState()
    val scheduledTasks by viewModel.scheduledTasks.collectAsState()
    val installedPlugins by viewModel.installedPlugins.collectAsState()
    val installedSkills by viewModel.installedSkills.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val mcpToolResult by viewModel.mcpToolResult.collectAsState()
    val isMcpToolCallRunning by viewModel.isMcpToolCallRunning.collectAsState()
    val extensionsError by viewModel.extensionsError.collectAsState()
    val isExtensionsLoading by viewModel.isExtensionsLoading.collectAsState()
    val extensionCatalog by viewModel.extensionCatalog.collectAsState()
    val mcpToolPermissions by viewModel.mcpToolPermissions.collectAsState()
    val skillDetailStates by viewModel.skillDetailStates.collectAsState()
    val skillEditState by viewModel.skillEditState.collectAsState()
    val permissionModeState by viewModel.permissionModeState.collectAsState()
    val pendingAttachments by viewModel.pendingAttachments.collectAsState()
    val pcBridgeStatus by viewModel.pcBridgeStatus.collectAsState()
    val drawerSection by viewModel.drawerSection.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val voiceOverlayActive by viewModel.voiceEngine.isCallActive.collectAsState()
    val voiceOverlayExpanded by viewModel.voiceEngine.isOverlayExpanded.collectAsState()
    val phoneFiles by viewModel.phoneFiles.collectAsState()

    // Round 5 Live StateFlows
    val openRouterModels by viewModel.openRouterModels.collectAsState()
    val openRouterTotalCount by viewModel.openRouterTotalCount.collectAsState()
    val openRouterSyncedAt by viewModel.openRouterSyncedAt.collectAsState()
    val openRouterSyncSource by viewModel.openRouterSyncSource.collectAsState()
    val openRouterCacheStatusText by viewModel.openRouterCacheStatusText.collectAsState()
    val openRouterApiKey by viewModel.openRouterApiKey.collectAsState()
    val isSyncingOpenRouter by viewModel.isSyncingOpenRouter.collectAsState()
    val isGeneratingMedia by viewModel.isGeneratingMedia.collectAsState()
    val swarmAgents by viewModel.swarmAgents.collectAsState()
    val androidCliHistory by viewModel.androidCliHistory.collectAsState()
    val isRunningAndroidCli by viewModel.isRunningAndroidCli.collectAsState()
    val virtualSandboxProject by viewModel.virtualSandboxProject.collectAsState()
    val sandboxTestOutput by viewModel.sandboxTestOutput.collectAsState()
    val aiPermissions by viewModel.aiPermissions.collectAsState()
    val contextWindowStatus by viewModel.contextWindowStatus.collectAsState()
    val lastCompactedSummary by viewModel.lastCompactedSummary.collectAsState()
    val liveTokensPerSecond by viewModel.liveTokensPerSecond.collectAsState()
    val googleDriveStatus by viewModel.googleDriveStatus.collectAsState()
    val googleDriveFiles by viewModel.googleDriveFiles.collectAsState()
    val isSyncingGoogleDrive by viewModel.isSyncingGoogleDrive.collectAsState()
    val activeDebugSheet by viewModel.activeDebugSheet.collectAsState()

    var showModelPickerDialog by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showOpenRouterSheet by remember { mutableStateOf(false) }
    var showGoogleDriveSheet by remember { mutableStateOf(false) }
    var showSwarmSheet by remember { mutableStateOf(false) }
    var showAndroidCliSandboxSheet by remember { mutableStateOf(false) }
    var cliSandboxInitialTab by remember { mutableIntStateOf(0) }
    var showAiPermissionsSheet by remember { mutableStateOf(false) }
    var showContextWindowSheet by remember { mutableStateOf(false) }
    var forceExpandBgTasks by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }

    LaunchedEffect(activeDebugSheet) {
        if (activeDebugSheet.isNotBlank()) {
            showOpenRouterSheet = false
            showGoogleDriveSheet = false
            showSwarmSheet = false
            showAndroidCliSandboxSheet = false
            showAiPermissionsSheet = false
            showContextWindowSheet = false
            forceExpandBgTasks = false
            when (activeDebugSheet.lowercase()) {
                "openrouter" -> {
                    drawerState.close()
                    viewModel.syncOpenRouterCatalog(forceRefresh = false)
                    showOpenRouterSheet = true
                }
                "gdrive" -> {
                    drawerState.close()
                    viewModel.refreshGoogleDriveFiles()
                    showGoogleDriveSheet = true
                }
                "swarm" -> {
                    drawerState.close()
                    showSwarmSheet = true
                }
                "expand_bg" -> {
                    drawerState.close()
                    forceExpandBgTasks = true
                }
                "context" -> {
                    drawerState.close()
                    viewModel.refreshContextWindowStatus()
                    showContextWindowSheet = true
                }
                "cli" -> {
                    drawerState.close()
                    cliSandboxInitialTab = 0
                    showAndroidCliSandboxSheet = true
                }
                "sandbox" -> {
                    drawerState.close()
                    cliSandboxInitialTab = 1
                    showAndroidCliSandboxSheet = true
                }
                "permissions" -> {
                    drawerState.close()
                    showAiPermissionsSheet = true
                }
                "drawer" -> {
                    drawerState.open()
                }
                "close", "chat" -> {
                    drawerState.close()
                }
            }
            viewModel.setDebugSheet("")
        }
    }

    // Системные лончеры Android для загрузки любых файлов, фотографий и съёмки камерой
    val multipleFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri -> viewModel.attachFileFromUri(uri) }
    }

    val photoGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri -> viewModel.attachFileFromUri(uri) }
    }

    val cameraPreviewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            viewModel.attachCameraBitmap(bitmap)
        }
    }

    // Документ контекста проекта: системный выбор файла привязывается к целевому проекту.
    var projectDocTargetId by remember { mutableStateOf<String?>(null) }
    val projectDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = projectDocTargetId
        projectDocTargetId = null
        if (uri != null && target != null) {
            viewModel.addProjectDocumentFromUri(target, uri)
        }
    }

    if (!googleProfile.isSignedIn) {
        ChatGptStyleGoogleLoginScreen(
            pcInstanceName = pcBridgeStatus.instanceName,
            isPcBridgeOnline = pcBridgeStatus.connected,
            onOpenGoogleOAuthWeb = {
                viewModel.signInWithGoogleCompleted(
                    targetMode = AgentExecutionTarget.GOOGLE_WEB_CONSOLE,
                    openWebConsoleFirst = true
                )
            },
            onConnectPcSessionNow = {
                viewModel.signInWithGoogleCompleted(
                    targetMode = AgentExecutionTarget.PC_REMOTE_SESSION,
                    openWebConsoleFirst = false
                )
            },
            onStartPhoneLocalAgent = {
                viewModel.signInWithGoogleCompleted(
                    targetMode = AgentExecutionTarget.PHONE_LOCAL_RESOURCES,
                    openWebConsoleFirst = false
                )
            }
        )
        return
    }

    BackHandler(enabled = drawerSection != DrawerSection.CHAT || topModeTab != TopModeTab.CHAT) {
        viewModel.selectDrawerSection(DrawerSection.CHAT)
        viewModel.selectTopModeTab(TopModeTab.CHAT)
    }

    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            viewModel.syncRemoteSessionsFromPc()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (voiceOverlayActive && voiceOverlayExpanded) Color.Black else DesignColors.Bg)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ChatGptExactNavigationDrawer(
                    viewModel = viewModel,
                    sessions = remoteSessions,
                    activeSessionId = settings.activeConversationId,
                    openRouterTotalCount = openRouterTotalCount.coerceAtLeast(openRouterModels.size),
                    googleDriveFilesCount = googleDriveFiles.size,
                    onRefreshSessions = {
                        viewModel.syncRemoteSessionsFromPc()
                    },
                    onSelectMenuSection = { section ->
                        viewModel.selectDrawerSection(section)
                        scope.launch { drawerState.close() }
                    },
                    onOpenOpenRouterHub = {
                        scope.launch { drawerState.close() }
                        viewModel.syncOpenRouterCatalog(forceRefresh = false)
                        showOpenRouterSheet = true
                    },
                    onOpenGoogleDriveHub = {
                        scope.launch { drawerState.close() }
                        viewModel.refreshGoogleDriveFiles()
                        showGoogleDriveSheet = true
                    },
                    onOpenCliSandboxHub = { tab ->
                        scope.launch { drawerState.close() }
                        cliSandboxInitialTab = tab
                        showAndroidCliSandboxSheet = true
                    },
                    onOpenPermissionsHub = {
                        scope.launch { drawerState.close() }
                        showAiPermissionsSheet = true
                    },
                    onOpenContextHub = {
                        scope.launch { drawerState.close() }
                        viewModel.refreshContextWindowStatus()
                        showContextWindowSheet = true
                    },
                    onSelectSession = { session ->
                        viewModel.connectToExistingRemoteSession(session)
                        scope.launch { drawerState.close() }
                    },
                    onNewChatClick = {
                        viewModel.startNewChat()
                        scope.launch { drawerState.close() }
                    },
                    onProfileClick = {
                        viewModel.selectDrawerSection(DrawerSection.SETTINGS)
                        scope.launch { drawerState.close() }
                    },
                    projects = scratchProjects,
                    onSelectProject = { proj ->
                        viewModel.openScratchProjectInChat(proj)
                        scope.launch { drawerState.close() }
                    },
                    onTemporaryChat = {
                        viewModel.startTemporaryChat()
                        scope.launch { drawerState.close() }
                    }
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DesignColors.Bg)
            ) {
                FloatingVoiceCallPill(
                    voiceEngine = viewModel.voiceEngine,
                    onEndCall = { viewModel.stopLiveVoiceCall() }
                )

                // Временный чат (как в ChatGPT): переписка не сохраняется.
                if (temporaryChatActive &&
                    (drawerSection == DrawerSection.CHAT || drawerSection == DrawerSection.PROJECTS)
                ) {
                    Surface(color = Color(0xFF1E2A3A), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.VisibilityOff, contentDescription = "Временный чат",
                                tint = Color(0xFF93C5FD), modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Временный чат — переписка не сохраняется и не синхронизируется",
                                color = Color(0xFF93C5FD), fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Работа без сети: после реальной проверки мост недоступен — честный
                // баннер с повтором подключения; переписка продолжает храниться на телефоне.
                if (pcBridgeStatus.checked && !pcBridgeStatus.connected &&
                    settings.executionTarget == AgentExecutionTarget.PC_REMOTE_SESSION &&
                    (drawerSection == DrawerSection.CHAT || drawerSection == DrawerSection.PROJECTS)
                ) {
                    Surface(color = Color(0xFF3A2E14), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CloudOff, contentDescription = "Мост ПК недоступен",
                                tint = Color(0xFFFFB74D), modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Мост ПК недоступен — переписка сохраняется на телефоне",
                                color = Color(0xFFFFB74D), fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.syncRemoteSessionsFromPc() }) {
                                Text("Повторить", color = Color(0xFFFFB74D))
                            }
                        }
                    }
                }

                when (drawerSection) {
                    DrawerSection.GOOGLE_WEB -> {
                        AntigravityGoogleWebConsole(
                            pcInstanceName = pcBridgeStatus.instanceName,
                            pcBridgeUrl = settings.pcBridgeUrl,
                            activeConversationId = settings.activeConversationId,
                            initialUrl = settings.antigravityGatewayUrl,
                            onGoogleLoginDetected = {
                                viewModel.signInWithGoogleCompleted(
                                    targetMode = AgentExecutionTarget.PC_REMOTE_SESSION,
                                    openWebConsoleFirst = false
                                )
                            },
                            onSwitchToNativeChat = {
                                viewModel.selectDrawerSection(DrawerSection.CHAT)
                            }
                        )
                    }

                    DrawerSection.PHONE_EXPLORER -> {
                        PhoneAssistantScreen(
                            viewModel = viewModel,
                            onBack = { viewModel.selectDrawerSection(DrawerSection.CHAT) },
                            onAskInChat = { prompt ->
                                viewModel.sendUserPrompt(prompt, viaVoice = false)
                            }
                        )
                    }

                    DrawerSection.PROJECTS -> {
                        val linkedChatTitles = remember(remoteSessions, chatLibraryPreferences, settings.activeConversationId, settings.activeSessionTitle) {
                            buildMap {
                                remoteSessions.forEach { put(it.id, it.title) }
                                chatLibraryPreferences.forEach { (id, p) -> if (p.title.isNotBlank()) put(id, p.title) }
                                if (settings.activeConversationId.isNotBlank()) {
                                    put(settings.activeConversationId, settings.activeSessionTitle)
                                }
                            }
                        }
                        com.google.antigravity.ui.components.ProjectWorkspaceScreen(
                            projects = scratchProjects,
                            workspaces = projectWorkspaces,
                            activeChatId = settings.activeConversationId,
                            linkedChatTitles = linkedChatTitles,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenProjectChat = { proj -> viewModel.openScratchProjectInChat(proj) },
                            onSetInstructions = { projectId, text -> viewModel.setProjectInstructions(projectId, text) },
                            onPickDocument = { projectId ->
                                projectDocTargetId = projectId
                                projectDocLauncher.launch(arrayOf("*/*"))
                            },
                            onRemoveDocument = { projectId, docId -> viewModel.removeProjectDocument(projectId, docId) },
                            onLinkActiveChat = { projectId ->
                                viewModel.linkChatToProject(projectId, settings.activeConversationId)
                            },
                            onUnlinkChat = { projectId, chatId -> viewModel.unlinkChatFromProject(projectId, chatId) },
                            onOpenLinkedChat = { chatId, title -> viewModel.openLinkedProjectChat(chatId, title) }
                        )
                    }

                    DrawerSection.LIBRARY -> {
                        ChatGptLibraryScreen(
                            artifacts = artifactsLibrary,
                            viewModel = viewModel,
                            phoneFiles = phoneFiles,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenPhoneExplorer = { viewModel.selectDrawerSection(DrawerSection.PHONE_EXPLORER) },
                            onPickFileFromSystem = { multipleFilesLauncher.launch(arrayOf("*/*")) }
                        )
                    }

                    DrawerSection.IMAGES -> {
                        ChatGptImagesScreen(
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onPickPhoto = { photoGalleryLauncher.launch("image/*") },
                            onTakeCameraPhoto = { cameraPreviewLauncher.launch(null) },
                            onSendImagePrompt = { prompt ->
                                viewModel.sendUserPrompt(prompt, viaVoice = false)
                            }
                        )
                    }

                    DrawerSection.SCHEDULED -> {
                        ChatGptScheduledScreen(
                            tasks = scheduledTasks,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onToggleTask = { id -> viewModel.toggleScheduledTask(id) },
                            onAddTask = { title, cron, cmd -> viewModel.addScheduledTask(title, cron, cmd) },
                            onRunTaskNow = { task ->
                                viewModel.sendUserPrompt(task.promptCommand, viaVoice = false)
                            }
                        )
                    }

                    DrawerSection.PLUGINS -> {
                        // Этап 6: единый интерфейс расширений — Подключения /
                        // Навыки / Плагины; внутри Мои / Каталог с поиском.
                        ExtensionsScreen(
                            plugins = installedPlugins,
                            skills = installedSkills,
                            mcpServers = mcpServers,
                            catalog = extensionCatalog,
                            errorText = extensionsError,
                            isLoading = isExtensionsLoading,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onRefresh = { viewModel.refreshExtensionsFromPc() },
                            onDismissError = { viewModel.clearExtensionsError() },
                            onTogglePlugin = { id -> viewModel.togglePluginEnabled(id) },
                            onUpdatePlugin = { id -> viewModel.updatePluginOnPc(id) },
                            onAddPlugin = { src, desc -> viewModel.addPluginOnPc(src, desc) },
                            onDeletePlugin = { id -> viewModel.deletePluginOnPc(id) },
                            onToggleSkill = { id -> viewModel.toggleSkillEnabled(id) },
                            onAddSkill = { name, desc, instr -> viewModel.addSkillOnPc(name, desc, instr) },
                            onDeleteSkill = { id -> viewModel.deleteSkill(id) },
                            onInsertSkillIntoChat = { name ->
                                viewModel.setComposerDraft("/$name ")
                                viewModel.selectDrawerSection(DrawerSection.CHAT)
                            },
                            onToggleMcp = { id -> viewModel.toggleMcpServerEnabled(id) },
                            onAddMcp = { name, cmd, args -> viewModel.addMcpServerOnPc(name, cmd, args) },
                            onDeleteMcp = { id -> viewModel.deleteMcpServerOnPc(id) },
                            onCheckMcp = { id -> viewModel.checkMcpServer(id) },
                            onLoginMcp = { id -> viewModel.loginMcpServer(id) },
                            onCallMcpTool = { id, tool -> viewModel.callMcpTool(id, tool) },
                            mcpToolResult = mcpToolResult,
                            isMcpToolCallRunning = isMcpToolCallRunning,
                            onDismissToolResult = { viewModel.dismissMcpToolResult() },
                            onInstallCatalogItem = { item -> viewModel.installCatalogExtension(item) },
                            mcpToolPermissions = mcpToolPermissions,
                            onLoadMcpToolPermissions = { id -> viewModel.loadMcpToolPermissions(id) },
                            onSetMcpToolPermission = { id, tool, permission, scope, comment ->
                                viewModel.setMcpToolPermission(id, tool, permission, scope, comment)
                            },
                            onSetAllMcpToolPermissions = { id, permission, scope, comment ->
                                viewModel.setAllMcpToolPermissions(id, permission, scope, comment)
                            },
                            mcpScopeSessionId = settings.activeConversationId,
                            mcpScopeTaskId = viewModel.currentSubagentTaskId,
                            permissionModeState = permissionModeState,
                            onLoadPermissionMode = { viewModel.loadPermissionMode() },
                            onSetPermissionMode = { mode -> viewModel.setPermissionMode(mode) },
                            skillDetailStates = skillDetailStates,
                            onLoadSkillDetail = { name -> viewModel.loadSkillDetail(name) },
                            skillEditState = skillEditState,
                            onStartSkillEdit = { id -> viewModel.startSkillEdit(id) },
                            onSkillEditNameChange = { name -> viewModel.changeSkillEditName(name) },
                            onSkillEditContentChange = { content -> viewModel.changeSkillEditContent(content) },
                            onSaveSkillEdit = { id, newName, content ->
                                viewModel.saveSkillEdit(id, newName, content)
                            }
                        )
                    }

                    DrawerSection.SETTINGS -> {
                        SettingsModalScreen(
                            viewModel = viewModel,
                            settings = settings,
                            googleEmail = googleProfile.email,
                            pcInstanceName = pcBridgeStatus.instanceName,
                            onOpenPermissions = { showAiPermissionsSheet = true },
                            onUpdate = { transform -> viewModel.updateSettings(transform) },
                            onOpenGoogleWeb = { viewModel.selectDrawerSection(DrawerSection.GOOGLE_WEB) },
                            onSignOut = { viewModel.signOutGoogle() },
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onBack = { viewModel.selectDrawerSection(DrawerSection.CHAT) }
                        )
                    }

                    else -> {
                        if (topModeTab == TopModeTab.CHAT && drawerSection == DrawerSection.CHAT) {
                            ChatGptMainChatScreen(
                                viewModel = viewModel,
                                topModeTab = topModeTab,
                                messages = messages,
                                pendingAttachments = pendingAttachments,
                                activeSessionTitle = settings.activeSessionTitle,
                                selectedModelId = settings.selectedModelId,
                                availableModels = availableModels,
                                contextWindowStatus = contextWindowStatus,
                                liveTokensPerSecond = liveTokensPerSecond,
                                swarmAgents = swarmAgents,
                                forceExpandBgTasks = forceExpandBgTasks,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                onSelectTopTab = { tab -> viewModel.selectTopModeTab(tab) },
                                onOpenModelPicker = { showModelPickerDialog = true },
                                onNewChat = { viewModel.startNewChat() },
                                onOpenAttachmentSheet = { showAttachmentSheet = true },
                                onRemoveAttachment = { id -> viewModel.removePendingAttachment(id) },
                                onStartVoiceLive = onRequestVoicePermissionAndStart,
                                onOpenContextSheet = {
                                    viewModel.refreshContextWindowStatus()
                                    showContextWindowSheet = true
                                },
                                onOpenOpenRouterSheet = {
                                    viewModel.syncOpenRouterCatalog(forceRefresh = false)
                                    showOpenRouterSheet = true
                                },
                                onOpenGoogleDriveSheet = {
                                    viewModel.refreshGoogleDriveFiles()
                                    showGoogleDriveSheet = true
                                },
                                onOpenSwarmSheet = { showSwarmSheet = true; viewModel.refreshSwarmAgents() },
                                onOpenCliSandboxSheet = { tab ->
                                    cliSandboxInitialTab = tab
                                    showAndroidCliSandboxSheet = true
                                },
                                onOpenPermissionsSheet = { showAiPermissionsSheet = true }
                            )
                        } else {
                            ChatGptRemoteSessionsCodexScreen(
                                viewModel = viewModel,
                                topModeTab = topModeTab,
                                messages = messages,
                                selectedFilter = remoteEnvFilter,
                                sessions = remoteSessions,
                                pendingAttachments = pendingAttachments,
                                searchQuery = searchQuery,
                                showSearchBar = showSearchBar,
                                contextWindowStatus = contextWindowStatus,
                                openRouterTotalCount = openRouterTotalCount.coerceAtLeast(openRouterModels.size),
                                swarmAgents = swarmAgents,
                                onSearchQueryChange = { searchQuery = it },
                                onToggleSearch = { showSearchBar = !showSearchBar },
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                onSelectTopTab = { tab -> viewModel.selectTopModeTab(tab) },
                                onOpenModelPicker = { showModelPickerDialog = true },
                                onSelectFilter = { viewModel.selectRemoteEnvFilter(it) },
                                onConnectSession = { session ->
                                    viewModel.connectToExistingRemoteSession(session)
                                },
                                onOpenAttachmentSheet = { showAttachmentSheet = true },
                                onRemoveAttachment = { id -> viewModel.removePendingAttachment(id) },
                                onStartVoiceLive = onRequestVoicePermissionAndStart,
                                onOpenContextSheet = {
                                    viewModel.refreshContextWindowStatus()
                                    showContextWindowSheet = true
                                },
                                onOpenOpenRouterSheet = {
                                    viewModel.syncOpenRouterCatalog(forceRefresh = false)
                                    showOpenRouterSheet = true
                                },
                                onOpenGoogleDriveSheet = {
                                    viewModel.refreshGoogleDriveFiles()
                                    showGoogleDriveSheet = true
                                },
                                onOpenSwarmSheet = { showSwarmSheet = true; viewModel.refreshSwarmAgents() },
                                onOpenCliSandboxSheet = { tab ->
                                    cliSandboxInitialTab = tab
                                    showAndroidCliSandboxSheet = true
                                },
                                onOpenPermissionsSheet = { showAiPermissionsSheet = true }
                            )
                        }
                    }
                }
            }
        }

        // Модальное меню прикрепления файлов и фото (кнопка `+` как в ChatGPT)
        if (showAttachmentSheet) {
            ChatGptAttachmentPickerDialog(
                onTakePhoto = {
                    showAttachmentSheet = false
                    cameraPreviewLauncher.launch(null)
                },
                onPickPhotos = {
                    showAttachmentSheet = false
                    photoGalleryLauncher.launch("image/*")
                },
                onPickAnyFiles = {
                    showAttachmentSheet = false
                    multipleFilesLauncher.launch(arrayOf("*/*"))
                },
                onOpenPhoneStorage = {
                    showAttachmentSheet = false
                    viewModel.selectDrawerSection(DrawerSection.PHONE_EXPLORER)
                },
                // PLUS_MENU («как у них»): «Упомянуть…» открывает палитру @
                // через триггер «@» в черновике.
                onMention = {
                    showAttachmentSheet = false
                    viewModel.setComposerDraft(viewModel.composerDraft.value + "@")
                },
                onDismiss = { showAttachmentSheet = false }
            )
        }

        if (showModelPickerDialog) {
            ModelAndModeSelectorDialog(
                settings = settings,
                availableModels = availableModels,
                openRouterTotalCount = openRouterTotalCount.coerceAtLeast(openRouterModels.size),
                onSelectTarget = { target ->
                    viewModel.setExecutionTarget(target)
                    showModelPickerDialog = false
                },
                onSelectModel = { modelId ->
                    viewModel.setSelectedModel(modelId)
                    showModelPickerDialog = false
                },
                onOpenOpenRouterCatalog = {
                    showModelPickerDialog = false
                    viewModel.syncOpenRouterCatalog(forceRefresh = false)
                    showOpenRouterSheet = true
                },
                onSwitchToChatTab = {
                    viewModel.selectTopModeTab(TopModeTab.CHAT)
                    showModelPickerDialog = false
                },
                onDismiss = { showModelPickerDialog = false }
            )
        }

        if (showContextWindowSheet) {
            ContextWindowSheet(
                status = contextWindowStatus,
                lastCompactedSummary = lastCompactedSummary,
                onDismiss = { showContextWindowSheet = false },
                onResetKeepMessages = {
                    viewModel.resetSessionContextWindow(clearMessages = false)
                },
                onResetClearMessages = {
                    viewModel.resetSessionContextWindow(clearMessages = true)
                },
                onCompactContext = {
                    viewModel.compactSessionContextWindow()
                },
                onClearToolOutputs = {
                    viewModel.clearToolOutputsFromContext()
                }
            )
        }

        if (showOpenRouterSheet) {
            OpenRouterHubSheet(
                models = openRouterModels,
                totalCount = openRouterTotalCount,
                syncedAt = openRouterSyncedAt,
                syncSource = openRouterSyncSource,
                cacheStatusText = openRouterCacheStatusText,
                selectedModelId = settings.selectedModelId,
                openRouterApiKey = openRouterApiKey,
                isSyncing = isSyncingOpenRouter,
                isGeneratingMedia = isGeneratingMedia,
                onDismiss = { showOpenRouterSheet = false },
                onSyncNow = { viewModel.syncOpenRouterCatalog(forceRefresh = true) },
                onSaveApiKey = { key -> viewModel.saveOpenRouterApiKey(key) },
                onSelectModel = { model ->
                    viewModel.selectOpenRouterModel(model)
                },
                onSelectCustomModel = { customId ->
                    viewModel.selectCustomOpenRouterModelId(customId)
                },
                onGenerateMedia = { prompt, modelId, isVideo ->
                    viewModel.generateOpenRouterMediaInChat(prompt = prompt, modelId = modelId, isVideo = isVideo)
                }
            )
        }

        if (showGoogleDriveSheet) {
            GoogleDriveSheet(
                status = googleDriveStatus,
                files = googleDriveFiles,
                isSyncing = isSyncingGoogleDrive,
                onDismiss = { showGoogleDriveSheet = false },
                onRefresh = { viewModel.refreshGoogleDriveFiles() },
                onCreateFile = { name, content, prompt, engine ->
                    viewModel.createGoogleDriveFile(name, content, prompt, engine, insertIntoChat = false)
                },
                onDeleteFile = { fileName -> viewModel.deleteGoogleDriveFile(fileName) },
                onInsertInChat = { mentionText ->
                    viewModel.sendUserPrompt(
                        "${mentionText.trim()} Проанализируй содержимое этого облачного файла из Google Диска (/AntigravityCloud)",
                        viaVoice = false
                    )
                    showGoogleDriveSheet = false
                }
            )
        }

        if (showSwarmSheet) {
            val swarmPanelState by viewModel.swarmPanelState.collectAsState()
            val swarmOwnTaskActive by viewModel.isSwarmTaskActive.collectAsState()
            MultiAgentSwarmSheet(
                agents = swarmAgents,
                panelState = swarmPanelState,
                onDismiss = { showSwarmSheet = false },
                onCancelTask = viewModel::cancelSubagentAnalysis,
                onDispatchCodeTask = { viewModel.dispatchSwarmTask(it, prepareChanges = true) },
                onDispatchSwarmTask = { taskPrompt ->
                    viewModel.dispatchSwarmTask(taskPrompt)
                },
                onInsertMention = { mentionText ->
                    viewModel.sendUserPrompt("${mentionText.trim()} Доложи текущий статус", viaVoice = false)
                },
                isOwnTaskActive = swarmOwnTaskActive
            )
        }

        if (showAndroidCliSandboxSheet) {
            AndroidCliAndSandboxSheet(
                cliHistory = androidCliHistory,
                isRunningCli = isRunningAndroidCli,
                sandboxProject = virtualSandboxProject,
                sandboxTestOutput = sandboxTestOutput,
                onDismiss = { showAndroidCliSandboxSheet = false },
                onRunCliCommand = { cmd -> viewModel.runAndroidCliCommand(cmd) },
                onClearCli = { viewModel.clearAndroidCliHistory() },
                onRunSandboxTests = { viewModel.runVirtualSandboxTests() },
                onCreateSnapshot = { viewModel.createVirtualSandboxSnapshot() },
                onResetSandbox = { viewModel.resetVirtualSandbox() },
                initialTab = cliSandboxInitialTab
            )
        }

        if (showAiPermissionsSheet) {
            AiPermissionsSheet(
                config = aiPermissions,
                onDismiss = { showAiPermissionsSheet = false },
                onUpdateConfig = { transform -> viewModel.updateAiPermissions(transform) },
                onApplyPreset = { preset -> viewModel.applyAiPermissionsPreset(preset) }
            )
        }

        val voiceDraft by viewModel.composerDraft.collectAsState()
        OrbitVoiceOverlay(
            voiceEngine = viewModel.voiceEngine,
            visualizerStyle = settings.voiceVisualizerStyle,
            transportMode = settings.voiceTransportMode,
            onCycleVisualizerStyle = { viewModel.cycleVisualizerStyle() },
            onEndCall = { viewModel.stopLiveVoiceCall() },
            textDraft = voiceDraft,
            onTextDraftChange = { viewModel.setComposerDraft(it) },
            onSendText = { viewModel.sendUserPrompt(it) },
            onAddAttachment = {
                viewModel.voiceEngine.setOverlayExpanded(false)
                showAttachmentSheet = true
            }
        )
    }
}

/**
 * Главный экран Чата 1:1 как в официальном ChatGPT Android 1.2026.293 и Claude Android 1.261029.20:
 * - Чистый верхний бар `Чат | Работа` + выбор модели `Antigravity ▾` с компактным индикатором контекста и скорости `⚡ ток/с` (без перегруженных рядов плашек сверху — Раздел 11.1)
 * - Компактная плашка `CodeBackgroundTasksEntryPoint` в правом нижнем углу прямо над строкой ввода (Разделы 11.2, 11.6, 11.7)
 * - Нижняя капсула `+ Спросить Antigravity 🎤 |||`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatGptMainChatScreen(
    viewModel: AntigravityViewModel,
    topModeTab: TopModeTab,
    messages: List<ChatMessage>,
    pendingAttachments: List<AttachedFileItem>,
    activeSessionTitle: String,
    selectedModelId: String,
    availableModels: List<AntigravityModelOption>,
    contextWindowStatus: ContextWindowStatus,
    liveTokensPerSecond: Float,
    swarmAgents: List<SwarmAgentNode>,
    forceExpandBgTasks: Boolean = false,
    onOpenDrawer: () -> Unit,
    onSelectTopTab: (TopModeTab) -> Unit,
    onOpenModelPicker: () -> Unit,
    onNewChat: () -> Unit,
    onOpenAttachmentSheet: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onStartVoiceLive: () -> Unit,
    onOpenContextSheet: () -> Unit,
    onOpenOpenRouterSheet: () -> Unit,
    onOpenGoogleDriveSheet: () -> Unit,
    onOpenSwarmSheet: () -> Unit,
    onOpenCliSandboxSheet: (Int) -> Unit,
    onOpenPermissionsSheet: () -> Unit
) {
    val listState = rememberLazyListState()
    val chatSettings by viewModel.settings.collectAsState()
    val conversationId = chatSettings.activeConversationId
    val navigationScope = rememberCoroutineScope()
    var showChatMenu by remember(conversationId) { mutableStateOf(false) }
    var showOutline by remember(conversationId) { mutableStateOf(false) }
    var showSearch by remember(conversationId) { mutableStateOf(false) }
    var searchQuery by remember(conversationId) { mutableStateOf("") }
    var searchPosition by remember(conversationId) { mutableIntStateOf(0) }
    val searchMatches = remember(messages, searchQuery) {
        if (searchQuery.isBlank()) emptyList() else messages.indices.filter { index ->
            messages[index].content.contains(searchQuery.trim(), true) ||
                messages[index].attachments.any { it.name.contains(searchQuery.trim(), true) }
        }
    }

    val openRouterModels by viewModel.openRouterModels.collectAsState()
    val isAnyMessageStreaming = remember(messages) {
        messages.any { it.isStreaming }
    }
    val isOpenRouterModel = remember(selectedModelId) {
        selectedModelId.contains("/")
    }
    val activeOrModel = remember(selectedModelId, openRouterModels) {
        openRouterModels.find { it.id == selectedModelId }
    }
    val isVideoModel = remember(selectedModelId, activeOrModel) {
        val lower = selectedModelId.lowercase()
        activeOrModel?.categories?.contains(OpenRouterCategory.VIDEO_GEN) == true ||
            Regex("\\b(veo|sora|kling|runway|luma|ray-2|pika|wan|hunyuan-video|minimax/video|minimax-video|cogvideo|mochi|seedance|vidu|hailuo|video-01)\\b").containsMatchIn(lower) ||
            lower.contains("video")
    }
    val isImageModel = remember(selectedModelId, activeOrModel, isVideoModel) {
        val lower = selectedModelId.lowercase()
        !isVideoModel && (
            activeOrModel?.categories?.contains(OpenRouterCategory.IMAGE_GEN) == true ||
                Regex("(flux|stable-diffusion|sdxl|dall-e|ideogram|recraft|imagen|seedream|midjourney|playground|gpt-image|riverflow)").containsMatchIn(lower)
            )
    }
    val isReasoningModel = remember(selectedModelId, activeOrModel) {
        val lower = selectedModelId.lowercase()
        activeOrModel?.categories?.contains(OpenRouterCategory.REASONING) == true ||
            Regex("(thinking|reason|r1|o1|o3|o4|qwq)").containsMatchIn(lower)
    }
    val isVisionModel = remember(selectedModelId, activeOrModel) {
        activeOrModel?.categories?.contains(OpenRouterCategory.VISION) == true
    }
    val shortModelSubtitle = remember(selectedModelId, availableModels, activeOrModel) {
        val modelObj = (availableModels.ifEmpty { AVAILABLE_AGY_MODELS }).find { it.id == selectedModelId }
        val rawName = activeOrModel?.name ?: modelObj?.displayName ?: selectedModelId.substringAfter('/')
        rawName
            .substringAfter(": ")
            .replace("Gemini ", "")
            .replace(" (High)", "")
            .replace(" (Medium)", " M")
            .replace(" (Low)", " L")
            .replace(" (Fast)", " Fast")
            .trim()
            .take(24)
    }
    val adaptiveInputPlaceholder = remember(isOpenRouterModel, isVideoModel, isImageModel, shortModelSubtitle) {
        when {
            isVideoModel -> "Опишите видео для генерации ($shortModelSubtitle)…"
            isImageModel -> "Опишите изображение для генерации ($shortModelSubtitle)…"
            isOpenRouterModel -> "Спросить $shortModelSubtitle"
            else -> "Спросить Antigravity"
        }
    }
    val adaptiveModelModeHint = remember(isOpenRouterModel, isVideoModel, isImageModel, isReasoningModel, isVisionModel, shortModelSubtitle) {
        when {
            isVideoModel -> "$shortModelSubtitle • Режим генерации видео (MP4)"
            isImageModel -> "$shortModelSubtitle • Режим генерации изображений (PNG)"
            isOpenRouterModel && isReasoningModel && isVisionModel -> "$shortModelSubtitle • Reasoning & Vision"
            isOpenRouterModel && isReasoningModel -> "$shortModelSubtitle • Глубокое рассуждение (Reasoning)"
            isOpenRouterModel && isVisionModel -> "$shortModelSubtitle • Мультимодальный чат (Vision)"
            isOpenRouterModel -> "$shortModelSubtitle • Текстовый чат и инструменты"
            else -> ""
        }
    }
    val compactContextLabel = remember(contextWindowStatus) {
        val usedK = (contextWindowStatus.usedTokens / 1000.0)
        val maxM = (contextWindowStatus.maxTokens / 1_000_000.0).coerceAtLeast(0.1)
        val usedStr = if (usedK >= 1.0) String.format(Locale.US, "%.1fK", usedK) else "${contextWindowStatus.usedTokens}"
        val maxStr = if (maxM >= 1.0) String.format(Locale.US, "%.0fM", maxM) else "${contextWindowStatus.maxTokens / 1000}K"
        "$usedStr/$maxStr"
    }

    var followLatest by remember(conversationId) { mutableStateOf(true) }
    val isDragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragging) { if (isDragging) followLatest = false }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { if (!it) followLatest = true }
    }
    LaunchedEffect(searchQuery, searchPosition) {
        searchMatches.getOrNull(searchPosition.coerceIn(0, (searchMatches.size - 1).coerceAtLeast(0)))?.let {
            followLatest = false
            listState.scrollToItem(it)
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull(), conversationId) {
        if (messages.isNotEmpty() && followLatest && !listState.isScrollInProgress) {
            listState.scrollToItem(messages.size - 1, Int.MAX_VALUE)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DesignColors.Bg)
    ) {
        com.google.antigravity.ui.components.ConversationHeader(
            workSelected = topModeTab == TopModeTab.WORK_SESSIONS,
            hasConversation = messages.isNotEmpty(),
            title = chatSettings.activeSessionTitle.ifBlank {
                messages.firstOrNull { it.role == MessageRole.USER }?.content.orEmpty().take(80)
            },
            executionLabel = when (chatSettings.executionTarget) {
                AgentExecutionTarget.PHONE_LOCAL_RESOURCES -> "Телефон"
                AgentExecutionTarget.PC_REMOTE_SESSION -> "ПК"
                AgentExecutionTarget.GOOGLE_WEB_CONSOLE -> "Веб"
            },
            modelName = activeOrModel?.name
                ?: availableModels.firstOrNull { it.id == selectedModelId }?.displayName
                ?: selectedModelId.substringAfter('/'),
            contextLabel = compactContextLabel,
            onDrawer = onOpenDrawer,
            onSelectWork = { onSelectTopTab(if (it) TopModeTab.WORK_SESSIONS else TopModeTab.CHAT) },
            onNewChat = onNewChat,
            onModel = onOpenModelPicker,
            onContext = onOpenContextSheet,
            onSearch = { showSearch = true },
            onOutline = { showOutline = true }
        )
        if (showSearch) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it; searchPosition = 0 },
                    placeholder = { Text("Поиск в чате") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    trailingIcon = { Text(if (searchMatches.isEmpty()) "0" else "${searchPosition.coerceAtMost(searchMatches.lastIndex) + 1}/${searchMatches.size}",
                        color = Color(0xFF9E9E9E), fontSize = 12.sp) })
                IconButton(enabled = searchMatches.isNotEmpty(), onClick = {
                    searchPosition = (searchPosition - 1 + searchMatches.size) % searchMatches.size
                }) { Icon(Icons.Default.ExpandLess, "Предыдущее совпадение", tint = Color(0xFFB4B4B4)) }
                IconButton(enabled = searchMatches.isNotEmpty(), onClick = {
                    searchPosition = (searchPosition + 1) % searchMatches.size
                }) { Icon(Icons.Default.ExpandMore, "Следующее совпадение", tint = Color(0xFFB4B4B4)) }
                IconButton(onClick = { showSearch = false; searchQuery = "" }) {
                    Icon(Icons.Default.Close, "Закрыть поиск", tint = Color(0xFFB4B4B4))
                }
            }
        }
        if (showOutline) {
            ModalBottomSheet(onDismissRequest = { showOutline = false }, containerColor = Color(0xFF1C1C1E), contentColor = Color.White) {
                com.google.antigravity.ui.components.SheetHeader("Содержание разговора", { showOutline = false })
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    val turns = messages.withIndex().filter { it.value.role == MessageRole.USER }
                    if (turns.isEmpty()) item { Text("В этом чате пока нет сообщений", color = Color.Gray, modifier = Modifier.padding(20.dp)) }
                    items(turns, key = { it.value.id }) { turn ->
                        Text(turn.value.content.ifBlank { turn.value.attachments.joinToString { it.name } },
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable {
                                showOutline = false; followLatest = false
                                navigationScope.launch { listState.scrollToItem(turn.index) }
                            }.padding(horizontal = 20.dp, vertical = 14.dp))
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isNotEmpty()) {
                val isSpeakingAloud by viewModel.voiceEngine.isSpeakingMessageAloud.collectAsState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(messages, key = { _, msg -> msg.id }) { _, msg ->
                        ChatGptMessageBubble(
                            message = msg,
                            readingTextSize = chatSettings.chatTextSize,
                            responseFont = chatSettings.responseFont,
                            isSpeakingAloud = isSpeakingAloud,
                            onToggleThinking = { viewModel.toggleThinkingExpanded(msg.id) },
                            onSpeak = { viewModel.toggleSpeakMessage(msg.content) },
                            onBranch = { viewModel.branchFromMessage(msg.id) },
                            onSelectVersion = { viewModel.selectUserMessageVersion(msg.id, it) },
                            onRegenerate = { viewModel.regenerateResponseForMessage(msg.id) },
                            onSaveToLibrary = { viewModel.saveMessageToLibrary(msg) },
                            onSaveToPhone = { fileName, code ->
                                viewModel.saveSnippetToPhoneFile(fileName, code)
                            },
                            onSaveToGoogleDrive = { fileName, code ->
                                viewModel.saveSnippetToGoogleDrive(fileName, code)
                            },
                            onDownloadMedia = { media ->
                                viewModel.downloadGeneratedMediaToPhone(media)
                            },
                            onEditUserMessage = { newPrompt ->
                                viewModel.editAndResendUserMessage(msg.id, newPrompt)
                            },
                            onDecideToolCall = { toolCallId, decision ->
                                viewModel.decideToolCallApproval(msg.id, toolCallId, decision)
                            },
                            onOpenWebSync = {
                                viewModel.selectDrawerSection(DrawerSection.GOOGLE_WEB)
                            },
                            onOpenCliSandbox = {
                                onOpenCliSandboxSheet(0)
                            }
                        )
                    }
                }
            }
            // The supplied ChatGPT empty conversation has no central headline or debug hints.
            if (messages.isNotEmpty() && !followLatest && listState.canScrollForward) {
                androidx.compose.material3.FilledIconButton(
                    onClick = {
                        followLatest = true
                        navigationScope.launch { listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE) }
                    }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF303030))
                ) { Icon(Icons.Default.KeyboardArrowDown, "К последнему сообщению", tint = Color.White) }
            }
        }

        val isSwarmTaskActive by viewModel.isSwarmTaskActive.collectAsState()
        val swarmPanelState by viewModel.swarmPanelState.collectAsState()

        // Компактная плашка фоновых задач появляется только при реально запущенной фоновой/мультиагентной задаче
        if (forceExpandBgTasks || isSwarmTaskActive) {
            CodeBackgroundTasksEntryPoint(
                agents = swarmAgents,
                panelState = swarmPanelState,
                isStreaming = isAnyMessageStreaming,
                isSwarmTaskActive = isSwarmTaskActive,
                activeSessionTitle = activeSessionTitle,
                onOpenFullSheet = onOpenSwarmSheet,
                onCancelTask = viewModel::cancelSubagentAnalysis,
                onDispatchCodeTask = { viewModel.dispatchSwarmTask(it, prepareChanges = true) },
                onDispatchSwarmTask = { prompt -> viewModel.dispatchSwarmTask(prompt) },
                onInsertMention = { mentionText ->
                    viewModel.sendUserPrompt("${mentionText.trim()} Доложи текущий статус", viaVoice = false)
                },
                forceExpandedInline = forceExpandBgTasks
            )
        }

        val isDictatingInline by viewModel.voiceEngine.isDictatingInline.collectAsState()

        ChatGptBottomInputPill(
            viewModel = viewModel,
            placeholderText = adaptiveInputPlaceholder,
            pendingAttachments = pendingAttachments,
            isDictatingInline = isDictatingInline,
            isStreaming = isAnyMessageStreaming,
            onStopStreaming = { viewModel.stopActiveGeneration() },
            onRemoveAttachment = onRemoveAttachment,
            onSendPrompt = { text -> viewModel.sendUserPrompt(text, viaVoice = false) },
            onPlusClick = onOpenAttachmentSheet,
            onMicDictationClick = { currentText, onDictatedText ->
                viewModel.voiceEngine.startInlineDictation(
                    initialText = currentText,
                    onDictatedText = onDictatedText
                )
            },
            onBlueVoiceWaveClick = onStartVoiceLive,
            onOpenOpenRouterSheet = onOpenOpenRouterSheet,
            onOpenGoogleDriveSheet = onOpenGoogleDriveSheet,
            onOpenSwarmSheet = onOpenSwarmSheet,
            onOpenCliSandboxSheet = onOpenCliSandboxSheet,
            onOpenPermissionsSheet = onOpenPermissionsSheet
        )
    }
}

@Composable
private fun Round5QuickHubBar(
    contextWindowStatus: ContextWindowStatus,
    openRouterTotalCount: Int,
    swarmAgentsCount: Int,
    onOpenContextSheet: () -> Unit,
    onOpenOpenRouterSheet: () -> Unit,
    onOpenSwarmSheet: () -> Unit,
    onOpenCliSandboxSheet: (Int) -> Unit,
    onOpenPermissionsSheet: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ContextWindowBadge(
            status = contextWindowStatus,
            onClick = onOpenContextSheet
        )

        QuickHubPill(
            text = "OpenRouter (${openRouterTotalCount.coerceAtLeast(452)})",
            accentColor = DesignColors.AccentBlue,
            onClick = onOpenOpenRouterSheet
        )

        QuickHubPill(
            text = "Задачи ($swarmAgentsCount)",
            accentColor = DesignColors.AccentBlue,
            onClick = onOpenSwarmSheet
        )

        QuickHubPill(
            text = "Android CLI",
            accentColor = DesignColors.AccentBlue,
            onClick = { onOpenCliSandboxSheet(0) }
        )

        QuickHubPill(
            text = "🧪 Песочница",
            accentColor = DesignColors.AccentBlue,
            onClick = { onOpenCliSandboxSheet(1) }
        )

        QuickHubPill(
            text = "🛡️ Разрешения",
            accentColor = DesignColors.AccentBlue,
            onClick = onOpenPermissionsSheet
        )
    }
}

@Composable
private fun QuickHubPill(
    text: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        color = Color(0xFF171B22),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Text(
            text = text,
            color = Color(0xFFE6EDF3),
            fontSize = 11.2.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

private fun formatEnvironmentRu(raw: String): String {
    val lower = raw.lowercase()
    return when {
        lower.contains("phone") || lower.contains("sdcard") || lower.contains("смартфон") -> "Память смартфона"
        lower.contains("cloud") || lower.contains("облако") -> "Облако Google"
        lower.contains("cr05318") || lower.contains("base environment") || lower.contains("win-11") -> "Рабочая станция ПК"
        else -> raw
    }
}

/**
 * Экран уже созданных удалённых сессий (`Работа`).
 * Нажатие на любую сессию сразу открывает Чат со всеми прошлыми сообщениями этой сессии!
 */
@Composable
private fun ChatGptRemoteSessionsCodexScreen(
    viewModel: AntigravityViewModel,
    topModeTab: TopModeTab,
    messages: List<ChatMessage>,
    selectedFilter: RemoteEnvFilter,
    sessions: List<RemoteSessionItem>,
    pendingAttachments: List<AttachedFileItem>,
    searchQuery: String,
    showSearchBar: Boolean,
    contextWindowStatus: ContextWindowStatus,
    openRouterTotalCount: Int,
    swarmAgents: List<SwarmAgentNode>,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onOpenDrawer: () -> Unit,
    onSelectTopTab: (TopModeTab) -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenModelPicker: () -> Unit,
    onSelectFilter: (RemoteEnvFilter) -> Unit,
    onConnectSession: (RemoteSessionItem) -> Unit,
    onOpenAttachmentSheet: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onStartVoiceLive: () -> Unit,
    onOpenContextSheet: () -> Unit,
    onOpenOpenRouterSheet: () -> Unit,
    onOpenGoogleDriveSheet: () -> Unit,
    onOpenSwarmSheet: () -> Unit,
    onOpenCliSandboxSheet: (Int) -> Unit,
    onOpenPermissionsSheet: () -> Unit
) {
    val isAnyMessageStreaming = remember(messages) {
        messages.any { it.isStreaming }
    }
    val filteredSessions = remember(sessions, selectedFilter, searchQuery) {
        sessions.filter { s ->
            val matchesFilter = selectedFilter.matches(s)
            val matchesQuery = searchQuery.isBlank() ||
                s.title.contains(searchQuery, ignoreCase = true) ||
                s.environment.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DesignColors.Bg)
    ) {
        com.google.antigravity.ui.components.ChatModeHeader(
            workSelected = topModeTab == TopModeTab.WORK_SESSIONS,
            onDrawer = onOpenDrawer,
            onSelectWork = { onSelectTopTab(if (it) TopModeTab.WORK_SESSIONS else TopModeTab.CHAT) }
        ) {
            androidx.compose.material3.FilledIconButton(onClick = onToggleSearch, modifier = Modifier.size(48.dp),
                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = DesignColors.SurfaceHigh)) {
                Icon(Icons.Default.Search, "Поиск сессий", tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }

        AnimatedVisibility(visible = showSearchBar) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Поиск удалённой сессии...", color = Color(0xFF8E8E8E)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                shape = RoundedCornerShape(20.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RemoteEnvFilter.entries.forEach { filter ->
                val isSelected = selectedFilter == filter
                Surface(
                    color = if (isSelected) Color(0xFF2B2B2B) else Color.Transparent,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.clickable { onSelectFilter(filter) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (filter) {
                            RemoteEnvFilter.ALL -> {}
                            RemoteEnvFilter.CLOUD -> {
                                Icon(
                                    imageVector = Icons.Outlined.Cloud,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(7.dp))
                            }
                            RemoteEnvFilter.PC_WIN11, RemoteEnvFilter.PC_CR05318 -> {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFD4D4D4))
                                )
                                Spacer(modifier = Modifier.width(7.dp))
                                Icon(
                                    imageVector = Icons.Outlined.Laptop,
                                    contentDescription = null,
                                    tint = Color(0xFFD4D4D4),
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            RemoteEnvFilter.PHONE_LOCAL -> {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(DesignColors.AccentBlue)
                                )
                                Spacer(modifier = Modifier.width(7.dp))
                                Icon(
                                    imageVector = Icons.Outlined.PhoneAndroid,
                                    contentDescription = null,
                                    tint = Color(0xFFD4D4D4),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                        }

                        Text(
                            text = filter.label,
                            color = if (isSelected) Color.White else Color(0xFFD4D4D4),
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { viewModel.syncRemoteSessionsFromPc() }
                        .padding(top = 6.dp, bottom = 4.dp)
                ) {
                    Text(
                        text = "Недавние",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            items(filteredSessions, key = { it.id }) { session ->
                val cleanEnv = formatEnvironmentRu(session.environment)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConnectSession(session) }
                ) {
                    Text(
                        text = session.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EnvironmentMiniIcon()
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = if (session.messageCount > 0) {
                                "$cleanEnv • ${session.messageCount} сообщ."
                            } else {
                                cleanEnv
                            },
                            color = Color(0xFF8E8E8E),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Больше диалогов нет",
                    color = Color(0xFF8E8E8E),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp)
                )
            }
        }

        val isDictatingInline by viewModel.voiceEngine.isDictatingInline.collectAsState()

        ChatGptBottomInputPill(
            viewModel = viewModel,
            placeholderText = "Спросить Antigravity",
            pendingAttachments = pendingAttachments,
            isDictatingInline = isDictatingInline,
            isStreaming = isAnyMessageStreaming,
            onStopStreaming = { viewModel.stopActiveGeneration() },
            onRemoveAttachment = onRemoveAttachment,
            onSendPrompt = { text -> viewModel.sendUserPrompt(text, viaVoice = false) },
            onPlusClick = onOpenAttachmentSheet,
            onMicDictationClick = { currentText, onDictatedText ->
                viewModel.voiceEngine.startInlineDictation(
                    initialText = currentText,
                    onDictatedText = onDictatedText
                )
            },
            onBlueVoiceWaveClick = onStartVoiceLive,
            onOpenOpenRouterSheet = onOpenOpenRouterSheet,
            onOpenGoogleDriveSheet = onOpenGoogleDriveSheet,
            onOpenSwarmSheet = onOpenSwarmSheet,
            onOpenCliSandboxSheet = onOpenCliSandboxSheet,
            onOpenPermissionsSheet = onOpenPermissionsSheet
        )
    }
}

/**
 * Боковое меню навигации 1:1 как в ChatGPT Android (Разделы 11.1, 11.5, 11.6):
 * отдельные пункты в левом меню (без дублирования Фоновых задач и субагентов, которые находятся над полем ввода чата).
 */
@Composable
private fun ChatGptExactNavigationDrawer(
    viewModel: AntigravityViewModel,
    sessions: List<RemoteSessionItem>,
    activeSessionId: String,
    openRouterTotalCount: Int,
    googleDriveFilesCount: Int,
    onRefreshSessions: () -> Unit,
    onSelectMenuSection: (DrawerSection) -> Unit,
    onOpenOpenRouterHub: () -> Unit,
    onOpenGoogleDriveHub: () -> Unit,
    onOpenCliSandboxHub: (Int) -> Unit,
    onOpenPermissionsHub: () -> Unit,
    onOpenContextHub: () -> Unit,
    onSelectSession: (RemoteSessionItem) -> Unit,
    onNewChatClick: () -> Unit,
    onProfileClick: () -> Unit,
    projects: List<ScratchProjectItem> = emptyList(),
    onSelectProject: (ScratchProjectItem) -> Unit = {},
    onTemporaryChat: () -> Unit = {}
) {
    val preferences by viewModel.chatLibraryPreferences.collectAsState()
    com.google.antigravity.ui.components.ChatHistoryDrawer(
        sessions = sessions, preferences = preferences, activeSessionId = activeSessionId,
        searchContents = viewModel::searchChatHistory,
        onUpdate = viewModel::updateChatLibraryPreferences,
        onSelect = onSelectSession, onNewChat = onNewChatClick, onRefresh = onRefreshSessions,
        onSection = onSelectMenuSection, onModels = onOpenOpenRouterHub, onFiles = onOpenGoogleDriveHub,
        onTerminal = { onOpenCliSandboxHub(0) }, onPermissions = onOpenPermissionsHub,
        onContext = onOpenContextHub, onProfile = onProfileClick,
        projects = projects, onSelectProject = onSelectProject,
        onTemporaryChat = onTemporaryChat
    )
}

@Composable
private fun DrawerMenuRow(
    @Suppress("UNUSED_PARAMETER") icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Нижняя капсула ввода 1:1 как в ChatGPT Android, с поддержкой прикреплённых файлов над строкой текста
 * и реактивным автодополнением `/` (скиллы и команды) и `@` (контекст и суб-агенты роя).
 */
@Composable
private fun ChatGptBottomInputPill(
    viewModel: AntigravityViewModel,
    placeholderText: String,
    pendingAttachments: List<AttachedFileItem>,
    isDictatingInline: Boolean = false,
    isStreaming: Boolean = false,
    onStopStreaming: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit,
    onSendPrompt: (String) -> Unit,
    onPlusClick: () -> Unit,
    onMicDictationClick: (String, (String) -> Unit) -> Unit,
    onBlueVoiceWaveClick: () -> Unit,
    onOpenOpenRouterSheet: () -> Unit = {},
    onOpenGoogleDriveSheet: () -> Unit = {},
    onOpenSwarmSheet: () -> Unit = {},
    onOpenCliSandboxSheet: (Int) -> Unit = {},
    onOpenPermissionsSheet: () -> Unit = {}
) {
    val text by viewModel.composerDraft.collectAsState()
    val queuedFollowUps by viewModel.queuedFollowUps.collectAsState()
    val queueSettings by viewModel.settings.collectAsState()
    val queueMessages by viewModel.messages.collectAsState()
    val queueContext = com.google.antigravity.model.conversationExecutionId(queueSettings.activeConversationId, queueMessages)
    val currentQueue = queuedFollowUps.filter { it.conversationId == queueSettings.activeConversationId && it.target == queueSettings.executionTarget &&
        (it.executionContextId.isBlank() || it.executionContextId == queueContext) }
    val attachmentError by viewModel.attachmentError.collectAsState()
    val installedSkills by viewModel.installedSkills.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val selectedMcpConnections by viewModel.selectedMcpConnections.collectAsState()
    val swarmAgents by viewModel.swarmAgents.collectAsState()
    val googleDriveFiles by viewModel.googleDriveFiles.collectAsState()
    val composerTokens by viewModel.composerTokens.collectAsState()

    val slashAndMentionSuggestions = remember(text, installedSkills, mcpServers, swarmAgents, googleDriveFiles) {
        viewModel.computeSlashAndMentionSuggestions(text)
    }

    val handleSendAction: (String) -> Unit = { rawInput ->
        val trimmed = rawInput.trim()
        when {
            trimmed.equals("/reset", ignoreCase = true) -> {
                viewModel.resetSessionContextWindow(clearMessages = false)
            }
            trimmed.equals("/compact", ignoreCase = true) -> {
                viewModel.compactSessionContextWindow()
            }
            trimmed.equals("/openrouter", ignoreCase = true) -> {
                onOpenOpenRouterSheet()
            }
            trimmed.equals("/gdrive", ignoreCase = true) -> {
                onOpenGoogleDriveSheet()
            }
            trimmed.equals("/swarm", ignoreCase = true) -> {
                onOpenSwarmSheet()
            }
            trimmed.equals("/cli", ignoreCase = true) -> {
                onOpenCliSandboxSheet(0)
            }
            trimmed.equals("/sandbox", ignoreCase = true) -> {
                onOpenCliSandboxSheet(1)
            }
            trimmed.equals("/permissions", ignoreCase = true) -> {
                onOpenPermissionsSheet()
            }
            trimmed.startsWith("/photo ", ignoreCase = true) -> {
                val prompt = trimmed.substringAfter(" ").trim()
                if (prompt.isNotBlank()) {
                    viewModel.generateOpenRouterMediaInChat(prompt = prompt, modelId = "black-forest-labs/flux.2-pro", isVideo = false)
                }
            }
            trimmed.startsWith("/video ", ignoreCase = true) -> {
                val prompt = trimmed.substringAfter(" ").trim()
                if (prompt.isNotBlank()) {
                    viewModel.generateOpenRouterMediaInChat(prompt = prompt, modelId = "google/veo-3.1-video", isVideo = true)
                }
            }
            else -> {
                onSendPrompt(rawInput)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DesignColors.Bg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        currentQueue.forEach { queued ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("В очереди: ${queued.prompt}", color = Color(0xFF9E9E9E), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!isStreaming) TextButton(onClick = { viewModel.sendQueuedFollowUp(queued.id) }) { Text("Отправить") }
                IconButton(onClick = { viewModel.removeQueuedFollowUp(queued.id) }) {
                    Icon(Icons.Default.Close, contentDescription = "Удалить сообщение из очереди", tint = Color.Gray)
                }
            }
        }
        attachmentError?.let { error ->
            Text(error, color = Color(0xFFF87171), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        // Этап 6: выбор подключений MCP для разговора (пусто — все включённые).
        if (selectedMcpConnections.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Подключения: ", color = Color(0xFF8E8E8E), fontSize = 11.sp)
                selectedMcpConnections.forEach { id ->
                    val name = mcpServers.firstOrNull { it.id == id }?.name ?: id
                    TextButton(
                        onClick = { viewModel.toggleMcpConnectionSelected(id) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("✕ $name", color = DesignColors.AccentBlue, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(
                    onClick = { viewModel.clearMcpConnectionSelection() },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text("все", color = Color(0xFF8E8E8E), fontSize = 11.sp)
                }
            }
        }
        // Реактивное всплывающее меню `/` (скиллы и команды) и `@` (упоминания, суб-агенты роя, файлы)
        if (slashAndMentionSuggestions.isNotEmpty()) {
            SlashAndMentionPopup(
                suggestions = slashAndMentionSuggestions,
                onSelectSuggestion = { item ->
                    when (item.trigger) {
                        "/reset" -> {
                            viewModel.setComposerDraft("")
                            viewModel.resetSessionContextWindow(clearMessages = false)
                        }
                        "/compact" -> {
                            viewModel.setComposerDraft("")
                            viewModel.compactSessionContextWindow()
                        }
                        "/openrouter" -> {
                            viewModel.setComposerDraft("")
                            onOpenOpenRouterSheet()
                        }
                        "/gdrive" -> {
                            viewModel.setComposerDraft("")
                            onOpenGoogleDriveSheet()
                        }
                        "/swarm" -> {
                            viewModel.setComposerDraft("")
                            onOpenSwarmSheet()
                        }
                        "/cli" -> {
                            viewModel.setComposerDraft("")
                            onOpenCliSandboxSheet(0)
                        }
                        "/sandbox" -> {
                            viewModel.setComposerDraft("")
                            onOpenCliSandboxSheet(1)
                        }
                        "/permissions" -> {
                            viewModel.setComposerDraft("")
                            onOpenPermissionsSheet()
                        }
                        else -> {
                            // Этап 6: выбор подключения в разговоре — упоминание
                            // @имя также включает сервер в allowlist хода
                            // (пустой набор — все включённые).
                            if (item.id.startsWith("mcp_")) {
                                viewModel.toggleMcpConnectionSelected(item.id.removePrefix("mcp_"))
                            }
                            val lastSpace = text.lastIndexOfAny(charArrayOf(' ', '\n'))
                            val prefix = if (lastSpace >= 0) text.substring(0, lastSpace + 1) else ""
                            viewModel.setComposerDraft(prefix + item.insertText)
                            // Фишка упоминания («как у них») — ДОПОЛНИТЕЛЬНО к
                            // текстовому insert (он сохранён как был); фишка
                            // добавляется только для @-предложений.
                            if (item.trigger.startsWith("@")) {
                                viewModel.addComposerToken(item)
                            }
                        }
                    }
                }
            )
        }

        // Фишки упоминаний над полем ввода («как у них»): label + тип серым +
        // «✕» удаления. Пустой список — строки нет.
        if (composerTokens.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                composerTokens.forEach { token ->
                    Surface(
                        color = Color(0xFF212121),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = token.label,
                                color = Color.White,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = token.typeLabel,
                                color = Color(0xFF8E8E8E),
                                fontSize = 10.5.sp,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Удалить упоминание",
                                tint = Color(0xFFB0B0B0),
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { viewModel.removeComposerToken(token.id) }
                            )
                        }
                    }
                }
            }
        }

        // Плашки прикреплённых файлов над полем ввода (как в ChatGPT при загрузке файлов)
        if (pendingAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pendingAttachments.forEach { att ->
                    Surface(
                        color = Color(0xFF212121),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (att.mimeType.startsWith("image/")) Icons.Default.Image else Icons.Default.Description,
                                contentDescription = null,
                                tint = DesignColors.AccentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = att.name,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${(att.sizeBytes / 1024).coerceAtLeast(1)} КБ",
                                    color = Color(0xFF8E8E8E),
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Удалить вложение",
                                tint = Color(0xFFB0B0B0),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onRemoveAttachment(att.id) }
                            )
                        }
                    }
                }
            }
        }

        Surface(
            color = DesignColors.SurfaceHigh,
            shape = RoundedCornerShape(32.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DesignColors.Border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onPlusClick() }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Загрузить файлы или фото",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = if (pendingAttachments.isNotEmpty()) "Добавьте сообщение к файлу..." else if (isStreaming) "Сообщение в очередь…" else placeholderText,
                            color = Color(0xFF8E8E8E),
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { viewModel.setComposerDraft(it) },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(Color.White),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isDictatingInline) Color(0xFFEF4444) else Color.Transparent)
                        .clickable {
                            val sessionId = viewModel.settings.value.activeConversationId
                            onMicDictationClick(text) { dictated ->
                                viewModel.setComposerDraft(dictated, sessionId)
                            }
                        }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = "Голосовая диктовка в текст",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                if (isStreaming) {
                    if (text.isNotBlank() || pendingAttachments.isNotEmpty()) IconButton(onClick = {
                        val toQueue = text
                        viewModel.setComposerDraft("")
                        onSendPrompt(toQueue)
                    }) { Icon(Icons.Default.ArrowUpward, "Добавить в очередь", tint = Color.White) }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { onStopStreaming() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Остановить генерацию",
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                } else if (text.isNotBlank() || pendingAttachments.isNotEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable {
                                val toSend = text
                                viewModel.setComposerDraft("")
                                handleSendAction(toSend)
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Отправить",
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    BlueVoiceWaveformCircleButton(onClick = onBlueVoiceWaveClick)
                }
            }
        }
    }
}

/**
 * Нижняя шторка выбора вложений по кнопке `+` 1:1 как в ChatGPT Android 1.2026.293 (`ModalBottomSheet`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatGptAttachmentPickerDialog(
    onTakePhoto: () -> Unit,
    onPickPhotos: () -> Unit,
    onPickAnyFiles: () -> Unit,
    onOpenPhoneStorage: () -> Unit,
    onMention: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DesignColors.Bg,
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            com.google.antigravity.ui.components.SheetHeader("Добавление в чат", onDismiss)
            // 3 большие плитки сверху (Камера | Фотографии | Файлы), как в ChatGPT Android 1.2026.293
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AttachmentTileButton(
                    icon = Icons.Outlined.CameraAlt,
                    label = "Камера",
                    onClick = onTakePhoto,
                    modifier = Modifier.weight(1f)
                )
                AttachmentTileButton(
                    icon = Icons.Outlined.Image,
                    label = "Фото",
                    onClick = onPickPhotos,
                    modifier = Modifier.weight(1f)
                )
                AttachmentTileButton(
                    icon = Icons.Outlined.Description,
                    label = "Файлы",
                    onClick = onPickAnyFiles,
                    modifier = Modifier.weight(1f)
                )
            }

            Surface(
                color = DesignColors.Surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onMention() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AlternateEmail,
                        contentDescription = null,
                        tint = DesignColors.TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Упомянуть…",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Добавьте навык, подключение или файл",
                            color = Color(0xFF9E9E9E),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Surface(
                color = DesignColors.Surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPhoneStorage() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = DesignColors.TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Файлы телефона",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Поиск, чтение и создание файлов",
                            color = Color(0xFF9E9E9E),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AttachmentTileButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = DesignColors.Surface,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .heightIn(min = 112.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(color = DesignColors.SurfaceHigh, shape = CircleShape) {
                Icon(imageVector = icon, contentDescription = null, tint = DesignColors.TextPrimary,
                    modifier = Modifier.padding(12.dp).size(26.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Раздел Бокового Меню: `Библиотека` (Артефакты сессий ПК + файлы смартфона).
 */
@Composable
private fun ChatGptLibraryScreen(
    artifacts: List<ArtifactDocument>,
    viewModel: AntigravityViewModel,
    phoneFiles: List<PhoneResourceController.PhoneFileEntry>,
    onOpenDrawer: () -> Unit,
    onOpenPhoneExplorer: () -> Unit,
    onPickFileFromSystem: () -> Unit
) {
    com.google.antigravity.ui.components.ArtifactLibraryScreen(
        artifacts = artifacts, onOpenDrawer = onOpenDrawer, onOpenFiles = onOpenPhoneExplorer,
        onSaveFile = viewModel::saveSnippetToPhoneFile, onRemove = viewModel::removeSavedArtifact,
        onRename = viewModel::renameSavedArtifact, onEditContent = viewModel::editSavedArtifactContent,
        onRestoreVersion = viewModel::restoreSavedArtifactVersion,
        onTogglePinned = viewModel::toggleSavedArtifactPinned
    )
}

@Composable
private fun ChatGptImagesScreen(
    onOpenDrawer: () -> Unit,
    onPickPhoto: () -> Unit,
    onTakeCameraPhoto: () -> Unit,
    onSendImagePrompt: (String) -> Unit
) {
    val presets = listOf(
        "Сгенерируй концепт иконки Android-приложения Antigravity в тёмном стиле",
        "Нарисуй архитектурную диаграмму голосового движка и моста синхронизации",
        "Создай UI-макет экрана удалённых сессий в стиле ChatGPT Dark"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DesignColors.Bg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TwoLineMenuCircleButton(onClick = onOpenDrawer)
            Spacer(modifier = Modifier.width(14.dp))
            Text("Изображения", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onPickPhoto,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF212121)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Из галереи")
            }
            Button(
                onClick = onTakeCameraPhoto,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF212121)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Камера")
            }
        }

        Text("Быстрые шаблоны генерации и анализа изображений", color = Color(0xFF8E8E8E), fontSize = 13.sp)

        presets.forEach { preset ->
            Surface(
                color = Color(0xFF171717),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSendImagePrompt(preset) }
            ) {
                Text(
                    text = preset,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

/**
 * Раздел Бокового Меню: `Запланировано` (фоновые расписания задач).
 */
@Composable
private fun ChatGptScheduledScreen(
    tasks: List<ScheduledAutomationItem>,
    onOpenDrawer: () -> Unit,
    onToggleTask: (String) -> Unit,
    onAddTask: (String, String, String) -> Unit,
    onRunTaskNow: (ScheduledAutomationItem) -> Unit
) {
    var newTitle by remember { mutableStateOf("") }
    var newCron by remember { mutableStateOf("Каждый день в 10:00") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DesignColors.Bg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TwoLineMenuCircleButton(onClick = onOpenDrawer)
            Spacer(modifier = Modifier.width(14.dp))
            Text("Запланировано", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF171717)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Новое расписание задач", color = Color.White, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    placeholder = { Text("Описание задачи...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newCron,
                        onValueChange = { newCron = it },
                        label = { Text("Периодичность") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            onAddTask(newTitle, newCron, newTitle)
                            newTitle = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DesignColors.AccentBlue)
                    ) {
                        Text("Добавить")
                    }
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tasks, key = { it.id }) { task ->
                Surface(
                    color = Color(0xFF171717),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onRunTaskNow(task) }
                        ) {
                            Text(task.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("Расписание: ${task.cronSchedule}", color = Color(0xFF8E8E8E), fontSize = 12.sp)
                        }
                        Switch(colors = com.google.antigravity.ui.design.designSwitchColors(), checked = task.enabled, onCheckedChange = { onToggleTask(task.id) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun TwoLineMenuCircleButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(DesignColors.SurfaceHigh)
            .semantics { contentDescription = "Открыть меню" }
            .clickable(onClick = onClick)
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val stroke = 2.2.dp.toPx()
            drawLine(
                color = Color.White,
                start = Offset(1.dp.toPx(), size.height * 0.34f),
                end = Offset(19.dp.toPx(), size.height * 0.34f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = Offset(1.dp.toPx(), size.height * 0.68f),
                end = Offset(12.dp.toPx(), size.height * 0.68f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun DashedBubbleCircleButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color(0xFF212121))
            .clickable(onClick = onClick)
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val r = size.minDimension * 0.40f
            drawCircle(
                color = Color.White,
                radius = r,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.5f, 4.5f), 0f),
                    cap = StrokeCap.Round
                )
            )
            // Хвостик речевого пузыря в левом нижнем углу (как в ChatGPT 1.2026.293)
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.25f, size.height * 0.75f),
                end = Offset(size.width * 0.14f, size.height * 0.88f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun SearchCircleButton(onClick: () -> Unit, sizeDp: Int = 46) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(Color(0xFF212121))
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Поиск",
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun BlueVoiceWaveformCircleButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(DesignColors.AccentBlue)
            .clickable(onClick = onClick)
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val barWidth = 2.2.dp.toPx()
            val heights = listOf(0.32f, 0.72f, 0.48f, 0.85f, 0.36f)
            val gap = size.width / (heights.size + 1)
            heights.forEachIndexed { idx, hRatio ->
                val x = gap * (idx + 1)
                val barH = size.height * hRatio
                val topY = (size.height - barH) / 2f
                drawLine(
                    color = Color.White,
                    start = Offset(x, topY),
                    end = Offset(x, topY + barH),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun EnvironmentMiniIcon() {
    Canvas(modifier = Modifier.size(14.dp)) {
        drawRoundRect(
            color = Color(0xFF8E8E8E),
            topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
            size = Size(size.width - 2.dp.toPx(), size.height * 0.58f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = 1.4.dp.toPx())
        )
        val dotY = size.height * 0.84f
        for (i in 0..3) {
            drawCircle(
                color = Color(0xFF8E8E8E),
                radius = 1.dp.toPx(),
                center = Offset(size.width * (0.2f + i * 0.2f), dotY)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChatGptMessageBubble(
    message: ChatMessage,
    readingTextSize: com.google.antigravity.model.ChatTextSize = com.google.antigravity.model.ChatTextSize.NORMAL,
    responseFont: com.google.antigravity.model.ResponseFont = com.google.antigravity.model.ResponseFont.SERIF,
    isSpeakingAloud: Boolean = false,
    onToggleThinking: () -> Unit,
    onSpeak: () -> Unit,
    onBranch: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onSaveToPhone: (String, String) -> Unit = { _, _ -> },
    onSaveToLibrary: () -> Unit = {},
    onSaveToGoogleDrive: (String, String) -> Unit = { _, _ -> },
    onDownloadMedia: (GeneratedMediaAttachment) -> Unit = {},
    onEditUserMessage: (String) -> Unit = {},
    onSelectVersion: (Int) -> Unit = {},
    onDecideToolCall: (String, ApprovalDecision) -> Unit = { _, _ -> },
    @Suppress("UNUSED_PARAMETER") onOpenWebSync: () -> Unit = {},
    onOpenCliSandbox: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copiedToast by remember { mutableStateOf(false) }
    var isEditingPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(copiedToast) {
        if (copiedToast) {
            kotlinx.coroutines.delay(1800L)
            copiedToast = false
        }
    }
    var editedText by remember(message.content) { mutableStateOf(message.content) }
    var showContextMenuSheet by remember { mutableStateOf(false) }
    var showSelectTextDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = isEditingPrompt) {
        editedText = message.content
        isEditingPrompt = false
    }


    val shareMessageText: () -> Unit = {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message.content)
            }
            context.startActivity(
                Intent.createChooser(sendIntent, "Поделиться сообщением")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }

    if (message.isContextResetDivider) {
        Surface(
            color = Color(0xFF0E291E),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DesignColors.AccentBlue.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = message.content,
                    color = Color(0xFF34D399),
                    fontSize = 12.2.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                )
            }
        }
        return
    }

    val isUser = message.role == MessageRole.USER
    if (isUser) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            if (message.attachments.isNotEmpty()) {
                message.attachments.forEach { att ->
                    Surface(
                        color = Color(0xFF212121),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .border(1.dp, Color(0xFF333333), RoundedCornerShape(14.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (att.mimeType.startsWith("image/")) Icons.Default.Image else Icons.Default.Description,
                                contentDescription = null,
                                tint = DesignColors.AccentBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${att.name} (${(att.sizeBytes / 1024).coerceAtLeast(1)} КБ)",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            message.versionState?.takeIf { it.branches.size > 1 }?.let { versions ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = !message.isStreaming && versions.selected > 0,
                        onClick = { onSelectVersion(versions.selected - 1) }) {
                        Icon(Icons.Default.KeyboardArrowLeft, "Предыдущая версия", tint = Color.Gray)
                    }
                    Text("${versions.selected + 1} / ${versions.branches.size}", color = Color.Gray, fontSize = 12.sp)
                    IconButton(enabled = !message.isStreaming && versions.selected < versions.branches.lastIndex,
                        onClick = { onSelectVersion(versions.selected + 1) }) {
                        Icon(Icons.Default.KeyboardArrowRight, "Следующая версия", tint = Color.Gray)
                    }
                }
            }
            if (isEditingPrompt) {
                Text("Сохранение создаст новую версию в этом разговоре.", color = Color.Gray, fontSize = 12.sp)
                Surface(
                    color = Color(0xFF242424),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .border(1.dp, Color(0xFF383838), RoundedCornerShape(18.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = editedText,
                            onValueChange = { editedText = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(onClick = {
                                editedText = message.content
                                isEditingPrompt = false
                            }) {
                                Text("Отмена", color = Color.White, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    isEditingPrompt = false
                                    onEditUserMessage(editedText)
                                },
                                enabled = editedText.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                            ) {
                                Text("Отправить", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                // Пузырь сообщения пользователя 1:1 как в ChatGPT Android 1.2026.293 (#2F2F2F, с поддержкой кликабельных ссылок)
                Surface(
                    color = DesignColors.UserBubble,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .padding(start = 32.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { showContextMenuSheet = true }
                        )
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                        Text(message.content, color = DesignColors.TextPrimary,
                            fontSize = (16 * readingTextSize.scale).sp,
                            lineHeight = (24 * readingTextSize.scale).sp)
                    }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showContextMenuSheet = true }
                )
        ) {
            com.google.antigravity.ui.components.ConversationActivity(
                message = message,
                responseFont = responseFont,
                readingTextSize = readingTextSize,
                onToggleThinking = onToggleThinking,
                onDecideToolCall = onDecideToolCall,
                onSaveCode = onSaveToPhone
            )

            // Карточки созданных/изменённых файлов с diff (+added зелёный / -removed красный) (Раздел 5.2 и 11.5)
            if (message.createdDiffs.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    message.createdDiffs.forEach { diff ->
                        ReferenceDiffCard(
                            diff = diff,
                            onOpenCliSandbox = onOpenCliSandbox,
                            onSaveToGoogleDrive = onSaveToGoogleDrive
                        )
                    }
                }
            }

            if (message.content.isNotBlank() && message.content != "Думаю…") {
                ChatGptMarkdownText(
                    markdown = message.content,
                    textColor = Color(0xFFECECEC),
                    textSize = readingTextSize,
                    responseFont = responseFont,
                    onSaveCodeToPhone = onSaveToPhone
                )
            }

            // Сгенерированные ИИ фото и видео с предпросмотром и скачиванием в память телефона (Разделы 2 и 8.1)
            if (message.generatedMedia.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    message.generatedMedia.forEach { media ->
                        GeneratedMediaCard(
                            media = media,
                            onDownloadToPhone = { onDownloadMedia(it) }
                        )
                    }
                }
            }

            if (!message.isStreaming && message.content.isNotBlank()) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(message.content))
                        copiedToast = true
                    }) { Icon(if (copiedToast) Icons.Default.Check else Icons.Default.ContentCopy,
                        "Копировать", tint = DesignColors.TextSecondary, modifier = Modifier.size(20.dp)) }
                    IconButton(onClick = onSpeak) {
                        Icon(if (isSpeakingAloud) Icons.Default.StopCircle else Icons.AutoMirrored.Filled.VolumeUp,
                            "Прочитать вслух", tint = DesignColors.TextSecondary, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = shareMessageText) {
                        Icon(Icons.Default.Share, "Поделиться", tint = DesignColors.TextSecondary,
                            modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { showContextMenuSheet = true }) {
                        Icon(Icons.Default.MoreHoriz, "Другие действия", tint = DesignColors.TextSecondary,
                            modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }

    // Контекстное меню сообщения по долгому нажатию 1:1 как в ChatGPT Android 1.2026.293
    if (showContextMenuSheet) {
        val contextSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showContextMenuSheet = false },
            sheetState = contextSheetState,
            containerColor = Color(0xFF1C1C1E),
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MessageContextMenuRow(
                    icon = Icons.Default.ContentCopy,
                    label = "Копировать",
                    onClick = {
                        clipboard.setText(AnnotatedString(message.content))
                        copiedToast = true
                        showContextMenuSheet = false
                    }
                )
                if (!isUser && !message.isStreaming) MessageContextMenuRow(
                    icon = Icons.Default.SaveAlt, label = "Сохранить в библиотеку", onClick = {
                        onSaveToLibrary(); showContextMenuSheet = false
                    }
                )
                if (!message.isStreaming) MessageContextMenuRow(
                    icon = Icons.Default.Edit, label = "Ветка в новом чате", onClick = {
                        showContextMenuSheet = false
                        onBranch()
                    }
                )
                MessageContextMenuRow(
                    icon = Icons.Default.SelectAll,
                    label = "Выделить текст",
                    onClick = {
                        showContextMenuSheet = false
                        showSelectTextDialog = true
                    }
                )
                MessageContextMenuRow(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    label = if (isSpeakingAloud) "Остановить озвучку" else "Прочитать вслух",
                    onClick = {
                        showContextMenuSheet = false
                        onSpeak()
                    }
                )
                if (isUser) {
                    MessageContextMenuRow(
                        icon = Icons.Default.Edit,
                        label = "Редактировать сообщение",
                        onClick = {
                            showContextMenuSheet = false
                            isEditingPrompt = true
                        }
                    )
                } else {
                    MessageContextMenuRow(
                        icon = Icons.Default.Refresh,
                        label = "Повторить",
                        onClick = {
                            showContextMenuSheet = false
                            onRegenerate()
                        }
                    )
                }
                MessageContextMenuRow(
                    icon = Icons.Default.SaveAlt,
                    label = "Сохранить в файл на телефон",
                    onClick = {
                        showContextMenuSheet = false
                        onSaveToPhone(
                            "antigravity_note_${System.currentTimeMillis() / 1000}.md",
                            message.content
                        )
                    }
                )
                MessageContextMenuRow(
                    icon = Icons.Default.Share,
                    label = "Поделиться",
                    onClick = {
                        showContextMenuSheet = false
                        shareMessageText()
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // Окно произвольного выделения текста (ChatGPT 1.2026.293 `0x7f130a7c Выделить текст`)
    if (showSelectTextDialog) {
        Dialog(
            onDismissRequest = { showSelectTextDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                color = Color(0xFF141414),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp)
                    .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(22.dp))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Выделить текст",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showSelectTextDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = message.content,
                                color = Color(0xFFECECEC),
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageContextMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SettingsModalScreen(
    viewModel: AntigravityViewModel,
    settings: AntigravitySettings,
    googleEmail: String,
    pcInstanceName: String,
    onOpenPermissions: () -> Unit,
    onUpdate: ((AntigravitySettings) -> AntigravitySettings) -> Unit,
    onOpenGoogleWeb: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    com.google.antigravity.ui.components.AppSettingsScreen(
        viewModel, settings, googleEmail, pcInstanceName, onUpdate,
        onOpenGoogleWeb, onSignOut, onBack, onOpenPermissions, onOpenDrawer
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelAndModeSelectorDialog(
    settings: AntigravitySettings,
    availableModels: List<AntigravityModelOption>,
    openRouterTotalCount: Int = 452,
    onSelectTarget: (AgentExecutionTarget) -> Unit,
    onSelectModel: (String) -> Unit,
    onOpenOpenRouterCatalog: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onSwitchToChatTab: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val modelsList = if (availableModels.isNotEmpty()) availableModels else AVAILABLE_AGY_MODELS

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DesignColors.Bg,
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Заголовок и подзаголовок из строковых ресурсов ChatGPT 1.2026.293 (0x7f130fab, 0x7f130fa9)
            com.google.antigravity.ui.components.SheetHeader("Выбрать модель", onDismiss)
            Text(
                text = "Переключайтесь между рекомендуемыми нами моделями",
                color = Color(0xFF9E9E9E),
                fontSize = 13.5.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Переключатель среды выполнения в единой компактной капсуле ChatGPT
            Surface(
                color = Color(0xFF2A2A2A),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val targets = listOf(
                        AgentExecutionTarget.PC_REMOTE_SESSION to "Сессия ПК",
                        AgentExecutionTarget.PHONE_LOCAL_RESOURCES to "Смартфон",
                        AgentExecutionTarget.GOOGLE_WEB_CONSOLE to "Веб-консоль"
                    )
                    targets.forEach { (target, shortLabel) ->
                        val isTargetSelected = settings.executionTarget == target
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(17.dp))
                                .background(if (isTargetSelected) Color(0xFF3D3D3D) else Color.Transparent)
                                .clickable { onSelectTarget(target) }
                        ) {
                            Text(
                                text = shortLabel,
                                color = if (isTargetSelected) Color.White else Color(0xFFA0A0A0),
                                fontSize = 13.sp,
                                fontWeight = if (isTargetSelected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Кнопка перехода в полный живой каталог OpenRouter (450+ моделей 2026: Фото, Видео, Reasoning, Vision)
            Surface(
                color = Color(0xFF132238),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF58A6FF).copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .clickable { onOpenOpenRouterCatalog() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Openrouter",
                            color = Color(0xFF58A6FF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.2.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Синхронизация openrouter.ai/api/v1/models • Фото, Видео, Reasoning, Free",
                            color = Color(0xFFA0B3C8),
                            fontSize = 11.8.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Чистый список моделей без рамок 1:1 как в ChatGPT Android 1.2026.293
            modelsList.forEach { model ->
                val selected = settings.selectedModelId == model.id
                Surface(
                    color = if (selected) Color(0xFF2B2B2B) else Color.Transparent,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectModel(model.id) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.displayName,
                                color = Color.White,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                fontSize = 15.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = model.badge,
                                color = Color(0xFF9E9E9E),
                                fontSize = 13.sp
                            )
                        }
                        if (selected) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Выбрано",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}


