// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import io.agents.pokeclaw.service.MissedCallFollowUpManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

/**
 * Debug-only receiver for missed-call follow-up smoke tests.
 *
 * Usage:
 *   adb shell am broadcast -a io.agents.pokeclaw.DEBUG_MISSED_CALL_ARM --es prompt "You are a salon booking assistant."
 *   adb shell am broadcast -a io.agents.pokeclaw.DEBUG_MISSED_CALL --es number "+16045551234" --es name "Monica"
 *   adb shell am broadcast -a io.agents.pokeclaw.DEBUG_MISSED_CALL_SMS --es number "+16045551234" --es text "Hi, I want to book tomorrow"
 */
class MissedCallDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!io.agents.pokeclaw.BuildConfig.DEBUG) return
        when (intent.action) {
            "io.agents.pokeclaw.DEBUG_MISSED_CALL_ARM" -> {
                val prompt = decodeMaybeBase64(intent, "prompt")
                val conversationId = intent.getStringExtra("conversation_id").orEmpty()
                    .ifBlank { KVUtils.getCurrentConversationId() }
                if (prompt.isBlank() || conversationId.isBlank()) {
                    XLog.w(
                        "MissedCallDebugReceiver",
                        "Ignoring arm request: promptBlank=${prompt.isBlank()} conversationBlank=${conversationId.isBlank()}"
                    )
                    return
                }
                XLog.i("MissedCallDebugReceiver", "Arming missed-call follow-up on $conversationId")
                MissedCallFollowUpManager.arm(context, prompt, conversationId)
            }
            "io.agents.pokeclaw.DEBUG_MISSED_CALL_STOP" -> {
                XLog.i("MissedCallDebugReceiver", "Stopping missed-call follow-up")
                MissedCallFollowUpManager.stop(context)
            }
            "io.agents.pokeclaw.DEBUG_MISSED_CALL" -> {
                val number = intent.getStringExtra("number").orEmpty()
                val name = decodeMaybeBase64(intent, "name")
                if (number.isBlank()) return
                XLog.i("MissedCallDebugReceiver", "Simulating missed call from $number")
                MissedCallFollowUpManager.debugTriggerMissedCall(context, number, name)
            }
            "io.agents.pokeclaw.DEBUG_MISSED_CALL_SMS" -> {
                val number = intent.getStringExtra("number").orEmpty()
                val text = decodeMaybeBase64(intent, "text")
                if (number.isBlank() || text.isBlank()) return
                XLog.i("MissedCallDebugReceiver", "Simulating inbound SMS from $number")
                MissedCallFollowUpManager.debugTriggerIncomingSms(context, number, text)
            }
        }
    }

    private fun decodeMaybeBase64(intent: Intent, key: String): String {
        val b64 = intent.getStringExtra("${key}_b64").orEmpty().trim()
        if (b64.isNotBlank()) {
            val normalized = normalizeBase64(b64)
            return try {
                String(Base64.decode(normalized, Base64.URL_SAFE or Base64.NO_WRAP)).trim()
            } catch (_: Exception) {
                try {
                    String(Base64.decode(normalized, Base64.DEFAULT)).trim()
                } catch (_: Exception) {
                    ""
                }
            }
        }
        return intent.getStringExtra(key).orEmpty()
    }

    private fun normalizeBase64(raw: String): String {
        val noWhitespace = raw.replace("\\s+".toRegex(), "")
        val paddingNeeded = (4 - (noWhitespace.length % 4)) % 4
        return noWhitespace + "=".repeat(paddingNeeded)
    }
}
