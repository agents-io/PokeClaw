// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import io.agents.pokeclaw.TaskEvent
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import io.agents.pokeclaw.agent.ModelPricing
import io.agents.pokeclaw.agent.llm.EngineHolder
import io.agents.pokeclaw.agent.llm.LlmClient
import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.agent.llm.LocalModelManager
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.appViewModel
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.agents.pokeclaw.channel.Channel as ChannelEnum
import io.agents.pokeclaw.floating.FloatingCircleManager
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.ui.settings.LlmConfigActivity
import io.agents.pokeclaw.ui.settings.SettingsActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.agent.TaskShortcuts
import io.agents.pokeclaw.utils.XLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.Executors

/**
 * PokeClaw Chat Activity — Compose UI with LiteRT-LM backend.
 *
 * Backend logic (LLM engine, chat history, compaction) stays here.
 * UI is delegated to ChatScreen composable.
 */
class ComposeChatActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ComposeChatActivity"
    }

    private var conversationId = "chat_${System.currentTimeMillis()}"
    private val executor = Executors.newSingleThreadExecutor()
    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private var conversation: Conversation? = null
    private var isModelReady = false

    // Cloud LLM chat support
    private var cloudClient: LlmClient? = null
    private var cloudModelName: String? = null
    private val cloudHistory = mutableListOf<dev.langchain4j.data.message.ChatMessage>()

    // Compose state — observed by ChatScreen
    private val _messages = mutableStateListOf<ChatMessage>()
    private val _modelStatus = mutableStateOf("No model loaded")
    private val _needsPermission = mutableStateOf(false)
    private val _isProcessing = mutableStateOf(false)   // True ONLY when a chat/task is actively running
    private val _inputEnabled = mutableStateOf(true)    // False when model not ready (no task running)
    private val _conversations = mutableStateListOf<ChatHistoryManager.ConversationSummary>()
    private val _isDownloading = mutableStateOf(false)
    private val _downloadProgress = mutableStateOf(0)

    // Session-level token tracking for chat mode
    private val _sessionTokens = mutableStateOf(0)
    private val _sessionCost = mutableStateOf(0.0)


    // Permission polling
    private val permHandler = Handler(Looper.getMainLooper())
    private val permPoller = object : Runnable {
        override fun run() {
            _needsPermission.value = !ClawAccessibilityService.isRunning()
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
            // Poll AutoReplyManager state every 2 seconds
            val autoReplyManager = io.agents.pokeclaw.service.AutoReplyManager.getInstance()
            var activeTasks by remember { mutableStateOf(listOf<String>()) }
            LaunchedEffect(Unit) {
                while (true) {
                    activeTasks = if (autoReplyManager.isEnabled) {
                        autoReplyManager.monitoredContacts.map { it.replaceFirstChar { c -> c.uppercase() } }
                    } else {
                        emptyList()
                    }
                    kotlinx.coroutines.delay(2000)
                }
            }

            ChatScreen(
                messages = _messages.toList(),
                modelStatus = _modelStatus.value,
                needsPermission = _needsPermission.value,
                isProcessing = _isProcessing.value,
                inputEnabled = _inputEnabled.value,
                isDownloading = _isDownloading.value,
                downloadProgress = _downloadProgress.value,
                isLocalModel = ModelConfigRepository.isLocalActive(),
                sessionTokens = _sessionTokens.value,
                sessionCost = _sessionCost.value,
                onSendChat = { sendChat(it) },
                onSendTask = { sendTask(it) },
                onStartMonitor = { contact -> handleMonitorTask("monitor $contact on WhatsApp") },
                onSendDirectMessage = { contact, app, message ->
                    sendTask("send \"$message\" to $contact on $app")
                },
                onNewChat = { newChat() },
                onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onOpenModels = { startActivity(Intent(this, LlmConfigActivity::class.java)) },
                onFixPermissions = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onAttach = { Toast.makeText(this, "Image upload coming soon", Toast.LENGTH_SHORT).show() },
                conversations = _conversations.toList(),
                onSelectConversation = { loadConversation(it) },
                onDeleteConversation = { conv ->
                    val deleted = ChatHistoryManager.delete(conv.file)
                    XLog.i(TAG, "Delete conversation: ${conv.file.absolutePath} deleted=$deleted")
                    loadSidebarHistory()
                },
                onRenameConversation = { conv, newName ->
                    val renamed = ChatHistoryManager.rename(conv.file, newName)
                    XLog.i(TAG, "Rename conversation: '${conv.title}' → '$newName' renamed=$renamed")
                    loadSidebarHistory()
                },
                activeTasks = activeTasks,
                onStopTask = { contact ->
                    autoReplyManager.removeContact(contact.lowercase())
                    if (autoReplyManager.monitoredContacts.isEmpty()) {
                        autoReplyManager.isEnabled = false
                    }
                    Toast.makeText(this, "Stopped monitoring $contact", Toast.LENGTH_SHORT).show()
                },
                onStopAllTasks = {
                    // Cancel running agent task
                    var requestedTaskStop = false
                    if (appViewModel.isTaskRunning()) {
                        appViewModel.stopTask()
                        requestedTaskStop = true
                    }
                    // Stop all monitoring
                    var stoppedMonitoring = false
                    if (autoReplyManager.isEnabled) {
                        autoReplyManager.stopAll()
                        stoppedMonitoring = true
                    }
                    val message = when {
                        requestedTaskStop -> "Stopping current task..."
                        stoppedMonitoring -> "All tasks stopped"
                        else -> "No active tasks"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                },
                onModelSwitch = { modelId, displayName -> switchModel(modelId, displayName) },
                colors = composeColors,
            )
        }

        loadSidebarHistory()

        // Restore last conversation if Activity was recreated (e.g., system killed it during a task)
        if (_messages.isEmpty()) {
            val savedConvId = KVUtils.getString("CURRENT_CONVERSATION_ID", "")
            if (savedConvId.isNotEmpty()) {
                val convos = ChatHistoryManager.listConversations(this)
                val match = convos.firstOrNull { it.id == savedConvId }
                if (match != null) {
                    conversationId = savedConvId
                    val restored = ChatHistoryManager.load(match.file)
                    if (restored.isNotEmpty()) {
                        _messages.addAll(restored)
                        XLog.i(TAG, "Restored ${restored.size} messages from conversation $savedConvId")
                    }
                }
            }
        }

        loadModelIfReady()

        // Release local LLM conversation before task starts so the agent can use the engine
        // (LiteRT-LM only supports 1 session at a time)
        appViewModel.onBeforeTask = {
            try { conversation?.close() } catch (_: Exception) {}
            conversation = null
            isModelReady = false
        }

        // Debug: auto-trigger task from ADB intent
        // Usage: adb shell am start -n io.agents.pokeclaw/.ui.chat.ComposeChatActivity --es task "open my camera"
        intent?.getStringExtra("task")?.let { taskText ->
            XLog.i(TAG, "Auto-task from intent: $taskText")
            // Wait for model to load, then send task
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed(object : Runnable {
                override fun run() {
                    if (isModelReady) {
                        sendTask(taskText)
                    } else {
                        handler.postDelayed(this, 1000)
                    }
                }
            }, 2000)
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle task from broadcast receiver (SINGLE_TOP re-delivery)
        intent.getStringExtra("task")?.let { taskText ->
            XLog.i(TAG, "Task from onNewIntent: $taskText")
            if (isModelReady) {
                sendTask(taskText)
            } else {
                val handler = Handler(Looper.getMainLooper())
                handler.postDelayed(object : Runnable {
                    override fun run() {
                        if (isModelReady) sendTask(taskText)
                        else handler.postDelayed(this, 1000)
                    }
                }, 1000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        _needsPermission.value = !ClawAccessibilityService.isRunning()
        loadSidebarHistory()
        permHandler.removeCallbacks(permPoller)
        permHandler.postDelayed(permPoller, 1000)

        // Reload model if changed, or reconnect if needed
        val currentModelPath = ModelConfigRepository.snapshot().local.modelPath
        if (currentModelPath.isNotEmpty() && currentModelPath != loadedModelPath) {
            loadModelIfReady()
        } else if (!isModelReady && engine != null && currentModelPath.isNotEmpty()) {
            executor.submit {
                try {
                    // Close existing conversation first — LiteRT only supports one at a time
                    try { conversation?.close() } catch (_: Exception) {}
                    conversation = null
                    conversation = engine!!.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of("You are a helpful AI assistant on an Android phone."),
                            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                        )
                    )
                    isModelReady = true
                    runOnUiThread {
                        updateLocalModelStatus(currentModelPath)
                        setButtonsEnabled(true)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to recreate conversation", e)
                    val isSessionConflict = e.message?.contains("session already exists") == true
                    runOnUiThread {
                        if (isSessionConflict) {
                            _modelStatus.value = "⚠ Model busy — tap model to retry"
                            Toast.makeText(this@ComposeChatActivity,
                                "Model is being used by a task. Wait for it to finish, then tap the model name to retry.",
                                Toast.LENGTH_LONG).show()
                        } else {
                            _modelStatus.value = "⚠ Model load failed — tap to retry"
                            Toast.makeText(this@ComposeChatActivity,
                                "Failed to load model: ${e.message?.take(80)}",
                                Toast.LENGTH_LONG).show()
                        }
                        setButtonsEnabled(false)
                    }
                }
            }
        } else if (!isModelReady && engine == null && currentModelPath.isNotEmpty()) {
            loadModelIfReady()
        }
    }

    override fun onPause() {
        super.onPause()
        saveChat()
        if (engine != null && ConversationCompactor.needsCompaction(_messages)) {
            executor.submit {
                try { conversation?.close() } catch (_: Exception) {}
                conversation = null
                ConversationCompactor.compact(engine!!, _messages, this, conversationId)
                isModelReady = false
            }
        }
        permHandler.removeCallbacks(permPoller)
        executor.submit {
            try { conversation?.close() } catch (_: Exception) {}
            conversation = null
            isModelReady = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Close the Conversation but leave the Engine in EngineHolder.
        // The engine will be reused if the Activity is recreated (e.g. rotation).
        // EngineHolder.close() is only called when the model file is being changed/deleted.
        executor.submit {
            XLog.i(TAG, "onDestroy: closing conversation (engine stays in EngineHolder)")
            try { conversation?.close() } catch (e: Exception) { XLog.w(TAG, "onDestroy: conversation close error", e) }
            conversation = null
        }
        executor.shutdown()
    }

    // ==================== MODEL LOADING ====================

    private fun loadModelIfReady() {
        val resolvedConfig = ModelConfigRepository.snapshot()

        // Cloud mode: create LlmClient, no local engine needed
        if (!resolvedConfig.isLocalActive()) {
            val cloudConfig = resolvedConfig.activeCloud
            if (cloudConfig.apiKey.isNotEmpty() && cloudConfig.modelName.isNotEmpty()) {
                val previousModel = cloudModelName
                cloudClient = LlmSessionManager.createCloudClient(temperature = 0.7)
                if (cloudClient == null) {
                    _modelStatus.value = "No model selected"
                    isModelReady = false
                    setButtonsEnabled(false)
                    return
                }
                cloudModelName = cloudConfig.modelName
                if (previousModel == null || cloudHistory.isEmpty()) {
                    // First load or no history — fresh start
                    cloudHistory.clear()
                    cloudHistory.add(SystemMessage.from("You are a helpful AI assistant on an Android phone."))
                } else if (previousModel != cloudConfig.modelName) {
                    // Mid-session model switch — keep history, notify via system message
                    cloudHistory.add(SystemMessage.from("The user has switched from $previousModel to ${cloudConfig.modelName}. Continue the conversation naturally."))
                    addSystem("Switched to ${cloudConfig.modelName}")
                    XLog.i(TAG, "Mid-session model switch: $previousModel → ${cloudConfig.modelName}")
                }
                isModelReady = true
                _modelStatus.value = "● ${cloudConfig.modelName} · Cloud"
                setButtonsEnabled(true)
                XLog.i(TAG, "Cloud chat ready: ${cloudConfig.modelName} via ${cloudConfig.resolvedBaseUrl}")
            } else {
                _modelStatus.value = "No model selected"
                isModelReady = false
                setButtonsEnabled(false)
            }
            return
        }

        // Local mode: load LiteRT engine
        cloudClient = null
        val modelPath = resolvedConfig.local.modelPath
        XLog.d(TAG, "loadModelIfReady: stored=$modelPath loaded=$loadedModelPath engine=${engine != null}")

        // If model changed OR engine not ready, close conversation and let EngineHolder
        // swap the engine on next getOrCreate() call.
        if (modelPath.isNotEmpty() && engine != null && modelPath != loadedModelPath) {
            XLog.d(TAG, "loadModelIfReady: model changed ($loadedModelPath -> $modelPath), closing conversation")
            val oldConv = conversation
            engine = null
            conversation = null
            isModelReady = false
            loadedModelPath = null
            executor.submit {
                // Close old conversation; EngineHolder.getOrCreate will handle engine swap
                try { oldConv?.close() } catch (e: Exception) { XLog.w(TAG, "loadModelIfReady: conv close error", e) }
                runOnUiThread { loadModelIfReady() }
            }
            return
        }

        if (modelPath.isEmpty()) {
            val defaultModel = LocalModelManager.bestSupportedModel(this)
            if (defaultModel == null) {
                val deviceRamGb = LocalModelManager.getDeviceRamGb(this)
                _modelStatus.value = "Local model unavailable on this device"
                _isDownloading.value = false
                setButtonsEnabled(false)
                addSystem("This device reports ${deviceRamGb}GB RAM. Current built-in local models need at least ${LocalModelManager.AVAILABLE_MODELS.minOf { it.minRamGb }}GB.")
                return
            }
            _modelStatus.value = "Downloading ${defaultModel.displayName}..."
            _isDownloading.value = true
            _downloadProgress.value = 0
            setButtonsEnabled(false)

            executor.submit {
                LocalModelManager.downloadModel(this, defaultModel, object : LocalModelManager.DownloadCallback {
                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                        val pct = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                        runOnUiThread {
                            _downloadProgress.value = pct
                            _modelStatus.value = "Downloading: $pct%"
                        }
                    }
                    override fun onComplete(modelPath: String) {
                        // Only set if user hasn't manually switched to another model
                        val currentPath = ModelConfigRepository.snapshot().local.modelPath
                        if (currentPath.isEmpty() || currentPath == modelPath) {
                            ModelConfigRepository.activateLocal(modelPath, defaultModel.id)
                        }
                        runOnUiThread {
                            _isDownloading.value = false
                            loadModelIfReady()
                        }
                    }
                    override fun onError(error: String) {
                        runOnUiThread {
                            _isDownloading.value = false
                            _modelStatus.value = "Download failed"
                            addSystem("Download failed: $error")
                        }
                    }
                })
            }
            return
        }

        _modelStatus.value = "Loading..."
        setButtonsEnabled(false)
        executor.submit { loadModel(modelPath) }
    }

    private fun loadModel(modelPath: String) {
        val preferredBackend = KVUtils.getLocalBackendPreference().uppercase()
        if (preferredBackend == "CPU") {
            XLog.i(TAG, "loadModel: using saved CPU preference for local model")
            loadModelWithBackend(modelPath, Backend.CPU())
            return
        }
        try {
            // Try GPU first for better performance
            loadModelWithBackend(modelPath, Backend.GPU())
        } catch (gpuError: Exception) {
            XLog.w(TAG, "GPU load failed: ${gpuError.message}, falling back to CPU")
            try {
                KVUtils.setLocalBackendPreference("CPU")
                EngineHolder.close()
                engine = null
                loadModelWithBackend(modelPath, Backend.CPU())
            } catch (cpuError: Exception) {
                throw cpuError
            }
        }
    }

    private fun loadModelWithBackend(modelPath: String, backend: com.google.ai.edge.litertlm.Backend) {
        try {
            // Use shared EngineHolder — avoids 2-3 s reinit when switching between chat
            // and task mode, since the task agent uses the same engine via LocalLlmClient.
            XLog.i(TAG, "loadModelWithBackend: requesting engine from EngineHolder for $modelPath")
            try { conversation?.close() } catch (_: Exception) {}
            conversation = null

            // Brief pause for task agent's conversation to close (if any)
            Thread.sleep(200)

            engine = EngineHolder.getOrCreate(modelPath, cacheDir.path, backend)
            XLog.i(TAG, "loadModelWithBackend: engine ready")

            // Retry createConversation with backoff — task conversation may still be closing
            val convConfig = ConversationConfig(
                systemInstruction = Contents.of("You are a helpful AI assistant on an Android phone."),
                samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
            )
            var created = false
            for (attempt in 1..5) {
                try {
                    conversation = engine!!.createConversation(convConfig)
                    created = true
                    break
                } catch (e: Exception) {
                    XLog.w(TAG, "loadModelWithBackend: createConversation attempt $attempt failed: ${e.message}")
                    if (attempt == 3) {
                        // Force-reset engine to clear stale task agent conversation
                        XLog.w(TAG, "loadModelWithBackend: resetting engine to clear stale conversations")
                        try {
                            EngineHolder.close()
                            engine = EngineHolder.getOrCreate(modelPath, cacheDir.path, backend)
                        } catch (resetErr: Exception) {
                            XLog.e(TAG, "loadModelWithBackend: engine reset failed", resetErr)
                        }
                    }
                    if (attempt < 5) Thread.sleep(1500)
                }
            }
            if (!created) throw RuntimeException("Failed to create conversation after 5 retries")

            isModelReady = true
            loadedModelPath = modelPath
            runOnUiThread {
                updateLocalModelStatus(modelPath)
                setButtonsEnabled(true)
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Model load failed", e)
            val isSessionConflict = e.message?.contains("session already exists") == true
                    || e.message?.contains("5 retries") == true
            runOnUiThread {
                if (isSessionConflict) {
                    _modelStatus.value = "⚠ Model busy — tap model to retry"
                    addSystem("Model is being used by a background task. Wait for it to finish, then tap the model name above to reload.")
                    Toast.makeText(this@ComposeChatActivity,
                        "Model is busy. Wait for the task to finish, then tap the model name to retry.",
                        Toast.LENGTH_LONG).show()
                } else {
                    _modelStatus.value = "⚠ Load failed — tap model to retry"
                    addSystem("Failed to load model: ${e.message?.take(100)}")
                    Toast.makeText(this@ComposeChatActivity,
                        "Model load failed: ${e.message?.take(80)}",
                        Toast.LENGTH_LONG).show()
                }
                setButtonsEnabled(false)
            }
        }
    }

    // ==================== CHAT ====================

    private fun sendChat(text: String) {
        addUser(text)
        _isProcessing.value = true
        _messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        executor.submit {
            try {
                if (cloudClient != null) {
                    // Cloud chat: use LlmClient
                    cloudHistory.add(UserMessage.from(text))
                    val llmResponse = cloudClient!!.chat(cloudHistory, emptyList())
                    val responseText = llmResponse.text ?: "(no response)"
                    cloudHistory.add(AiMessage.from(responseText))
                    val usage = llmResponse.tokenUsage
                    val inputTokens = usage?.inputTokenCount() ?: (text.length / 4 + 1)
                    val outputTokens = usage?.outputTokenCount() ?: (responseText.length / 4 + 1)
                    // Use the actual model name from the API response, not the configured name
                    val fallbackModelName = cloudModelName ?: ModelConfigRepository.snapshot().activeCloud.modelName
                    val modelTag = llmResponse.modelName ?: fallbackModelName
                    XLog.d(TAG, "sendChat: cloud response modelName='${llmResponse.modelName}', fallback='$fallbackModelName'")
                    runOnUiThread {
                        val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                        if (idx >= 0) _messages[idx] = _messages[idx].copy(content = responseText, modelName = modelTag)
                        _isProcessing.value = false
                        _sessionTokens.value += inputTokens + outputTokens
                        _sessionCost.value += ModelPricing.estimateCost(modelTag, inputTokens, outputTokens)
                        saveChat()
                    }
                } else {
                    // Local chat: use LiteRT conversation
                    val response = conversation!!.sendMessage(text)
                    val responseText = response?.toString() ?: "(no response)"
                    val inputTokensEst = text.length / 4 + 1
                    val outputTokensEst = responseText.length / 4 + 1
                    val localModelTag = java.io.File(KVUtils.getLocalModelPath()).nameWithoutExtension
                    runOnUiThread {
                        val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                        if (idx >= 0) _messages[idx] = _messages[idx].copy(content = responseText, modelName = localModelTag)
                        _isProcessing.value = false
                        _sessionTokens.value += inputTokensEst + outputTokensEst
                        saveChat()
                    }
                }
            } catch (e: Exception) {
                // GPU→CPU fallback: if OpenCL/GPU fails during inference, reload with CPU and retry
                if (conversation != null && (e.message?.contains("OpenCL") == true
                        || e.message?.contains("GPU") == true
                        || e.message?.contains("nativeSendMessage") == true)) {
                    XLog.w(TAG, "GPU inference failed, falling back to CPU: ${e.message}")
                    try {
                        KVUtils.setLocalBackendPreference("CPU")
                        conversation?.close()
                        conversation = null
                        EngineHolder.close()
                        engine = null
                        val modelPath = KVUtils.getLocalModelPath()
                        loadModelWithBackend(modelPath, com.google.ai.edge.litertlm.Backend.CPU())
                        XLog.i(TAG, "CPU fallback engine ready, retrying sendMessage")
                        val response = conversation!!.sendMessage(text)
                        val responseText = response?.toString() ?: "(no response)"
                        val inputTokensEst = text.length / 4 + 1
                        val outputTokensEst = responseText.length / 4 + 1
                        val cpuModelTag = java.io.File(KVUtils.getLocalModelPath()).nameWithoutExtension + " (CPU)"
                        runOnUiThread {
                            val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                            if (idx >= 0) _messages[idx] = _messages[idx].copy(content = responseText, modelName = cpuModelTag)
                            _isProcessing.value = false
                            _sessionTokens.value += inputTokensEst + outputTokensEst
                            updateLocalModelStatus(modelPath)
                            saveChat()
                        }
                        return@submit
                    } catch (cpuError: Exception) {
                        XLog.e(TAG, "CPU fallback also failed", cpuError)
                    }
                }
                XLog.e(TAG, "Chat error", e)
                runOnUiThread {
                    val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                    if (idx >= 0) _messages[idx] = _messages[idx].copy(content = "Error: ${e.message}")
                    _isProcessing.value = false
                }
            }
        }
    }

    private var sendTaskRetryCount = 0

    private fun sendTask(text: String) {
        // Java keyword routing FIRST — monitor/auto-reply don't need accessibility service
        val lower = text.lowercase()
        if (lower.contains("monitor") || lower.contains("auto-reply") || lower.contains("auto reply")
            || lower.contains("watch") && (lower.contains("message") || lower.contains("reply"))) {
            handleMonitorTask(text)
            return
        }

        // Accessibility check — only for tasks that need phone control (agent loop)
        if (!ClawAccessibilityService.isRunning()) {
            if (!ClawAccessibilityService.isEnabledInSettings(this)) {
                // Show Toast first, then navigate to PokeClaw Settings (not Android Settings directly)
                Toast.makeText(this, "Enable Accessibility Service to run tasks", Toast.LENGTH_LONG).show()
                addSystem("⚠️ Task mode needs Accessibility Service enabled. Opening Settings...")
                startActivity(Intent(this, SettingsActivity::class.java))
                sendTaskRetryCount = 0
                return
            }
            // Enabled in settings but service not yet connected — wait briefly
            if (sendTaskRetryCount >= 1) {
                Toast.makeText(this, "Accessibility service not connected. Try toggling it off and on.", Toast.LENGTH_LONG).show()
                addSystem("Accessibility service didn't connect. Try toggling it off and on in Settings.")
                startActivity(Intent(this, SettingsActivity::class.java))
                sendTaskRetryCount = 0
                return
            }
            sendTaskRetryCount++
            addSystem("Accessibility service connecting, please wait...")
            executor.submit {
                val connected = ClawAccessibilityService.awaitRunning(5000)
                runOnUiThread {
                    if (connected) {
                        sendTask(text)
                    } else {
                        Toast.makeText(this, "Accessibility service didn't connect", Toast.LENGTH_LONG).show()
                        addSystem("Accessibility service didn't connect. Go to Settings and toggle it off then on.")
                        sendTaskRetryCount = 0
                    }
                }
            }
            return
        }
        sendTaskRetryCount = 0

        // Ensure notification permission + foreground service for task progress visibility
        ensureNotificationPermission()

        // Reset stuck processing state from previous task
        _isProcessing.value = false

        if (!KVUtils.hasLlmConfig()) {
            Toast.makeText(this, "Configure LLM in Settings first", Toast.LENGTH_LONG).show()
            return
        }

        addUser(text)
        _isProcessing.value = true
        XLog.i(TAG, "sendTask: isProcessing=TRUE")
        _messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        val taskText = text
        val taskId = "task_${System.currentTimeMillis()}"

        // Release chat conversation so task agent can use the engine
        executor.submit {
            try { appViewModel.stopTask(); Thread.sleep(200) } catch (_: Exception) {}
            try { conversation?.close() } catch (_: Exception) {}
            conversation = null
            isModelReady = false

            runOnUiThread {
                try {
                    appViewModel.startTask(taskText, taskId) { event ->
                        runOnUiThread { handleTaskEvent(event) }
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "sendTask failed: ${e.message}", e)
                    addSystem("Error: ${e.message}")
                    cleanupAfterTask()
                }
            }
        }
    }

    /** Handle typed events from TaskOrchestrator — no string parsing. */
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
                        // First real tool action = this is a task, remove typing "..."
                        removeTypingIndicator()
                        addSystem("${event.toolName}...")
                    }
                }
                is TaskEvent.ToolResult -> {
                    if (!event.success) addSystem("${event.toolName} failed")
                }
                is TaskEvent.Response -> {
                    replaceTypingIndicator(event.text)
                }
                is TaskEvent.Progress -> {
                    addSystem(event.description)
                }
                // These are handled by floating button / notification, not chat
                is TaskEvent.LoopStart, is TaskEvent.TokenUpdate, is TaskEvent.Thinking -> { }
            }
        } catch (e: Exception) {
            XLog.w(TAG, "handleTaskEvent error", e)
        }
    }

    /** Replace "..." typing indicator with actual text, or add new message. */
    /**
     * Replace "..." typing indicator with actual response.
     * @param text the response text
     * @param actualModelName the real model that generated this response (from API).
     *                        If null, falls back to parsing _modelStatus (less reliable).
     */
    private fun replaceTypingIndicator(text: String, actualModelName: String? = null) {
        val modelTag = actualModelName
            ?: _modelStatus.value.removePrefix("● ").split(" ·").firstOrNull()?.trim()
            ?: ""
        val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) {
            _messages[idx] = ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag)
        } else {
            _messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag))
        }
        saveChat()
    }

    /** Remove "..." typing indicator if it exists. */
    private fun removeTypingIndicator() {
        val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) _messages.removeAt(idx)
    }

    /** Clean up state after any task finishes. */
    private fun cleanupAfterTask() {
        XLog.i(TAG, "cleanupAfterTask: isProcessing=FALSE")
        _isProcessing.value = false
        appViewModel.clearTaskCallback()
        Handler(Looper.getMainLooper()).postDelayed({
            try { loadModelIfReady() } catch (e: Exception) {
                XLog.e(TAG, "cleanupAfterTask: loadModel error", e)
            }
        }, 500)
    }

    /** If auto-reply was enabled by the task, show confirmation and keep the user in PokeClaw. */
    private fun checkAutoReplyConfirmation() {
        val arm = io.agents.pokeclaw.service.AutoReplyManager.getInstance()
        if (!arm.isEnabled) return
        val contacts = arm.monitoredContacts.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }
        addSystem("✓ Auto-reply active for $contacts.\nMonitoring in background — stop from bar above.")
        XLog.i(TAG, "checkAutoReplyConfirmation: monitor active, staying in PokeClaw")
    }

    private fun syncTaskAgentConfig() {
        if (!appViewModel.updateAgentConfig()) {
            XLog.w(TAG, "syncTaskAgentConfig: failed to update task agent config")
        }
    }

    private fun switchModel(modelId: String, displayName: String) {
        if (modelId == "NONE") {
            // No model configured for this tab
            _modelStatus.value = "No model selected"
            isModelReady = false
            setButtonsEnabled(false)
            XLog.i(TAG, "switchModel: NONE — no model configured for current tab")
            return
        }
        if (modelId == "LOCAL") {
            val localConfig = ModelConfigRepository.snapshot().local
            if (!localConfig.isConfigured) {
                _modelStatus.value = "No model selected"
                isModelReady = false
                setButtonsEnabled(false)
                XLog.i(TAG, "switchModel: LOCAL requested but no local default configured")
                return
            }
            ModelConfigRepository.activateLocal(localConfig.modelPath, localConfig.modelId)
            _modelStatus.value = "● ${localConfig.displayName} · On-device"
            addSystem("Switched to local model")
            loadModelIfReady()
            syncTaskAgentConfig()
        } else {
            ModelConfigRepository.activateCloudSelection(modelId)
            loadModelIfReady()
            addSystem("Switched to $displayName")
            syncTaskAgentConfig()
        }
        XLog.i(TAG, "Model switched to: $modelId ($displayName)")
    }

    private fun newChat() {
        saveChat()
        conversationId = "chat_${System.currentTimeMillis()}"
        _messages.clear()
        _sessionTokens.value = 0
        _sessionCost.value = 0.0
        if (cloudClient != null) {
            // Cloud mode: reset history
            cloudHistory.clear()
            cloudHistory.add(SystemMessage.from("You are a helpful AI assistant on an Android phone."))
            runOnUiThread {
                addSystem("New conversation started.")
                loadSidebarHistory()
            }
        } else {
            executor.submit {
                try { conversation?.close() } catch (_: Exception) {}
                conversation = engine?.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of("You are a helpful AI assistant on an Android phone."),
                        samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                    )
                )
                runOnUiThread {
                    addSystem("New conversation started.")
                    loadSidebarHistory()
                }
            }
        }
    }

    private fun loadConversation(conv: ChatHistoryManager.ConversationSummary) {
        saveChat()
        conversationId = conv.id
        _messages.clear()
        val messages = ChatHistoryManager.load(conv.file)
        _messages.addAll(messages)

        if (engine != null) {
            executor.submit {
                try {
                    try { conversation?.close() } catch (_: Exception) {}
                    val recentMsgs = messages.takeLast(5)
                    val systemPrompt = ConversationCompactor.buildRestoredSystemPrompt(this, conv.id, recentMsgs)
                    conversation = engine!!.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(systemPrompt),
                            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                        )
                    )
                    isModelReady = true
                    runOnUiThread {
                        setButtonsEnabled(true)
                        addSystem("Conversation restored.")
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to restore conversation", e)
                    runOnUiThread { addSystem("History loaded. New context started.") }
                }
            }
        }
    }

    // ==================== MONITOR (Java routing, no LLM) ====================

    /**
     * Handle monitor/auto-reply tasks directly via Java — no LLM needed.
     * Extracts contact name from user input, enables AutoReplyManager,
     * shows confirmation, then goes to home so notifications can fire.
     */
    private fun handleMonitorTask(text: String) {
        val contact = extractContactName(text)
        if (contact.isEmpty()) {
            addUser(text)
            addSystem("Could not figure out who to monitor. Try: \"Monitor Mom on WhatsApp\"")
            return
        }

        addUser(text)

        // Check required permissions before starting monitor
        val needsAccessibility = !ClawAccessibilityService.isRunning()
        val needsNotifAccess = !io.agents.pokeclaw.service.ClawNotificationListener.isConnected()
        if (needsAccessibility || needsNotifAccess) {
            val missing = mutableListOf<String>()
            if (needsAccessibility) missing.add("Accessibility")
            if (needsNotifAccess) missing.add("Notification Access")
            Toast.makeText(this, "Enable ${missing.joinToString(" & ")} in Settings first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, io.agents.pokeclaw.ui.settings.SettingsActivity::class.java))
            return
        }

        _isProcessing.value = true
        addSystem("Setting up auto-reply for $contact...")

        val arm = io.agents.pokeclaw.service.AutoReplyManager.getInstance()
        arm.addContact(contact)
        arm.setEnabled(true)
        XLog.i(TAG, "handleMonitorTask: enabled auto-reply for '$contact'")

        Handler(Looper.getMainLooper()).postDelayed({
            _isProcessing.value = false
            addSystem("✓ Auto-reply is now active for $contact.\nMonitoring in background — you can stop anytime from the bar above.")
            // Stay in PokeClaw — ClawNotificationListener catches notifications
            // regardless of which app is in foreground. No need to press Home.
            XLog.i(TAG, "handleMonitorTask: monitor active, staying in PokeClaw")
        }, 1500)
    }

    /**
     * Check if PokeClaw's NotificationListenerService is enabled.
     * Required for monitor/auto-reply to detect incoming messages.
     */
    private fun isNotificationListenerEnabled(): Boolean {
        val flat = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(packageName)
    }

    /**
     * Extract contact name from monitor task text.
     * Handles: "monitor Mom on WhatsApp", "auto-reply to Mom's messages", "watch Mom", etc.
     */
    private fun extractContactName(text: String): String {
        val lower = text.lowercase()
        // Remove known keywords to isolate the contact name
        var cleaned = lower
        val removeWords = listOf(
            "monitoring", "monitor", "auto-reply", "auto reply", "watching", "watch",
            "on whatsapp", "on telegram", "on messages", "on wechat", "on line",
            "messages", "message", "'s", "'s", "for", "from",
            "please", "can you", "start", "enable", "begin", "help me",
        )
        for (word in removeWords) {
            cleaned = cleaned.replace(word, " ")
        }
        // Collapse whitespace and trim
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        // What's left should be the contact name
        return if (cleaned.isNotEmpty()) cleaned.replaceFirstChar { it.uppercase() } else ""
    }

    // ==================== HELPERS ====================

    /**
     * Request notification permission (Android 13+) and start ForegroundService
     * so the user sees task progress in the status bar while PokeClaw is in background.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        // Start foreground service if not running
        if (!io.agents.pokeclaw.service.ForegroundService.isRunning()) {
            io.agents.pokeclaw.service.ForegroundService.start(this)
        }
    }

    private fun addUser(text: String) { _messages.add(ChatMessage(ChatMessage.Role.USER, text)) }
    private fun addSystem(text: String) { _messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text)) }

    private fun updateLocalModelStatus(modelPath: String?) {
        if (modelPath.isNullOrEmpty()) {
            _modelStatus.value = "No model selected"
            return
        }
        val modelInfo = LocalModelManager.AVAILABLE_MODELS.find { modelPath.endsWith(it.fileName) }
        val modelName = modelInfo?.displayName ?: modelPath.substringAfterLast('/').substringBeforeLast('.')
        val backendLabel = EngineHolder.getBackendLabel(modelPath) ?: "On-device"
        _modelStatus.value = "● $modelName · $backendLabel"
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        _inputEnabled.value = enabled
    }

    private fun saveChat() {
        val modelName = KVUtils.getLocalModelPath().substringAfterLast('/').substringBeforeLast('.')
        ChatHistoryManager.save(this, conversationId, _messages, modelName)
        // Persist current conversation ID so we can restore on Activity recreation
        KVUtils.putString("CURRENT_CONVERSATION_ID", conversationId)
        loadSidebarHistory()
    }

    private fun loadSidebarHistory() {
        val convos = ChatHistoryManager.listConversations(this)
        _conversations.clear()
        _conversations.addAll(convos)
    }
}
