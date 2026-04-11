// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.AppViewModel
import io.agents.pokeclaw.ServiceBindingState
import io.agents.pokeclaw.TaskEvent
import io.agents.pokeclaw.agent.TaskPromptEnvelope
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.service.AutoReplyManager
import io.agents.pokeclaw.ui.settings.SettingsActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.ExecutorService

data class TaskFlowUiState(
    val messages: SnapshotStateList<ChatMessage>,
    val modelStatus: MutableState<String>,
    val isProcessing: MutableState<Boolean>,
)

/**
 * Owns task-mode send flow, typed TaskEvent rendering, and monitor start wiring.
 *
 * ComposeChatActivity keeps the shell; this controller keeps task-specific behavior.
 */
class TaskFlowController(
    private val activity: ComponentActivity,
    private val executor: ExecutorService,
    private val appViewModel: AppViewModel,
    private val chatSessionController: ChatSessionController,
    private val uiState: TaskFlowUiState,
    private val onPersistConversation: () -> Unit,
) {

    companion object {
        private const val TAG = "TaskFlowController"
    }

    private var sendTaskRetryCount = 0

    fun sendTask(text: String) {
        val lower = text.lowercase()
        if (lower.contains("monitor") || lower.contains("auto-reply") || lower.contains("auto reply") || lower.contains("autoreply")
            || lower.contains("watch") && (lower.contains("message") || lower.contains("reply"))) {
            handleMonitorTask(text)
            return
        }

        when (AppCapabilityCoordinator.accessibilityState(activity)) {
            ServiceBindingState.DISABLED -> {
                Toast.makeText(activity, "Enable Accessibility Service to run tasks", Toast.LENGTH_LONG).show()
                addSystem("⚠️ Task mode needs Accessibility Service enabled. Opening Settings...")
                openSettings()
                sendTaskRetryCount = 0
                return
            }
            ServiceBindingState.CONNECTING -> {
                if (sendTaskRetryCount >= 1) {
                    Toast.makeText(activity, "Accessibility service not connected. Try toggling it off and on.", Toast.LENGTH_LONG).show()
                    addSystem("Accessibility service didn't connect. Try toggling it off and on in Settings.")
                    openSettings()
                    sendTaskRetryCount = 0
                    return
                }
                sendTaskRetryCount++
                addSystem("Accessibility service connecting, please wait...")
                executor.submit {
                    val connected = ClawAccessibilityService.awaitRunning(5000)
                    activity.runOnUiThread {
                        if (connected) {
                            sendTask(text)
                        } else {
                            Toast.makeText(activity, "Accessibility service didn't connect", Toast.LENGTH_LONG).show()
                            addSystem("Accessibility service didn't connect. Go to Settings and toggle it off then on.")
                            sendTaskRetryCount = 0
                        }
                    }
                }
                return
            }
            ServiceBindingState.READY -> Unit
        }
        sendTaskRetryCount = 0

        ensureNotificationPermission()
        uiState.isProcessing.value = false

        if (!KVUtils.hasLlmConfig()) {
            Toast.makeText(activity, "Configure LLM in Settings first", Toast.LENGTH_LONG).show()
            return
        }

        val agentPromptOverride = buildAgentPromptOverride(text)
        addUser(text)
        uiState.isProcessing.value = true
        XLog.i(TAG, "sendTask: isProcessing=TRUE")
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        val taskId = "task_${System.currentTimeMillis()}"

        executor.submit {
            try {
                appViewModel.stopTask()
                Thread.sleep(200)
            } catch (_: Exception) {
            }
            chatSessionController.prepareForTaskStart()

            activity.runOnUiThread {
                try {
                    appViewModel.startTask(text, taskId, agentPromptOverride = agentPromptOverride) { event ->
                        activity.runOnUiThread { handleTaskEvent(event) }
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "sendTask failed: ${e.message}", e)
                    addSystem("Error: ${e.message}")
                    cleanupAfterTask()
                }
            }
        }
    }

    fun handleMonitorTask(text: String) {
        val target = MonitorTargetParser.fromTaskText(text)
        if (target == null) {
            addUser(text)
            addSystem("Could not figure out who to monitor. Try: \"Monitor Mom on WhatsApp\"")
            return
        }

        startMonitor(target, typedInput = text)
    }

    fun startMonitor(target: MonitorTargetSpec, typedInput: String? = null) {
        val trimmedLabel = target.label.trim()
        if (trimmedLabel.isEmpty()) {
            addSystem("Could not figure out who to monitor. Try: \"Monitor Mom on WhatsApp\"")
            return
        }

        addUser(typedInput ?: "monitor ${trimmedLabel} on ${target.app}")
        val missing = AppCapabilityCoordinator.missingMonitorRequirements(activity)
        if (missing.isNotEmpty()) {
            Toast.makeText(
                activity,
                "Enable ${missing.joinToString(" & ") { it.label }} in Settings first",
                Toast.LENGTH_LONG
            ).show()
            openSettings()
            return
        }

        val contact = trimmedLabel
        val app = target.app
        uiState.isProcessing.value = true
        addSystem("Setting up auto-reply for $contact on $app...")

        val autoReplyManager = AutoReplyManager.getInstance()
        autoReplyManager.addTarget(contact, app)
        autoReplyManager.setEnabled(true)
        XLog.i(TAG, "startMonitor: enabled auto-reply for '${target.displayLabel}'")

        Handler(Looper.getMainLooper()).postDelayed({
            uiState.isProcessing.value = false
            addSystem("✓ Auto-reply is now active for ${target.displayLabel}.\nMonitoring in background — you can stop anytime from the bar above.")
            XLog.i(TAG, "startMonitor: monitor active, staying in PokeClaw")
        }, 1500)
    }

    private fun handleTaskEvent(event: TaskEvent) {
        try {
            when (event) {
                is TaskEvent.Completed -> {
                    replaceTypingIndicator(event.answer, event.modelName)
                    cleanupAfterTask()
                    checkAutoReplyConfirmation()
                }
                is TaskEvent.Failed -> {
                    replaceTypingIndicator("Error: ${event.error}")
                    cleanupAfterTask()
                }
                is TaskEvent.Cancelled -> {
                    removeTypingIndicator()
                    cleanupAfterTask()
                }
                is TaskEvent.Blocked -> {
                    replaceTypingIndicator("Blocked by system dialog.")
                    cleanupAfterTask()
                }
                is TaskEvent.ToolAction -> {
                    if (!event.toolName.contains("Finish", ignoreCase = true)) {
                        removeTypingIndicator()
                        addSystem("${event.toolName}...")
                    }
                }
                is TaskEvent.ToolResult -> {
                    if (!event.success) addSystem("${event.toolName} failed")
                }
                is TaskEvent.Response -> replaceTypingIndicator(event.text)
                is TaskEvent.Progress -> addSystem(event.description)
                is TaskEvent.LoopStart, is TaskEvent.TokenUpdate, is TaskEvent.Thinking -> Unit
            }
        } catch (e: Exception) {
            XLog.w(TAG, "handleTaskEvent error", e)
        }
    }

    private fun replaceTypingIndicator(text: String, actualModelName: String? = null) {
        val modelTag = actualModelName
            ?: uiState.modelStatus.value.removePrefix("● ").split(" ·").firstOrNull()?.trim()
            ?: ""
        val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) {
            uiState.messages[idx] = ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag)
        } else {
            uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag))
        }
        onPersistConversation()
    }

    private fun removeTypingIndicator() {
        val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) uiState.messages.removeAt(idx)
    }

    private fun cleanupAfterTask() {
        XLog.i(TAG, "cleanupAfterTask: isProcessing=FALSE")
        uiState.isProcessing.value = false
        appViewModel.clearTaskCallback()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                chatSessionController.loadModelIfReady()
            } catch (e: Exception) {
                XLog.e(TAG, "cleanupAfterTask: loadModel error", e)
            }
        }, 500)
    }

    private fun checkAutoReplyConfirmation() {
        val autoReplyManager = AutoReplyManager.getInstance()
        if (!autoReplyManager.isEnabled) return
        val contacts = autoReplyManager.monitoredContacts.joinToString(", ")
        addSystem("✓ Auto-reply active for $contacts.\nMonitoring in background — stop from bar above.")
        XLog.i(TAG, "checkAutoReplyConfirmation: monitor active, staying in PokeClaw")
    }

    private fun ensureNotificationPermission() {
        if (!AppCapabilityCoordinator.isNotificationPermissionGranted(activity)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        if (!ForegroundService.isRunning()) {
            ForegroundService.start(activity)
        }
    }

    private fun addUser(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.USER, text))
    }

    private fun addSystem(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    private fun openSettings() {
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }

    private fun buildAgentPromptOverride(rawTask: String): String? {
        if (ModelConfigRepository.snapshot().isLocalActive()) {
            return null
        }

        val historyLines = uiState.messages.mapNotNull { message ->
            val content = message.content.trim()
            if (content.isEmpty() || content == "...") {
                return@mapNotNull null
            }
            when (message.role) {
                ChatMessage.Role.USER -> "User: $content"
                ChatMessage.Role.ASSISTANT -> "Assistant: $content"
                ChatMessage.Role.SYSTEM -> "System: $content"
                ChatMessage.Role.TOOL_GROUP -> null
            }
        }

        return TaskPromptEnvelope.build(historyLines, rawTask)
    }
}
