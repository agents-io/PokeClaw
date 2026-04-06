package io.agents.pokeclaw.agent

object AgentServiceFactory {

    @JvmStatic
    fun create(): AgentService = DefaultAgentService()
}
