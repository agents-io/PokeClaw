package com.apk.claw.android.ui.chat

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apk.claw.android.R
import com.apk.claw.android.appViewModel
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.channel.Channel as ChannelEnum
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.ui.settings.LlmConfigActivity
import com.apk.claw.android.ui.settings.SettingsActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.Executors

class ChatActivity : BaseActivity() {

    companion object {
        private const val TAG = "ChatActivity"
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: TextView
    private lateinit var btnTask: TextView

    private val adapter = ChatMessageAdapter()
    private val executor = Executors.newSingleThreadExecutor()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var isModelReady = false
    private var isProcessing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        tvTitle = findViewById(R.id.tvTitle)
        tvStatus = findViewById(R.id.tvStatus)
        recyclerMessages = findViewById(R.id.recyclerMessages)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnTask = findViewById(R.id.btnTask)

        // RecyclerView
        recyclerMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerMessages.adapter = adapter

        // New Chat
        findViewById<TextView>(R.id.btnNewChat).setOnClickListener { newChat() }

        // Settings
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Send = chat
        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty() || !isModelReady || isProcessing) return@setOnClickListener
            sendChatMessage(text)
        }

        // Task = agent
        btnTask.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty() || isProcessing) return@setOnClickListener
            sendTaskMessage(text)
        }

        loadModelIfConfigured()
    }

    override fun onResume() {
        super.onResume()
        if (!isModelReady && KVUtils.getLocalModelPath().isNotEmpty()) {
            loadModelIfConfigured()
        }
    }

    private fun loadModelIfConfigured() {
        val modelPath = KVUtils.getLocalModelPath()
        if (modelPath.isEmpty()) {
            tvStatus.text = "No model — tap Settings to download"
            addSystemMessage("No model downloaded. Tap Settings → LLM Config to download one.")
            return
        }

        tvStatus.text = "Loading: ${modelPath.substringAfterLast('/')}"
        setButtonsEnabled(false)
        executor.submit { loadModel(modelPath) }
    }

    private fun loadModel(modelPath: String) {
        try {
            val backend = try {
                Backend.GPU()
            } catch (e: Exception) {
                XLog.w(TAG, "GPU not available, using CPU", e)
                runOnUiThread { tvStatus.text = "Using CPU backend..." }
                Backend.CPU()
            }

            runOnUiThread { tvStatus.text = "Loading model..." }

            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = 2048,
                cacheDir = cacheDir.path
            )
            engine = Engine(engineConfig)
            engine!!.initialize()

            conversation = engine!!.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of("You are a helpful AI assistant on an Android phone."),
                    samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                )
            )

            isModelReady = true
            runOnUiThread {
                val modelName = modelPath.substringAfterLast('/').substringBeforeLast('.')
                tvStatus.text = "Ready: $modelName"
                setButtonsEnabled(true)
                addSystemMessage("Model loaded. Send = chat, Task = phone action.")
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to load model", e)
            runOnUiThread {
                tvStatus.text = "Error: ${e.message}"
                addSystemMessage("Failed: ${e.message}")
            }
        }
    }

    private fun sendChatMessage(text: String) {
        addUserMessage(text)
        etInput.text.clear()
        setProcessing(true)

        adapter.addMessage(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))
        scrollToBottom()

        executor.submit {
            try {
                val response = conversation!!.sendMessage(text)
                val responseText = response?.toString() ?: "(no response)"
                runOnUiThread {
                    adapter.updateLastAssistantMessage(responseText)
                    scrollToBottom()
                    setProcessing(false)
                }
            } catch (e: Exception) {
                XLog.e(TAG, "Chat error", e)
                runOnUiThread {
                    adapter.updateLastAssistantMessage("Error: ${e.message}")
                    setProcessing(false)
                }
            }
        }
    }

    private fun sendTaskMessage(text: String) {
        if (!ClawAccessibilityService.isRunning()) {
            Toast.makeText(this, "Enable Accessibility Service in Settings first", Toast.LENGTH_LONG).show()
            return
        }
        if (!KVUtils.hasLlmConfig()) {
            Toast.makeText(this, "Configure LLM in Settings first", Toast.LENGTH_LONG).show()
            return
        }

        addUserMessage("🚀 $text")
        etInput.text.clear()
        setProcessing(true)
        addSystemMessage("Starting task...")

        appViewModel.startNewTask(ChannelEnum.LOCAL, text, "chat_${System.currentTimeMillis()}")

        val checkRunnable = object : Runnable {
            override fun run() {
                if (appViewModel.isTaskRunning()) {
                    recyclerMessages.postDelayed(this, 1000)
                } else {
                    runOnUiThread {
                        addSystemMessage("Task completed.")
                        setProcessing(false)
                    }
                }
            }
        }
        recyclerMessages.postDelayed(checkRunnable, 1000)
    }

    private fun newChat() {
        adapter.clear()
        executor.submit {
            conversation?.close()
            conversation = engine?.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of("You are a helpful AI assistant on an Android phone."),
                    samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                )
            )
            runOnUiThread {
                tvTitle.text = "Chat"
                addSystemMessage("New conversation started.")
            }
        }
    }

    private fun addUserMessage(text: String) {
        adapter.addMessage(ChatMessage(ChatMessage.Role.USER, text))
        scrollToBottom()
    }

    private fun addSystemMessage(text: String) {
        adapter.addMessage(ChatMessage(ChatMessage.Role.SYSTEM, text))
        scrollToBottom()
    }

    private fun scrollToBottom() {
        recyclerMessages.post {
            val count = adapter.itemCount
            if (count > 0) recyclerMessages.smoothScrollToPosition(count - 1)
        }
    }

    private fun setProcessing(processing: Boolean) {
        isProcessing = processing
        setButtonsEnabled(!processing && isModelReady)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnSend.isEnabled = enabled
        btnSend.alpha = if (enabled) 1.0f else 0.4f
        btnTask.isEnabled = enabled
        btnTask.alpha = if (enabled) 1.0f else 0.4f
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.submit {
            conversation?.close()
            engine?.close()
        }
        executor.shutdown()
    }
}
