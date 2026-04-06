// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw

import io.agents.pokeclaw.agent.AgentCallback
import io.agents.pokeclaw.agent.AgentConfig
import io.agents.pokeclaw.agent.AgentService
import io.agents.pokeclaw.agent.AgentServiceFactory
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.floating.FloatingCircleManager
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog

/**
 * 任务编排器，负责 Agent 生命周期管理、任务锁、任务执行与回调处理。
 *
 * @param agentConfigProvider 延迟获取最新 AgentConfig 的回调
 * @param onTaskFinished 每次任务结束（成功/失败/取消）后的通知，用于刷新用户信息等
 */
class TaskOrchestrator(
    private val agentConfigProvider: () -> AgentConfig,
    private val onTaskFinished: () -> Unit
) {
    /**
     * Optional progress callback for in-app Task mode UI.
     * Called on the agent executor thread — UI must post to main thread.
     * Set by ComposeChatActivity when running LOCAL channel tasks.
     */
    var taskProgressCallback: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "TaskOrchestrator"
    }

    private lateinit var agentService: AgentService

    private val taskLock = Any()
    @Volatile
    var inProgressTaskMessageId: String = ""
        private set
    @Volatile
    var inProgressTaskChannel: Channel? = null
        private set

    // ==================== Agent 生命周期 ====================

    fun initAgent() {
        agentService = AgentServiceFactory.create()
        try {
            agentService.initialize(agentConfigProvider())
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to initialize AgentService", e)
        }
    }

    fun updateAgentConfig(): Boolean {
        return try {
            val config = agentConfigProvider()
            if (::agentService.isInitialized) {
                agentService.updateConfig(config)
                XLog.d(TAG, "Agent config updated: model=${config.modelName}, temp=${config.temperature}")
                true
            } else {
                XLog.w(TAG, "AgentService not initialized, initializing with new config")
                agentService = AgentServiceFactory.create()
                agentService.initialize(config)
                true
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to update agent config", e)
            false
        }
    }

    // ==================== 任务锁 ====================

    /**
     * 原子地尝试获取任务锁。如果当前无任务在执行，则标记为占用并返回 true；否则返回 false。
     */
    fun tryAcquireTask(messageId: String, channel: Channel): Boolean {
        synchronized(taskLock) {
            if (inProgressTaskMessageId.isNotEmpty()) return false
            inProgressTaskMessageId = messageId
            inProgressTaskChannel = channel
            return true
        }
    }

    /**
     * 释放任务锁，返回释放前的 (channel, messageId) 供调用方使用。
     */
    private fun releaseTask(): Pair<Channel?, String> {
        synchronized(taskLock) {
            val ch = inProgressTaskChannel
            val id = inProgressTaskMessageId
            inProgressTaskMessageId = ""
            inProgressTaskChannel = null
            return ch to id
        }
    }

    fun isTaskRunning(): Boolean {
        synchronized(taskLock) {
            return inProgressTaskMessageId.isNotEmpty()
        }
    }

    // ==================== 任务执行 ====================

    fun cancelCurrentTask() {
        if (!isTaskRunning()) return
        if (::agentService.isInitialized) {
            agentService.cancel()
        }
        val (channel, messageId) = releaseTask()
        if (channel != null && messageId.isNotEmpty()) {
            ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_task_cancelled), messageId)
        }
        FloatingCircleManager.setErrorState()
        onTaskFinished()
        XLog.d(TAG, "Current task cancelled by user")
    }

    fun startNewTask(channel: Channel, task: String, messageID: String) {
        if (!::agentService.isInitialized) {
            XLog.e(TAG, "AgentService not initialized, attempting to initialize")
            try {
                agentService = AgentServiceFactory.create()
                agentService.initialize(agentConfigProvider())
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to initialize AgentService", e)
                releaseTask()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_service_not_ready), messageID)
                return
            }
        }

        ClawAccessibilityService.getInstance()?.pressHome()

        FloatingCircleManager.showTaskNotify(task, channel)

        // 每轮消息聚合缓冲：thinking + toolResult 攒成一条，减少发送次数
        val roundBuffer = StringBuilder()

        fun flushRoundBuffer() {
            if (roundBuffer.isNotEmpty()) {
                ChannelManager.sendMessage(channel, roundBuffer.toString().trim(), messageID)
                roundBuffer.clear()
            }
        }

        agentService.executeTask(task, object : AgentCallback {
            override fun onLoopStart(round: Int) {
                // 新一轮开始前，flush 上一轮积攒的消息
                flushRoundBuffer()
                FloatingCircleManager.setRunningState(round, channel)
                XLog.d(TAG, "onLoopStart: round=$round")
                taskProgressCallback?.invoke("Reading screen... (step $round)")
            }

            override fun onContent(round: Int, content: String) {
                if (content.isNotEmpty()) {
                    roundBuffer.append(content)
                }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                XLog.d(TAG, "onToolCall: $toolId($toolName), $parameters")
                // Show human-readable tool name to user (e.g. "Tapping screen...")
                if (toolName.isNotEmpty()) {
                    taskProgressCallback?.invoke("$toolName...")
                }
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                val app = ClawApplication.instance
                val status = if (result.isSuccess) app.getString(R.string.channel_msg_tool_success) else app.getString(R.string.channel_msg_tool_failure)
                var data = if (result.isSuccess) result.data else result.error
                if (data != null && data.length > 300) {
                    data = data.substring(0, 300) + "...(truncated)"
                }
                if (!result.isSuccess) {
                    XLog.e(TAG, "!!!!!!!!!!Fail: $toolName, $parameters $data")
                }
                XLog.e(TAG, "onToolResult: $toolName, $status $data")
                if (toolId == "finish" && (result.data?.isNotEmpty() ?: false)) {
                    // finish 的结果单独发，不合并（这是最终回复）
                    flushRoundBuffer()
                    ChannelManager.sendMessage(channel, result.data, messageID)
                } else {
                    // 追加到本轮缓冲
                    if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                    roundBuffer.append(
                        app.getString(R.string.channel_msg_tool_execution, toolName + parameters, status)
                    )
                }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                XLog.i(TAG, "onComplete: 轮数=$round, totalTokens=$totalTokens, answer=$finalAnswer")
                taskProgressCallback?.invoke("Task completed.")
                flushRoundBuffer()
                releaseTask()
                ChannelManager.flushMessages(channel)
                FloatingCircleManager.setSuccessState()
                onTaskFinished()
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                XLog.e(TAG, "onError: ${error.message}, totalTokens=$totalTokens", error)
                taskProgressCallback?.invoke("Task failed: ${error.message}")
                flushRoundBuffer()
                releaseTask()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_task_error, error.message), messageID)
                ChannelManager.flushMessages(channel)
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                XLog.w(TAG, "onSystemDialogBlocked: round=$round, totalTokens=$totalTokens")
                taskProgressCallback?.invoke("Blocked by system dialog.")
                flushRoundBuffer()
                releaseTask()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_system_dialog_blocked), messageID)
                try {
                    val service = ClawAccessibilityService.getInstance()
                    val bitmap = service?.takeScreenshot(5000)
                    if (bitmap != null) {
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                        bitmap.recycle()
                        ChannelManager.sendImage(channel, stream.toByteArray(), messageID)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to send screenshot for system dialog", e)
                }
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }
        })
    }
}
