package io.agents.pokeclaw.channel.wechat

import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 收消息解析 + contextToken 管理。
 * 对应官方 @tencent-weixin/openclaw-weixin 的 src/messaging/inbound.ts
 * 1.0.2：contextToken 仅内存缓存
 * 2.0.1：新增 contextToken 持久化（内存 + 磁盘），进程重启后可恢复
 */
object WeChatInbound {

    private const val TAG = "WeChatInbound"

    /** MMKV key 前缀，存储 contextToken JSON */
    private const val KV_PREFIX = "WECHAT_CONTEXT_TOKENS_"

    // ==================== Context Token Store (inbound.ts) ====================
    // contextToken 由 getupdates 下发，每条消息一个，回复时必须原样带回。
    // 内存 map 为主查找，MMKV 持久化确保 App 重启后可恢复（对应 2.0.1 的磁盘持久化）。

    private val contextTokenStore = ConcurrentHashMap<String, String>()

    private fun contextTokenKey(accountId: String, userId: String): String = "$accountId:$userId"

    /** 存储 contextToken（内存 + MMKV 持久化）。对应 2.0.1 inbound.ts setContextToken */
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

    /** 根据 contextToken 值反查 userId（遍历 store） */
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

    // ==================== 持久化（2.0.1 新增） ====================

    /** 将指定 account 的全部 contextToken 持久化到 MMKV */
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
     * 从 MMKV 恢复 contextToken 到内存。App 启动 / 通道重连时调用。
     * 对应 2.0.1 inbound.ts restoreContextTokens
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
     * 清除指定 account 的全部 contextToken（内存 + MMKV）。
     * 对应 2.0.1 inbound.ts clearContextTokensForAccount
     */
    fun clearContextTokensForAccount(accountId: String) {
        val prefix = "$accountId:"
        contextTokenStore.keys().toList().forEach { key ->
            if (key.startsWith(prefix)) contextTokenStore.remove(key)
        }
        KVUtils.putString(KV_PREFIX + accountId, "")
        XLog.i(TAG, "clearContextTokensForAccount: cleared tokens for account=$accountId")
    }

    // ==================== 消息解析 (inbound.ts) ====================

    /**
     * 从 getupdates 返回的 JSON 消息中解析出 WeChatMessage。
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

    // ==================== 提取消息文本 (inbound.ts bodyFromItemList) ====================

    /**
     * 从 item_list 提取文本内容。
     * 支持：纯文本、语音转文字、引用消息上下文。
     */
    fun bodyFromItemList(itemList: List<WeChatMessageItem>?): String {
        if (itemList.isNullOrEmpty()) return ""
        for (item in itemList) {
            // 文本消息
            if (item.type == MessageItemType.TEXT && !item.textItem?.text.isNullOrEmpty()) {
                val text = item.textItem!!.text!!
                val ref = item.refMsg ?: return text

                // 引用的是媒体消息：只返回当前文本
                if (ref.messageItem != null && isMediaItem(ref.messageItem)) return text

                // 构建引用上下文
                val parts = mutableListOf<String>()
                ref.title?.let { parts.add(it) }
                ref.messageItem?.let {
                    val refBody = bodyFromItemList(listOf(it))
                    if (refBody.isNotEmpty()) parts.add(refBody)
                }
                return if (parts.isEmpty()) text else "[引用: ${parts.joinToString(" | ")}]\n$text"
            }

            // 语音转文字
            if (item.type == MessageItemType.VOICE && !item.voiceItem?.text.isNullOrEmpty()) {
                return item.voiceItem!!.text!!
            }
        }
        return ""
    }

    /** 判断是否为媒体类型 */
    fun isMediaItem(item: WeChatMessageItem): Boolean {
        return item.type in listOf(
            MessageItemType.IMAGE,
            MessageItemType.VIDEO,
            MessageItemType.FILE,
            MessageItemType.VOICE
        )
    }
}
