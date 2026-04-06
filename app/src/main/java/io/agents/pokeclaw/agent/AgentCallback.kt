package io.agents.pokeclaw.agent

import io.agents.pokeclaw.tool.ToolResult

interface AgentCallback {
    /**
     * 新的一轮 Agent Loop 开始时的回调
     * @param round 当前轮数（从 1 开始）
     */
    fun onLoopStart(round: Int)
    fun onContent(round: Int, content: String)
    fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String)
    fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult)
    fun onComplete(round: Int, finalAnswer: String, totalTokens: Int)
    fun onError(round: Int, error: Exception, totalTokens: Int)
    fun onSystemDialogBlocked(round: Int, totalTokens: Int)
}
