// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import androidx.compose.runtime.snapshots.SnapshotStateList
import io.agents.pokeclaw.TaskEvent

enum class ExecutionEventSource {
    TASK,
    MONITOR,
    MODEL,
    SYSTEM,
}

enum class ExecutionEventKind {
    STATUS,
    PROGRESS,
    LOOP,
    TOOL_ACTION,
    TOOL_RESULT,
    COMPLETED,
    FAILED,
    CANCELLED,
    BLOCKED,
}

data class ExecutionEvent(
    val source: ExecutionEventSource,
    val kind: ExecutionEventKind,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Runtime-only execution event log.
 *
 * This is intentionally separate from chat markdown persistence. Slice 1 keeps
 * chatroom rendering unchanged while giving task/system/monitor activity its
 * own structured truth source.
 */
class ExecutionEventLog(
    private val events: SnapshotStateList<ExecutionEvent>,
    private val maxEntries: Int = 300,
) {

    fun snapshot(): List<ExecutionEvent> = events.toList()

    fun clear() {
        events.clear()
    }

    fun record(
        source: ExecutionEventSource,
        kind: ExecutionEventKind,
        message: String,
    ): ExecutionEvent {
        val normalized = message.trim()
        val event = ExecutionEvent(source = source, kind = kind, message = normalized)
        append(event)
        return event
    }

    fun recordTaskEvent(event: TaskEvent) {
        when (event) {
            is TaskEvent.Completed -> record(ExecutionEventSource.TASK, ExecutionEventKind.COMPLETED, event.answer)
            is TaskEvent.Failed -> record(ExecutionEventSource.TASK, ExecutionEventKind.FAILED, event.error)
            is TaskEvent.Cancelled -> record(ExecutionEventSource.TASK, ExecutionEventKind.CANCELLED, "Task cancelled")
            is TaskEvent.Blocked -> record(ExecutionEventSource.TASK, ExecutionEventKind.BLOCKED, "Blocked by system dialog")
            is TaskEvent.ToolAction -> record(ExecutionEventSource.TASK, ExecutionEventKind.TOOL_ACTION, event.toolName)
            is TaskEvent.ToolResult -> {
                val status = if (event.success) "success" else "failed"
                val detail = event.detail.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
                record(
                    ExecutionEventSource.TASK,
                    ExecutionEventKind.TOOL_RESULT,
                    "${event.toolName} $status$detail"
                )
            }
            is TaskEvent.Response -> record(ExecutionEventSource.TASK, ExecutionEventKind.STATUS, event.text)
            is TaskEvent.Progress -> record(ExecutionEventSource.TASK, ExecutionEventKind.PROGRESS, event.description)
            is TaskEvent.LoopStart -> record(ExecutionEventSource.TASK, ExecutionEventKind.LOOP, "Loop ${event.round}")
            is TaskEvent.TokenUpdate, is TaskEvent.Thinking -> Unit
        }
    }

    private fun append(event: ExecutionEvent) {
        val last = events.lastOrNull()
        if (last != null &&
            last.source == event.source &&
            last.kind == event.kind &&
            last.message.equals(event.message, ignoreCase = true)
        ) {
            return
        }
        events.add(event)
        val overflow = events.size - maxEntries
        if (overflow > 0) {
            repeat(overflow) { events.removeAt(0) }
        }
    }
}
