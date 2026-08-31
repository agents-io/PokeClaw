// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import io.agents.pokeclaw.agent.AgentConfig
import io.agents.pokeclaw.agent.CloudProvider
import io.agents.pokeclaw.agent.LlmProvider
import io.agents.pokeclaw.utils.KVUtils
import java.io.File

enum class ActiveModelMode { LOCAL, CLOUD }

data class LocalModelConfig(
    val modelPath: String,
    val modelId: String,
    val displayName: String,
    val backendPreference: String
) {
    val isConfigured: Boolean get() = modelPath.isNotBlank()
}

data class CloudModelConfig(
    val providerName: String,
    val modelName: String,
    val baseUrl: String,
    val apiKey: String
) {
    val provider: CloudProvider get() = CloudProvider.fromName(providerName)
    val resolvedBaseUrl: String
        get() = baseUrl.ifBlank {
            if (provider == CloudProvider.CUSTOM) "" else provider.defaultBaseUrl
        }
    val isConfigured: Boolean get() = modelName.isNotBlank() && apiKey.isNotBlank()
    val agentProvider: LlmProvider
        get() = when (provider) {
            CloudProvider.ANTHROPIC -> LlmProvider.ANTHROPIC
            else -> LlmProvider.OPENAI
        }
}

data class ResolvedModelConfig(
    val activeMode: ActiveModelMode,
    val local: LocalModelConfig,
    val activeCloud: CloudModelConfig,
    val defaultCloud: CloudModelConfig
) {
    fun isLocalActive(): Boolean = activeMode == ActiveModelMode.LOCAL

    fun toAgentConfig(
        temperature: Double,
        maxIterations: Int,
        streaming: Boolean = false
    ): AgentConfig {
        // Inject persistent global instructions (#45) into systemPrompt.
        // This is the runtime construction path used by AppViewModel/AgentService,
        // bypassing AgentConfig.Builder.build(), so we must apply the helper here too.
        val finalSystemPrompt = io.agents.pokeclaw.agent.PromptUtils
            .applyGlobalPrompt(AgentConfig.DEFAULT_SYSTEM_PROMPT)
        return if (activeMode == ActiveModelMode.LOCAL) {
            AgentConfig(
                apiKey = "",
                baseUrl = local.modelPath,
                modelName = local.modelId,
                systemPrompt = finalSystemPrompt,
                maxIterations = maxIterations,
                temperature = temperature,
                provider = LlmProvider.LOCAL,
                streaming = streaming
            )
        } else {
            AgentConfig(
                apiKey = activeCloud.apiKey,
                baseUrl = activeCloud.resolvedBaseUrl,
                modelName = activeCloud.modelName,
                systemPrompt = finalSystemPrompt,
                maxIterations = maxIterations,
                temperature = temperature,
                provider = activeCloud.agentProvider,
                streaming = streaming
            )
        }
    }
}

/**
 * Resolves active/default local and cloud model config from KVUtils without changing
 * the persisted key format. This is the single source of truth for model selection.
 */
object ModelConfigRepository {

    fun snapshot(): ResolvedModelConfig {
        val activeProviderRaw = KVUtils.getLlmProvider().ifBlank { "OPENAI" }.uppercase()
        val activeMode = if (activeProviderRaw == "LOCAL") ActiveModelMode.LOCAL else ActiveModelMode.CLOUD

        val localModelPath = KVUtils.getLocalModelPath()
        val matchedLocalModel = LocalModelManager.AVAILABLE_MODELS.find { localModelPath.endsWith(it.fileName) }
        val localModelId = matchedLocalModel?.id
            ?: if (activeMode == ActiveModelMode.LOCAL) KVUtils.getLlmModelName() else ""
        val localDisplayName = matchedLocalModel?.displayName
            ?: localModelPath.takeIf { it.isNotBlank() }?.let { File(it).nameWithoutExtension }
            ?: localModelId

        val local = LocalModelConfig(
            modelPath = localModelPath,
            modelId = localModelId,
            displayName = localDisplayName,
            backendPreference = KVUtils.getLocalBackendPreference()
        )

        val defaultProvider = normalizeCloudProvider(
            KVUtils.getDefaultCloudProvider().ifBlank {
                if (activeMode == ActiveModelMode.CLOUD) activeProviderRaw else "OPENAI"
            }
        )
        val defaultModel = KVUtils.getDefaultCloudModel().ifBlank {
            if (activeMode == ActiveModelMode.CLOUD && activeProviderRaw == defaultProvider) {
                KVUtils.getLlmModelName()
            } else {
                ""
            }
        }
        val defaultBaseUrl = KVUtils.getDefaultCloudBaseUrl().ifBlank {
            if (activeMode == ActiveModelMode.CLOUD && activeProviderRaw == defaultProvider) {
                KVUtils.getLlmBaseUrl()
            } else {
                ""
            }
        }
        val defaultCloud = buildCloudConfig(defaultProvider, defaultModel, defaultBaseUrl)

        val activeCloudProvider = if (activeMode == ActiveModelMode.CLOUD) {
            normalizeCloudProvider(activeProviderRaw)
        } else {
            defaultCloud.providerName
        }
        val sameProviderFallback = activeCloudProvider == defaultCloud.providerName
        val activeCloudModel = if (activeMode == ActiveModelMode.CLOUD) {
            KVUtils.getLlmModelName().ifBlank { if (sameProviderFallback) defaultCloud.modelName else "" }
        } else {
            defaultCloud.modelName
        }
        val activeCloudBaseUrl = if (activeMode == ActiveModelMode.CLOUD) {
            KVUtils.getLlmBaseUrl().ifBlank { if (sameProviderFallback) defaultCloud.baseUrl else "" }
        } else {
            defaultCloud.baseUrl
        }
        val activeCloud = buildCloudConfig(activeCloudProvider, activeCloudModel, activeCloudBaseUrl)

        return ResolvedModelConfig(
            activeMode = activeMode,
            local = local,
            activeCloud = activeCloud,
            defaultCloud = defaultCloud
        )
    }

    fun isLocalActive(): Boolean = snapshot().isLocalActive()

    fun saveLocalDefault(modelPath: String, modelId: String, activateNow: Boolean) {
        KVUtils.setLocalModelPath(modelPath)
        if (activateNow) {
            activateLocal(modelPath, modelId)
        }
    }

    fun activateLocal(modelPath: String, modelId: String) {
        KVUtils.setLocalModelPath(modelPath)
        KVUtils.setLlmProvider("LOCAL")
        KVUtils.setLlmModelName(modelId)
    }

    fun saveCloudDefault(
        providerName: String,
        modelId: String,
        baseUrl: String,
        apiKey: String,
        activateNow: Boolean
    ) {
        val normalizedProvider = normalizeCloudProvider(providerName)
        val resolvedBaseUrl = resolveCloudBaseUrl(normalizedProvider, baseUrl)
        KVUtils.setDefaultCloudModel(modelId)
        KVUtils.setDefaultCloudProvider(normalizedProvider)
        KVUtils.setDefaultCloudBaseUrl(resolvedBaseUrl)
        KVUtils.setLlmApiKey(apiKey)
        KVUtils.setApiKeyForProvider(normalizedProvider, apiKey)
        if (activateNow) {
            activateCloudSelection(
                modelId = modelId,
                explicitProviderName = normalizedProvider,
                explicitBaseUrl = resolvedBaseUrl
            )
        }
    }

    fun activateCloudSelection(
        modelId: String,
        explicitProviderName: String? = null,
        explicitBaseUrl: String? = null
    ) {
        val snapshot = snapshot()
        val inferredProvider = explicitProviderName
            ?.takeIf { it.isNotBlank() }
            ?: CloudProvider.findProviderForModel(modelId)?.name
            ?: snapshot.defaultCloud.providerName
        val normalizedProvider = normalizeCloudProvider(inferredProvider)
        val resolvedBaseUrl = resolveCloudBaseUrl(
            normalizedProvider,
            explicitBaseUrl
                ?: if (snapshot.defaultCloud.providerName == normalizedProvider) snapshot.defaultCloud.baseUrl else ""
        )

        KVUtils.setDefaultCloudModel(modelId)
        KVUtils.setDefaultCloudProvider(normalizedProvider)
        KVUtils.setDefaultCloudBaseUrl(resolvedBaseUrl)
        KVUtils.setLlmProvider(normalizedProvider)
        KVUtils.setLlmModelName(modelId)
        KVUtils.setLlmBaseUrl(resolvedBaseUrl)
    }

    private fun buildCloudConfig(
        providerName: String,
        modelName: String,
        baseUrl: String
    ): CloudModelConfig {
        val normalizedProvider = normalizeCloudProvider(providerName)
        val apiKey = KVUtils.getApiKeyForProvider(normalizedProvider)
            .ifEmpty { KVUtils.getLlmApiKey() }
        return CloudModelConfig(
            providerName = normalizedProvider,
            modelName = modelName,
            baseUrl = resolveCloudBaseUrl(normalizedProvider, baseUrl),
            apiKey = apiKey
        )
    }

    private fun normalizeCloudProvider(providerName: String): String {
        val normalized = providerName.ifBlank { "OPENAI" }.uppercase()
        return if (normalized == "LOCAL") "OPENAI" else normalized
    }

    private fun resolveCloudBaseUrl(providerName: String, baseUrl: String): String {
        if (baseUrl.isNotBlank()) return baseUrl.trim()
        val provider = CloudProvider.fromName(providerName)
        return if (provider == CloudProvider.CUSTOM) "" else provider.defaultBaseUrl
    }
}
