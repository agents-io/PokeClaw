// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.skill.Skill
import io.agents.pokeclaw.agent.skill.SkillCategory
import io.agents.pokeclaw.agent.skill.SkillRegistry
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * PokeClaw Chat Screen — Jetpack Compose
 * Inspired by WhatsApp/Telegram/Slack dark theme
 */

// ======================== THEME COLORS ========================

data class PokeclawColors(
    val background: Color,
    val surface: Color,
    val userBubble: Color,
    val userText: Color,
    val aiBubble: Color,
    val aiBubbleBorder: Color,
    val aiText: Color,
    val avatar: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val inputBorder: Color,
)

val AbyssDark = PokeclawColors(
    background = Color(0xFF0C111B),
    surface = Color(0xFF151D2E),
    userBubble = Color(0xFF2563EB),
    userText = Color.White,
    aiBubble = Color(0xFF1E2D45),
    aiBubbleBorder = Color(0xFF2A3D5A),
    aiText = Color(0xFFD0DAE8),
    avatar = Color(0xFF1D4ED8),
    accent = Color(0xFF60A5FA),
    textPrimary = Color(0xFFECECF1),
    textSecondary = Color(0xFFA3A3B5),
    textTertiary = Color(0xFF52526E),
    divider = Color(0xFF1A2234),
    inputBorder = Color(0xFF1E293B),
)

// ======================== MAIN SCREEN ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    modelStatus: String,
    needsPermission: Boolean,
    isProcessing: Boolean,
    isDownloading: Boolean = false,
    downloadProgress: Int = 0,
    isLocalModel: Boolean = true,
    sessionTokens: Int = 0,
    sessionCost: Double = 0.0,
    onSendChat: (String) -> Unit,
    onSendTask: (String) -> Unit,
    onStartMonitor: (contact: String) -> Unit = {},
    onSendDirectMessage: (contact: String, app: String, message: String) -> Unit = { _, _, _ -> },
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onFixPermissions: () -> Unit,
    onAttach: () -> Unit,
    conversations: List<ChatHistoryManager.ConversationSummary>,
    onSelectConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    onDeleteConversation: (ChatHistoryManager.ConversationSummary) -> Unit = {},
    onRenameConversation: (ChatHistoryManager.ConversationSummary, String) -> Unit = { _, _ -> },
    activeTasks: List<String> = emptyList(),
    onStopTask: (String) -> Unit = {},
    onStopAllTasks: () -> Unit = {},
    inputEnabled: Boolean = true,
    onModelSwitch: (modelId: String, displayName: String) -> Unit = { _, _ -> },
    colors: PokeclawColors = AbyssDark,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Shared state for prompt chip → input bar prefill
    var prefillText by remember { mutableStateOf("") }
    var prefillIsTask by remember { mutableStateOf(false) }
    // Task mode state — lifted here so content area can react
    var isTaskMode by remember { mutableStateOf(false) }
    // Local/Cloud tab — controls UI presentation AND triggers model switch.
    // Keep the tab aligned with the actual active model so returning from
    // Settings/model changes cannot leave the toolbar UI out of sync.
    var selectedTab by remember { mutableStateOf(if (isLocalModel) "local" else "cloud") }
    val isLocalUI = selectedTab == "local"
    // Skill dialog and activation states
    var showMonitorSheet by remember { mutableStateOf(false) }
    var showSendSheet by remember { mutableStateOf(false) }
    var activatingSkill by remember { mutableStateOf<String?>(null) }

    // Chat mode is always the default — user can switch to Task manually

    // When activating finishes (2s animation), clear state
    LaunchedEffect(activatingSkill) {
        if (activatingSkill != null) {
            kotlinx.coroutines.delay(2000)
            activatingSkill = null
        }
    }

    LaunchedEffect(isLocalModel) {
        selectedTab = if (isLocalModel) "local" else "cloud"
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = colors.surface,
            ) {
                SidebarContent(
                    conversations = conversations,
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        onNewChat()
                    },
                    onSelectConversation = {
                        scope.launch { drawerState.close() }
                        onSelectConversation(it)
                    },
                    onDeleteConversation = onDeleteConversation,
                    onRenameConversation = onRenameConversation,
                    onSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    onModels = {
                        scope.launch { drawerState.close() }
                        onOpenModels()
                    },
                    colors = colors,
                )
            }
        }
    ) {
        Scaffold(
            containerColor = colors.background,
            topBar = {
                Column {
                    ChatTopBar(
                        modelStatus = modelStatus,
                        sessionTokens = sessionTokens,
                        sessionCost = sessionCost,
                        isLocalModel = isLocalModel,
                        selectedTab = selectedTab,
                        onTabChange = { tab ->
                            selectedTab = tab
                            val kvUtils = io.agents.pokeclaw.utils.KVUtils
                            if (tab == "cloud") {
                                // Check if cloud default model is configured
                                if (kvUtils.hasDefaultCloudModel()) {
                                    val modelId = kvUtils.getDefaultCloudModel()
                                    onModelSwitch(modelId, modelId)
                                } else {
                                    // No cloud model configured — signal "no model" state
                                    io.agents.pokeclaw.utils.XLog.i("ChatScreen", "Cloud tab: no default cloud model configured")
                                    onModelSwitch("NONE", "")
                                }
                            } else {
                                // Check if local default model is configured
                                if (kvUtils.hasDefaultLocalModel()) {
                                    val localPath = kvUtils.getLocalModelPath()
                                    val name = java.io.File(localPath).nameWithoutExtension
                                        .replace("-", " ").replace("_", " ")
                                    onModelSwitch("LOCAL", name)
                                } else {
                                    // No local model configured — signal "no model" state
                                    io.agents.pokeclaw.utils.XLog.i("ChatScreen", "Local tab: no default local model configured")
                                    onModelSwitch("NONE", "")
                                }
                            }
                        },
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onSettings = onOpenSettings,
                        onModelSwitch = onModelSwitch,
                        colors = colors,
                    )
                    if (activeTasks.isNotEmpty() || isProcessing) {
                        ActiveTaskBar(
                            tasks = activeTasks,
                            isRunningTask = isProcessing,
                            onStopTask = onStopTask,
                            onStopAll = onStopAllTasks,
                            colors = colors,
                        )
                    }
                }
            },
            bottomBar = {
                if (!isDownloading) {
                    Column(
                        modifier = Modifier.imePadding()
                    ) {
                        // Quick Tasks collapsible panel (v9 style)
                        QuickTasksPanel(
                            isLocalModel = isLocalUI,
                            onFillTask = { text ->
                                prefillText = text
                                prefillIsTask = true
                                if (isLocalUI) isTaskMode = true
                            },
                            onMonitorClick = { showMonitorSheet = true },
                            monitorActive = activeTasks.isNotEmpty(),
                            colors = colors,
                        )

                        ChatInputBar(
                            isProcessing = isProcessing,
                            inputEnabled = inputEnabled,
                            isTaskMode = isTaskMode,
                            isLocalModel = isLocalUI,
                            onTaskModeChange = { isTaskMode = it },
                            onSendChat = onSendChat,
                            onSendTask = onSendTask,
                            onStopAll = onStopAllTasks,
                            onAttach = onAttach,
                            colors = colors,
                            prefillText = prefillText,
                            prefillIsTask = prefillIsTask,
                            onPrefillConsumed = { prefillText = "" },
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (!isDownloading) {
                    // v9: always show messages or empty state regardless of mode
                    val userMessages = messages.filter { it.role != ChatMessage.Role.SYSTEM }
                    if (userMessages.isEmpty()) {
                        EmptyStateWithPrompts(
                            isLocalModel = isLocalUI,
                            onSelectPrompt = { text, isTask ->
                                prefillText = text
                                prefillIsTask = isTask
                                if (isTask && isLocalUI) isTaskMode = true
                            },
                            colors = colors,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        MessageList(
                            messages = messages,
                            colors = colors,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Download blocking overlay
                if (isDownloading) {
                    DownloadOverlay(progress = downloadProgress, colors = colors)
                }
            }
        }
    }

    // Monitor skill dialog
    if (showMonitorSheet) {
        MonitorDialog(
            onDismiss = { showMonitorSheet = false },
            onStart = { contact ->
                showMonitorSheet = false
                activatingSkill = "monitor"
                onStartMonitor(contact)
            },
            colors = colors,
        )
    }

    // Send Message skill dialog
    if (showSendSheet) {
        SendMessageDialog(
            onDismiss = { showSendSheet = false },
            onSend = { contact, app, message ->
                showSendSheet = false
                onSendDirectMessage(contact, app, message)
            },
            colors = colors,
        )
    }
}

// ======================== TOP BAR ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    modelStatus: String,
    sessionTokens: Int = 0,
    sessionCost: Double = 0.0,
    isLocalModel: Boolean = true,
    selectedTab: String,
    onTabChange: (String) -> Unit,
    onMenuClick: () -> Unit,
    onSettings: () -> Unit,
    onModelSwitch: (modelId: String, displayName: String) -> Unit = { _, _ -> },
    colors: PokeclawColors,
) {
    // Token count color: grey → blue → amber → red
    val tokenColor = when {
        sessionTokens < 5000 -> colors.textTertiary
        sessionTokens < 15000 -> Color(0xFF60A5FA) // blue
        sessionTokens < 25000 -> Color(0xFFFBBF24) // amber
        else -> Color(0xFFF87171) // soft red
    }

    Column {
        var showModelMenu by remember { mutableStateOf(false) }

        TopAppBar(
            title = {
                Text(
                    buildAnnotatedString {
                        append("Poke")
                        withStyle(SpanStyle(color = colors.accent)) {
                            append("Claw")
                        }
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = colors.textPrimary,
                )
            },
            navigationIcon = {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            },
            actions = {
                // Local/Cloud toggle — two plain buttons, no container
                Surface(
                    onClick = { onTabChange("local") },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedTab == "local") colors.aiBubble else Color.Transparent,
                    border = if (selectedTab == "local") androidx.compose.foundation.BorderStroke(1.dp, colors.aiBubbleBorder) else null,
                ) {
                    Text(
                        "Local",
                        fontSize = 12.sp,
                        color = if (selectedTab == "local") colors.accent else colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Surface(
                    onClick = { onTabChange("cloud") },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedTab == "cloud") colors.aiBubble else Color.Transparent,
                    border = if (selectedTab == "cloud") androidx.compose.foundation.BorderStroke(1.dp, colors.aiBubbleBorder) else null,
                ) {
                    Text(
                        "Cloud",
                        fontSize = 12.sp,
                        color = if (selectedTab == "cloud") colors.accent else colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.surface,
                titleContentColor = colors.textPrimary,
                navigationIconContentColor = colors.textPrimary,
                actionIconContentColor = colors.textSecondary,
            ),
        )

        // Model status + dropdown — filtered by selected tab
        Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .clickable { showModelMenu = true }
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = modelStatus,
                fontSize = 11.sp,
                color = colors.textTertiary,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.UnfoldMore,
                contentDescription = "Switch model",
                tint = colors.textTertiary,
                modifier = Modifier.size(12.dp),
            )
            if (sessionTokens > 0 && !isLocalModel) {
                val formattedTokens = if (sessionTokens >= 1000) {
                    String.format("%.1fK", sessionTokens / 1000.0)
                } else {
                    "$sessionTokens"
                }
                val costText = if (sessionCost < 0.01) "< $0.01" else "$${String.format("%.2f", sessionCost)}"
                val tokenSuffix = if (!isLocalModel && sessionCost > 0) {
                    " · $formattedTokens tokens · $costText"
                } else {
                    " · $formattedTokens tokens"
                }
                Text(
                    text = tokenSuffix,
                    fontSize = 11.sp,
                    color = tokenColor,
                )
            }
        }
            // Model switcher dropdown — only show configured/downloaded models
            DropdownMenu(
                expanded = showModelMenu,
                onDismissRequest = { showModelMenu = false },
            ) {
                val kvUtils = io.agents.pokeclaw.utils.KVUtils
                val apiKey = kvUtils.getLlmApiKey()
                val baseUrl = kvUtils.getLlmBaseUrl()
                val currentModel = kvUtils.getLlmModelName()

                if (selectedTab == "cloud") {
                    // Cloud models: from configured provider
                    if (apiKey.isNotEmpty()) {
                        val activeProvider = io.agents.pokeclaw.agent.CloudProvider.entries.find {
                            it.defaultBaseUrl == baseUrl
                        }
                        val modelsToShow = activeProvider?.models
                            ?: io.agents.pokeclaw.agent.CloudProvider.OPENAI.models
                        modelsToShow.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            model.displayName,
                                            fontSize = 13.sp,
                                            fontWeight = if (model.id == currentModel && !isLocalModel) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        if (model.id == currentModel && !isLocalModel) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("✓", fontSize = 12.sp, color = colors.accent)
                                        }
                                    }
                                },
                                onClick = {
                                    showModelMenu = false
                                    onModelSwitch(model.id, model.displayName)
                                },
                            )
                        }
                    } else {
                        // No API key configured
                        DropdownMenuItem(
                            text = { Text("No API key configured", fontSize = 13.sp, color = colors.textTertiary) },
                            onClick = { showModelMenu = false; onSettings() },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Configure API key...", fontSize = 13.sp, color = colors.accent) },
                        onClick = { showModelMenu = false; onSettings() },
                    )
                } else {
                    // Local models: downloaded models
                    val localPath = kvUtils.getLocalModelPath()
                    if (localPath.isNotEmpty() && java.io.File(localPath).exists()) {
                        val localName = java.io.File(localPath).nameWithoutExtension
                            .replace("-", " ").replace("_", " ")
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$localName (On-device)", fontSize = 13.sp,
                                        fontWeight = if (isLocalModel) FontWeight.Bold else FontWeight.Normal)
                                    if (isLocalModel) {
                                        Spacer(Modifier.width(6.dp))
                                        Text("✓", fontSize = 12.sp, color = colors.accent)
                                    }
                                }
                            },
                            onClick = {
                                showModelMenu = false
                                onModelSwitch("LOCAL", localName)
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("No local model downloaded", fontSize = 13.sp, color = colors.textTertiary) },
                            onClick = { showModelMenu = false; onSettings() },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Download models...", fontSize = 13.sp, color = colors.accent) },
                        onClick = { showModelMenu = false; onSettings() },
                    )
                }
            }
        }
        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
    }
}

// ======================== PERMISSION BANNER ========================

@Composable
private fun PermissionBanner(onClick: () -> Unit, colors: PokeclawColors) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.accent.copy(alpha = 0.12f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Permissions needed. Tap to fix.",
                color = colors.accent,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
    }
}

// ======================== MESSAGE LIST ========================

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    colors: PokeclawColors,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(messages.size - 1) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(messages.size) { index ->
            val message = messages[index]
            when (message.role) {
                ChatMessage.Role.USER -> UserBubble(message.content, colors)
                ChatMessage.Role.ASSISTANT -> AssistantBubble(message.content, colors, message.modelName)
                ChatMessage.Role.SYSTEM -> SystemMessage(message.content, colors)
                ChatMessage.Role.TOOL_GROUP -> ToolGroup(message, colors)
            }
        }
    }
}

// ======================== BUBBLES ========================

@Composable
private fun UserBubble(text: String, colors: PokeclawColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 64.dp, end = 14.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = colors.userBubble,
            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
        ) {
            Text(
                text = text,
                color = colors.userText,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantBubble(text: String, colors: PokeclawColors, modelName: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 64.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            // Avatar
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.pokeclaw_avatar),
                contentDescription = "PokeClaw",
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.width(8.dp))

            // Bubble
            if (text == "...") {
                Surface(
                    color = colors.aiBubble,
                    shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
                ) {
                    TypingIndicator(
                        color = colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                }
            } else {
                Surface(
                    color = colors.aiBubble,
                    shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.aiBubbleBorder),
                ) {
                    Text(
                        text = text,
                        color = colors.aiText,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        // Model name tag below bubble
        if (!modelName.isNullOrEmpty() && text != "...") {
            Text(
                text = modelName,
                fontSize = 9.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 40.dp, top = 1.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun TypingIndicator(color: Color, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dots = listOf(0, 1, 2)

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        dots.forEach { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 200),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun SystemMessage(text: String, colors: PokeclawColors) {
    Text(
        text = text,
        color = colors.textTertiary,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 6.dp),
    )
}

@Composable
private fun ToolGroup(message: ChatMessage, colors: PokeclawColors) {
    Column(
        modifier = Modifier.padding(start = 54.dp, end = 64.dp, top = 2.dp, bottom = 2.dp),
    ) {
        message.toolSteps?.forEach { step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 1.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (step.success) colors.accent else colors.textTertiary),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${step.toolName} → ${step.summary}",
                    fontSize = 12.sp,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

// ======================== INPUT BAR ========================

@Composable
private fun ChatInputBar(
    isProcessing: Boolean,
    inputEnabled: Boolean = true,
    isTaskMode: Boolean,
    isLocalModel: Boolean,
    onTaskModeChange: (Boolean) -> Unit,
    onSendChat: (String) -> Unit,
    onSendTask: (String) -> Unit,
    onStopAll: () -> Unit = {},
    onAttach: () -> Unit,
    colors: PokeclawColors,
    prefillText: String = "",
    prefillIsTask: Boolean = false,
    onPrefillConsumed: () -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    // Consume prefill from prompt chips
    LaunchedEffect(prefillText) {
        if (prefillText.isNotEmpty()) {
            text = prefillText
            if (isLocalModel) onTaskModeChange(prefillIsTask)
            onPrefillConsumed()
        }
    }

    val taskBg = Color(0xFF1A1410)
    val taskBorder = colors.accent.copy(alpha = 0.25f)

    Column(
        modifier = Modifier
            .background(if (isTaskMode && isLocalModel) taskBg else colors.surface)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(
            color = if (isTaskMode && isLocalModel) taskBorder else colors.divider,
            thickness = 1.dp,
        )

        // Segmented Chat/Task toggle — Local LLM only
        if (isLocalModel) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Chat button
                Surface(
                    onClick = { onTaskModeChange(false) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (!isTaskMode) colors.aiBubble else Color.Transparent,
                    border = if (!isTaskMode) androidx.compose.foundation.BorderStroke(1.dp, colors.aiBubbleBorder) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "💬 Chat",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (!isTaskMode) colors.textPrimary else colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 9.dp),
                    )
                }
                // Task button
                Surface(
                    onClick = { onTaskModeChange(true) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isTaskMode) colors.accent else Color.Transparent,
                    border = if (isTaskMode) androidx.compose.foundation.BorderStroke(1.dp, colors.accent) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "🤖 Task",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isTaskMode) Color.White else colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 9.dp),
                    )
                }
            }
        }

        // Input bar — always visible, style changes in Task mode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        when {
                            isLocalModel && isTaskMode -> "Describe a phone task..."
                            !isLocalModel -> "Chat or give a task..."
                            else -> "Chat with local AI..."
                        },
                        color = if (isTaskMode && isLocalModel) colors.accent.copy(alpha = 0.5f) else colors.textTertiary,
                        fontSize = 14.sp,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp, max = 100.dp),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (isTaskMode && isLocalModel) colors.accent else colors.accent.copy(alpha = 0.4f),
                    unfocusedBorderColor = if (isTaskMode && isLocalModel) colors.accent.copy(alpha = 0.6f) else colors.inputBorder,
                    cursorColor = if (isTaskMode && isLocalModel) colors.accent else colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedContainerColor = if (isTaskMode && isLocalModel) taskBg else Color.Transparent,
                    unfocusedContainerColor = if (isTaskMode && isLocalModel) taskBg else Color.Transparent,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                maxLines = 4,
            )

            Spacer(Modifier.width(6.dp))

            FloatingActionButton(
                onClick = {
                    if (isProcessing) {
                        onStopAll()
                    } else if (inputEnabled && text.isNotBlank()) {
                        if (!isLocalModel || isTaskMode) {
                            onSendTask(text.trim())
                            text = ""
                            focusManager.clearFocus()
                        } else {
                            onSendChat(text.trim())
                            text = ""
                            focusManager.clearFocus()
                        }
                    }
                },
                modifier = Modifier
                    .size(34.dp)
                    .alpha(if ((text.isBlank() || !inputEnabled) && !isProcessing) 0.35f else 1f),
                containerColor = when {
                    isProcessing -> Color(0xFFF44336)
                    text.isBlank() -> colors.background
                    isTaskMode && isLocalModel -> colors.accent
                    else -> colors.userBubble
                },
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
            ) {
                Icon(
                    if (isProcessing) Icons.Default.Close else Icons.Default.ArrowUpward,
                    contentDescription = if (isProcessing) "Stop" else "Send",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ======================== SKILL SHORTCUT BAR ========================

@Composable
private fun SkillShortcutBar(
    skills: List<Skill>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSkillTap: (Skill) -> Unit,
    colors: PokeclawColors,
) {
    val categoryIcons = mapOf(
        SkillCategory.INPUT to Icons.Outlined.Keyboard,
        SkillCategory.DISMISS to Icons.Outlined.Close,
        SkillCategory.NAVIGATION to Icons.Outlined.Navigation,
        SkillCategory.MESSAGING to Icons.Outlined.Chat,
        SkillCategory.MEDIA to Icons.Outlined.CameraAlt,
        SkillCategory.GENERAL to Icons.Outlined.AutoAwesome,
    )

    Column {
        // Toggle row
        Surface(
            onClick = onToggle,
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Skills",
                    fontSize = 12.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Expanded skill chips
        if (expanded) {
            // Two rows of chips using FlowRow-style layout
            val rows = skills.chunked((skills.size + 1) / 2)
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (row in rows) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (skill in row) {
                            val icon = categoryIcons[skill.category] ?: Icons.Outlined.AutoAwesome
                            Surface(
                                onClick = { onSkillTap(skill) },
                                shape = RoundedCornerShape(20.dp),
                                color = colors.accent.copy(alpha = 0.1f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = colors.accent,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        skill.name,
                                        fontSize = 11.sp,
                                        color = colors.accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ======================== DOWNLOAD OVERLAY ========================

@Composable
private fun DownloadOverlay(progress: Int, colors: PokeclawColors) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.pokeclaw_avatar),
                    contentDescription = "PokeClaw",
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Downloading your AI brain",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This only happens once",
                    fontSize = 13.sp,
                    color = colors.textTertiary,
                )
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = colors.accent,
                    trackColor = colors.inputBorder,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "$progress%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                )
            }
        }
    }
}

// ======================== EMPTY STATE ========================

@Composable
private fun EmptyStateWithPrompts(
    isLocalModel: Boolean,
    onSelectPrompt: (String, Boolean) -> Unit,
    colors: PokeclawColors,
    modifier: Modifier = Modifier,
) {
    data class Prompt(val text: String, val isTask: Boolean)

    // Cloud: show task examples (user can give tasks from chat)
    // Local: show chat examples (chat only, tasks go to Workflows tab)
    val prompts = if (!isLocalModel) {
        listOf(
            Prompt("What time is it in Tokyo?", false),
            Prompt("Help me write a birthday message", false),
            Prompt("💬 Send hi to Mom on WhatsApp", true),
        )
    } else {
        listOf(
            Prompt("Tell me a joke", false),
            Prompt("What can you do?", false),
            Prompt("Help me draft an email", false),
        )
    }

    val headerText = if (!isLocalModel) "Cloud AI" else "Local AI"

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.pokeclaw_avatar),
            contentDescription = "PokeClaw",
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "PokeClaw",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            headerText,
            fontSize = 12.sp,
            color = colors.accent,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        // Hint text — Local has styled bold parts, Cloud is plain
        if (isLocalModel) {
            Text(
                buildAnnotatedString {
                    append("Chat in ")
                    withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold)) {
                        append("💬 Chat")
                    }
                    append(" mode, or switch to ")
                    withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold)) {
                        append("🤖 Task")
                    }
                    append(" to control your phone")
                },
                fontSize = 11.sp,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        } else {
            Text(
                "Chat and tasks work together \u2014 just type anything",
                fontSize = 11.sp,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        // Suggested prompt chips — same style as Quick Tasks items
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            prompts.forEach { prompt ->
                val barAlpha = if (prompt.isTask) 1f else 0.5f
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = colors.background,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.inputBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPrompt(prompt.text, prompt.isTask) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(38.dp)
                                .background(
                                    colors.accent.copy(alpha = barAlpha),
                                    RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp),
                                ),
                        )
                        Text(
                            prompt.text,
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

// ======================== QUICK TASKS PANEL (v9) ========================

@Composable
private fun QuickTasksPanel(
    isLocalModel: Boolean,
    onFillTask: (String) -> Unit,
    onMonitorClick: () -> Unit,
    monitorActive: Boolean,
    colors: PokeclawColors,
) {
    var expanded by remember { mutableStateOf(true) }

    // Cloud-only tasks at the top (multi-step, Siri/GA can't do these)
    // Cloud-only tasks (multi-step, Siri can't do)
    val cloudOnlyTasks = listOf(
        "🦞 Open Reddit and search for pokeclaw",
        "🎬 Search YouTube for funny cat fails",
        "📦 Install Telegram from Play Store",
        "🐦 Check what's trending on Twitter and tell me",
        "💬 Check my latest WhatsApp chat and summarize it",
        "📋 Copy the latest email subject and Google it",
        "📧 Write an email saying I'll be late today",
    )
    // Reasoning tasks (1-2 tool calls + LLM analysis) — impressive, work on both
    val reasoningTasks = listOf(
        "📵 Check my notifications — anything important?",
        "📋 Read my clipboard and explain what it says",
        "🧹 Check my storage and apps — what can I delete?",
        "🔔 Read my notifications and summarize",
        "🔋 Check my battery and tell me if I need to charge",
    )
    // Simple deterministic tasks (1 tool, no reasoning)
    val deterministicTasks = listOf(
        "💬 Send hi to Mom on WhatsApp",
        "📱 What apps do I have?",
        "🌡️ How hot is my phone?",
        "🔵 Is bluetooth on?",
        "🔋 How much battery left?",
        "📞 Call Mom",
        "💾 How much storage do I have?",
        "📲 What Android version am I running?",
    )
    // Cloud: cloud-only → reasoning → deterministic
    // Local: reasoning first (impressive) → deterministic
    val quickTasks = if (isLocalModel) {
        reasoningTasks + deterministicTasks
    } else {
        cloudOnlyTasks + reasoningTasks + deterministicTasks
    }

    Column(
        modifier = Modifier.background(colors.surface),
    ) {
        HorizontalDivider(color = colors.divider, thickness = 1.dp)

        // Handle bar — ▲ Quick Tasks ▲
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = "Toggle",
                tint = colors.accent,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Quick Task Templates",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = "Toggle",
                tint = colors.accent,
                modifier = Modifier.size(12.dp),
            )
        }

        // Collapsible content
        if (expanded) {
            // Quick task items — scrollable
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                quickTasks.forEach { task ->
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = colors.background,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.inputBorder),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onFillTask(task.substringAfter(" ")) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(38.dp)
                                    .background(colors.accent, RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp)),
                            )
                            Text(
                                task,
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }

            // Background section — always visible, NOT inside scroll
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                    "BACKGROUND",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textTertiary,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )

                // Monitor card
                val monitorBorderColor = if (monitorActive) colors.accent else colors.inputBorder
                Surface(
                    onClick = onMonitorClick,
                    shape = RoundedCornerShape(10.dp),
                    color = colors.background,
                    border = androidx.compose.foundation.BorderStroke(
                        if (monitorActive) 1.dp else 0.5.dp,
                        monitorBorderColor,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    colors.accent.copy(alpha = 0.12f),
                                    RoundedCornerShape(9.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("👁️", fontSize = 15.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (monitorActive) "Active" else "Monitor & Auto-Reply",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            )
                            Text(
                                if (monitorActive) "Monitoring active" else "Watch messages and reply automatically",
                                fontSize = 9.sp,
                                color = colors.textTertiary,
                            )
                        }
                        Text("›", color = colors.textTertiary, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
            } // end Background Column
        }
    }
}

// ======================== SIDEBAR ========================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarContent(
    conversations: List<ChatHistoryManager.ConversationSummary>,
    onNewChat: () -> Unit,
    onSelectConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    onDeleteConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    onRenameConversation: (ChatHistoryManager.ConversationSummary, String) -> Unit,
    onSettings: () -> Unit,
    onModels: () -> Unit,
    colors: PokeclawColors,
) {
    var actionTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var renameTarget by remember { mutableStateOf<ChatHistoryManager.ConversationSummary?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Long-press action menu: Rename / Delete
    if (actionTarget != null) {
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(actionTarget!!.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            renameTarget = actionTarget
                            renameText = actionTarget!!.title
                            actionTarget = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Rename", color = colors.textPrimary)
                        }
                    }
                    TextButton(
                        onClick = {
                            deleteTarget = actionTarget
                            actionTarget = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Delete", color = Color(0xFFF87171))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionTarget = null }) { Text("Cancel", color = colors.textSecondary) }
            },
            containerColor = colors.surface,
        )
    }

    // Delete confirmation
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete conversation?", color = colors.textPrimary) },
            text = { Text(deleteTarget!!.title, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteConversation(deleteTarget!!)
                    deleteTarget = null
                }) { Text("Delete", color = Color(0xFFF87171)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = colors.textSecondary) }
            },
            containerColor = colors.surface,
        )
    }

    // Rename dialog
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename conversation", color = colors.textPrimary) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.inputBorder,
                        cursorColor = colors.accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.trim()
                    if (newName.isNotEmpty() && renameTarget != null) {
                        onRenameConversation(renameTarget!!, newName)
                    }
                    renameTarget = null
                }) { Text("Save", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = colors.textSecondary) }
            },
            containerColor = colors.surface,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(top = 48.dp),
    ) {
        // Title with logo
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.pokeclaw_avatar),
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "PokeClaw",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
        }

        // New Chat button
        Button(
            onClick = onNewChat,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New Chat")
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(8.dp))

        // Recent label
        Text(
            "Recent",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )

        // Conversations
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (conversations.isEmpty()) {
                item {
                    Text(
                        "No conversations yet",
                        fontSize = 13.sp,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
            items(conversations.size) { index ->
                val conv = conversations[index]
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onSelectConversation(conv) },
                            onLongClick = { actionTarget = conv },
                        ),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    Text(
                        text = conv.title,
                        fontSize = 14.sp,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }

        HorizontalDivider(color = colors.divider)

        // Bottom nav
        TextButton(
            onClick = onSettings,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Settings", color = colors.textSecondary)
            }
        }
        TextButton(
            onClick = onModels,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Models", color = colors.textSecondary)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ======================== TASK SKILLS PANEL ========================

@Composable
private fun TaskSkillsPanel(
    isLocalModel: Boolean,
    taskMessages: List<ChatMessage>,
    onMonitorClick: () -> Unit,
    onSendClick: () -> Unit,
    onSkillTap: (String) -> Unit,
    activatingSkill: String?,
    monitorActive: Boolean,
    colors: PokeclawColors,
    modifier: Modifier = Modifier,
) {
    val builtInSkills = remember { SkillRegistry.getUserFacing() }
    val categoryIcons = mapOf(
        SkillCategory.INPUT to Icons.Outlined.Keyboard,
        SkillCategory.DISMISS to Icons.Outlined.Close,
        SkillCategory.NAVIGATION to Icons.Outlined.Navigation,
        SkillCategory.MESSAGING to Icons.Outlined.Chat,
        SkillCategory.GENERAL to Icons.Outlined.AutoAwesome,
    )

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Workflows",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            Text(
                "Background tasks powered by AI — things a single prompt can't do.",
                fontSize = 12.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = colors.accent.copy(alpha = 0.12f),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    "Experimental — more workflows coming soon",
                    fontSize = 11.sp,
                    color = colors.accent,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        // Monitor Messages — always shown (background workflow, both modes need it)
        item {
            SkillCard(
                icon = Icons.Outlined.Visibility,
                title = "Monitor Messages",
                description = "Auto-reply to someone's messages in background",
                onClick = onMonitorClick,
                isActivating = activatingSkill == "monitor",
                isActive = monitorActive,
                colors = colors,
            )
        }

        // Send Message — available on both (workflow card shortcut)
        item {
            SkillCard(
                icon = Icons.Outlined.Send,
                title = "Send Message",
                description = "Send a message to someone via any messaging app",
                onClick = onSendClick,
                colors = colors,
            )
        }

        // Built-in user-facing skills from SkillRegistry
        if (builtInSkills.isNotEmpty()) {
            items(builtInSkills.size) { index ->
                val skill = builtInSkills[index]
                val example = skill.triggerPatterns.firstOrNull()
                    ?.replace(Regex("\\{\\w+\\}"), "...")
                    ?.replace(".+", "...")
                    ?: skill.name
                SkillCard(
                    icon = categoryIcons[skill.category] ?: Icons.Outlined.AutoAwesome,
                    title = skill.name,
                    description = skill.description,
                    onClick = { onSkillTap(example) },
                    colors = colors,
                )
            }
        }

        // Task progress messages (if any)
        if (taskMessages.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Recent",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textTertiary,
                )
            }
            items(taskMessages.size) { index ->
                val msg = taskMessages[index]
                if (msg.role == ChatMessage.Role.USER) {
                    UserBubble(msg.content, colors)
                } else {
                    SystemMessage(msg.content, colors)
                }
            }
        }
    }
}

@Composable
private fun SkillCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    isActivating: Boolean = false,
    isActive: Boolean = false,
    colors: PokeclawColors,
) {
    val activeOrange = Color(0xFFE8751A)
    val borderColor = when {
        isActive -> activeOrange
        isActivating -> colors.accent
        else -> colors.inputBorder
    }
    val cardBg = when {
        isActive -> activeOrange.copy(alpha = 0.08f)
        else -> colors.surface
    }
    val iconBg = when {
        isActive -> activeOrange.copy(alpha = 0.15f)
        else -> colors.accent.copy(alpha = 0.12f)
    }
    val iconTint = if (isActive) activeOrange else colors.accent

    // Progress animation
    val progress by animateFloatAsState(
        targetValue = if (isActivating) 1f else 0f,
        animationSpec = if (isActivating) tween(2000, easing = LinearEasing) else snap(),
        label = "skillProgress",
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        border = androidx.compose.foundation.BorderStroke(if (isActive) 1.dp else 0.5.dp, borderColor),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(iconBg, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isActive) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = activeOrange, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (isActive) activeOrange else colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (isActive) "Running in background" else description,
                        fontSize = 12.sp,
                        color = if (isActive) activeOrange.copy(alpha = 0.7f) else colors.textTertiary,
                        lineHeight = 16.sp,
                    )
                }
                if (!isActive && !isActivating) {
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
                }
            }

            // Progress bar during activation
            if (isActivating) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = activeOrange,
                    trackColor = colors.inputBorder,
                )
            }
        }
    }
}

// ======================== SKILL DIALOGS ========================

@Composable
private fun MonitorDialog(
    onDismiss: () -> Unit,
    onStart: (contact: String) -> Unit,
    colors: PokeclawColors,
) {
    var contact by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf("WhatsApp") }
    var appMenuExpanded by remember { mutableStateOf(false) }
    var selectedTone by remember { mutableStateOf("Casual") }
    val apps = listOf("WhatsApp", "LINE", "Telegram", "WeChat")
    val tones = listOf("Casual", "Formal", "Funny")

    // Centered modal overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.44f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        // Centered card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { /* block clicks from dismissing */ },
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(3.dp)
                        .align(Alignment.CenterHorizontally)
                        .background(colors.textTertiary, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(14.dp))

                // Title
                Text(
                    "\uD83D\uDC41\uFE0F Monitor & Auto-Reply",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(12.dp))

                // Contact row: label + input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Contact",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.width(50.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = contact,
                        onValueChange = { contact = it },
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp,
                            color = colors.textPrimary,
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .background(colors.background, RoundedCornerShape(8.dp))
                                    .then(
                                        Modifier.border(1.dp, colors.inputBorder, RoundedCornerShape(8.dp))
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                if (contact.isEmpty()) {
                                    Text("e.g. Mom, Girlfriend", fontSize = 12.sp, color = colors.textTertiary)
                                }
                                innerTextField()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))

                // App row: label + dropdown
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "App",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.width(50.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.background,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.inputBorder),
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable { appMenuExpanded = true }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(selectedApp, fontSize = 12.sp, color = colors.textPrimary)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                            }
                        }
                        DropdownMenu(
                            expanded = appMenuExpanded,
                            onDismissRequest = { appMenuExpanded = false },
                        ) {
                            apps.forEach { app ->
                                DropdownMenuItem(
                                    text = { Text(app, fontSize = 12.sp) },
                                    onClick = { selectedApp = app; appMenuExpanded = false },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Tone row: label + pill chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Tone",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.width(50.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tones.forEach { tone ->
                            val isOn = tone == selectedTone
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isOn) colors.userBubble.copy(alpha = 0.1f) else colors.background,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isOn) colors.userBubble else colors.inputBorder,
                                ),
                            ) {
                                Text(
                                    tone,
                                    fontSize = 11.sp,
                                    color = if (isOn) colors.accent else colors.textSecondary,
                                    modifier = Modifier
                                        .clickable { selectedTone = tone }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))

                // Start Monitoring button
                Surface(
                    onClick = { if (contact.isNotBlank()) onStart(contact.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = colors.userBubble,
                ) {
                    Text(
                        "Start Monitoring",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 11.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SendMessageDialog(
    onDismiss: () -> Unit,
    onSend: (contact: String, app: String, message: String) -> Unit,
    colors: PokeclawColors,
) {
    var contact by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf("WhatsApp") }
    var appMenuExpanded by remember { mutableStateOf(false) }
    val apps = listOf("WhatsApp", "Telegram", "Messages")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text("Send Message", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        },
        text = {
            Column {
                Text("With a smarter LLM, you can just type:", fontSize = 11.sp, color = colors.textTertiary)
                Spacer(Modifier.height(2.dp))
                Text("\"send hi to Mom on WhatsApp\"", fontSize = 11.sp, color = colors.accent.copy(alpha = 0.7f))
                Spacer(Modifier.height(16.dp))

                // Fill-in-the-blank: "Send [___] to [___] on [WhatsApp ▾]"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Send ", fontSize = 15.sp, color = colors.textPrimary)
                    Text("\"", fontSize = 15.sp, color = colors.textTertiary)
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        placeholder = { Text("message", color = colors.textTertiary, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.inputBorder,
                            cursorColor = colors.accent,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                        ),
                    )
                    Text("\"", fontSize = 15.sp, color = colors.textTertiary)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("to ", fontSize = 15.sp, color = colors.textPrimary)
                    OutlinedTextField(
                        value = contact,
                        onValueChange = { contact = it },
                        placeholder = { Text("name", color = colors.textTertiary, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.inputBorder,
                            cursorColor = colors.accent,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                        ),
                    )
                    Text(" on ", fontSize = 15.sp, color = colors.textPrimary)
                    Box {
                        Surface(
                            onClick = { appMenuExpanded = true },
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.inputBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(selectedApp, fontSize = 13.sp, color = colors.textPrimary)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                            }
                        }
                        DropdownMenu(
                            expanded = appMenuExpanded,
                            onDismissRequest = { appMenuExpanded = false },
                        ) {
                            apps.forEach { app ->
                                DropdownMenuItem(
                                    text = { Text(app) },
                                    onClick = { selectedApp = app; appMenuExpanded = false },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (contact.isNotBlank() && message.isNotBlank()) onSend(contact.trim(), selectedApp, message.trim()) },
                enabled = contact.isNotBlank() && message.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        },
    )
}

// ======================== ACTIVE TASK BAR ========================

@Composable
private fun ActiveTaskBar(
    tasks: List<String>,
    isRunningTask: Boolean = false,
    onStopTask: (String) -> Unit,
    onStopAll: () -> Unit,
    colors: PokeclawColors,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
    ) {
        // Running task bar (agent task in progress)
        if (isRunningTask) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0xFF332200))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = androidx.compose.ui.graphics.Color(0xFFFF9800),
                            shape = androidx.compose.foundation.shape.CircleShape,
                        )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Task running...",
                    color = androidx.compose.ui.graphics.Color(0xFFFF9800),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Stop",
                    color = androidx.compose.ui.graphics.Color(0xFFF44336),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onStopAll() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        // Monitor tasks bar
        if (tasks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                            shape = androidx.compose.foundation.shape.CircleShape,
                        )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (tasks.size == 1) "Monitoring: ${tasks[0]}" else "${tasks.size} monitoring",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "▴" else "▾",
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                )
            }
        }

        // Expanded — show each task with stop button
        if (expanded) {
            Divider(color = colors.textSecondary.copy(alpha = 0.2f), thickness = 0.5.dp)
            tasks.forEach { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                                shape = androidx.compose.foundation.shape.CircleShape,
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = task,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Stop",
                        color = androidx.compose.ui.graphics.Color(0xFFF44336),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onStopTask(task) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (tasks.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = "Stop All",
                        color = androidx.compose.ui.graphics.Color(0xFFF44336),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onStopAll() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
