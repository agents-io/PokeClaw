// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import io.agents.pokeclaw.agent.llm.LlmSessionManager
import io.agents.pokeclaw.ui.chat.BackgroundChatBridge
import io.agents.pokeclaw.utils.ContactMatchUtils
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class MissedCallFollowUpThread(
    val number: String,
    val displayName: String,
    val turns: MutableList<Pair<String, String>> = mutableListOf(),
)

data class MissedCallEntry(
    val number: String,
    val displayName: String,
    val occurredAt: Long,
)

/**
 * Cloud-only missed-call assistant.
 *
 * Independent from WhatsApp monitor/auto-reply:
 * - trigger: missed call notification + call log
 * - transport: native SMS
 * - reply engine: default Cloud model
 */
object MissedCallFollowUpManager {

    private const val TAG = "MissedCallFollowUp"
    private const val ACTIVE_LABEL = "Missed calls → SMS"
    private const val MAX_TURNS = 12

    private val executor = Executors.newSingleThreadExecutor()
    private val threads = ConcurrentHashMap<String, MissedCallFollowUpThread>()

    @Volatile
    private var enabled = false
    @Volatile
    private var customPrompt = ""
    @Volatile
    private var conversationId = ""
    @Volatile
    private var armedAt = 0L
    @Volatile
    private var lastMissedCallFingerprint = ""
    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        enabled = KVUtils.isMissedCallFollowUpEnabled()
        customPrompt = KVUtils.getMissedCallFollowUpPrompt()
        conversationId = KVUtils.getMissedCallFollowUpConversationId()
        armedAt = KVUtils.getMissedCallFollowUpArmedAt()
        initialized = true
        ForegroundService.syncToBackgroundState(context)
    }

    fun isEnabled(): Boolean = enabled && customPrompt.isNotBlank() && conversationId.isNotBlank()

    fun activeLabel(): String? = if (isEnabled()) ACTIVE_LABEL else null

    fun arm(context: Context, prompt: String, targetConversationId: String): String {
        val trimmedPrompt = prompt.trim()
        require(trimmedPrompt.isNotEmpty()) { "Prompt cannot be empty" }
        require(targetConversationId.isNotBlank()) { "Conversation ID cannot be empty" }

        enabled = true
        customPrompt = trimmedPrompt
        conversationId = targetConversationId
        armedAt = System.currentTimeMillis()
        lastMissedCallFingerprint = ""
        threads.clear()

        KVUtils.setMissedCallFollowUpEnabled(true)
        KVUtils.setMissedCallFollowUpPrompt(trimmedPrompt)
        KVUtils.setMissedCallFollowUpConversationId(targetConversationId)
        KVUtils.setMissedCallFollowUpArmedAt(armedAt)

        XLog.i(TAG, "Armed missed-call follow-up on conversation=$targetConversationId")
        ForegroundService.syncToBackgroundState(context)
        BackgroundChatBridge.postSystem(
            context,
            targetConversationId,
            "✓ Missed-call follow-up is armed. New missed calls will trigger a Cloud-generated SMS follow-up."
        )
        return "Missed-call follow-up armed"
    }

    fun stop(context: Context): String {
        val targetConversationId = conversationId
        enabled = false
        customPrompt = ""
        conversationId = ""
        armedAt = 0L
        lastMissedCallFingerprint = ""
        threads.clear()

        KVUtils.setMissedCallFollowUpEnabled(false)
        KVUtils.setMissedCallFollowUpPrompt("")
        KVUtils.setMissedCallFollowUpConversationId("")
        KVUtils.setMissedCallFollowUpArmedAt(0L)

        XLog.i(TAG, "Stopped missed-call follow-up")
        ForegroundService.syncToBackgroundState(context)
        if (targetConversationId.isNotBlank()) {
            BackgroundChatBridge.postSystem(
                context,
                targetConversationId,
                "Stopped missed-call follow-up."
            )
        }
        return "Stopped missed-call follow-up"
    }

    fun onMissedCallNotificationPosted(context: Context) {
        if (!isEnabled()) return
        executor.submit {
            try {
                val missedCall = queryLatestMissedCall(context)
                if (missedCall == null) {
                    XLog.w(TAG, "Missed-call trigger received but no recent call-log entry found")
                    return@submit
                }

                handleMissedCallEntry(context, missedCall)
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to process missed-call trigger", e)
                postStatus(context, "Missed-call follow-up failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun debugTriggerMissedCall(context: Context, number: String, displayName: String) {
        if (!isEnabled()) return
        executor.submit {
            handleMissedCallEntry(
                context,
                MissedCallEntry(
                    number = number,
                    displayName = displayName.ifBlank { number },
                    occurredAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun debugTriggerIncomingSms(context: Context, from: String, body: String) {
        onIncomingSms(context, from, body)
    }

    fun onIncomingSms(context: Context, from: String, body: String) {
        if (!isEnabled()) return
        val normalizedNumber = normalizeNumber(from)
        val thread = threads[normalizedNumber] ?: return
        val trimmedBody = body.trim()
        if (trimmedBody.isEmpty()) return

        executor.submit {
            try {
                thread.turns += "caller" to trimmedBody
                trimThread(thread)

                val reply = generateReplyToIncomingSms(thread)
                if (reply.isBlank()) {
                    postStatus(context, "Received SMS from ${thread.displayName}, but Cloud could not draft a reply.")
                    return@submit
                }

                if (!sendSms(context, thread.number, reply)) {
                    postStatus(context, "Received SMS from ${thread.displayName}, but SMS reply send failed.")
                    return@submit
                }

                thread.turns += "assistant" to reply
                trimThread(thread)

                postStatus(
                    context,
                    "SMS from ${thread.displayName}: \"$trimmedBody\"\nReply sent: \"$reply\""
                )
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to process inbound SMS", e)
                postStatus(context, "Inbound SMS follow-up failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun generateInitialReply(entry: MissedCallEntry): String {
        val systemPrompt = buildSystemPrompt()
        val userPrompt = buildString {
            appendLine("A caller just tried to reach the user and the call was missed.")
            appendLine("Caller name: ${entry.displayName}")
            appendLine("Caller number: ${entry.number}")
            appendLine()
            appendLine("Write the first SMS follow-up now.")
            appendLine("Return only the message body.")
        }
        return sanitizeSms(LlmSessionManager.singleShotDefaultCloud(systemPrompt, userPrompt, temperature = 0.4))
    }

    private fun generateReplyToIncomingSms(thread: MissedCallFollowUpThread): String {
        val transcript = thread.turns.takeLast(MAX_TURNS).joinToString("\n") { (role, text) ->
            if (role == "assistant") "Assistant: $text" else "Caller: $text"
        }
        val systemPrompt = buildSystemPrompt()
        val userPrompt = buildString {
            appendLine("Continue this SMS conversation with the caller.")
            appendLine("Caller: ${thread.displayName} (${thread.number})")
            appendLine()
            appendLine("Conversation so far:")
            appendLine(transcript)
            appendLine()
            appendLine("Write the next SMS reply only.")
        }
        return sanitizeSms(LlmSessionManager.singleShotDefaultCloud(systemPrompt, userPrompt, temperature = 0.4))
    }

    private fun buildSystemPrompt(): String {
        return buildString {
            appendLine("You are writing SMS replies on behalf of the user after a missed phone call.")
            appendLine("Follow the user's business/communication brief exactly.")
            appendLine("Keep messages concise, natural, and appropriate for SMS.")
            appendLine("Do not mention being an AI.")
            appendLine()
            appendLine("User brief:")
            append(customPrompt)
        }
    }

    private fun sanitizeSms(reply: String?): String {
        return reply
            ?.trim()
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.replace(Regex("^(Assistant|Reply|SMS):\\s*", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s+"), " ")
            ?.take(320)
            .orEmpty()
    }

    private fun sendSms(context: Context, destination: String, message: String): Boolean {
        if (message.isBlank()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            XLog.w(TAG, "SEND_SMS permission missing")
            return false
        }
        return try {
            XLog.i(TAG, "Sending SMS to $destination")
            SmsManager.getDefault().sendTextMessage(destination, null, message, null, null)
            true
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to send SMS", e)
            false
        }
    }

    private fun queryLatestMissedCall(context: Context): MissedCallEntry? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            XLog.w(TAG, "READ_CALL_LOG permission missing")
            return null
        }

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
        )
        val since = maxOf(armedAt - 60_000L, System.currentTimeMillis() - 5 * 60_000L)
        val selection = "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.DATE}>=?"
        val selectionArgs = arrayOf(
            CallLog.Calls.MISSED_TYPE.toString(),
            since.toString(),
        )

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val number = cursor.getString(0).orEmpty()
            val cachedName = cursor.getString(1).orEmpty()
            val date = cursor.getLong(2)
            val display = cachedName.ifBlank { number.ifBlank { "Unknown caller" } }
            return MissedCallEntry(number = number, displayName = display, occurredAt = date)
        }

        return null
    }

    private fun postStatus(context: Context, text: String) {
        val targetConversationId = conversationId
        if (targetConversationId.isBlank()) return
        BackgroundChatBridge.postSystem(context, targetConversationId, text)
        ForegroundService.syncToBackgroundState(context)
    }

    private fun handleMissedCallEntry(context: Context, missedCall: MissedCallEntry) {
        val normalizedNumber = normalizeNumber(missedCall.number)
        if (normalizedNumber.isBlank()) {
            postStatus(context, "Missed call detected, but no usable phone number was available for SMS follow-up.")
            return
        }

        val fingerprint = "$normalizedNumber:${missedCall.occurredAt}"
        if (fingerprint == lastMissedCallFingerprint) {
            XLog.d(TAG, "Skipping duplicate missed-call trigger: $fingerprint")
            return
        }
        lastMissedCallFingerprint = fingerprint
        XLog.i(TAG, "Handling missed call from ${missedCall.displayName} (${missedCall.number})")

        val reply = generateInitialReply(missedCall)
        if (reply.isBlank()) {
            postStatus(context, "Missed call from ${missedCall.displayName}, but Cloud could not draft a follow-up SMS.")
            return
        }

        if (!sendSms(context, missedCall.number, reply)) {
            postStatus(context, "Missed call from ${missedCall.displayName}, but SMS send failed.")
            return
        }

        val thread = MissedCallFollowUpThread(
            number = missedCall.number,
            displayName = missedCall.displayName,
        )
        thread.turns += "assistant" to reply
        threads[normalizedNumber] = thread

        postStatus(
            context,
            "Missed call from ${missedCall.displayName}. Sent SMS follow-up: \"$reply\""
        )
    }

    private fun normalizeNumber(raw: String): String {
        val digits = ContactMatchUtils.digitsOnly(raw)
        return if (digits.isNotBlank()) digits else raw.trim()
    }

    private fun trimThread(thread: MissedCallFollowUpThread) {
        while (thread.turns.size > MAX_TURNS) {
            thread.turns.removeAt(0)
        }
    }
}
