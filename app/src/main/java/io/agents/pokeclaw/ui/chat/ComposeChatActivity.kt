// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import io.agents.pokeclaw.agent.llm.EngineHolder
import io.agents.pokeclaw.agent.llm.LocalModelManager
import io.agents.pokeclaw.appViewModel
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

    // Compose state — observed by ChatScreen
    private val _messages = mutableStateListOf<ChatMessage>()
    private val _modelStatus = mutableStateOf("No model loaded")
    private val _needsPermission = mutableStateOf(false)
    private val _isProcessing = mutableStateOf(false)
    private val _conversations = mutableStateListOf<ChatHistoryManager.ConversationSummary>()
    private val _isDownloading = mutableStateOf(false)
    private val _downloadProgress = mutableStateOf(0)

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

        // Hide floating circle
        try { FloatingCircleManager.hide() } catch (_: Exception) {}

        // Status bar color
        val themeColors = ThemeManager.getColors()
        window.statusBarColor = themeColors.toolbarBg

        // Build Compose colors from ThemeManager
        val composeColors = with(ThemeManager) { themeColors.toComposeColors() }

        setContent {
            ChatScreen(
                messages = _messages.toList(),
                modelStatus = _modelStatus.value,
                needsPermission = _needsPermission.value,
                isProcessing = _isProcessing.value,
                isDownloading = _isDownloading.value,
                downloadProgress = _downloadProgress.value,
                onSendChat = { sendChat(it) },
                onSendTask = { sendTask(it) },
                onNewChat = { newChat() },
                onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onOpenModels = { startActivity(Intent(this, LlmConfigActivity::class.java)) },
                onFixPermissions = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onAttach = { Toast.makeText(this, "Image upload coming soon", Toast.LENGTH_SHORT).show() },
                conversations = _conversations.toList(),
                onSelectConversation = { loadConversation(it) },
                colors = composeColors,
            )
        }

        loadSidebarHistory()
        loadModelIfReady()

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
        val currentModelPath = KVUtils.getLocalModelPath()
        if (currentModelPath.isNotEmpty() && currentModelPath != loadedModelPath) {
            loadModelIfReady()
        } else if (!isModelReady && engine != null && currentModelPath.isNotEmpty()) {
            executor.submit {
                try {
                    conversation = engine!!.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of("You are a helpful AI assistant on an Android phone."),
                            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                        )
                    )
                    isModelReady = true
                    runOnUiThread { setButtonsEnabled(true) }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to recreate conversation", e)
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
        val modelPath = KVUtils.getLocalModelPath()
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
            // Pick best model that fits device RAM
            val totalRam = (Runtime.getRuntime().maxMemory() / 1024 / 1024 / 1024).toInt() + 1
            val actMgr = getSystemService(android.app.ActivityManager::class.java)
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actMgr.getMemoryInfo(memInfo)
            val deviceRamGb = (memInfo.totalMem / 1024 / 1024 / 1024).toInt() + 1
            val defaultModel = LocalModelManager.AVAILABLE_MODELS.firstOrNull { it.minRamGb <= deviceRamGb }
                ?: LocalModelManager.AVAILABLE_MODELS.last()
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
                        val currentPath = KVUtils.getLocalModelPath()
                        if (currentPath.isEmpty() || currentPath == modelPath) {
                            KVUtils.setLlmProvider("LOCAL")
                            KVUtils.setLocalModelPath(modelPath)
                            KVUtils.setLlmModelName(defaultModel.id)
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
        try {
            loadModelWithBackend(modelPath, Backend.CPU())
        } catch (gpuError: Exception) {
            XLog.w(TAG, "Load failed: ${gpuError.message}, retrying with CPU")
            try {
                engine?.close()
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

            // Wait for task agent's conversation to fully close before creating new one
            Thread.sleep(1000)

            engine = EngineHolder.getOrCreate(modelPath, cacheDir.path)
            XLog.i(TAG, "loadModelWithBackend: engine ready")

            // Retry createConversation with backoff — task conversation may still be closing
            var created = false
            for (attempt in 1..5) {
                try {
                    conversation = engine!!.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of("You are a helpful AI assistant on an Android phone."),
                            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                        )
                    )
                    created = true
                    break
                } catch (e: Exception) {
                    XLog.w(TAG, "loadModelWithBackend: createConversation attempt $attempt failed: ${e.message}")
                    if (attempt < 5) Thread.sleep(1500)
                }
            }
            if (!created) throw RuntimeException("Failed to create conversation after 5 retries")

            isModelReady = true
            loadedModelPath = modelPath
            val modelInfo = LocalModelManager.AVAILABLE_MODELS.find { modelPath.endsWith(it.fileName) }
            val modelName = modelInfo?.displayName ?: modelPath.substringAfterLast('/').substringBeforeLast('.')
            val backendLabel = if (backend is Backend.CPU) "CPU" else "GPU"
            runOnUiThread {
                _modelStatus.value = "● $modelName · $backendLabel"
                setButtonsEnabled(true)
                if (_messages.isEmpty()) {
                    _messages.add(ChatMessage(ChatMessage.Role.ASSISTANT,
                        "I'm a small AI running entirely on your phone — no cloud, no internet needed. I work best with simple questions and phone tasks. Switch to Task mode to let me control your phone for you."))
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Model load failed", e)
            runOnUiThread {
                _modelStatus.value = "Error: ${e.message}"
                addSystem("Failed: ${e.message}")
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
                val response = conversation!!.sendMessage(text)
                val responseText = response?.toString() ?: "(no response)"
                runOnUiThread {
                    // Update last assistant message
                    val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                    if (idx >= 0) _messages[idx] = _messages[idx].copy(content = responseText)
                    _isProcessing.value = false
                    saveChat()
                }
            } catch (e: Exception) {
                XLog.e(TAG, "Chat error", e)
                runOnUiThread {
                    val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                    if (idx >= 0) _messages[idx] = _messages[idx].copy(content = "Error: ${e.message}")
                    _isProcessing.value = false
                }
            }
        }
    }

    private fun sendTask(text: String) {
        if (!ClawAccessibilityService.isRunning()) {
            // Lazy permission — only ask when Task mode is first used
            addSystem("Task mode needs Accessibility permission to control your phone.")
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Enable PokeClaw in Accessibility settings", Toast.LENGTH_LONG).show()
            return
        }

        // Reset stuck processing state from previous task
        _isProcessing.value = false

        if (!KVUtils.hasLlmConfig()) {
            Toast.makeText(this, "Configure LLM in Settings first", Toast.LENGTH_LONG).show()
            return
        }

        addUser("🚀 $text")
        _isProcessing.value = true
        addSystem("Starting task...")

        // Register progress callback so TaskOrchestrator events appear in chat.
        val taskStartTime = System.currentTimeMillis()
        appViewModel.taskOrchestrator.taskProgressCallback = { msg ->
            val elapsed = (System.currentTimeMillis() - taskStartTime) / 1000.0
            XLog.i(TAG, "sendTask progress [${elapsed}s]: $msg")
            runOnUiThread {
                try {
                    addSystem(msg)
                } catch (e: Exception) {
                    XLog.w(TAG, "sendTask: addSystem error", e)
                }
                // When task finishes (complete / error / dialog-blocked), reload chat engine.
                // Wait 500ms after the callback fires so DefaultAgentService's finally block
                // (which calls llmClient.close()) has time to complete before we allocate a
                // new chat engine. This prevents two LiteRT-LM engines coexisting = OOM.
                if (msg.startsWith("Task completed") || msg.startsWith("Task failed") || msg.startsWith("Blocked")) {
                    XLog.i(TAG, "sendTask: task done via progress callback, scheduling chat engine reload")
                    _isProcessing.value = false
                    appViewModel.taskOrchestrator.taskProgressCallback = null
                    Handler(Looper.getMainLooper()).postDelayed({
                        XLog.i(TAG, "sendTask: reloading chat engine after task engine released")
                        try {
                            loadModelIfReady()
                        } catch (e: Exception) {
                            XLog.e(TAG, "sendTask: loadModelIfReady error", e)
                            addSystem("Error reloading model: ${e.message}")
                        }
                    }, 500)
                }
            }
        }

        // Close only the chat Conversation — the Engine stays alive in EngineHolder so
        // LocalLlmClient (used by the task agent) can reuse it immediately without the
        // 2-3 s reinit cost. LiteRT-LM's constraint is one Conversation at a time, not
        // one Engine at a time, so releasing the Conversation is sufficient.
        val taskText = text
        val taskId = "task_${System.currentTimeMillis()}"
        executor.submit {
            // Cancel any running task first
            try {
                appViewModel.taskOrchestrator.cancelCurrentTask()
                Thread.sleep(500)
            } catch (_: Exception) {}

            XLog.i(TAG, "sendTask: closing chat conversation before task id=$taskId (engine stays in EngineHolder)")
            try { conversation?.close() } catch (e: Exception) { XLog.w(TAG, "sendTask: conversation close error", e) }
            conversation = null
            isModelReady = false
            XLog.i(TAG, "sendTask: chat conversation closed, launching task")

            // Now safe to start task — engine is released
            runOnUiThread {
                try {
                    appViewModel.startNewTask(ChannelEnum.LOCAL, taskText, taskId)
                    XLog.i(TAG, "sendTask: task started id=$taskId")
                } catch (e: Exception) {
                    XLog.e(TAG, "sendTask: failed to start task", e)
                    addSystem("Error starting task: ${e.message}")
                    _isProcessing.value = false
                    appViewModel.taskOrchestrator.taskProgressCallback = null
                    try {
                        loadModelIfReady()
                    } catch (re: Exception) {
                        XLog.e(TAG, "sendTask: loadModelIfReady after start failure", re)
                    }
                }
            }
        }
    }

    private fun newChat() {
        saveChat()
        conversationId = "chat_${System.currentTimeMillis()}"
        _messages.clear()
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

    // ==================== HELPERS ====================

    private fun addUser(text: String) { _messages.add(ChatMessage(ChatMessage.Role.USER, text)) }
    private fun addSystem(text: String) { _messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text)) }

    private fun setButtonsEnabled(enabled: Boolean) {
        _isProcessing.value = !enabled
    }

    private fun saveChat() {
        val modelName = KVUtils.getLocalModelPath().substringAfterLast('/').substringBeforeLast('.')
        ChatHistoryManager.save(this, conversationId, _messages, modelName)
        loadSidebarHistory()
    }

    private fun loadSidebarHistory() {
        val convos = ChatHistoryManager.listConversations(this)
        _conversations.clear()
        _conversations.addAll(convos)
    }
}
