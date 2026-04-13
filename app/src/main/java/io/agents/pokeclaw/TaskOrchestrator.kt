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
 * Task orchestrator — manages agent lifecycle, task locking, pipeline routing, and execution.
 */
class TaskOrchestrator(
    private val agentConfigProvider: () -> AgentConfig,
    private val onTaskFinished: () -> Unit
) {
    /**
     * Typed event callback for in-app Task mode UI.
     * Called on the agent executor thread — UI must post to main thread.
     */
    var taskEventCallback: ((TaskEvent) -> Unit)? = null

    companion object {
        private const val TAG = "TaskOrchestrator"
    }

    private lateinit var agentService: AgentService
    private val pipelineRouter = PipelineRouter(ClawApplication.instance)
    private val skillExecutor = SkillExecutor()
    val taskSessionStore = TaskSessionStore()

    val inProgressTaskMessageId: String
        get() = taskSessionStore.snapshot().messageId
    val inProgressTaskChannel: Channel?
        get() = taskSessionStore.snapshot().channel

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

    fun tryAcquireTask(messageId: String, channel: Channel, taskText: String = ""): Boolean {
        return taskSessionStore.tryAcquire(
            messageId = messageId,
            channel = channel,
            taskText = taskText,
        )
    }

    private fun releaseTask(): TaskSessionState = taskSessionStore.release()

    fun isTaskRunning(): Boolean = taskSessionStore.isTaskRunning()

    // ==================== Task Execution ====================

    fun cancelCurrentTask() {
        if (!taskSessionStore.markStopping()) return
        if (::agentService.isInitialized) {
            agentService.cancel()
        }
        if (ForegroundService.isRunning()) {
            ForegroundService.updateTaskStatus(ClawApplication.instance, "Stopping task...")
        }
        XLog.d(TAG, "Current task cancellation requested")
    }

    /**
     * Start a new task. Routes through the 3-tier pipeline.
     */
    fun startNewTask(
        channel: Channel,
        task: String,
        messageID: String,
        agentPromptOverride: String? = null,
        isFallback: Boolean = false,
    ) {
        // Acquire task lock if not already held
        if (!isTaskRunning()) {
            if (!tryAcquireTask(messageID, channel, task)) {
                XLog.w(TAG, "Failed to acquire task lock for: $task")
                taskEventCallback?.invoke(TaskEvent.Failed("Another task is running"))
                return
            }
        } else {
            val current = taskSessionStore.snapshot()
            if (current.messageId == messageID && current.channel == channel) {
                taskSessionStore.updateTaskText(task)
            } else {
                XLog.w(
                    TAG,
                    "Rejecting new task while another task is still active: current=${current.messageId}/${current.channel} new=$messageID/$channel"
                )
                taskEventCallback?.invoke(TaskEvent.Failed("Another task is still running. Stop it first."))
                ChannelManager.sendMessage(channel, "Another task is still running. Stop it first.", messageID)
                return
            }
        }

        // Tier 1: Deterministic routing
        val route = pipelineRouter.route(task)
        when (route) {
            is PipelineRouter.Route.DirectIntent -> {
                XLog.i(TAG, "Pipeline Tier 1: DirectIntent — ${route.description}")
                pipelineRouter.executeIntent(route.intent)
                taskEventCallback?.invoke(TaskEvent.Completed(route.description))
                ChannelManager.sendMessage(channel, "✓ ${route.description}", messageID)
                releaseTask()
                ForegroundService.resetToIdle(ClawApplication.instance)
                FloatingCircleManager.setSuccessState()
                onTaskFinished()
                return
            }
            is PipelineRouter.Route.DirectTool -> {
                XLog.i(TAG, "Pipeline Tier 1: DirectTool — ${route.toolName}")
                val toolResult = pipelineRouter.executeTool(route.toolName, route.params)
                if (toolResult.contains("Failed") || toolResult.contains("error") || toolResult.contains("Cannot")) {
                    XLog.w(TAG, "Tier 1 tool failed: $toolResult")
                    taskEventCallback?.invoke(TaskEvent.Completed("Failed: ${route.description}"))
                    ChannelManager.sendMessage(channel, "✗ ${route.description}: $toolResult", messageID)
                } else {
                    taskEventCallback?.invoke(TaskEvent.Completed(route.description))
                    ChannelManager.sendMessage(channel, "✓ ${route.description}", messageID)
                }
                releaseTask()
                ForegroundService.resetToIdle(ClawApplication.instance)
                FloatingCircleManager.setSuccessState()
                onTaskFinished()
                return
            }
            is PipelineRouter.Route.Skill -> {
                if (isFallback) {
                    XLog.i(TAG, "Skipping skill route on fallback, going to agent loop: ${route.skillId}")
                } else {
                    XLog.i(TAG, "Pipeline Tier 2: Skill — ${route.skillId}")
                    val skill = SkillRegistry.findById(route.skillId)
                    if (skill != null) {
                        FloatingCircleManager.ensureShowing()
                        FloatingCircleManager.showTaskNotify(task, channel)
                        Thread({
                            val skillResult = skillExecutor.execute(skill, route.params) { step, total, desc ->
                                taskEventCallback?.invoke(TaskEvent.Progress(step, "Step $step/$total: $desc"))
                                ForegroundService.updateTaskStatus(ClawApplication.instance, desc)
                            }
                            if (skillResult.success) {
                                ChannelManager.sendMessage(channel, skillResult.message, messageID)
                                taskEventCallback?.invoke(TaskEvent.Completed(skillResult.message))
                                releaseTask()
                                FloatingCircleManager.setSuccessState()
                                ForegroundService.resetToIdle(ClawApplication.instance)
                                onTaskFinished()
                            } else {
                                val fallbackGoal = skill.fallbackGoal
                                    .let { g -> route.params.entries.fold(g) { acc, (k, v) -> acc.replace("{$k}", v) } }
                                XLog.i(TAG, "Skill ${skill.id} failed, falling back to agent loop: $fallbackGoal")
                                taskEventCallback?.invoke(TaskEvent.ToolAction("Retrying with AI agent"))
                                startNewTask(channel, fallbackGoal, messageID, isFallback = true)
                            }
                        }, "skill-executor").start()
                        return
                    }
                    XLog.w(TAG, "Skill ${route.skillId} not found, falling through to agent loop")
                }
            }
            is PipelineRouter.Route.Chat, is PipelineRouter.Route.AgentLoop -> {
                // Fall through to agent loop
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
                ForegroundService.resetToIdle(ClawApplication.instance)
                taskEventCallback?.invoke(TaskEvent.Failed("AI service not ready"))
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_service_not_ready), messageID)
                return
            }
        }

        // Per-round message buffer for channel messaging
        val roundBuffer = StringBuilder()
        fun flushRoundBuffer() {
            if (roundBuffer.isNotEmpty()) {
                ChannelManager.sendMessage(channel, roundBuffer.toString().trim(), messageID)
                roundBuffer.clear()
            }
        }

        var floatingShown = false

        val agentPrompt = agentPromptOverride?.takeIf { it.isNotBlank() } ?: task
        agentService.executeTask(agentPrompt, object : AgentCallback {
            override fun onLoopStart(round: Int) {
                flushRoundBuffer()
                XLog.d(TAG, "onLoopStart: round=$round")
                if (round > 1) {
                    FloatingCircleManager.ensureShowing()
                    FloatingCircleManager.setRunningState(round, channel)
                    taskEventCallback?.invoke(TaskEvent.LoopStart(round))
                    if (ForegroundService.isRunning()) {
                        ForegroundService.updateTaskStatus(ClawApplication.instance, "Step $round")
                    }
                }
            }

            override fun onTokenUpdate(status: io.agents.pokeclaw.agent.TokenMonitor.Status) {
                FloatingCircleManager.updateTokenStatus(
                    step = status.step,
                    formattedTokens = status.formattedTokens,
                    formattedCost = status.formattedCost,
                    tokenState = status.state
                )
                taskEventCallback?.invoke(TaskEvent.TokenUpdate(
                    step = status.step,
                    formattedTokens = status.formattedTokens,
                    formattedCost = status.formattedCost,
                    tokenState = status.state
                ))
            }

            override fun onContent(round: Int, content: String) {
                if (content.isNotEmpty()) {
                    roundBuffer.append(content)
                    taskEventCallback?.invoke(TaskEvent.Thinking(content))
                }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                XLog.d(TAG, "onToolCall: $toolId($toolName), $parameters")
                // Don't show floating circle for finish tool (it's just completion, not a real action)
                val isFinish = toolName == "finish" || toolId == "finish"
                if (!floatingShown && !isFinish) {
                    floatingShown = true
                    FloatingCircleManager.ensureShowing()
                    FloatingCircleManager.showTaskNotify(task, channel)
                    ForegroundService.updateTaskStatus(ClawApplication.instance, "Running task...")
                }
                if (toolName.isNotEmpty()) {
                    val displayName = io.agents.pokeclaw.tool.ToolRegistry.getInstance().getDisplayName(toolName)
                    taskEventCallback?.invoke(TaskEvent.ToolAction(displayName))
                    ForegroundService.updateTaskStatus(ClawApplication.instance, "$displayName...")
                }
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                val app = ClawApplication.instance
                val success = result.isSuccess
                var data = if (success) result.data else result.error
                if (data != null && data.length > 300) data = data.substring(0, 300) + "..."
                if (!success) XLog.e(TAG, "Tool failed: $toolName $data")

                val displayName = io.agents.pokeclaw.tool.ToolRegistry.getInstance().getDisplayName(toolName)
                taskEventCallback?.invoke(TaskEvent.ToolResult(displayName, success, data ?: ""))

                if (toolId == "finish" && result.data?.isNotEmpty() == true) {
                    flushRoundBuffer()
                    ChannelManager.sendMessage(channel, result.data, messageID)
                } else {
                    if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                    roundBuffer.append(app.getString(R.string.channel_msg_tool_execution, toolName + parameters,
                        if (success) app.getString(R.string.channel_msg_tool_success) else app.getString(R.string.channel_msg_tool_failure)))
                }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String?) {
                XLog.i(TAG, "onComplete: rounds=$round, totalTokens=$totalTokens, model=$modelName, answer=$finalAnswer")
                val cancelAnswers = setOf(
                    ClawApplication.instance.getString(R.string.agent_task_cancel),
                    ClawApplication.instance.getString(R.string.agent_task_cancelled),
                    ClawApplication.instance.getString(R.string.channel_msg_task_cancelled)
                )
                if (finalAnswer.trim() in cancelAnswers) {
                    taskEventCallback?.invoke(TaskEvent.Cancelled)
                    ForegroundService.resetToIdle(ClawApplication.instance)
                    flushRoundBuffer()
                    val cancelledSession = releaseTask()
                    if (cancelledSession.channel != null && cancelledSession.messageId.isNotEmpty()) {
                        ChannelManager.sendMessage(
                            cancelledSession.channel,
                            ClawApplication.instance.getString(R.string.channel_msg_task_cancelled),
                            cancelledSession.messageId
                        )
                        ChannelManager.flushMessages(cancelledSession.channel)
                    }
                    FloatingCircleManager.setErrorState()
                    onTaskFinished()
                    XLog.d(TAG, "Current task cancelled by user")
                    return
                }
                // Strip common LLM-added prefixes from the answer
                var answer = finalAnswer.ifEmpty { "Done." }
                answer = answer.removePrefix("Task completed:").removePrefix("Task completed").trim()
                if (answer.isEmpty()) answer = "Done."
                taskEventCallback?.invoke(TaskEvent.Completed(answer, modelName))
                ForegroundService.resetToIdle(ClawApplication.instance)
                flushRoundBuffer()
                val completedSession = releaseTask()
                ChannelManager.flushMessages(completedSession.channel ?: channel)
                FloatingCircleManager.setSuccessState()
                // Auto-return to PokeClaw after in-app task completes
                if (completedSession.autoReturnToChat) {
                    XLog.i(TAG, "onComplete: auto-returning to PokeClaw chatroom")
                    try {
                        val context = ClawApplication.instance
                        val intent = android.content.Intent(context, io.agents.pokeclaw.ui.chat.ComposeChatActivity::class.java).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        XLog.w(TAG, "onComplete: auto-return failed", e)
                    }
                }
                onTaskFinished()
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                XLog.e(TAG, "onError: ${error.message}, totalTokens=$totalTokens", error)
                taskEventCallback?.invoke(TaskEvent.Failed(error.message ?: "Unknown error"))
                ForegroundService.resetToIdle(ClawApplication.instance)
                flushRoundBuffer()
                val failedSession = releaseTask()
                val failedChannel = failedSession.channel ?: channel
                val failedMessageId = failedSession.messageId.ifEmpty { messageID }
                ChannelManager.sendMessage(
                    failedChannel,
                    ClawApplication.instance.getString(R.string.channel_msg_task_error, error.message),
                    failedMessageId
                )
                ChannelManager.flushMessages(failedChannel)
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                XLog.w(TAG, "onSystemDialogBlocked: round=$round, totalTokens=$totalTokens")
                taskEventCallback?.invoke(TaskEvent.Blocked)
                flushRoundBuffer()
                val blockedSession = releaseTask()
                val blockedChannel = blockedSession.channel ?: channel
                val blockedMessageId = blockedSession.messageId.ifEmpty { messageID }
                ChannelManager.sendMessage(
                    blockedChannel,
                    ClawApplication.instance.getString(R.string.channel_msg_system_dialog_blocked),
                    blockedMessageId
                )
                try {
                    val service = ClawAccessibilityService.getInstance()
                    val bitmap = service?.takeScreenshot(5000)
                    if (bitmap != null) {
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                        bitmap.recycle()
                        ChannelManager.sendImage(blockedChannel, stream.toByteArray(), blockedMessageId)
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
