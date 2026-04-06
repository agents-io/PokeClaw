// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import io.agents.pokeclaw.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    onSendChat: (String) -> Unit,
    onSendTask: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onFixPermissions: () -> Unit,
    onAttach: () -> Unit,
    conversations: List<ChatHistoryManager.ConversationSummary>,
    onSelectConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    colors: PokeclawColors = AbyssDark,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Shared state for prompt chip → input bar prefill
    var prefillText by remember { mutableStateOf("") }
    var prefillIsTask by remember { mutableStateOf(false) }

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
            modifier = Modifier.imePadding(),
            containerColor = colors.background,
            topBar = {
                ChatTopBar(
                    modelStatus = modelStatus,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onNewChat = onNewChat,
                    onSettings = onOpenSettings,
                    colors = colors,
                )
            },
            bottomBar = {
                if (!isDownloading) {
                    ChatInputBar(
                        isProcessing = isProcessing,
                        onSendChat = onSendChat,
                        onSendTask = onSendTask,
                        onAttach = onAttach,
                        colors = colors,
                        prefillText = prefillText,
                        prefillIsTask = prefillIsTask,
                        onPrefillConsumed = { prefillText = "" },
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Messages or empty state with suggested prompts
                val userMessages = messages.filter { it.role != ChatMessage.Role.SYSTEM }
                if (userMessages.isEmpty() && !isDownloading) {
                    EmptyStateWithPrompts(
                        onSelectPrompt = { text, isTask ->
                            prefillText = text
                            prefillIsTask = isTask
                        },
                        colors = colors,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (!isDownloading) {
                    MessageList(
                        messages = messages,
                        colors = colors,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Download blocking overlay
                if (isDownloading) {
                    DownloadOverlay(progress = downloadProgress, colors = colors)
                }
            }
        }
    }
}

// ======================== TOP BAR ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    modelStatus: String,
    onMenuClick: () -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
    colors: PokeclawColors,
) {
    Column {
        TopAppBar(
            title = {
                Text(
                    "PokeClaw",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            },
            navigationIcon = {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            },
            actions = {
                IconButton(onClick = onNewChat) {
                    Icon(Icons.Default.Edit, contentDescription = "New Chat")
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
        // Model status
        Text(
            text = modelStatus,
            fontSize = 11.sp,
            color = colors.textTertiary,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
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
                ChatMessage.Role.ASSISTANT -> AssistantBubble(message.content, colors)
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
private fun AssistantBubble(text: String, colors: PokeclawColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 64.dp, top = 3.dp, bottom = 3.dp),
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
            // Typing indicator
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
    onSendChat: (String) -> Unit,
    onSendTask: (String) -> Unit,
    onAttach: () -> Unit,
    colors: PokeclawColors,
    prefillText: String = "",
    prefillIsTask: Boolean = false,
    onPrefillConsumed: () -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    var isTaskMode by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Consume prefill from prompt chips
    LaunchedEffect(prefillText) {
        if (prefillText.isNotEmpty()) {
            text = prefillText
            isTaskMode = prefillIsTask
            onPrefillConsumed()
        }
    }

    Column(modifier = Modifier.background(colors.surface)) {
        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

        // Mode toggle tabs — Material Icons, no emoji
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeTab(
                label = "Chat",
                icon = Icons.Outlined.ChatBubbleOutline,
                selected = !isTaskMode,
                onClick = { isTaskMode = false },
                colors = colors,
                modifier = Modifier.weight(1f),
            )
            ModeTab(
                label = "Task",
                icon = Icons.Outlined.TouchApp,
                selected = isTaskMode,
                onClick = { isTaskMode = true },
                colors = colors,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Input — compact height like ChatGPT
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        if (isTaskMode) "Tell me what to do..." else "Ask anything...",
                        color = colors.textTertiary,
                        fontSize = 14.sp,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp, max = 100.dp),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent.copy(alpha = 0.4f),
                    unfocusedBorderColor = colors.inputBorder,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                maxLines = 4,
            )

            Spacer(Modifier.width(4.dp))

            // Send button
            FloatingActionButton(
                onClick = {
                    if (text.isNotBlank()) {
                        if (isTaskMode) {
                            onSendTask(text.trim())
                            text = ""
                            focusManager.clearFocus()
                        } else if (!isProcessing) {
                            onSendChat(text.trim())
                            text = ""
                            focusManager.clearFocus()
                        }
                    }
                },
                modifier = Modifier.size(36.dp),
                containerColor = if (text.isBlank()) colors.accent.copy(alpha = 0.4f) else colors.accent,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
            ) {
                Icon(
                    if (isTaskMode) Icons.Outlined.TouchApp else Icons.Default.ArrowUpward,
                    contentDescription = "Send",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ModeTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    colors: PokeclawColors,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) colors.accent.copy(alpha = 0.12f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) colors.accent else colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) colors.accent else colors.textTertiary,
            )
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
    onSelectPrompt: (String, Boolean) -> Unit,
    colors: PokeclawColors,
    modifier: Modifier = Modifier,
) {
    data class Prompt(val text: String, val isTask: Boolean)
    val prompts = listOf(
        Prompt("What can you do?", false),
        Prompt("Summarize this for me", false),
        Prompt("Open WhatsApp and say hi to Mom", true),
        Prompt("Monitor Mom's messages and auto-reply", true),
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.pokeclaw_avatar),
            contentDescription = "PokeClaw",
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp)),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "PokeClaw",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "On-device AI, entirely yours",
            fontSize = 14.sp,
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(32.dp))

        // Suggested prompt chips
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            prompts.forEach { prompt ->
                val chipColor = if (prompt.isTask) colors.accent else colors.accent.copy(alpha = 0.7f)
                Surface(
                    onClick = { onSelectPrompt(prompt.text, prompt.isTask) },
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surface,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.inputBorder),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Color bar indicator instead of emoji
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(chipColor, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            prompt.text,
                            fontSize = 14.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

// ======================== SIDEBAR ========================

@Composable
private fun SidebarContent(
    conversations: List<ChatHistoryManager.ConversationSummary>,
    onNewChat: () -> Unit,
    onSelectConversation: (ChatHistoryManager.ConversationSummary) -> Unit,
    onSettings: () -> Unit,
    onModels: () -> Unit,
    colors: PokeclawColors,
) {
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
                        .clickable { onSelectConversation(conv) },
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

