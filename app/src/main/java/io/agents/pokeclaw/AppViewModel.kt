// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw

import android.os.PowerManager
import androidx.lifecycle.ViewModel
import io.agents.pokeclaw.ClawApplication.Companion.appViewModelInstance
import io.agents.pokeclaw.agent.AgentConfig
import io.agents.pokeclaw.agent.LlmProvider
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.channel.ChannelSetup
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.floating.FloatingCircleManager
import io.agents.pokeclaw.server.ConfigServerManager
import io.agents.pokeclaw.service.KeepAliveJobService
import io.agents.pokeclaw.ui.home.HomeActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

class AppViewModel : ViewModel() {

    companion object {
        private const val TAG = "AppViewModel"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private var _commonInitialized = false

    val taskOrchestrator = TaskOrchestrator(
        agentConfigProvider = { getAgentConfig() },
        onTaskFinished = { /* refresh */ }
    )

    private val channelSetup = ChannelSetup(taskOrchestrator = taskOrchestrator)

    val inProgressTaskMessageId: String get() = taskOrchestrator.inProgressTaskMessageId
    val inProgressTaskChannel: Channel? get() = taskOrchestrator.inProgressTaskChannel

    fun init() {
        initCommon()
        initAgent()
    }

    fun initCommon() {
        if (_commonInitialized) return
        _commonInitialized = true
    }

    fun initAgent() {
        if (!KVUtils.hasLlmConfig()) return
        taskOrchestrator.initAgent()
    }

    fun getAgentConfig(): AgentConfig {
        val provider = try {
            LlmProvider.valueOf(KVUtils.getLlmProvider())
        } catch (_: Exception) {
            LlmProvider.OPENAI
        }

        val baseUrl = if (provider == LlmProvider.LOCAL) {
            KVUtils.getLocalModelPath()
        } else {
            KVUtils.getLlmBaseUrl().trim().ifEmpty { "https://api.openai.com/v1" }
        }

        return AgentConfig.Builder()
            .apiKey(KVUtils.getLlmApiKey())
            .baseUrl(baseUrl)
            .modelName(KVUtils.getLlmModelName())
            .temperature(0.1)
            .maxIterations(60)
            .provider(provider)
            .build()
    }

    fun updateAgentConfig(): Boolean = taskOrchestrator.updateAgentConfig()

    fun afterInit() {
        acquireScreenWakeLock()
        ForegroundService.start(ClawApplication.instance)
        KeepAliveJobService.schedule(ClawApplication.instance)
        ConfigServerManager.autoStartIfNeeded(ClawApplication.instance)
        if (android.provider.Settings.canDrawOverlays(ClawApplication.instance)) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                appViewModelInstance.showFloatingCircle()
            }
        }
        channelSetup.setup()
    }


    /**
     * Acquire a wake lock to prevent the screen from turning off during accessibility operations
     */
    private fun acquireScreenWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = ClawApplication.instance.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "PokeClaw::ScreenWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minute timeout to prevent battery drain
        }
        XLog.i(TAG, "Wake lock acquired")
    }

    /**
     * Release the wake lock
     */
    private fun releaseScreenWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                XLog.i(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    /**
     * Show the circular floating window
     */
    fun showFloatingCircle() {
        try {
            FloatingCircleManager.show(ClawApplication.instance)
            FloatingCircleManager.onFloatClick = {
                XLog.d(TAG, "Floating circle clicked")
                bringAppToForeground()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to show floating circle: ${e.message}")
        }
    }

    /**
     * Bring the app to the foreground
     */
    private fun bringAppToForeground() {
        val context = ClawApplication.instance
        val intent = android.content.Intent(context, io.agents.pokeclaw.ui.chat.ComposeChatActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    fun isTaskRunning(): Boolean = taskOrchestrator.isTaskRunning()

    fun cancelCurrentTask() = taskOrchestrator.cancelCurrentTask()

    fun startNewTask(channel: Channel, task: String, messageID: String) =
        taskOrchestrator.startNewTask(channel, task, messageID)

    private fun trySendScreenshot(channel: Channel, filePath: String, messageID: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                XLog.w(TAG, "Screenshot file does not exist: $filePath")
                return
            }
            val imageBytes = file.readBytes()
            ChannelManager.sendImage(channel, imageBytes, messageID)
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to send screenshot", e)
        }
    }
}
