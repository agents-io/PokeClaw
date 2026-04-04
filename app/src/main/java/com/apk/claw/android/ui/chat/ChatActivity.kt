package com.apk.claw.android.ui.chat

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apk.claw.android.R
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.appViewModel
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.agent.AgentCallback
import com.apk.claw.android.channel.Channel as ChannelEnum
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.ToolResult
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

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnTask: ImageButton

    private val adapter = ChatMessageAdapter()
    private val executor = Executors.newSingleThreadExecutor()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var isModelReady = false
    private var isProcessing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        drawerLayout = findViewById(R.id.drawerLayout)
        tvTitle = findViewById(R.id.tvTitle)
        tvStatus = findViewById(R.id.tvStatus)
        recyclerMessages = findViewById(R.id.recyclerMessages)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnTask = findViewById(R.id.btnTask)

        // RecyclerView setup
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerMessages.layoutManager = layoutManager
        recyclerMessages.adapter = adapter

        // Menu button → open drawer
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            drawerLayout.open()
        }

        // Settings button
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Sidebar buttons
        findViewById<TextView>(R.id.btnNewChat).setOnClickListener {
            newChat()
            drawerLayout.close()
        }
        findViewById<TextView>(R.id.btnSidebarSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            drawerLayout.close()
        }
        findViewById<TextView>(R.id.btnSidebarModels).setOnClickListener {
            startActivity(Intent(this, LlmConfigActivity::class.java))
            drawerLayout.close()
        }

        // Send button → chat
        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty() || !isModelReady || isProcessing) return@setOnClickListener
            sendChatMessage(text)
        }

        // Task button → agent pipeline
        btnTask.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty() || isProcessing) return@setOnClickListener
            sendTaskMessage(text)
        }

        // Load model
        loadModelIfConfigured()
    }

    override fun onResume() {
        super.onResume()
        // Reload model if config changed
        if (!isModelReady && KVUtils.getLocalModelPath().isNotEmpty()) {
            loadModelIfConfigured()
        }
    }

    private fun loadModelIfConfigured() {
        val modelPath = KVUtils.getLocalModelPath()
        if (modelPath.isEmpty()) {
            tvStatus.text = "No model. Tap Settings → LLM Config → Download"
            addSystemMessage("No model downloaded. Go to Settings to download one.")
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
                runOnUiThread { tvStatus.text = "GPU unavailable, using CPU..." }
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
                    systemInstruction = Contents.of("You are a helpful AI assistant running on an Android phone. You can also control the phone using accessibility tools when the user asks you to perform tasks."),
                    samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                )
            )

            isModelReady = true
            runOnUiThread {
                val modelName = modelPath.substringAfterLast('/').substringBeforeLast('.')
                tvStatus.text = "Ready: $modelName"
                setButtonsEnabled(true)
                addSystemMessage("Model loaded. Send = chat, 🚀 = phone task.")
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to load model", e)
            runOnUiThread {
                tvStatus.text = "Error: ${e.message}"
                addSystemMessage("Failed to load model: ${e.message}")
            }
        }
    }

    /**
     * Send a chat message — pure conversation with the model.
     */
    private fun sendChatMessage(text: String) {
        addUserMessage(text)
        etInput.text.clear()
        setProcessing(true)

        // Add placeholder for AI response
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

    /**
     * Send a task — triggers the Accessibility agent pipeline.
     * Results are displayed inline in the chat.
     */
    private fun sendTaskMessage(text: String) {
        if (!ClawAccessibilityService.isRunning()) {
            Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_LONG).show()
            return
        }
        if (!KVUtils.hasLlmConfig()) {
            Toast.makeText(this, "Configure LLM first", Toast.LENGTH_LONG).show()
            return
        }

        addUserMessage("🚀 $text")
        etInput.text.clear()
        setProcessing(true)
        addSystemMessage("Starting task...")

        appViewModel.startNewTask(ChannelEnum.LOCAL, text, "chat_${System.currentTimeMillis()}")

        // Monitor task via polling (since we can't easily hook into callbacks from here)
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
                    systemInstruction = Contents.of("You are a helpful AI assistant running on an Android phone."),
                    samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                )
            )
            runOnUiThread {
                tvTitle.text = "New Chat"
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
