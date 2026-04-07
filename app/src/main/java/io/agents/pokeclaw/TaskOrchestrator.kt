// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw

import io.agents.pokeclaw.agent.AgentCallback
import io.agents.pokeclaw.agent.AgentConfig
import io.agents.pokeclaw.agent.AgentService
import io.agents.pokeclaw.agent.AgentServiceFactory
import io.agents.pokeclaw.agent.PipelineRouter
import io.agents.pokeclaw.agent.skill.SkillExecutor
import io.agents.pokeclaw.agent.skill.SkillRegistry
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.floating.FloatingCircleManager
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog

/**
 * Task orchestrator, responsible for Agent lifecycle management, task locking, task execution and callback handling.
 *
 * @param agentConfigProvider callback to lazily retrieve the latest AgentConfig
 * @param onTaskFinished notification after each task ends (success/failure/cancel), used for refreshing user info etc
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
    private val pipelineRouter = PipelineRouter(ClawApplication.instance)
    private val skillExecutor = SkillExecutor()

    private val taskLock = Any()
    @Volatile
    var inProgressTaskMessageId: String = ""
        private set
    @Volatile
    var inProgressTaskChannel: Channel? = null
        private set

    // ==================== Agent Lifecycle ====================

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

    // ==================== Task Lock ====================

    /**
     * Atomically try to acquire the task lock. Returns true and marks as busy if no task is running; returns false otherwise.
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
     * Release the task lock. Returns the (channel, messageId) held before release for the caller to use.
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

    // ==================== Task Execution ====================

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
        // Tier 1: Try PipelineRouter for deterministic routing before agent loop
        val route = pipelineRouter.route(task)
        when (route) {
            is PipelineRouter.Route.DirectIntent -> {
                XLog.i(TAG, "Pipeline Tier 1: DirectIntent — ${route.description}")
                pipelineRouter.executeIntent(route.intent)
                taskProgressCallback?.invoke(route.description)
                ChannelManager.sendMessage(channel, "✓ ${route.description}", messageID)
                releaseTask()
                FloatingCircleManager.setSuccessState()
                onTaskFinished()
                return
            }
            is PipelineRouter.Route.DirectTool -> {
                XLog.i(TAG, "Pipeline Tier 1: DirectTool — ${route.toolName}")
                val result = pipelineRouter.executeTool(route.toolName, route.params)
                taskProgressCallback?.invoke(route.description)
                ChannelManager.sendMessage(channel, "✓ ${route.description}", messageID)
                releaseTask()
                FloatingCircleManager.setSuccessState()
                onTaskFinished()
                return
            }
            is PipelineRouter.Route.Skill -> {
                XLog.i(TAG, "Pipeline Tier 2: Skill — ${route.skillId}")
                val skill = SkillRegistry.findById(route.skillId)
                if (skill != null) {
                    val skillResult = skillExecutor.execute(skill, route.params) { step, total, desc ->
                        taskProgressCallback?.invoke("Step $step/$total: $desc")
                        ForegroundService.updateTaskStatus(ClawApplication.instance, desc)
                    }
                    ChannelManager.sendMessage(channel, skillResult.message, messageID)
                    releaseTask()
                    if (skillResult.success) FloatingCircleManager.setSuccessState()
                    else FloatingCircleManager.setErrorState()
                    onTaskFinished()
                    return
                }
                XLog.w(TAG, "Skill ${route.skillId} not found, falling through to agent loop")
            }
            is PipelineRouter.Route.Chat, is PipelineRouter.Route.AgentLoop -> {
                // Fall through to agent loop below
            }
        }

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

        FloatingCircleManager.showTaskNotify(task, channel)
        ForegroundService.updateTaskStatus(ClawApplication.instance, "Warming up AI...")

        // Per-round message aggregation buffer: accumulate thinking + toolResult into one message to reduce sends
        val roundBuffer = StringBuilder()

        fun flushRoundBuffer() {
            if (roundBuffer.isNotEmpty()) {
                ChannelManager.sendMessage(channel, roundBuffer.toString().trim(), messageID)
                roundBuffer.clear()
            }
        }

        agentService.executeTask(task, object : AgentCallback {
            override fun onLoopStart(round: Int) {
                // Before starting a new round, flush the accumulated messages from the previous round
                flushRoundBuffer()
                FloatingCircleManager.setRunningState(round, channel)
                XLog.d(TAG, "onLoopStart: round=$round")
                val msg = "Reading screen... (step $round)"
                taskProgressCallback?.invoke(msg)
                ForegroundService.updateTaskStatus(ClawApplication.instance, msg)
            }

            override fun onTokenUpdate(status: io.agents.pokeclaw.agent.TokenMonitor.Status) {
                FloatingCircleManager.updateTokenStatus(
                    step = status.step,
                    formattedTokens = status.formattedTokens,
                    formattedCost = status.formattedCost,
                    tokenState = status.state
                )
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
                    val msg = "$toolName..."
                    taskProgressCallback?.invoke(msg)
                    ForegroundService.updateTaskStatus(ClawApplication.instance, msg)
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
                    // finish result is sent separately, not merged (this is the final reply)
                    flushRoundBuffer()
                    ChannelManager.sendMessage(channel, result.data, messageID)
                } else {
                    // Append to current round buffer
                    if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                    roundBuffer.append(
                        app.getString(R.string.channel_msg_tool_execution, toolName + parameters, status)
                    )
                }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                XLog.i(TAG, "onComplete: rounds=$round, totalTokens=$totalTokens, answer=$finalAnswer")
                taskProgressCallback?.invoke("Task completed.")
                ForegroundService.resetToIdle(ClawApplication.instance)
                flushRoundBuffer()
                releaseTask()
                ChannelManager.flushMessages(channel)
                FloatingCircleManager.setSuccessState()
                onTaskFinished()
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                XLog.e(TAG, "onError: ${error.message}, totalTokens=$totalTokens", error)
                taskProgressCallback?.invoke("Task failed: ${error.message}")
                ForegroundService.resetToIdle(ClawApplication.instance)
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
