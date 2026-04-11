// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import io.agents.pokeclaw.utils.XLog

class MissedCallSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MissedCallSmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val from = messages.firstOrNull()?.displayOriginatingAddress.orEmpty()
            val body = buildString {
                messages.forEach { append(it.messageBody.orEmpty()) }
            }.trim()

            if (from.isBlank() || body.isBlank()) return

            XLog.i(TAG, "Inbound SMS from $from")
            MissedCallFollowUpManager.onIncomingSms(context, from, body)
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to process inbound SMS", e)
        }
    }
}
