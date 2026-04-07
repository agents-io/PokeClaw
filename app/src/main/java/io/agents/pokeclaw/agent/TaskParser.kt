// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import io.agents.pokeclaw.utils.XLog

/**
 * Tier 1: Deterministic task parser.
 * Matches user input against regex patterns to resolve tasks that can be
 * handled with a direct Android intent — zero LLM calls.
 *
 * Architecture reference: DroidClaw's parser.ts (11 regex patterns).
 * Handles ~30% of tasks with 0 LLM calls, < 1 second.
 */
object TaskParser {

    private const val TAG = "TaskParser"

    data class ParseResult(
        val action: String,
        val intent: Intent?,
        val toolName: String? = null,
        val toolParams: Map<String, Any>? = null,
        val description: String = ""
    )

    /**
     * Try to parse a task into a direct action.
     * Returns null if no pattern matches (falls through to Tier 2).
     */
    fun parse(task: String, installedPackages: List<String> = emptyList()): ParseResult? {
        val lower = task.lowercase().trim()

        return matchCall(lower, task)
            ?: matchSms(lower, task)
            ?: matchAlarm(lower, task)
            ?: matchTimer(lower, task)
            ?: matchScreenshot(lower)
            ?: matchBackHome(lower)
            ?: matchOpenUrl(lower, task)
            ?: matchOpenSettings(lower)
            ?: matchOpenApp(lower, task, installedPackages)
    }

    // ==================== Pattern Matchers ====================

    private val CALL_PATTERN = Regex(
        """(?:call|phone|ring|dial|打電話|打畀|致電)\s+(.+)""", RegexOption.IGNORE_CASE
    )

    private fun matchCall(lower: String, original: String): ParseResult? {
        val match = CALL_PATTERN.find(lower) ?: return null
        val target = match.groupValues[1].trim()
        // Check if target looks like a phone number
        val numberMatch = Regex("""[\d\s\-+()]{7,}""").find(target)
        return if (numberMatch != null) {
            val number = numberMatch.value.replace(Regex("""[\s\-()]"""), "")
            ParseResult(
                action = "call",
                intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
                description = "Dialing $number"
            )
        } else null
    }

    private val SMS_PATTERN = Regex(
        """(?:sms|text|message|send.*(?:sms|text)|發短訊|發信息)\s+(?:to\s+)?(.+)""", RegexOption.IGNORE_CASE
    )

    private fun matchSms(lower: String, original: String): ParseResult? {
        val match = SMS_PATTERN.find(lower) ?: return null
        val target = match.groupValues[1].trim()
        val numberMatch = Regex("""[\d\s\-+()]{7,}""").find(target)
        return if (numberMatch != null) {
            val number = numberMatch.value.replace(Regex("""[\s\-()]"""), "")
            ParseResult(
                action = "sms",
                intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")),
                description = "Opening SMS to $number"
            )
        } else null
    }

    private val ALARM_PATTERN = Regex(
        """(?:set|create)?\s*(?:alarm|鬧鐘|叫醒|wake\s*(?:me\s*)?up)\s*(?:at|for)?\s*(\d{1,2})[:\s]?(\d{2})?\s*(am|pm)?""",
        RegexOption.IGNORE_CASE
    )

    private fun matchAlarm(lower: String, original: String): ParseResult? {
        val match = ALARM_PATTERN.find(lower) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val ampm = match.groupValues[3].lowercase()
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0

        return ParseResult(
            action = "alarm",
            intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            },
            description = "Setting alarm for ${String.format("%02d:%02d", hour, minute)}"
        )
    }

    private val TIMER_PATTERN = Regex(
        """(?:set|start)?\s*(?:timer|countdown|計時)\s*(?:for)?\s*(\d+)\s*(second|sec|minute|min|hour|hr|s|m|h)""",
        RegexOption.IGNORE_CASE
    )

    private fun matchTimer(lower: String, original: String): ParseResult? {
        val match = TIMER_PATTERN.find(lower) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val seconds = when {
            unit.startsWith("h") -> amount * 3600
            unit.startsWith("m") -> amount * 60
            else -> amount
        }

        return ParseResult(
            action = "timer",
            intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            },
            description = "Setting timer for $amount ${match.groupValues[2]}"
        )
    }

    private fun matchScreenshot(lower: String): ParseResult? {
        if (!lower.contains("screenshot") && !lower.contains("screencap") &&
            !lower.contains("截圖") && !lower.contains("影相")) return null
        return ParseResult(
            action = "screenshot",
            intent = null,
            toolName = "take_screenshot",
            toolParams = emptyMap(),
            description = "Taking a screenshot"
        )
    }

    private fun matchBackHome(lower: String): ParseResult? {
        return when {
            lower == "go back" || lower == "back" || lower == "返回" ->
                ParseResult("back", null, "system_key", mapOf("key" to "back"), "Going back")
            lower == "go home" || lower == "home" || lower == "返回主頁" ->
                ParseResult("home", null, "system_key", mapOf("key" to "home"), "Going home")
            else -> null
        }
    }

    private val URL_PATTERN = Regex(
        """(?:open|go\s*to|visit|navigate\s*to|打開)\s+(https?://\S+)""", RegexOption.IGNORE_CASE
    )

    private fun matchOpenUrl(lower: String, original: String): ParseResult? {
        val match = URL_PATTERN.find(original) ?: return null
        val url = match.groupValues[1].trim()
        return ParseResult(
            action = "open_url",
            intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            description = "Opening $url"
        )
    }

    private val SETTINGS_KEYWORDS = mapOf(
        "wifi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "display" to "android.settings.DISPLAY_SETTINGS",
        "brightness" to "android.settings.DISPLAY_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "volume" to "android.settings.SOUND_SETTINGS",
        "battery" to "android.intent.action.POWER_USAGE_SUMMARY",
        "storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
        "location" to "android.settings.LOCATION_SOURCE_SETTINGS",
        "airplane" to "android.settings.AIRPLANE_MODE_SETTINGS",
        "notification" to "android.settings.APP_NOTIFICATION_SETTINGS",
        "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
    )

    private fun matchOpenSettings(lower: String): ParseResult? {
        if (!lower.contains("settings") && !lower.contains("設定")) return null

        // Check for specific settings keywords
        for ((keyword, action) in SETTINGS_KEYWORDS) {
            if (lower.contains(keyword)) {
                return ParseResult(
                    action = "open_settings",
                    intent = Intent(action),
                    description = "Opening $keyword settings"
                )
            }
        }

        // Generic "open settings"
        if (lower.matches(Regex(".*(?:open|go to|打開)\\s*(?:the\\s*)?settings.*"))) {
            return ParseResult(
                action = "open_settings",
                intent = Intent(android.provider.Settings.ACTION_SETTINGS),
                description = "Opening Settings"
            )
        }

        return null
    }

    private val OPEN_APP_PATTERN = Regex(
        """(?:open|launch|start|打開|開)\s+(?:the\s+)?(.+?)(?:\s+app)?$""", RegexOption.IGNORE_CASE
    )

    private fun matchOpenApp(lower: String, original: String, installedPackages: List<String>): ParseResult? {
        val match = OPEN_APP_PATTERN.find(lower) ?: return null
        val appName = match.groupValues[1].trim()

        // Don't match if the task has more complexity (e.g., "open YouTube and search for cats")
        if (lower.contains(" and ") || lower.contains(" then ") || lower.contains("，然後")) return null

        return ParseResult(
            action = "open_app",
            intent = null,
            toolName = "open_app",
            toolParams = mapOf("app_name" to appName),
            description = "Opening $appName"
        )
    }
}
