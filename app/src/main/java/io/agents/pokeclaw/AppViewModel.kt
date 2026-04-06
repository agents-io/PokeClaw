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
        onTaskFinished = { /* 刷新 */ }
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
     * 获取亮屏锁，防止息屏后无障碍服务无法操作
     */
    private fun acquireScreenWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = ClawApplication.instance.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "PokeClaw::ScreenWakeLock"
        ).apply {
            acquire()
        }
        XLog.i(TAG, "亮屏锁已获取")
    }

    /**
     * 释放亮屏锁
     */
    private fun releaseScreenWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                XLog.i(TAG, "亮屏锁已释放")
            }
        }
        wakeLock = null
    }

    /**
     * 显示圆形悬浮窗
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
     * 将应用带回前台
     */
    private fun bringAppToForeground() {
        val context = ClawApplication.instance
        val intent = android.content.Intent(context, HomeActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
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
                XLog.w(TAG, "截图文件不存在: $filePath")
                return
            }
            val imageBytes = file.readBytes()
            ChannelManager.sendImage(channel, imageBytes, messageID)
        } catch (e: Exception) {
            XLog.e(TAG, "发送截图失败", e)
        }
    }
}
