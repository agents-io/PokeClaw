// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.agents.pokeclaw.agent.ModelPricing
import io.agents.pokeclaw.agent.llm.LlmClient
import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.agent.llm.LocalModelManager
import io.agents.pokeclaw.agent.llm.LocalModelRuntime
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.utils.XLog
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.ExecutorService

data class ChatSessionUiState(
    val messages: SnapshotStateList<ChatMessage>,
    val modelStatus: MutableState<String>,
    val isProcessing: MutableState<Boolean>,
    val inputEnabled: MutableState<Boolean>,
    val isDownloading: MutableState<Boolean>,
    val downloadProgress: MutableState<Int>,
    val sessionTokens: MutableState<Int>,
    val sessionCost: MutableState<Double>,
)

class ChatSessionController(
    private val activity: ComponentActivity,
    private val executor: ExecutorService,
    private val uiState: ChatSessionUiState,
    private val onPersistConversation: () -> Unit,
    private val onRefreshSidebarHistory: () -> Unit,
) {

    companion object {
        private const val TAG = "ChatSessionController"
        private const val BASE_SYSTEM_PROMPT = "You are a helpful AI assistant on an Android phone."
    }

    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private var conversation: Conversation? = null
    private var isModelReady = false

    private var cloudClient: LlmClient? = null
    private var cloudModelName: String? = null
    private val cloudHistory = mutableListOf<dev.langchain4j.data.message.ChatMessage>()

    fun isModelReady(): Boolean = isModelReady

    fun loadModelIfReady() {
        val resolvedConfig = ModelConfigRepository.snapshot()

        if (!resolvedConfig.isLocalActive()) {
            val cloudConfig = resolvedConfig.activeCloud
            if (cloudConfig.apiKey.isNotEmpty() && cloudConfig.modelName.isNotEmpty()) {
                val previousModel = cloudModelName
                cloudClient = LlmSessionManager.createCloudClient(temperature = 0.7)
                if (cloudClient == null) {
                    uiState.modelStatus.value = "No model selected"
                    isModelReady = false
                    setButtonsEnabled(false)
                    return
                }
                cloudModelName = cloudConfig.modelName
                if (previousModel == null || cloudHistory.isEmpty()) {
                    rebuildCloudHistoryFromVisibleMessages()
                } else if (previousModel != cloudConfig.modelName) {
                    cloudHistory.add(
                        SystemMessage.from(
                            "The user has switched from $previousModel to ${cloudConfig.modelName}. Continue the conversation naturally."
                        )
                    )
                    addSystem("Switched to ${cloudConfig.modelName}")
                }
                isModelReady = true
                uiState.modelStatus.value = "● ${cloudConfig.modelName} · Cloud"
                setButtonsEnabled(true)
                XLog.i(TAG, "Cloud chat ready: ${cloudConfig.modelName} via ${cloudConfig.resolvedBaseUrl}")
            } else {
                uiState.modelStatus.value = "No model selected"
                isModelReady = false
                setButtonsEnabled(false)
            }
            return
        }

        cloudClient = null
        val modelPath = resolvedConfig.local.modelPath
        XLog.d(TAG, "loadModelIfReady: stored=$modelPath loaded=$loadedModelPath engine=${engine != null}")

        if (modelPath.isNotEmpty() && engine != null && modelPath != loadedModelPath) {
            XLog.d(TAG, "loadModelIfReady: model changed ($loadedModelPath -> $modelPath), closing conversation")
            val oldConv = conversation
            engine = null
            conversation = null
            isModelReady = false
            loadedModelPath = null
            executor.submit {
                try {
                    oldConv?.close()
                } catch (e: Exception) {
                    XLog.w(TAG, "loadModelIfReady: conv close error", e)
                }
                postToMain { loadModelIfReady() }
            }
            return
        }

        if (modelPath.isEmpty()) {
            val deviceSupport = LocalModelManager.deviceSupport(activity)
            val defaultModel = deviceSupport.bestSupportedModel
            if (defaultModel == null) {
                uiState.modelStatus.value = "Local model unavailable on this device"
                uiState.isDownloading.value = false
                setButtonsEnabled(false)
                addSystem(
                    "This device reports ${deviceSupport.deviceRamGb}GB RAM. Current built-in local models need at least ${deviceSupport.minimumBuiltInRamGb}GB."
                )
                return
            }
            uiState.modelStatus.value = "Downloading ${defaultModel.displayName}..."
            uiState.isDownloading.value = true
            uiState.downloadProgress.value = 0
            setButtonsEnabled(false)

            executor.submit {
                LocalModelManager.downloadModel(activity, defaultModel, object : LocalModelManager.DownloadCallback {
                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                        val pct = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                        postToMain {
                            uiState.downloadProgress.value = pct
                            uiState.modelStatus.value = "Downloading: $pct%"
                        }
                    }

                    override fun onComplete(modelPath: String) {
                        val currentPath = ModelConfigRepository.snapshot().local.modelPath
                        if (currentPath.isEmpty() || currentPath == modelPath) {
                            ModelConfigRepository.activateLocal(modelPath, defaultModel.id)
                        }
                        postToMain {
                            uiState.isDownloading.value = false
                            loadModelIfReady()
                        }
                    }

                    override fun onError(error: String) {
                        postToMain {
                            uiState.isDownloading.value = false
                            uiState.modelStatus.value = "Download failed"
                            addSystem("Download failed: $error")
                        }
                    }
                })
            }
            return
        }

        uiState.modelStatus.value = "Loading..."
        setButtonsEnabled(false)
        executor.submit { loadModel(modelPath) }
    }

    fun onResume() {
        val currentModelPath = ModelConfigRepository.snapshot().local.modelPath
        if (currentModelPath.isNotEmpty() && currentModelPath != loadedModelPath) {
            loadModelIfReady()
        } else if (!isModelReady && engine != null && currentModelPath.isNotEmpty()) {
            executor.submit {
                try {
                    try {
                        conversation?.close()
                    } catch (_: Exception) {
                    }
                    conversation = null
                    val lease = LocalModelRuntime.openConversation(activity, currentModelPath, buildConversationConfig())
                    engine = lease.engine
                    conversation = lease.conversation
                    isModelReady = true
                    postToMain {
                        updateLocalModelStatus(currentModelPath)
                        setButtonsEnabled(true)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to recreate conversation", e)
                    val isSessionConflict = e.message?.contains("session already exists") == true
                    postToMain {
                        if (isSessionConflict) {
                            uiState.modelStatus.value = "⚠ Model busy — tap model to retry"
                            Toast.makeText(
                                activity,
                                "Model is being used by a task. Wait for it to finish, then tap the model name to retry.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            uiState.modelStatus.value = "⚠ Model load failed — tap to retry"
                            Toast.makeText(
                                activity,
                                "Failed to load model: ${e.message?.take(80)}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        setButtonsEnabled(false)
                    }
                }
            }
        } else if (!isModelReady && engine == null && currentModelPath.isNotEmpty()) {
            loadModelIfReady()
        }
    }

    fun onPause(conversationId: String) {
        if (engine != null && ConversationCompactor.needsCompaction(uiState.messages)) {
            executor.submit {
                try {
                    conversation?.close()
                } catch (_: Exception) {
                }
                conversation = null
                ConversationCompactor.compact(engine!!, uiState.messages, activity, conversationId)
                isModelReady = false
            }
        }
        executor.submit {
            try {
                conversation?.close()
            } catch (_: Exception) {
            }
            conversation = null
            isModelReady = false
        }
    }

    fun onDestroy() {
        executor.submit {
            XLog.i(TAG, "onDestroy: closing conversation (engine stays in EngineHolder)")
            try {
                conversation?.close()
            } catch (e: Exception) {
                XLog.w(TAG, "onDestroy: conversation close error", e)
            }
            conversation = null
        }
    }

    fun releaseForTask() {
        try {
            conversation?.close()
        } catch (_: Exception) {
        }
        conversation = null
        isModelReady = false
    }

    fun prepareForTaskStart() {
        try {
            conversation?.close()
        } catch (_: Exception) {
        }
        conversation = null
        isModelReady = false
    }

    fun sendChat(text: String) {
        addUser(text)
        uiState.isProcessing.value = true
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        executor.submit {
            try {
                if (cloudClient != null) {
                    ensureCloudHistoryInitialized()
                    cloudHistory.add(UserMessage.from(text))
                    val llmResponse = cloudClient!!.chat(cloudHistory, emptyList())
                    val responseText = llmResponse.text ?: "(no response)"
                    cloudHistory.add(AiMessage.from(responseText))
                    val usage = llmResponse.tokenUsage
                    val inputTokens = usage?.inputTokenCount() ?: (text.length / 4 + 1)
                    val outputTokens = usage?.outputTokenCount() ?: (responseText.length / 4 + 1)
                    val fallbackModelName = cloudModelName ?: ModelConfigRepository.snapshot().activeCloud.modelName
                    val modelTag = llmResponse.modelName ?: fallbackModelName
                    XLog.d(TAG, "sendChat: cloud response modelName='${llmResponse.modelName}', fallback='$fallbackModelName'")
                    postToMain {
                        replaceTypingIndicator(responseText, modelTag)
                        uiState.isProcessing.value = false
                        uiState.sessionTokens.value += inputTokens + outputTokens
                        uiState.sessionCost.value += ModelPricing.estimateCost(modelTag, inputTokens, outputTokens)
                        onPersistConversation()
                    }
                } else {
                    val response = conversation!!.sendMessage(text)
                    val responseText = response?.toString() ?: "(no response)"
                    val inputTokensEst = text.length / 4 + 1
                    val outputTokensEst = responseText.length / 4 + 1
                    val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
                    val localModelTag = localModelTag(modelPath)
                    postToMain {
                        replaceTypingIndicator(responseText, localModelTag)
                        uiState.isProcessing.value = false
                        uiState.sessionTokens.value += inputTokensEst + outputTokensEst
                        onPersistConversation()
                    }
                }
            } catch (e: Exception) {
                if (conversation != null && LocalModelRuntime.isGpuBackendFailure(e)) {
                    XLog.w(TAG, "GPU inference failed, falling back to CPU: ${e.message}")
                    try {
                        val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
                        val responseText = retryLocalChatOnCpu(modelPath, text)
                        val inputTokensEst = text.length / 4 + 1
                        val outputTokensEst = responseText.length / 4 + 1
                        val cpuModelTag = localModelTag(modelPath)
                        postToMain {
                            replaceTypingIndicator(responseText, cpuModelTag)
                            uiState.isProcessing.value = false
                            uiState.sessionTokens.value += inputTokensEst + outputTokensEst
                            updateLocalModelStatus(modelPath)
                            onPersistConversation()
                        }
                        return@submit
                    } catch (cpuError: Exception) {
                        XLog.e(TAG, "CPU fallback also failed", cpuError)
                    }
                }
                XLog.e(TAG, "Chat error", e)
                postToMain {
                    replaceTypingIndicator("Error: ${e.message}")
                    uiState.isProcessing.value = false
                }
            }
        }
    }

    fun switchModel(modelId: String, displayName: String) {
        if (modelId == "NONE") {
            uiState.modelStatus.value = "No model selected"
            isModelReady = false
            setButtonsEnabled(false)
            XLog.i(TAG, "switchModel: NONE — no model configured for current tab")
            return
        }
        if (modelId == "LOCAL") {
            val localConfig = ModelConfigRepository.snapshot().local
            if (!localConfig.isConfigured) {
                uiState.modelStatus.value = "No model selected"
                isModelReady = false
                setButtonsEnabled(false)
                XLog.i(TAG, "switchModel: LOCAL requested but no local default configured")
                return
            }
            ModelConfigRepository.activateLocal(localConfig.modelPath, localConfig.modelId)
            uiState.modelStatus.value = "● ${localConfig.displayName} · On-device"
            addSystem("Switched to local model")
            loadModelIfReady()
        } else {
            ModelConfigRepository.activateCloudSelection(modelId)
            loadModelIfReady()
            addSystem("Switched to $displayName")
        }
        XLog.i(TAG, "Model switched to: $modelId ($displayName)")
    }

    fun startNewConversationRuntime() {
        if (cloudClient != null) {
            cloudHistory.clear()
            cloudHistory.add(SystemMessage.from(BASE_SYSTEM_PROMPT))
            postToMain {
                addSystem("New conversation started.")
                onRefreshSidebarHistory()
            }
            return
        }

        executor.submit {
            try {
                conversation?.close()
            } catch (_: Exception) {
            }
            val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
            if (modelPath.isNotEmpty()) {
                val lease = LocalModelRuntime.openConversation(activity, modelPath, buildConversationConfig())
                engine = lease.engine
                conversation = lease.conversation
                isModelReady = true
            }
            postToMain {
                addSystem("New conversation started.")
                onRefreshSidebarHistory()
            }
        }
    }

    fun restoreConversationRuntime(conversationId: String, messages: List<ChatMessage>) {
        if (cloudClient != null) {
            rebuildCloudHistoryFromVisibleMessages()
            return
        }
        if (engine != null) {
            executor.submit {
                try {
                    try {
                        conversation?.close()
                    } catch (_: Exception) {
                    }
                    val recentMsgs = messages.takeLast(5)
                    val systemPrompt = ConversationCompactor.buildRestoredSystemPrompt(activity, conversationId, recentMsgs)
                    val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
                    val lease = LocalModelRuntime.openConversation(
                        context = activity,
                        modelPath = modelPath,
                        conversationConfig = ConversationConfig(
                            systemInstruction = Contents.of(systemPrompt),
                            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                        )
                    )
                    engine = lease.engine
                    conversation = lease.conversation
                    isModelReady = true
                    postToMain {
                        setButtonsEnabled(true)
                        addSystem("Conversation restored.")
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to restore conversation", e)
                    postToMain { addSystem("History loaded. New context started.") }
                }
            }
        }
    }

    private fun loadModel(modelPath: String) {
        try {
            XLog.i(TAG, "loadModel: acquiring shared runtime for $modelPath")
            try {
                conversation?.close()
            } catch (_: Exception) {
            }
            conversation = null
            Thread.sleep(200)

            val lease = LocalModelRuntime.openConversation(activity, modelPath, buildConversationConfig())
            engine = lease.engine
            XLog.i(TAG, "loadModel: engine ready (${lease.backendLabel})")
            conversation = lease.conversation

            isModelReady = true
            loadedModelPath = modelPath
            postToMain {
                updateLocalModelStatus(modelPath)
                setButtonsEnabled(true)
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Model load failed", e)
            val isSessionConflict = e.message?.contains("session already exists") == true
                || e.message?.contains("5 retries") == true
            postToMain {
                if (isSessionConflict) {
                    uiState.modelStatus.value = "⚠ Model busy — tap model to retry"
                    addSystem("Model is being used by a background task. Wait for it to finish, then tap the model name above to reload.")
                    Toast.makeText(
                        activity,
                        "Model is busy. Wait for the task to finish, then tap the model name to retry.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    uiState.modelStatus.value = "⚠ Load failed — tap model to retry"
                    addSystem("Failed to load model: ${e.message?.take(100)}")
                    Toast.makeText(
                        activity,
                        "Model load failed: ${e.message?.take(80)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                setButtonsEnabled(false)
            }
        }
    }

    private fun retryLocalChatOnCpu(modelPath: String, text: String): String {
        require(modelPath.isNotEmpty()) { "Local model path missing for CPU retry" }
        try {
            conversation?.close()
        } catch (_: Exception) {
        }
        conversation = null
        LocalModelRuntime.forceCpuEngine(activity, modelPath)
        val lease = LocalModelRuntime.openConversation(
            context = activity,
            modelPath = modelPath,
            conversationConfig = buildConversationConfig(),
            preferCpu = true,
        )
        engine = lease.engine
        loadedModelPath = modelPath
        conversation = lease.conversation
        XLog.i(TAG, "retryLocalChatOnCpu: CPU runtime ready, retrying sendMessage")
        return conversation!!.sendMessage(text)?.toString() ?: "(no response)"
    }

    private fun buildConversationConfig(): ConversationConfig {
        return ConversationConfig(
            systemInstruction = Contents.of(BASE_SYSTEM_PROMPT),
            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
        )
    }

    private fun rebuildCloudHistoryFromVisibleMessages() {
        cloudHistory.clear()
        cloudHistory.add(SystemMessage.from(BASE_SYSTEM_PROMPT))
        uiState.messages.forEach { msg ->
            when (msg.role) {
                ChatMessage.Role.USER -> cloudHistory.add(UserMessage.from(msg.content))
                ChatMessage.Role.ASSISTANT -> cloudHistory.add(AiMessage.from(msg.content))
                else -> Unit
            }
        }
    }

    private fun ensureCloudHistoryInitialized() {
        if (cloudHistory.isEmpty()) {
            rebuildCloudHistoryFromVisibleMessages()
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
    }

    private fun addUser(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.USER, text))
    }

    private fun addSystem(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    private fun updateLocalModelStatus(modelPath: String?) {
        if (modelPath.isNullOrEmpty()) {
            uiState.modelStatus.value = "No model selected"
            return
        }
        val modelInfo = LocalModelManager.AVAILABLE_MODELS.find { modelPath.endsWith(it.fileName) }
        val modelName = modelInfo?.displayName ?: modelPath.substringAfterLast('/').substringBeforeLast('.')
        val backendLabel = LocalModelRuntime.currentBackendLabel(modelPath) ?: "On-device"
        uiState.modelStatus.value = "● $modelName · $backendLabel"
    }

    private fun localModelTag(modelPath: String): String {
        val baseName = modelPath.takeIf { it.isNotEmpty() }?.let { File(it).nameWithoutExtension } ?: "Local"
        val backendLabel = LocalModelRuntime.currentBackendLabel(modelPath)
        return if (backendLabel.isNullOrBlank() || backendLabel.equals("GPU", ignoreCase = true)) {
            baseName
        } else {
            "$baseName ($backendLabel)"
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        uiState.inputEnabled.value = enabled
    }

    private fun postToMain(action: () -> Unit) {
        activity.runOnUiThread(action)
    }
}
