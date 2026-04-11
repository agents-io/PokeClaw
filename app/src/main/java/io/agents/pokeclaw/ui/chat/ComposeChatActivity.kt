// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.ServiceBindingState
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.floating.FloatingCircleManager
import io.agents.pokeclaw.ui.settings.LlmConfigActivity
import io.agents.pokeclaw.ui.settings.SettingsActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * PokeClaw Chat Activity — Compose shell for the chat screen.
 *
 * Chat runtime ownership lives in [ChatSessionController].
 * This activity keeps lifecycle wiring, task flows, and sidebar/history UI state.
 */
class ComposeChatActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ComposeChatActivity"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_CHAT = "chat"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val conversationStore by lazy { ConversationStore(this) }

    // Compose state — observed by ChatScreen
    private val _messages = mutableStateListOf<ChatMessage>()
    private val _modelStatus = mutableStateOf("No model loaded")
    private val _isLocalModelActive = mutableStateOf(ModelConfigRepository.isLocalActive())
    private val _needsPermission = mutableStateOf(false)
    private val _isAwaitingReply = mutableStateOf(false)
    private val _isTaskRunning = mutableStateOf(false)
    private val _inputEnabled = mutableStateOf(true)    // False when model not ready (no task running)
    private val _conversations = mutableStateListOf<ChatHistoryManager.ConversationSummary>()
    private val _isDownloading = mutableStateOf(false)
    private val _downloadProgress = mutableStateOf(0)

    // Session-level token tracking for chat mode
    private val _sessionTokens = mutableStateOf(0)
    private val _sessionCost = mutableStateOf(0.0)

    private val chatSessionController by lazy {
        ChatSessionController(
            activity = this,
            executor = executor,
            uiState = ChatSessionUiState(
                messages = _messages,
                modelStatus = _modelStatus,
                isAwaitingReply = _isAwaitingReply,
                inputEnabled = _inputEnabled,
                isDownloading = _isDownloading,
                downloadProgress = _downloadProgress,
                sessionTokens = _sessionTokens,
                sessionCost = _sessionCost,
            ),
            onPersistConversation = { saveChat() },
            onRefreshSidebarHistory = { refreshSidebarHistory() }
        )
    }

    private val taskFlowController by lazy {
        TaskFlowController(
            activity = this,
            executor = executor,
            appViewModel = appViewModel,
            chatSessionController = chatSessionController,
            uiState = TaskFlowUiState(
                messages = _messages,
                modelStatus = _modelStatus,
                isAwaitingReply = _isAwaitingReply,
                isTaskRunning = _isTaskRunning,
            ),
            onPersistConversation = { saveChat() },
        )
    }

    private val activeTaskShellController by lazy {
        ActiveTaskShellController(appViewModel = appViewModel)
    }

    private val missedCallFlowController by lazy {
        MissedCallFlowController(activity = this)
    }

    // Permission polling
    private val permHandler = Handler(Looper.getMainLooper())
    private val permPoller = object : Runnable {
        override fun run() {
            _needsPermission.value =
                AppCapabilityCoordinator.accessibilityState(this@ComposeChatActivity) != ServiceBindingState.READY
            permHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // Hide floating circle only when no task is running
        // (task running = keep floating pill visible for step/token status)
        try {
            if (!appViewModel.isTaskRunning()) {
                FloatingCircleManager.hide()
            } else {
                XLog.d(TAG, "onCreate: task running, keeping floating circle visible")
            }
        } catch (_: Exception) {}

        // Check for updates
        io.agents.pokeclaw.utils.UpdateChecker.checkForUpdate(this)

        // Status bar color
        val themeColors = ThemeManager.getColors()
        window.statusBarColor = themeColors.toolbarBg

        // Build Compose colors from ThemeManager
        val composeColors = with(ThemeManager) { themeColors.toComposeColors() }

        setContent {
            val activeTasks by activeTaskShellController.activeTasks.collectAsState()
            val monitorActive by activeTaskShellController.monitorActive.collectAsState()
            val missedCallFollowUpActive by activeTaskShellController.missedCallActive.collectAsState()

            ChatScreen(
                messages = _messages.toList(),
                modelStatus = _modelStatus.value,
                needsPermission = _needsPermission.value,
                isAwaitingReply = _isAwaitingReply.value,
                isTaskRunning = _isTaskRunning.value,
                inputEnabled = _inputEnabled.value,
                isDownloading = _isDownloading.value,
                downloadProgress = _downloadProgress.value,
                isLocalModel = _isLocalModelActive.value,
                sessionTokens = _sessionTokens.value,
                sessionCost = _sessionCost.value,
                onSendChat = { sendChat(it) },
                onSendTask = { taskFlowController.sendTask(it) },
                onStartMonitor = { target -> taskFlowController.startMonitor(target) },
                onStartMissedCallFollowUp = { prompt ->
                    missedCallFlowController.start(prompt, conversationStore.currentConversationId)
                },
                onSendDirectMessage = { contact, app, message ->
                    taskFlowController.sendTask("send \"$message\" to $contact on $app")
                },
                onNewChat = { newChat() },
                onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onOpenModels = { startActivity(Intent(this, LlmConfigActivity::class.java)) },
                onFixPermissions = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onAttach = { Toast.makeText(this, "Image upload coming soon", Toast.LENGTH_SHORT).show() },
                conversations = _conversations.toList(),
                onSelectConversation = { loadConversation(it) },
                onDeleteConversation = { conv ->
                    val deleted = conversationStore.deleteConversation(conv)
                    XLog.i(TAG, "Delete conversation: ${conv.file.absolutePath} deleted=$deleted")
                    refreshSidebarHistory()
                },
                onRenameConversation = { conv, newName ->
                    val renamed = conversationStore.renameConversation(conv, newName)
                    XLog.i(TAG, "Rename conversation: '${conv.title}' → '$newName' renamed=$renamed")
                    refreshSidebarHistory()
                },
                activeTasks = activeTasks,
                monitorActive = monitorActive,
                missedCallFollowUpActive = missedCallFollowUpActive,
                onStopTask = { contact ->
                    _isTaskRunning.value = appViewModel.isTaskRunning()
                    Toast.makeText(
                        this,
                        activeTaskShellController.stopTask(contact),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onStopAllTasks = {
                    _isAwaitingReply.value = false
                    _isTaskRunning.value = false
                    Toast.makeText(
                        this,
                        activeTaskShellController.stopAllTasks(),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onModelSwitch = { modelId, displayName -> switchModel(modelId, displayName) },
                colors = composeColors,
            )
        }

        lifecycleScope.launch {
            BackgroundChatBridge.events.collect { event ->
                if (event.conversationId == conversationStore.currentConversationId) {
                    val alreadyPresent = _messages.any {
                        it.timestamp == event.message.timestamp &&
                            it.role == event.message.role &&
                            it.content == event.message.content
                    }
                    if (!alreadyPresent) {
                        _messages.add(event.message)
                        saveChat()
                    }
                } else {
                    refreshSidebarHistory()
                }
            }
        }

        refreshSidebarHistory()

        // Restore last conversation if Activity was recreated (e.g., system killed it during a task)
        if (_messages.isEmpty()) {
            conversationStore.restoreLastConversation()?.let { restored ->
                syncSidebar(restored.conversations)
                if (restored.messages.isNotEmpty()) {
                    _messages.addAll(restored.messages)
                    XLog.i(
                        TAG,
                        "Restored ${restored.messages.size} messages from conversation ${restored.conversationId}"
                    )
                }
            }
        }

        chatSessionController.loadModelIfReady(
            conversationId = conversationStore.currentConversationId,
            visibleMessages = _messages.toList(),
        )
        _isLocalModelActive.value = ModelConfigRepository.isLocalActive()

        // Release local LLM conversation before task starts so the agent can use the engine
        // (LiteRT-LM only supports 1 session at a time)
        appViewModel.onBeforeTask = {
            chatSessionController.releaseForTask()
        }

        // Debug: auto-trigger task from ADB intent
        // Usage: adb shell am start -n io.agents.pokeclaw/.ui.chat.ComposeChatActivity --es task "open my camera"
        handleIntentAutomation(intent, initialDelayMs = 2000)

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentAutomation(intent, initialDelayMs = 1000)
    }

    override fun onResume() {
        super.onResume()
        _needsPermission.value =
            AppCapabilityCoordinator.accessibilityState(this) != ServiceBindingState.READY
        _isLocalModelActive.value = ModelConfigRepository.isLocalActive()
        _isTaskRunning.value = appViewModel.isTaskRunning()
        refreshSidebarHistory()
        permHandler.removeCallbacks(permPoller)
        permHandler.postDelayed(permPoller, 1000)
        activeTaskShellController.onResume()
        chatSessionController.onResume(
            conversationId = conversationStore.currentConversationId,
            visibleMessages = _messages.toList(),
        )
    }

    override fun onPause() {
        super.onPause()
        saveChat()
        permHandler.removeCallbacks(permPoller)
        activeTaskShellController.onPause()
        chatSessionController.onPause(conversationStore.currentConversationId)
    }

    override fun onDestroy() {
        super.onDestroy()
        chatSessionController.onDestroy()
        executor.shutdown()
    }

    // ==================== CHAT ====================

    private fun sendChat(text: String) {
        chatSessionController.sendChat(text)
    }

    private fun handleIntentAutomation(intent: Intent?, initialDelayMs: Long) {
        val taskText = intent?.getStringExtra(EXTRA_TASK)?.takeIf { it.isNotBlank() }
        val chatText = intent?.getStringExtra(EXTRA_CHAT)?.takeIf { it.isNotBlank() }
        val automationText = taskText ?: chatText ?: return
        val isTask = taskText != null

        XLog.i(
            TAG,
            if (isTask) "Auto-task from intent: $automationText" else "Auto-chat from intent: $automationText"
        )

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (chatSessionController.isModelReady()) {
                    if (isTask) {
                        taskFlowController.sendTask(automationText)
                    } else {
                        sendChat(automationText)
                    }
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }, initialDelayMs)
    }

    private fun syncTaskAgentConfig() {
        if (!appViewModel.updateAgentConfig()) {
            XLog.w(TAG, "syncTaskAgentConfig: failed to update task agent config")
        }
    }

    private fun switchModel(modelId: String, displayName: String) {
        chatSessionController.switchModel(modelId, displayName)
        _isLocalModelActive.value = ModelConfigRepository.isLocalActive()
        if (modelId != "NONE") {
            syncTaskAgentConfig()
        }
        XLog.i(TAG, "Model switched to: $modelId ($displayName)")
    }

    private fun newChat() {
        val session = conversationStore.startNewConversation(_messages, currentConversationModelName())
        syncSidebar(session.conversations)
        _messages.clear()
        _sessionTokens.value = 0
        _sessionCost.value = 0.0
        _isAwaitingReply.value = false
        _isTaskRunning.value = false
        chatSessionController.startNewConversationRuntime()
    }

    private fun loadConversation(conv: ChatHistoryManager.ConversationSummary) {
        val session = conversationStore.openConversation(conv, _messages, currentConversationModelName())
        syncSidebar(session.conversations)
        _messages.clear()
        _messages.addAll(session.messages)
        _isAwaitingReply.value = false
        _isTaskRunning.value = false
        chatSessionController.restoreConversationRuntime(session.conversationId, session.messages)
    }

    private fun saveChat() {
        syncSidebar(conversationStore.saveCurrent(_messages, currentConversationModelName()))
    }

    private fun refreshSidebarHistory() {
        syncSidebar(conversationStore.refreshSidebar())
    }

    private fun syncSidebar(convos: List<ChatHistoryManager.ConversationSummary>) {
        _conversations.clear()
        _conversations.addAll(convos)
    }

    private fun currentConversationModelName(): String {
        val config = ModelConfigRepository.snapshot()
        return if (config.isLocalActive()) {
            config.local.displayName.ifBlank {
                KVUtils.getLocalModelPath().substringAfterLast('/').substringBeforeLast('.')
            }
        } else {
            config.activeCloud.modelName
        }
    }
}
