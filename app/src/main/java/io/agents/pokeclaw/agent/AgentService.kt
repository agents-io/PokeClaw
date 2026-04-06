package io.agents.pokeclaw.agent

interface AgentService {
    fun initialize(config: AgentConfig)
    fun updateConfig(config: AgentConfig)
    fun executeTask(userPrompt: String, callback: AgentCallback)
    fun cancel()
    fun shutdown()
    fun isRunning(): Boolean
}
