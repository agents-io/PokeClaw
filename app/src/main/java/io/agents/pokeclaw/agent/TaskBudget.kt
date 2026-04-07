// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

/**
 * Per-task token budget with soft and hard limits.
 *
 * Architecture reference:
 * - AgentBudget: soft 90% warning + hard 100% kill
 * - Adapted for PokeClaw: configurable via KVUtils settings
 */
class TaskBudget(
    val maxTokens: Int,
    val maxCostUsd: Double,
    private val softLimitPercent: Float = 0.8f
) {

    enum class Status {
        OK,
        SOFT_LIMIT,     // 80% — inject warning prompt
        HARD_LIMIT      // 100% — force finish
    }

    /**
     * Check current token/cost against budget.
     */
    fun check(currentTokens: Int, currentCostUsd: Double): Status {
        // Hard limit check (either tokens or cost)
        if (currentTokens >= maxTokens) {
            XLog.w(TAG, "HARD LIMIT: tokens $currentTokens >= max $maxTokens")
            return Status.HARD_LIMIT
        }
        if (maxCostUsd > 0 && currentCostUsd >= maxCostUsd) {
            XLog.w(TAG, "HARD LIMIT: cost $$currentCostUsd >= max $$maxCostUsd")
            return Status.HARD_LIMIT
        }

        // Soft limit check
        val tokenPercent = currentTokens.toFloat() / maxTokens
        if (tokenPercent >= softLimitPercent) {
            XLog.i(TAG, "SOFT LIMIT: tokens at ${(tokenPercent * 100).toInt()}% of budget")
            return Status.SOFT_LIMIT
        }
        if (maxCostUsd > 0) {
            val costPercent = currentCostUsd / maxCostUsd
            if (costPercent >= softLimitPercent) {
                XLog.i(TAG, "SOFT LIMIT: cost at ${(costPercent * 100).toInt()}% of budget")
                return Status.SOFT_LIMIT
            }
        }

        return Status.OK
    }

    companion object {
        private const val TAG = "TaskBudget"

        private const val KEY_MAX_TOKENS = "KEY_TASK_MAX_TOKENS"
        private const val KEY_MAX_COST = "KEY_TASK_MAX_COST_USD"

        const val DEFAULT_MAX_TOKENS = 100_000
        const val DEFAULT_MAX_COST_USD = 0.50

        /**
         * Create a TaskBudget from user settings.
         */
        fun fromSettings(): TaskBudget {
            val maxTokens = KVUtils.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
            val maxCost = KVUtils.getDouble(KEY_MAX_COST, DEFAULT_MAX_COST_USD)
            return TaskBudget(maxTokens, maxCost)
        }

        fun saveMaxTokens(value: Int) = KVUtils.putInt(KEY_MAX_TOKENS, value)
        fun saveMaxCost(value: Double) = KVUtils.putDouble(KEY_MAX_COST, value)
        fun getMaxTokens(): Int = KVUtils.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
        fun getMaxCost(): Double = KVUtils.getDouble(KEY_MAX_COST, DEFAULT_MAX_COST_USD)
    }
}
