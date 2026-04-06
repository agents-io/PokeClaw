package io.agents.pokeclaw.agent

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.langchain.LangChain4jToolBridge
import io.agents.pokeclaw.agent.llm.LlmClient
import io.agents.pokeclaw.agent.llm.LlmClientFactory
import io.agents.pokeclaw.agent.llm.LlmResponse
import io.agents.pokeclaw.agent.llm.StreamingListener
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.tool.impl.GetScreenInfoTool
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import java.io.File
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DefaultAgentService : AgentService {

    companion object {
        private const val TAG = "AgentService"
        private val GSON = Gson()

        /** LLM API 调用失败时的最大重试次数 */
        private const val MAX_API_RETRIES = 3
        /** 死循环检测：滑动窗口大小 */
        private const val LOOP_DETECT_WINDOW = 4

        /**
         * Opt-3: Action tools — after any of these execute we auto-attach a fresh
         * get_screen_info result so the LLM can see the updated UI without spending
         * an extra inference round (5 s) to call it manually.
         */
        private val ACTION_TOOLS = setOf(
            "phone_click_node", "phone_tap", "phone_swipe", "phone_long_press",
            "tap", "long_press", "swipe", "scroll_to_find",
            "input_text", "type_text", "system_key", "open_app",
            "dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center",
            "volume_up", "volume_down", "press_menu", "press_power",
            "clipboard", "send_file", "repeat_actions", "wait"
        )
        /** ms to wait for UI to settle before capturing screen after an action */
        private const val SCREEN_SETTLE_MS = 500L

        /** 是否将网络请求/响应原始数据输出到沙盒缓存文件，方便调试 */
        @JvmField
        var FILE_LOGGING_ENABLED = false
        @JvmField
        var FILE_LOGGING_CACHE_DIR: File? = null
    }

    private lateinit var config: AgentConfig
    private lateinit var llmClient: LlmClient
    private lateinit var toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    override fun initialize(config: AgentConfig) {
        this.config = config
        this.llmClient = LlmClientFactory.create(config)
        this.toolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        this.executor = Executors.newSingleThreadExecutor()
        XLog.i(TAG, "Agent initialized: provider=${config.provider}, model=${config.modelName}, streaming=${config.streaming}")
    }

    override fun updateConfig(config: AgentConfig) {
        if (running.get()) {
            cancel()
            XLog.w(TAG, "Task was running during config update, cancelled")
        }
        executor?.shutdownNow()
        // Close old LlmClient before reinitializing to free engine memory
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
                XLog.i(TAG, "Old LlmClient closed before config update")
            } catch (e: Exception) {
                XLog.w(TAG, "Old LlmClient close error during config update", e)
            }
        }
        initialize(config)
        XLog.i(TAG, "Agent config updated, new model: ${config.modelName}")
    }

    override fun executeTask(userPrompt: String, callback: AgentCallback) {
        if (running.get()) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)

        executor?.submit {
            try {
                runAgentLoop(userPrompt, callback)
            } catch (e: Exception) {
                XLog.e(TAG, "Agent execution error", e)
                callback.onError(0, e, 0)
            } finally {
                // Close local engine BEFORE clearing running flag so the chat engine
                // reload (triggered by onComplete/onError) never overlaps with task engine.
                if (::llmClient.isInitialized) {
                    try {
                        llmClient.close()
                        XLog.i(TAG, "LlmClient closed after task completion")
                    } catch (e: Exception) {
                        XLog.w(TAG, "LlmClient close error after task", e)
                    }
                }
                running.set(false)
            }
        }
    }

    // ==================== 环境预检 ====================

    private fun preCheck(): String? {
        if (ClawAccessibilityService.getInstance() == null) {
            return ClawApplication.instance.getString(R.string.agent_accessibility_not_enabled)
        }
        return null
    }

    // ==================== 设备上下文 ====================

    private fun buildDeviceContext(): String {
        val app = ClawApplication.instance
        val sb = StringBuilder()
        sb.append("\n\n## 设备信息\n")
        sb.append("- 品牌: ").append(Build.BRAND).append("\n")
        sb.append("- 型号: ").append(Build.MODEL).append("\n")
        sb.append("- Android 版本: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        try {
            val wm = app
                .getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            sb.append("- 屏幕分辨率: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get display metrics", e)
        }

        sb.append("- 已注册工具数: ").append(ToolRegistry.getAllTools().size).append("\n")

        val appName = try {
            val appInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
            app.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { "CoPaw" }
        sb.append("\n## 本应用信息\n")
        sb.append("- 应用名: ").append(appName).append("\n")
        sb.append("- 包名: ").append(app.packageName).append("\n")
        sb.append("- 当用户提到'自己/本应用/这个应用'时，指的就是上述应用\n")

        return sb.toString()
    }

    // ==================== LLM 调用（带重试） ====================

    private fun chatWithRetry(messages: List<ChatMessage>, callback: AgentCallback, iteration: Int): LlmResponse {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_API_RETRIES) {
            if (cancelled.get()) throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
            try {
                return if (config.streaming) {
                    val textBuilder = StringBuilder()
                    llmClient.chatStreaming(messages, toolSpecs, object : StreamingListener {
                        override fun onPartialText(token: String) {
                            textBuilder.append(token)
                            callback.onContent(iteration, token)
                        }
                        override fun onComplete(response: LlmResponse) {}
                        override fun onError(error: Throwable) {}
                    })
                } else {
                    llmClient.chat(messages, toolSpecs)
                }
            } catch (e: Exception) {
                lastException = e
                val msg = e.message ?: ""
                // Token 耗尽或认证失败不重试
                if (msg.contains("401") || msg.contains("403") || msg.contains("insufficient")) {
                    throw e
                }
                val delay = (Math.pow(2.0, attempt.toDouble()) * 1000).toLong()
                XLog.w(TAG, "LLM API call failed (attempt ${attempt + 1}/$MAX_API_RETRIES), retrying in ${delay}ms: $msg")
                try {
                    Thread.sleep(delay)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastException!!
    }

    // ==================== 死循环检测 ====================

    private data class RoundFingerprint(val screenHash: Int, val toolCall: String)

    private fun isStuckInLoop(history: LinkedList<RoundFingerprint>): Boolean {
        if (history.size < LOOP_DETECT_WINDOW) return false
        val first = history.first()
        return history.all { it == first }
    }

    // ==================== 上下文压缩 ====================

    /** 保护区：最近 N 轮完整保留 */
    private val KEEP_RECENT_ROUNDS = 3

    /** 大输出观察类工具 → 压缩后占位符 */
    private val OBSERVATION_PLACEHOLDERS = mapOf(
        "get_screen_info" to "[屏幕信息已省略]",
        "take_screenshot" to "[截图结果已省略]",
        "find_node_info" to "[节点查找结果已省略]",
        "get_installed_apps" to "[应用列表已省略]",
        "scroll_to_find" to "[滚动查找结果已省略]"
    )

    /**
     * 发送前压缩历史消息，节省 input token：
     * - get_screen_info：全局只保留最新一条完整结果
     * - 保护区（最近 KEEP_RECENT_ROUNDS 轮）：完整保留
     * - 保护区外：AI thinking 不动，tool result 压缩为一行摘要
     */
    private fun compressHistoryForSend(messages: MutableList<ChatMessage>) {
        // 压缩前统计总字符数
        val charsBefore = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val msgCountBefore = messages.size

        // 0. get_screen_info 特殊处理：无视分级，全局只保留最新一条完整结果
        val screenPlaceholder = OBSERVATION_PLACEHOLDERS["get_screen_info"]!!
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
        }
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage
                && msg.toolName() == "get_screen_info"
                && i != lastScreenIdx
                && msg.text() != screenPlaceholder
            ) {
                messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), screenPlaceholder)
            }
        }

        // 1. 找出所有 AiMessage 的索引，每个代表一轮
        val aiIndices = messages.indices.filter { messages[it] is AiMessage }
        if (aiIndices.size <= KEEP_RECENT_ROUNDS) return

        val totalRounds = aiIndices.size

        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= KEEP_RECENT_ROUNDS) break // 保护区

            val aiIndex = aiIndices[roundIdx]

            // 收集本轮的 ToolExecutionResultMessage 索引
            var j = aiIndex + 1
            while (j < messages.size && messages[j] is ToolExecutionResultMessage) {
                compressToolResultMessage(messages, j)
                j++
            }
        }

        // 压缩后统计
        val charsAfter = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val saved = charsBefore - charsAfter
        if (saved > 0) {
            XLog.i(TAG, "上下文压缩: ${charsBefore}→${charsAfter}字符, 节省${saved}字符(${saved * 100 / charsBefore}%), 轮数=${aiIndices.size}")
        }
    }

    /** 压缩 Tool Result：观察类工具用占位符，其他工具截取摘要 */
    private fun compressToolResultMessage(messages: MutableList<ChatMessage>, index: Int) {
        val msg = messages[index] as ToolExecutionResultMessage
        val text = msg.text()
        if (text.length <= 100) return // 已足够简短，无需压缩

        val placeholder = OBSERVATION_PLACEHOLDERS[msg.toolName()]
        if (placeholder != null) {
            messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), placeholder)
            return
        }

        // 其他工具：解析 JSON 提取摘要
        val compressed = summarizeToolResult(text)
        messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), compressed)
    }

    /** 将 ToolResult JSON 压缩为一行摘要 */
    private fun summarizeToolResult(resultJson: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = GSON.fromJson(resultJson, mapType)
            val isSuccess = map["isSuccess"] as? Boolean ?: false
            if (isSuccess) {
                val data = map["data"]?.toString() ?: "ok"
                "✓ " + if (data.length > 80) data.take(80) + "..." else data
            } else {
                val error = map["error"]?.toString() ?: "failed"
                "✗ " + if (error.length > 80) error.take(80) + "..." else error
            }
        } catch (_: Exception) {
            if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
        }
    }

    // ==================== 主执行循环 ====================

    private fun runAgentLoop(userPrompt: String, callback: AgentCallback) {
        // 环境预检
        preCheck()?.let {
            callback.onError(0, RuntimeException(it), 0)
            return
        }

        // 构建 System Prompt（原始 + 设备上下文）
        val fullSystemPrompt = config.systemPrompt + buildDeviceContext()

        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(fullSystemPrompt))

        // Opt-2: Pre-warm — grab screen state BEFORE first LLM call so the model can
        // act immediately without spending a full inference round (5 s) just calling
        // get_screen_info. Saves one round trip on every task.
        val enrichedPrompt = try {
            val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
            if (screenTool != null) {
                val screenResult = screenTool.execute(emptyMap())
                if (screenResult.isSuccess && !screenResult.data.isNullOrBlank()) {
                    XLog.i(TAG, "runAgentLoop: pre-warm screen attached (${screenResult.data!!.length} chars)")
                    "$userPrompt\n\nCurrent screen:\n${screenResult.data}"
                } else {
                    XLog.w(TAG, "runAgentLoop: pre-warm get_screen_info failed: ${screenResult.error}")
                    userPrompt
                }
            } else {
                XLog.w(TAG, "runAgentLoop: get_screen_info tool not found, skipping pre-warm")
                userPrompt
            }
        } catch (e: Exception) {
            XLog.w(TAG, "runAgentLoop: pre-warm exception, using plain prompt", e)
            userPrompt
        }
        messages.add(UserMessage.from(enrichedPrompt))

        var iterations = 0
        var totalTokens = 0
        val maxIterations = config.maxIterations
        val loopHistory = LinkedList<RoundFingerprint>()
        var lastScreenHash = 0

        while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)

            // 发送前分级压缩历史消息，节省 token
            compressHistoryForSend(messages)

            // LLM 调用（带重试）
            val llmResponse: LlmResponse
            try {
                llmResponse = chatWithRetry(messages, callback, iterations)
            } catch (e: Exception) {
                XLog.e(TAG, "LLM API call failed after retries", e)
                callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_api_call_failed, e.message)), totalTokens)
                return
            }

            // 累加 token 用量
            llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }

            // DEBUG: log raw LLM response for tool calling diagnosis
            XLog.i(TAG, "runAgentLoop iter=$iterations response.text=${llmResponse.text?.take(500)}")
            XLog.i(TAG, "runAgentLoop iter=$iterations hasToolCalls=${llmResponse.hasToolExecutionRequests()} toolCallCount=${llmResponse.toolExecutionRequests?.size ?: 0}")

            // 将 AI 消息添加到历史（需要构造 AiMessage）
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                if (llmResponse.text.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)

            // 非流式模式下推送思考内容
            if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
                callback.onContent(iterations, llmResponse.text)
            }

            // 如果没有工具调用
            if (!llmResponse.hasToolExecutionRequests()) {
                val responseText = llmResponse.text ?: ""
                // Only finish if LLM explicitly says done, or we've been going too long
                if (responseText.lowercase().contains("finish") || responseText.lowercase().contains("completed") || responseText.lowercase().contains("done") || iterations >= maxIterations - 1) {
                    callback.onComplete(iterations, responseText.ifEmpty { ClawApplication.instance.getString(R.string.agent_task_completed) }, totalTokens)
                    return
                }
                // LLM responded with text but no tool call — re-prompt to continue
                XLog.w(TAG, "runAgentLoop: no tool call in response, re-prompting LLM to continue")
                messages.add(UserMessage.from("Continue the task. Use a tool call to perform the next action. Do not just describe what to do — actually do it with a tool call."))
                continue
            }

            // 执行工具调用
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
                    return
                }

                val toolName = toolRequest.name() ?: ""
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val toolArgs = toolRequest.arguments() ?: "{}"
                callback.onToolCall(iterations, toolName, displayName, toolArgs)

                // 解析参数
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                var params: Map<String, Any>? = try {
                    GSON.fromJson(toolArgs, mapType)
                } catch (e: Exception) {
                    HashMap()
                }
                if (params == null) params = HashMap()

                val result = ToolRegistry.getInstance().executeTool(toolName, params)
                val paramsString = if (params.isEmpty()) "" else params.toString()
                callback.onToolResult(iterations, toolName, displayName, paramsString, result)

                // 检测到系统弹窗阻塞 → 截图通知用户并结束任务
                if (!result.isSuccess && result.error == GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) {
                    XLog.w(TAG, "System dialog blocked, notifying user and stopping task")
                    callback.onSystemDialogBlocked(iterations, totalTokens)
                    return
                }

                // finish 工具 → 任务完成
                if (toolName == "finish" && result.isSuccess) {
                    val finishData = result.data
                    callback.onComplete(iterations, finishData ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
                    return
                }

                // Opt-3: Auto-attach fresh screen state after action tools.
                // LLM sees updated UI in the same tool result → can decide next step
                // immediately without spending an extra 5 s inference round on get_screen_info.
                val combinedResultData: String = if (toolName in ACTION_TOOLS) {
                    try {
                        Thread.sleep(SCREEN_SETTLE_MS) // let UI animate/settle
                        val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                        val screenAfter = screenTool?.execute(emptyMap())
                        if (screenAfter != null && screenAfter.isSuccess && !screenAfter.data.isNullOrBlank()) {
                            // Update lastScreenHash for loop detection
                            lastScreenHash = screenAfter.data!!.hashCode()
                            XLog.i(TAG, "Opt3: auto-attached screen after $toolName (${screenAfter.data!!.length} chars)")
                            // Build enriched result JSON inline — ToolResult has private constructor
                            val baseData = result.data ?: ""
                            val enrichedData = "$baseData\n\nScreen after action:\n${screenAfter.data}"
                            val enriched = if (result.isSuccess) ToolResult.success(enrichedData)
                                           else ToolResult.error(result.error ?: "")
                            GSON.toJson(enriched)
                        } else {
                            XLog.w(TAG, "Opt3: get_screen_info failed after $toolName: ${screenAfter?.error}")
                            GSON.toJson(result)
                        }
                    } catch (e: Exception) {
                        XLog.w(TAG, "Opt3: exception fetching screen after $toolName", e)
                        GSON.toJson(result)
                    }
                } else {
                    // 记录指纹用于死循环检测（非 action tools 路径）
                    if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
                        lastScreenHash = result.data.hashCode()
                    }
                    GSON.toJson(result)
                }

                // For action tools the loop detection hash was already updated above;
                // for non-get_screen_info action tools also record the fingerprint.
                if (toolName in ACTION_TOOLS) {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
                } else if (toolName.isNotEmpty() && toolName != "get_screen_info") {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
                }

                // 添加工具结果到消息
                messages.add(ToolExecutionResultMessage.from(toolRequest, combinedResultData))
                XLog.d(TAG, "displayName:$displayName toolName:$toolName")
            }

            // 死循环检测
            if (isStuckInLoop(loopHistory)) {
                XLog.w(TAG, "Dead loop detected at iteration $iterations")
                messages.add(
                    UserMessage.from(
                        "[系统提示] 检测到你连续多轮执行了相同的操作且屏幕没有变化，你可能陷入了死循环。" +
                        "请尝试完全不同的方法：按 system_key(key=\"back\") 回退、滑动页面寻找目标、或重新打开 App。" +
                        "如果确实无法完成任务，请调用 finish 说明原因。"
                    )
                )
                loopHistory.clear()
            }
            XLog.d(TAG, "轮数:$iterations all=$totalTokens 本轮=${llmResponse.tokenUsage?.totalTokenCount()}")
        }

        if (cancelled.get()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
        } else {
            callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, maxIterations)), totalTokens)
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun shutdown() {
        cancel()
        executor?.shutdownNow()
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
                XLog.i(TAG, "LlmClient closed on shutdown")
            } catch (e: Exception) {
                XLog.w(TAG, "LlmClient close error on shutdown", e)
            }
        }
    }

    override fun isRunning(): Boolean = running.get()
}
