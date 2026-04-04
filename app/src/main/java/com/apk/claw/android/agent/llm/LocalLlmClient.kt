package com.apk.claw.android.agent.llm

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.utils.XLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import com.google.gson.Gson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * LlmClient implementation using Google LiteRT-LM SDK for on-device inference.
 *
 * Bridges the stateless LangChain4j chat interface (full message list per call)
 * to LiteRT-LM's stateful Conversation API (incremental messages).
 *
 * config.baseUrl is repurposed to hold the local model file path.
 */
class LocalLlmClient(private val config: AgentConfig) : LlmClient {

    private val GSON = Gson()

    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var processedMessageCount = 0

    private fun ensureEngine() {
        if (engine != null) return

        val modelPath = config.baseUrl // repurposed: holds model file path
        XLog.i(TAG, "Initializing LiteRT-LM engine with model: $modelPath")

        val context = ClawApplication.instance
        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = Backend.GPU(),
            maxNumTokens = 4096,
            cacheDir = context.cacheDir.path
        )
        engine = Engine(engineConfig).also { it.initialize() }
        XLog.i(TAG, "LiteRT-LM engine initialized")
    }

    /**
     * Create a new conversation with system prompt and tool declarations.
     */
    private fun createConversation(systemPrompt: String, toolSpecs: List<ToolSpecification>) {
        val toolWrappers = toolSpecs.map { spec -> DynamicOpenApiTool(spec) }

        conversation = engine!!.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                samplerConfig = SamplerConfig(
                    topK = 64,
                    topP = 0.95,
                    temperature = config.temperature
                ),
                tools = toolWrappers.map { tool(it) },
                automaticToolCalling = false
            )
        )
        processedMessageCount = 0
    }

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        ensureEngine()

        // Detect new task: messages start with SystemMessage + UserMessage
        if (processedMessageCount == 0 || messages.size < processedMessageCount) {
            val systemPrompt = (messages.firstOrNull { it is SystemMessage } as? SystemMessage)?.text() ?: ""
            createConversation(systemPrompt, toolSpecs)
        }

        // Find new messages to send
        val newMessages = messages.subList(
            processedMessageCount.coerceAtMost(messages.size),
            messages.size
        )

        var lastResponse: Any? = null

        for (msg in newMessages) {
            when (msg) {
                is SystemMessage -> {
                    // Already handled in createConversation
                }
                is UserMessage -> {
                    lastResponse = conversation!!.sendMessage(msg.singleText())
                }
                is AiMessage -> {
                    // AI's own response — already in conversation state, skip
                }
                is ToolExecutionResultMessage -> {
                    // Send tool result back to conversation
                    val toolResultText = "[Tool ${msg.toolName()} result]: ${msg.text()}"
                    lastResponse = conversation!!.sendMessage(toolResultText)
                }
            }
        }

        processedMessageCount = messages.size

        // Parse the response
        return parseResponse(lastResponse)
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener
    ): LlmResponse {
        // For now, delegate to blocking chat and simulate streaming
        // LiteRT-LM streaming requires Flow or MessageCallback which needs more integration
        val response = chat(messages, toolSpecs)
        if (!response.text.isNullOrEmpty()) {
            listener.onPartialText(response.text)
        }
        listener.onComplete(response)
        return response
    }

    /**
     * Parse LiteRT-LM response into LlmResponse.
     *
     * The response text may contain tool calls in Gemma's function calling format:
     * <tool_call>{"name": "tap", "arguments": {"x": 100, "y": 200}}</tool_call>
     *
     * Or it may be plain text (thinking + final answer).
     */
    private fun parseResponse(response: Any?): LlmResponse {
        val responseText = response?.toString() ?: ""

        // Try to extract structured tool calls from response
        val toolCalls = extractToolCalls(responseText)

        if (toolCalls.isNotEmpty()) {
            // Remove tool call markup from text for the thinking portion
            val thinkingText = responseText
                .replace(TOOL_CALL_PATTERN, "")
                .trim()
                .ifEmpty { null }

            return LlmResponse(
                text = thinkingText,
                toolExecutionRequests = toolCalls
            )
        }

        return LlmResponse(
            text = responseText,
            toolExecutionRequests = emptyList()
        )
    }

    /**
     * Extract tool calls from model output.
     *
     * Gemma 4 uses special tokens for function calling. The format may be:
     * - <tool_call>{"name":"tap","arguments":{"x":100,"y":200}}</tool_call>
     * - ```tool_call\n{"name":"tap","arguments":{"x":100,"y":200}}\n```
     * - Or JSON objects with "name" and "arguments" fields
     *
     * This parser tries multiple patterns.
     */
    private fun extractToolCalls(text: String): List<ToolExecutionRequest> {
        val calls = mutableListOf<ToolExecutionRequest>()

        // Pattern 1: <tool_call>...</tool_call> tags
        TOOL_CALL_PATTERN.findAll(text).forEach { match ->
            parseToolCallJson(match.groupValues[1])?.let { calls.add(it) }
        }

        if (calls.isNotEmpty()) return calls

        // Pattern 2: ```tool_call\n...\n``` blocks
        TOOL_CALL_BLOCK_PATTERN.findAll(text).forEach { match ->
            parseToolCallJson(match.groupValues[1])?.let { calls.add(it) }
        }

        if (calls.isNotEmpty()) return calls

        // Pattern 3: Gemma function calling format
        // functioncall: {"name": "tap", "args": {"x": 100, "y": 200}}
        FUNCTION_CALL_PATTERN.findAll(text).forEach { match ->
            parseToolCallJson(match.groupValues[1], argsKey = "args")?.let { calls.add(it) }
        }

        return calls
    }

    private fun parseToolCallJson(json: String, argsKey: String = "arguments"): ToolExecutionRequest? {
        return try {
            val map = GSON.fromJson(json.trim(), Map::class.java) as Map<*, *>
            val name = map["name"]?.toString() ?: return null
            val args = map[argsKey]
            val argsJson = if (args is Map<*, *>) GSON.toJson(args) else args?.toString() ?: "{}"

            ToolExecutionRequest.builder()
                .id("local_${System.currentTimeMillis()}")
                .name(name)
                .arguments(argsJson)
                .build()
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to parse tool call JSON: $json", e)
            null
        }
    }

    fun close() {
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
        processedMessageCount = 0
    }

    companion object {
        private const val TAG = "LocalLlmClient"
        private val TOOL_CALL_PATTERN = Regex("""<tool_call>(.*?)</tool_call>""", RegexOption.DOT_MATCHES_ALL)
        private val TOOL_CALL_BLOCK_PATTERN = Regex("""```tool_call\s*\n(.*?)\n\s*```""", RegexOption.DOT_MATCHES_ALL)
        private val FUNCTION_CALL_PATTERN = Regex("""(?:functioncall|function_call|tool_call)\s*:\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)
    }
}

/**
 * Wraps a LangChain4j ToolSpecification as a LiteRT-LM OpenApiTool.
 * Only declares the schema — execution is handled by the agent loop.
 */
private class DynamicOpenApiTool(private val spec: ToolSpecification) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String {
        val json = buildMap {
            put("name", spec.name())
            put("description", spec.description() ?: "")
            spec.parameters()?.let { params ->
                put("parameters", buildMap {
                    put("type", "object")
                    val properties = mutableMapOf<String, Any>()
                    val required = mutableListOf<String>()

                    // Extract properties from JsonObjectSchema
                    params.properties()?.forEach { (name, schema) ->
                        val prop = mutableMapOf<String, Any>()
                        prop["description"] = schema.description() ?: ""
                        prop["type"] = when (schema.javaClass.simpleName) {
                            "JsonIntegerSchema" -> "integer"
                            "JsonNumberSchema" -> "number"
                            "JsonBooleanSchema" -> "boolean"
                            else -> "string"
                        }
                        properties[name] = prop
                    }
                    put("properties", properties)

                    params.required()?.let { put("required", it) }
                })
            }
        }
        return Gson().toJson(json)
    }

    override fun execute(paramsJsonString: String): String {
        // Not called with automaticToolCalling = false
        return """{"result": "ok"}"""
    }
}
