// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.utils.XLog
import java.util.ArrayDeque

/**
 * Detects stuck agent loops using 5 signals.
 *
 * Architecture reference:
 * - DroidClaw: sliding window (8 steps), same-action, screen-unchanged, high-repetition
 * - ralph-claude-code: repeated-error detection
 * - PokeClaw original: screen diff (previousScreenTexts)
 *
 * Recovery is 3-level:
 *   Level 1: Inject recovery hint into prompt
 *   Level 2: Suggest strategy switch (different tool)
 *   Level 3: Auto-kill (force finish)
 */
class StuckDetector(private val windowSize: Int = 8) {

    private val actions = ArrayDeque<String>(windowSize + 1)
    private val screenHashes = ArrayDeque<Int>(windowSize + 1)
    private val screenDiffCounts = ArrayDeque<Int>(windowSize + 1)
    private val errors = ArrayDeque<String>(windowSize + 1)
    private var consecutiveStuckSteps = 0

    sealed class Signal(val description: String) {
        class SameAction(val action: String, val count: Int) :
            Signal("Same action '$action' repeated $count times consecutively")

        class ScreenUnchanged(val steps: Int) :
            Signal("Screen unchanged for $steps consecutive steps")

        class ZeroDiff(val steps: Int) :
            Signal("Zero screen text diff for $steps consecutive steps")

        class HighRepetition(val action: String, val count: Int, val window: Int) :
            Signal("Action '$action' appeared $count times in last $window steps")

        class RepeatedError(val error: String, val count: Int) :
            Signal("Same error repeated $count times consecutively")
    }

    enum class RecoveryLevel {
        HINT,           // Level 1: inject recovery hint
        STRATEGY_SWITCH, // Level 2: suggest different approach
        AUTO_KILL        // Level 3: force finish
    }

    data class Detection(
        val signal: Signal,
        val level: RecoveryLevel,
        val recoveryHint: String
    )

    /**
     * Record one agent loop step and check for stuck patterns.
     *
     * @param action tool name + args fingerprint (e.g. "find_and_tap:cat videos")
     * @param screenHash hash of current screen content
     * @param screenDiffCount number of text lines changed vs previous screen
     * @param error error message if tool failed, null otherwise
     * @return Detection if stuck, null if OK
     */
    fun record(action: String, screenHash: Int, screenDiffCount: Int, error: String?): Detection? {
        // Add to sliding windows
        actions.addLast(action)
        if (actions.size > windowSize) actions.removeFirst()

        screenHashes.addLast(screenHash)
        if (screenHashes.size > windowSize) screenHashes.removeFirst()

        screenDiffCounts.addLast(screenDiffCount)
        if (screenDiffCounts.size > windowSize) screenDiffCounts.removeFirst()

        if (error != null) {
            errors.addLast(error)
            if (errors.size > windowSize) errors.removeFirst()
        } else {
            errors.clear() // consecutive errors broken
        }

        // Check all 5 signals
        val signal = checkSameAction()
            ?: checkScreenUnchanged()
            ?: checkZeroDiff()
            ?: checkHighRepetition()
            ?: checkRepeatedError()

        if (signal != null) {
            consecutiveStuckSteps++
            val level = when {
                consecutiveStuckSteps >= 5 -> RecoveryLevel.AUTO_KILL
                consecutiveStuckSteps >= 3 -> RecoveryLevel.STRATEGY_SWITCH
                else -> RecoveryLevel.HINT
            }
            val hint = generateRecoveryHint(signal, level)
            val detection = Detection(signal, level, hint)
            XLog.w(TAG, "[StuckDetector] ${signal.description} → Level ${level.name}")
            return detection
        }

        // No stuck signal → reset counter
        consecutiveStuckSteps = 0
        return null
    }

    private fun checkSameAction(): Signal? {
        if (actions.size < 3) return null
        val last3 = actions.toList().takeLast(3)
        return if (last3.all { it == last3[0] }) {
            Signal.SameAction(last3[0].take(50), 3)
        } else null
    }

    private fun checkScreenUnchanged(): Signal? {
        if (screenHashes.size < 3) return null
        val last3 = screenHashes.toList().takeLast(3)
        return if (last3.all { it == last3[0] }) {
            Signal.ScreenUnchanged(3)
        } else null
    }

    private fun checkZeroDiff(): Signal? {
        if (screenDiffCounts.size < 3) return null
        val last3 = screenDiffCounts.toList().takeLast(3)
        return if (last3.all { it == 0 }) {
            Signal.ZeroDiff(3)
        } else null
    }

    private fun checkHighRepetition(): Signal? {
        if (actions.size < windowSize) return null
        val counts = actions.groupingBy { it }.eachCount()
        val maxEntry = counts.maxByOrNull { it.value } ?: return null
        return if (maxEntry.value >= 3) {
            Signal.HighRepetition(maxEntry.key.take(50), maxEntry.value, windowSize)
        } else null
    }

    private fun checkRepeatedError(): Signal? {
        if (errors.size < 3) return null
        val last3 = errors.toList().takeLast(3)
        return if (last3.all { it == last3[0] }) {
            Signal.RepeatedError(last3[0].take(80), 3)
        } else null
    }

    private fun generateRecoveryHint(signal: Signal, level: RecoveryLevel): String {
        val base = when (signal) {
            is Signal.SameAction -> when {
                signal.action.contains("find_and_tap") ->
                    "Your find_and_tap action is not working. Try using tap_node with a specific node ID from get_screen_info, or use system_key(key=\"enter\") to submit."
                signal.action.contains("scroll") ->
                    "You may have reached the end of scrollable content. Try a different approach or press back."
                signal.action.contains("tap") ->
                    "Your tap action may not be hitting the right target. Call get_screen_info to refresh the screen state and try a different element."
                else ->
                    "Your last action '${signal.action}' is not producing results. Try a completely different approach."
            }
            is Signal.ScreenUnchanged ->
                "The screen has not changed for ${signal.steps} steps. Your actions may not be having any effect. Try pressing system_key(key=\"back\") or system_key(key=\"home\") and restart from a different angle."
            is Signal.ZeroDiff ->
                "No new content has appeared on screen. You may be stuck. Try navigating away and back, or use a different tool."
            is Signal.HighRepetition ->
                "You are repeating '${signal.action}' too frequently. This approach is not working. Try something fundamentally different."
            is Signal.RepeatedError ->
                "The same error keeps occurring: '${signal.error}'. Do not retry the same approach. Try a different tool or strategy."
        }

        return when (level) {
            RecoveryLevel.HINT ->
                "[System Notice] $base"
            RecoveryLevel.STRATEGY_SWITCH ->
                "[System Warning] You have been stuck for multiple rounds. $base If you cannot make progress, call finish and explain what went wrong."
            RecoveryLevel.AUTO_KILL ->
                "" // caller handles auto-kill
        }
    }

    fun reset() {
        actions.clear()
        screenHashes.clear()
        screenDiffCounts.clear()
        errors.clear()
        consecutiveStuckSteps = 0
    }

    companion object {
        private const val TAG = "StuckDetector"
    }
}
