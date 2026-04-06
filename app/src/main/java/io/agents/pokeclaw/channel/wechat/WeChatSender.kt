// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.wechat

import io.agents.pokeclaw.utils.XLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Message sending.
 * Strictly mirrors the official @tencent-weixin/openclaw-weixin@1.0.2:
 * - src/messaging/send.ts (sendMessageWeixin, sendImageMessageWeixin, sendFileMessageWeixin, sendMediaItems)
 * - src/messaging/send-media.ts (sendWeixinMediaFile - MIME routing)
 */
object WeChatSender {

    private const val TAG = "WeChatSender"

    // ==================== Text Message (send.ts sendMessageWeixin) ====================

    /**
     * Send a plain text message. Automatically converts markdown → plaintext.
     */
    fun sendText(
        apiClient: WeChatApiClient,
        to: String,
        text: String,
        contextToken: String?
    ): Boolean {
        val plainText = WeChatMarkdown.markdownToPlainText(text)
        return sendRawText(apiClient, to, plainText, contextToken)
    }

    /**
     * Send plain text (without markdown conversion).
     * Used by the message merge buffer: each message is converted individually, then merged and sent via this method.
     */
    fun sendRawText(
        apiClient: WeChatApiClient,
        to: String,
        text: String,
        contextToken: String?
    ): Boolean {
        if (contextToken.isNullOrEmpty()) {
            XLog.w(TAG, "sendRawText: contextToken missing for to=$to, sending without context")
        }
        val clientId = WeChatCdn.generateId("openclaw-weixin")
        val msg = buildMsgJson(to, clientId, contextToken, JSONArray().put(
            JSONObject().apply {
                put("type", MessageItemType.TEXT)
                put("text_item", JSONObject().put("text", text))
            }
        ))
        return apiClient.sendMessage(msg)
    }

    // ==================== Image Message (send.ts sendImageMessageWeixin) ====================

    fun sendImage(
        apiClient: WeChatApiClient,
        to: String,
        imageBytes: ByteArray,
        contextToken: String?
    ): Boolean {
        if (contextToken.isNullOrEmpty()) {
            XLog.w(TAG, "sendImage: contextToken missing for to=$to, sending without context")
        }
        val uploaded = WeChatCdn.uploadMedia(imageBytes, to, UploadMediaType.IMAGE, apiClient)
        if (uploaded == null) {
            XLog.e(TAG, "sendImage: upload failed")
            return false
        }
        // Corresponds to SDK send.ts:194: Buffer.from(uploaded.aeskey).toString("base64")
        // uploaded.aeskey is a hex string; Buffer.from(hexStr) defaults to UTF-8 → 32 bytes → base64
        val aesKeyBase64 = android.util.Base64.encodeToString(
            uploaded.aeskeyHex.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val imageItem = JSONObject().apply {
            put("type", MessageItemType.IMAGE)
            put("image_item", JSONObject().apply {
                put("media", JSONObject().apply {
                    put("encrypt_query_param", uploaded.downloadEncryptedQueryParam)
                    put("aes_key", aesKeyBase64)
                    put("encrypt_type", 1)
                })
                put("mid_size", uploaded.fileSizeCiphertext)
            })
        }
        return sendSingleItem(apiClient, to, contextToken, imageItem)
    }

    // ==================== Video Message (send.ts sendVideoMessageWeixin) ====================

    fun sendVideo(
        apiClient: WeChatApiClient,
        to: String,
        videoBytes: ByteArray,
        contextToken: String?
    ): Boolean {
        if (contextToken.isNullOrEmpty()) {
            XLog.w(TAG, "sendVideo: contextToken missing for to=$to, sending without context")
        }
        val uploaded = WeChatCdn.uploadMedia(videoBytes, to, UploadMediaType.VIDEO, apiClient)
            ?: return false
        val aesKeyBase64 = android.util.Base64.encodeToString(
            uploaded.aeskeyHex.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val videoItem = JSONObject().apply {
            put("type", MessageItemType.VIDEO)
            put("video_item", JSONObject().apply {
                put("media", JSONObject().apply {
                    put("encrypt_query_param", uploaded.downloadEncryptedQueryParam)
                    put("aes_key", aesKeyBase64)
                    put("encrypt_type", 1)
                })
                put("video_size", uploaded.fileSizeCiphertext)
            })
        }
        return sendSingleItem(apiClient, to, contextToken, videoItem)
    }

    // ==================== File Message (send.ts sendFileMessageWeixin) ====================

    fun sendFile(
        apiClient: WeChatApiClient,
        to: String,
        fileBytes: ByteArray,
        fileName: String,
        contextToken: String?
    ): Boolean {
        if (contextToken.isNullOrEmpty()) {
            XLog.w(TAG, "sendFile: contextToken missing for to=$to, sending without context")
        }
        val uploaded = WeChatCdn.uploadMedia(fileBytes, to, UploadMediaType.FILE, apiClient)
            ?: return false
        val aesKeyBase64 = android.util.Base64.encodeToString(
            uploaded.aeskeyHex.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        val fileItem = JSONObject().apply {
            put("type", MessageItemType.FILE)
            put("file_item", JSONObject().apply {
                put("media", JSONObject().apply {
                    put("encrypt_query_param", uploaded.downloadEncryptedQueryParam)
                    put("aes_key", aesKeyBase64)
                    put("encrypt_type", 1)
                })
                put("file_name", fileName)
                put("len", uploaded.fileSize.toString())
            })
        }
        return sendSingleItem(apiClient, to, contextToken, fileItem)
    }

    // ==================== MIME Routing (send-media.ts sendWeixinMediaFile) ====================

    /**
     * Route to image/video/file sending based on file extension.
     * Corresponds to SDK sendWeixinMediaFile.
     */
    fun sendMediaFile(
        apiClient: WeChatApiClient,
        to: String,
        file: java.io.File,
        contextToken: String?
    ): Boolean {
        val ext = file.extension.lowercase()
        val fileBytes = file.readBytes()
        return when {
            ext in IMAGE_EXTENSIONS -> {
                XLog.i(TAG, "sendMediaFile: routing as image (${file.name})")
                sendImage(apiClient, to, fileBytes, contextToken)
            }
            ext in VIDEO_EXTENSIONS -> {
                XLog.i(TAG, "sendMediaFile: routing as video (${file.name})")
                sendVideo(apiClient, to, fileBytes, contextToken)
            }
            else -> {
                XLog.i(TAG, "sendMediaFile: routing as file (${file.name})")
                sendFile(apiClient, to, fileBytes, file.name, contextToken)
            }
        }
    }

    // ==================== Internal Utilities ====================

    /**
     * Send a single media item.
     * Corresponds to SDK send.ts sendMediaItems: each item is a separate request with a single-entry item_list.
     */
    private fun sendSingleItem(
        apiClient: WeChatApiClient,
        to: String,
        contextToken: String?,
        item: JSONObject
    ): Boolean {
        val clientId = WeChatCdn.generateId("openclaw-weixin")
        val msg = buildMsgJson(to, clientId, contextToken, JSONArray().put(item))
        return apiClient.sendMessage(msg)
    }

    /** Build the msg JSON object (shared by all send methods) */
    private fun buildMsgJson(
        to: String,
        clientId: String,
        contextToken: String?,
        itemList: JSONArray
    ): JSONObject {
        return JSONObject().apply {
            put("from_user_id", "")
            put("to_user_id", to)
            put("client_id", clientId)
            put("message_type", MessageType.BOT)
            put("message_state", MessageState.FINISH)
            if (contextToken != null) put("context_token", contextToken)
            put("item_list", itemList)
        }
    }

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "avi", "mkv", "webm", "3gp")
}
