// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

/**
 * Model pricing table and cost estimation.
 *
 * Prices are in USD per 1 million tokens.
 * Source: official provider pricing pages as of 2026-04.
 */
object ModelPricing {

    data class Price(
        val inputPerMillion: Double,
        val outputPerMillion: Double
    )

    private val PRICES = mapOf(
        // OpenAI
        "gpt-4o" to Price(2.50, 10.00),
        "gpt-4o-mini" to Price(0.15, 0.60),
        "gpt-4.1" to Price(2.00, 8.00),
        "gpt-4.1-mini" to Price(0.40, 1.60),
        "gpt-4.1-nano" to Price(0.10, 0.40),
        "gpt-4-turbo" to Price(10.00, 30.00),
        "gpt-3.5-turbo" to Price(0.50, 1.50),
        "o4-mini" to Price(1.10, 4.40),

        // Anthropic
        "claude-opus-4-6" to Price(15.00, 75.00),
        "claude-sonnet-4-6" to Price(3.00, 15.00),
        "claude-haiku-4-5" to Price(0.80, 4.00),

        // Google
        "gemini-2.5-flash" to Price(0.15, 0.60),
        "gemini-2.5-pro" to Price(1.25, 10.00),
        "gemini-2.0-flash" to Price(0.10, 0.40),

        // Open-source via OpenRouter/Groq
        "llama-3.3-70b-versatile" to Price(0.59, 0.79),
        "llama-4-maverick" to Price(0.50, 0.70),
        "deepseek-chat" to Price(0.27, 1.10),
        "deepseek-reasoner" to Price(0.55, 2.19),
        "qwen-2.5-72b" to Price(0.29, 0.39),
    )

    /**
     * Estimate cost in USD for a given model and token counts.
     * Returns 0.0 if model is not found (e.g. local models).
     */
    fun estimateCost(model: String, inputTokens: Int, outputTokens: Int): Double {
        val price = findPrice(model) ?: return 0.0
        return (inputTokens * price.inputPerMillion / 1_000_000.0) +
               (outputTokens * price.outputPerMillion / 1_000_000.0)
    }

    /**
     * Get the price entry for a model, with fuzzy matching for dated variants.
     * e.g. "gpt-4o-2025-03-01" → strips date suffixes → matches "gpt-4o"
     */
    fun findPrice(model: String): Price? {
        if (model.isEmpty()) return null

        // Direct match
        PRICES[model]?.let { return it }

        // Strip common prefixes (OpenRouter format: "openai/gpt-4o")
        val stripped = if (model.contains("/")) model.substringAfterLast("/") else model
        PRICES[stripped]?.let { return it }

        // Strip date suffixes: "gpt-4o-2025-03-01" → "gpt-4o-2025-03" → "gpt-4o-2025" → "gpt-4o"
        var candidate = stripped
        val dateSuffixPattern = Regex("-\\d{2,4}$")
        while (dateSuffixPattern.containsMatchIn(candidate)) {
            candidate = candidate.replace(dateSuffixPattern, "")
            PRICES[candidate]?.let { return it }
        }

        return null
    }

    /**
     * Format cost as a human-readable string.
     * < $0.01 → "$0.001" (3 decimals)
     * >= $0.01 → "$0.02" (2 decimals)
     * >= $1.00 → "$1.23" (2 decimals)
     */
    fun formatCost(costUsd: Double): String {
        return when {
            costUsd < 0.001 -> "< $0.001"
            costUsd < 0.01 -> String.format("$%.3f", costUsd)
            else -> String.format("$%.2f", costUsd)
        }
    }

    /**
     * Format token count as human-readable.
     * < 1000 → "500"
     * >= 1000 → "8.2K"
     * >= 1000000 → "1.2M"
     */
    fun formatTokens(tokens: Int): String {
        return when {
            tokens < 1000 -> tokens.toString()
            tokens < 1_000_000 -> String.format("%.1fK", tokens / 1000.0)
            else -> String.format("%.1fM", tokens / 1_000_000.0)
        }
    }

    /**
     * Estimate how many agent steps a budget allows for a given model.
     * Assumes ~5000 tokens per step (input + output).
     */
    fun estimateSteps(model: String, budgetUsd: Double): Int {
        val price = findPrice(model) ?: return 0
        val costPerStep = (4000 * price.inputPerMillion / 1_000_000.0) +
                          (1000 * price.outputPerMillion / 1_000_000.0)
        return if (costPerStep > 0) (budgetUsd / costPerStep).toInt() else 0
    }
}
