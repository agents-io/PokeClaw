// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import io.agents.pokeclaw.utils.XLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig

/**
 * Process-wide singleton that keeps a single LiteRT-LM Engine alive across
 * the chat UI and the task agent.
 *
 * Why: Engine initialisation on CPU backend takes 2-3 s. Without this,
 * ComposeChatActivity closes its engine before a task, TaskOrchestrator opens a
 * new one, then after the task chat reloads again — 4-6 s wasted per round trip.
 *
 * Thread safety: all mutations are @Synchronized so chat executor and task
 * executor threads can both call getOrCreate() safely.
 */
object EngineHolder {

    private const val TAG = "EngineHolder"

    private var engine: Engine? = null
    private var currentModelPath: String? = null
    private var currentBackendLabel: String? = null

    // ------------------------------------------------------------------
    // Session-handoff experiment (fix/experiment: serialize local model session handoff)
    // ------------------------------------------------------------------

    /**
     * FIX 1 — Conversation-slot mutex.
     *
     * LiteRT-LM allows exactly ONE live [com.google.ai.edge.litertlm.Conversation]
     * per [Engine]. The chat owner (ChatSessionController) and the task owner
     * (LocalLlmClient) each keep their own Conversation on this one shared engine.
     * Without serialisation, the chat executor and the task executor can both reach
     * `engine.createConversation()` at the same moment and one gets
     * `FAILED_PRECONDITION: A session already exists`.
     *
     * Hold this lock ONLY around session lifecycle — "close old Conversation" +
     * "open new Conversation". NEVER hold it during sendMessage / prefill / decode.
     */
    private val conversationSlotLock = Any()

    fun <T> withConversationSlot(block: () -> T): T =
        synchronized(conversationSlotLock) {
            XLog.d(TAG, "[SESSION] CONVERSATION_SLOT_ACQUIRE")
            try {
                block()
            } finally {
                XLog.d(TAG, "[SESSION] CONVERSATION_SLOT_RELEASE")
            }
        }

    /**
     * FIX 2 — "task active or starting" fence (starting-window half).
     *
     * A task launch can bring ComposeChatActivity to the foreground, whose
     * `onResume()` would otherwise (re)open a chat Conversation on the shared
     * engine right as the task opens its own. `isTaskRunning()` (TaskSessionStore)
     * only becomes true once the orchestrator has acquired the task lock — too late
     * to fence the initial `onResume`. This flag covers the launch -> acquire gap.
     *
     * Cleared by the orchestrator once `isTaskRunning()` takes over, and again at
     * task terminal. Auto-expires after [TASK_STARTING_GRACE_MS] so a launch that
     * never reaches startTask cannot wedge the chat side forever.
     */
    internal const val TASK_STARTING_GRACE_MS = 15_000L

    @Volatile
    private var taskStartingAtMs = 0L

    /** Pure, unit-testable expiry check. */
    internal fun startingFenceActive(startedAtMs: Long, nowMs: Long): Boolean {
        if (startedAtMs == 0L) return false
        val age = nowMs - startedAtMs
        return age in 0L until TASK_STARTING_GRACE_MS
    }

    fun markTaskStarting() {
        taskStartingAtMs = System.currentTimeMillis()
        XLog.i(TAG, "[SESSION] TASK_ACTIVE_OR_STARTING=true (starting)")
    }

    fun clearTaskStarting() {
        if (taskStartingAtMs != 0L) {
            taskStartingAtMs = 0L
            XLog.i(TAG, "[SESSION] TASK_ACTIVE_OR_STARTING=false (starting cleared)")
        }
    }

    fun isTaskStarting(): Boolean {
        val startedAt = taskStartingAtMs
        if (startedAt == 0L) return false
        if (startingFenceActive(startedAt, System.currentTimeMillis())) return true
        taskStartingAtMs = 0L
        XLog.w(TAG, "[SESSION] task-starting fence expired — auto-cleared")
        return false
    }

    private fun backendLabel(backend: Backend): String =
        if (backend is Backend.CPU) "CPU" else if (backend is Backend.GPU) "GPU" else backend.javaClass.simpleName

    /**
     * Return the existing Engine if the model path matches, otherwise close the
     * old one and create a fresh Engine for the new model.
     *
     * @param modelPath  absolute path to the .task model file
     * @param cacheDir   app's cacheDir.path — passed in so this object stays
     *                   context-free and easier to unit-test
     */
    @Synchronized
    @JvmOverloads
    fun getOrCreate(modelPath: String, cacheDir: String, backend: Backend = Backend.CPU()): Engine {
        val existing = engine
        val requestedBackendLabel = backendLabel(backend)
        if (existing != null && currentModelPath == modelPath && currentBackendLabel == requestedBackendLabel) {
            XLog.d(TAG, "getOrCreate: reusing engine for $modelPath (${currentBackendLabel ?: "unknown"})")
            return existing
        }

        // Different model or first call — close old engine first
        if (existing != null) {
            XLog.i(
                TAG,
                "getOrCreate: runtime changed (model=$currentModelPath/${currentBackendLabel ?: "?"} -> $modelPath/$requestedBackendLabel), closing old engine"
            )
            try {
                existing.close()
            } catch (e: Exception) {
                XLog.w(TAG, "getOrCreate: error closing old engine", e)
            }
            engine = null
            currentModelPath = null
        }

        XLog.i(TAG, "getOrCreate: creating new engine for $modelPath with $requestedBackendLabel")
        return try {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = 8192,
                cacheDir = cacheDir
            )
            if (backend is Backend.GPU) {
                LocalBackendHealth.markGpuInitStarted(modelPath)
            }
            val newEngine = Engine(engineConfig).also { it.initialize() }
            if (backend is Backend.GPU) {
                LocalBackendHealth.markGpuInitFinished()
                LocalBackendHealth.noteGpuInitSuccess(modelPath)
            }
            engine = newEngine
            currentModelPath = modelPath
            currentBackendLabel = requestedBackendLabel
            XLog.i(TAG, "getOrCreate: engine ready for $modelPath (${currentBackendLabel})")
            newEngine
        } catch (e: Exception) {
            if (backend is Backend.GPU) {
                LocalBackendHealth.noteRecoverableGpuFailure(modelPath, e)
            } else {
                LocalBackendHealth.markGpuInitFinished()
            }
            XLog.e(TAG, "getOrCreate: failed to create engine for $modelPath", e)
            throw e
        }
    }

    /**
     * Explicitly close and release the engine. Call only when the model is being
     * unloaded entirely (e.g. user deletes the model file). Normal chat/task
     * transitions should NOT call this — they just close their Conversation objects.
     */
    @Synchronized
    fun close() {
        XLog.i(TAG, "close: releasing engine for $currentModelPath")
        try {
            engine?.close()
        } catch (e: Exception) {
            XLog.w(TAG, "close: error closing engine", e)
        }
        engine = null
        currentModelPath = null
        currentBackendLabel = null
        XLog.i(TAG, "close: done")
    }

    /** Returns true if an engine is live for the given model path. */
    @Synchronized
    fun isReady(modelPath: String): Boolean = engine != null && currentModelPath == modelPath

    /** Returns the actual backend label of the current shared engine, if any. */
    @Synchronized
    fun getBackendLabel(modelPath: String? = null): String? {
        return if (modelPath == null || currentModelPath == modelPath) currentBackendLabel else null
    }
}
