// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

data class LocalEngineLease(
    val engine: Engine,
    val backendLabel: String,
)

data class LocalConversationLease(
    val engine: Engine,
    val conversation: Conversation,
    val backendLabel: String,
)

data class LocalSingleShotResult(
    val text: String?,
    val backendLabel: String,
)

object LocalModelRuntime {

    private const val TAG = "LocalModelRuntime"
    private const val DEFAULT_RETRY_COUNT = 5
    private const val DEFAULT_RESET_ATTEMPT = 3
    private const val DEFAULT_RETRY_SLEEP_MS = 1500L

    fun acquireSharedEngine(
        context: Context,
        modelPath: String,
        preferCpu: Boolean = false,
    ): LocalEngineLease {
        val shouldUseCpu = LocalBackendHealth.shouldForceCpu(preferCpu)
        if (shouldUseCpu) {
            val engine = EngineHolder.getOrCreate(modelPath, context.cacheDir.path, Backend.CPU())
            return LocalEngineLease(engine = engine, backendLabel = "CPU")
        }

        return try {
            val engine = EngineHolder.getOrCreate(modelPath, context.cacheDir.path, Backend.GPU())
            LocalEngineLease(engine = engine, backendLabel = EngineHolder.getBackendLabel(modelPath) ?: "GPU")
        } catch (e: Exception) {
            if (!isGpuBackendFailure(e)) throw e
            XLog.w(TAG, "GPU runtime failed for $modelPath, retrying on CPU: ${e.message}")
            LocalBackendHealth.noteRecoverableGpuFailure(modelPath, e)
            forceCpuEngine(context, modelPath)
        }
    }

    fun forceCpuEngine(context: Context, modelPath: String): LocalEngineLease {
        resetSharedEngine()
        val engine = EngineHolder.getOrCreate(modelPath, context.cacheDir.path, Backend.CPU())
        return LocalEngineLease(engine = engine, backendLabel = "CPU")
    }

    fun resetSharedEngine() {
        try {
            EngineHolder.close()
        } catch (e: Exception) {
            XLog.w(TAG, "resetSharedEngine: close failed", e)
        }
    }

    fun currentBackendLabel(modelPath: String?): String? {
        return EngineHolder.getBackendLabel(modelPath)
    }

    fun openConversation(
        context: Context,
        modelPath: String,
        conversationConfig: ConversationConfig,
        preferCpu: Boolean = false,
        maxRetries: Int = DEFAULT_RETRY_COUNT,
    ): LocalConversationLease {
        var lastError: Exception? = null
        var forceCpu = preferCpu

        for (attempt in 1..maxRetries) {
            try {
                val engineLease = acquireSharedEngine(context, modelPath, preferCpu = forceCpu)
                val conversation = engineLease.engine.createConversation(conversationConfig)
                return LocalConversationLease(
                    engine = engineLease.engine,
                    conversation = conversation,
                    backendLabel = engineLease.backendLabel,
                )
            } catch (e: Exception) {
                lastError = e
                XLog.w(TAG, "openConversation attempt $attempt failed for $modelPath: ${e.message}")

                if (isSessionConflict(e)) {
                    throw IllegalStateException("Local model session already in use", e)
                }

                if (!forceCpu && isGpuBackendFailure(e)) {
                    XLog.w(TAG, "openConversation: GPU path failed, forcing CPU for $modelPath")
                    LocalBackendHealth.noteRecoverableGpuFailure(modelPath, e)
                    forceCpuEngine(context, modelPath)
                    forceCpu = true
                } else if (attempt == DEFAULT_RESET_ATTEMPT) {
                    XLog.w(TAG, "openConversation: resetting shared runtime for $modelPath")
                    try {
                        resetSharedEngine()
                    } catch (resetError: Exception) {
                        XLog.e(TAG, "openConversation: shared runtime reset failed", resetError)
                    }
                }

                if (attempt < maxRetries) {
                    Thread.sleep(DEFAULT_RETRY_SLEEP_MS)
                }
            }
        }

        throw RuntimeException(
            "Failed to create conversation after $maxRetries retries: ${lastError?.message}",
            lastError
        )
    }

    fun runSingleShot(
        context: Context,
        modelPath: String,
        systemPrompt: String,
        prompt: String,
        temperature: Double = 0.3,
        preferCpu: Boolean = false,
    ): LocalSingleShotResult {
        val lease = openConversation(
            context = context,
            modelPath = modelPath,
            conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                samplerConfig = SamplerConfig(
                    topK = 64,
                    topP = 0.95,
                    temperature = temperature,
                )
            ),
            preferCpu = preferCpu,
        )

        return try {
            val response = lease.conversation.sendMessage(prompt, emptyMap())
            if (lease.backendLabel == "GPU") {
                LocalBackendHealth.noteGpuInferenceSuccess(modelPath)
            }
            LocalSingleShotResult(
                text = response.contents?.toString()?.trim(),
                backendLabel = lease.backendLabel,
            )
        } finally {
            try {
                lease.conversation.close()
            } catch (e: Exception) {
                XLog.w(TAG, "runSingleShot: conversation close failed", e)
            }
        }
    }

    fun isGpuBackendFailure(error: Throwable?): Boolean {
        val message = error?.message.orEmpty()
        if (message.isEmpty()) return false
        return message.contains("OpenCL", ignoreCase = true) ||
            message.contains("GPU", ignoreCase = true) ||
            message.contains("nativeSendMessage", ignoreCase = true) ||
            message.contains("Failed to create engine", ignoreCase = true) ||
            message.contains("compiled model", ignoreCase = true)
    }

    fun isSessionConflict(error: Throwable?): Boolean {
        val message = error?.message.orEmpty()
        if (message.isEmpty()) return false
        return message.contains("A session already exists", ignoreCase = true) ||
            message.contains("Only one session is supported at a time", ignoreCase = true) ||
            message.contains("session already in use", ignoreCase = true)
    }
}
