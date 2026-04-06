// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.wechat

import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Inbound message parsing + contextToken management.
 * Corresponds to the official @tencent-weixin/openclaw-weixin src/messaging/inbound.ts
 * 1.0.2: contextToken in-memory cache only
 * 2.0.1: added contextToken persistence (memory + disk), survives process restart
 */
object WeChatInbound {

    private const val TAG = "WeChatInbound"

    /** MMKV key prefix for storing contextToken JSON */
    private const val KV_PREFIX = "WECHAT_CONTEXT_TOKENS_"

    // ==================== Context Token Store (inbound.ts) ====================
    // contextToken is issued by getupdates, one per message, and must be echoed back when replying.
    // In-memory map is the primary lookup; MMKV persistence ensures recovery after app restart (corresponds to 2.0.1 disk persistence).

    private val contextTokenStore = ConcurrentHashMap<String, String>()

    private fun contextTokenKey(accountId: String, userId: String): String = "$accountId:$userId"

    /** Store contextToken (memory + MMKV persistence). Corresponds to 2.0.1 inbound.ts setContextToken */
    fun setContextToken(accountId: String, userId: String, token: String) {
        contextTokenStore[contextTokenKey(accountId, userId)] = token
        persistContextTokens(accountId)
    }

    fun getContextToken(accountId: String, userId: String): String? {
        return contextTokenStore[contextTokenKey(accountId, userId)]
    }

    fun clearAll() {
        contextTokenStore.clear()
    }

    /** Reverse-lookup userId from contextToken value (iterates store) */
    fun findUserIdByContextToken(accountId: String, contextToken: String): String? {
        if (contextToken.isEmpty()) return null
        val prefix = "$accountId:"
        for ((key, value) in contextTokenStore) {
            if (key.startsWith(prefix) && value == contextToken) {
                return key.removePrefix(prefix)
            }
        }
        return null
    }

    // ==================== Persistence (added in 2.0.1) ====================

    /** Persist all contextTokens for the specified account to MMKV */
    private fun persistContextTokens(accountId: String) {
        val prefix = "$accountId:"
        val tokens = JSONObject()
        for ((key, value) in contextTokenStore) {
            if (key.startsWith(prefix)) {
                tokens.put(key.removePrefix(prefix), value)
            }
        }
        KVUtils.putString(KV_PREFIX + accountId, tokens.toString())
    }

    /**
     * Restore contextTokens from MMKV to memory. Call on app start / channel reconnect.
     * Corresponds to 2.0.1 inbound.ts restoreContextTokens
     */
    fun restoreContextTokens(accountId: String) {
        val raw = KVUtils.getString(KV_PREFIX + accountId, "")
        if (raw.isEmpty()) return
        try {
            val json = JSONObject(raw)
            var count = 0
            for (key in json.keys()) {
                val token = json.optString(key, "")
                if (token.isNotEmpty()) {
                    contextTokenStore[contextTokenKey(accountId, key)] = token
                    count++
                }
            }
            XLog.i(TAG, "restoreContextTokens: restored $count tokens for account=$accountId")
        } catch (e: Exception) {
            XLog.w(TAG, "restoreContextTokens: failed to parse", e)
        }
    }

    /**
     * Clear all contextTokens for the specified account (memory + MMKV).
     * Corresponds to 2.0.1 inbound.ts clearContextTokensForAccount
     */
    fun clearContextTokensForAccount(accountId: String) {
        val prefix = "$accountId:"
        contextTokenStore.keys().toList().forEach { key ->
            if (key.startsWith(prefix)) contextTokenStore.remove(key)
        }
        KVUtils.putString(KV_PREFIX + accountId, "")
        XLog.i(TAG, "clearContextTokensForAccount: cleared tokens for account=$accountId")
    }

    // ==================== Message Parsing (inbound.ts) ====================

    /**
     * Parse a WeChatMessage from the JSON message returned by getupdates.
     */
    fun parseMessage(json: JSONObject): WeChatMessage? {
        val fromUserId = json.optString("from_user_id", "")
        val contextToken = json.optString("context_token", "")
        if (fromUserId.isEmpty()) return null

        val itemList = mutableListOf<WeChatMessageItem>()
        val items = json.optJSONArray("item_list")
        if (items != null) {
            for (i in 0 until items.length()) {
                parseMessageItem(items.getJSONObject(i))?.let { itemList.add(it) }
            }
        }

        return WeChatMessage(
            seq = json.optInt("seq", 0),
            messageId = json.optLong("message_id", 0),
            fromUserId = fromUserId,
            toUserId = json.optString("to_user_id", ""),
            clientId = json.optString("client_id", ""),
            createTimeMs = json.optLong("create_time_ms", 0),
            updateTimeMs = json.optLong("update_time_ms", 0),
            deleteTimeMs = json.optLong("delete_time_ms", 0),
            sessionId = json.optString("session_id", ""),
            groupId = json.optString("group_id", ""),
            messageType = json.optInt("message_type", 0),
            messageState = json.optInt("message_state", 0),
            itemList = itemList,
            contextToken = contextToken
        )
    }

    private fun parseMessageItem(json: JSONObject): WeChatMessageItem? {
        val type = json.optInt("type", 0)
        return WeChatMessageItem(
            type = type,
            createTimeMs = if (json.has("create_time_ms")) json.optLong("create_time_ms") else null,
            updateTimeMs = if (json.has("update_time_ms")) json.optLong("update_time_ms") else null,
            isCompleted = if (json.has("is_completed")) json.optBoolean("is_completed") else null,
            msgId = json.optString("msg_id", "").ifEmpty { null },
            textItem = json.optJSONObject("text_item")?.let {
                WeChatTextItem(text = it.optString("text", ""))
            },
            imageItem = json.optJSONObject("image_item")?.let { parseImageItem(it) },
            voiceItem = json.optJSONObject("voice_item")?.let { parseVoiceItem(it) },
            fileItem = json.optJSONObject("file_item")?.let { parseFileItem(it) },
            videoItem = json.optJSONObject("video_item")?.let { parseVideoItem(it) },
            refMsg = json.optJSONObject("ref_msg")?.let { parseRefMsg(it) }
        )
    }

    private fun parseCdnMedia(json: JSONObject): CdnMedia {
        return CdnMedia(
            encryptQueryParam = json.optString("encrypt_query_param", "").ifEmpty { null },
            aesKey = json.optString("aes_key", "").ifEmpty { null },
            encryptType = json.optInt("encrypt_type", 0)
        )
    }

    private fun parseImageItem(json: JSONObject): WeChatImageItem {
        return WeChatImageItem(
            media = json.optJSONObject("media")?.let { parseCdnMedia(it) },
            thumbMedia = json.optJSONObject("thumb_media")?.let { parseCdnMedia(it) },
            aeskey = json.optString("aeskey", "").ifEmpty { null },
            midSize = json.optInt("mid_size", 0)
        )
    }

    private fun parseVoiceItem(json: JSONObject): WeChatVoiceItem {
        return WeChatVoiceItem(
            media = json.optJSONObject("media")?.let { parseCdnMedia(it) },
            encodeType = if (json.has("encode_type")) json.optInt("encode_type") else null,
            bitsPerSample = if (json.has("bits_per_sample")) json.optInt("bits_per_sample") else null,
            sampleRate = if (json.has("sample_rate")) json.optInt("sample_rate") else null,
            playtime = if (json.has("playtime")) json.optInt("playtime") else null,
            text = json.optString("text", "").ifEmpty { null }
        )
    }

    private fun parseFileItem(json: JSONObject): WeChatFileItem {
        return WeChatFileItem(
            media = json.optJSONObject("media")?.let { parseCdnMedia(it) },
            fileName = json.optString("file_name", "").ifEmpty { null },
            len = json.optString("len", "").ifEmpty { null }
        )
    }

    private fun parseVideoItem(json: JSONObject): WeChatVideoItem {
        return WeChatVideoItem(
            media = json.optJSONObject("media")?.let { parseCdnMedia(it) }
        )
    }

    private fun parseRefMsg(json: JSONObject): RefMessage {
        return RefMessage(
            messageItem = json.optJSONObject("message_item")?.let { parseMessageItem(it) },
            title = json.optString("title", "").ifEmpty { null }
        )
    }

    // ==================== Extract Message Text (inbound.ts bodyFromItemList) ====================

    /**
     * Extract text content from item_list.
     * Supports: plain text, voice-to-text, and quoted message context.
     */
    fun bodyFromItemList(itemList: List<WeChatMessageItem>?): String {
        if (itemList.isNullOrEmpty()) return ""
        for (item in itemList) {
            // Text message
            if (item.type == MessageItemType.TEXT && !item.textItem?.text.isNullOrEmpty()) {
                val text = item.textItem!!.text!!
                val ref = item.refMsg ?: return text

                // Quoted message is media: return current text only
                if (ref.messageItem != null && isMediaItem(ref.messageItem)) return text

                // Build quote context
                val parts = mutableListOf<String>()
                ref.title?.let { parts.add(it) }
                ref.messageItem?.let {
                    val refBody = bodyFromItemList(listOf(it))
                    if (refBody.isNotEmpty()) parts.add(refBody)
                }
                return if (parts.isEmpty()) text else "[Quote: ${parts.joinToString(" | ")}]\n$text"
            }

            // Voice-to-text
            if (item.type == MessageItemType.VOICE && !item.voiceItem?.text.isNullOrEmpty()) {
                return item.voiceItem!!.text!!
            }
        }
        return ""
    }

    /** Determine whether the type is a media type */
    fun isMediaItem(item: WeChatMessageItem): Boolean {
        return item.type in listOf(
            MessageItemType.IMAGE,
            MessageItemType.VIDEO,
            MessageItemType.FILE,
            MessageItemType.VOICE
        )
    }
}
