// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.wechat

import io.agents.pokeclaw.utils.XLog
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * WeChat iLink Bot API client.
 * Strictly mirrors the official @tencent-weixin/openclaw-weixin@1.0.2 src/api/api.ts + src/api/session-guard.ts
 */
class WeChatApiClient(
    private var baseUrl: String = DEFAULT_BASE_URL,
    private var botToken: String = ""
) {

    companion object {
        private const val TAG = "WeChatApiClient"
        private val JSON_MEDIA = "application/json".toMediaTypeOrNull()

        // ==================== Session Guard (session-guard.ts) ====================
        private const val SESSION_PAUSE_DURATION_MS = 60 * 60 * 1000L
        private val pauseUntilMap = ConcurrentHashMap<String, Long>()

        fun pauseSession(accountId: String) {
            val until = System.currentTimeMillis() + SESSION_PAUSE_DURATION_MS
            pauseUntilMap[accountId] = until
            XLog.w(TAG, "session paused: accountId=$accountId, until=${until}, duration=${SESSION_PAUSE_DURATION_MS / 1000}s")
        }

        fun isSessionPaused(accountId: String): Boolean {
            val until = pauseUntilMap[accountId] ?: return false
            if (System.currentTimeMillis() >= until) {
                pauseUntilMap.remove(accountId)
                return false
            }
            return true
        }

        fun getRemainingPauseMs(accountId: String): Long {
            val until = pauseUntilMap[accountId] ?: return 0
            val remaining = until - System.currentTimeMillis()
            if (remaining <= 0) {
                pauseUntilMap.remove(accountId)
                return 0
            }
            return remaining
        }
    }

    fun setBotToken(token: String) { this.botToken = token }
    fun setBaseUrl(url: String) { this.baseUrl = url }

    // ==================== HTTP Client (api.ts DEFAULT_*_TIMEOUT_MS) ====================

    /** Long-polling (getUpdates/QR poll: SDK timeout 35s, readTimeout with 5s margin) */
    private val longPollClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)  // 35s server hold + 5s buffer
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Normal API use (sendMessage, getUploadUrl) */
    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Lightweight API use (getConfig, sendTyping) */
    private val configClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ==================== Request Building (api.ts buildHeaders + apiFetch) ====================

    /**
     * X-WECHAT-UIN: random uint32 → decimal string → base64
     * Corresponds to SDK's randomWechatUin()
     */
    private fun randomWechatUin(): String {
        val bytes = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val uint32 = ((bytes[0].toLong() and 0xFF) shl 24) or
                ((bytes[1].toLong() and 0xFF) shl 16) or
                ((bytes[2].toLong() and 0xFF) shl 8) or
                (bytes[3].toLong() and 0xFF)
        return android.util.Base64.encodeToString(
            uint32.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
    }

    private fun buildApiRequest(endpoint: String, body: String, client: OkHttpClient): Pair<Request, OkHttpClient> {
        val url = if (baseUrl.endsWith("/")) "$baseUrl$endpoint" else "$baseUrl/$endpoint"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("AuthorizationType", "ilink_bot_token")
            .addHeader("Content-Length", bodyBytes.size.toString())
            .addHeader("X-WECHAT-UIN", randomWechatUin())
            .post(bodyBytes.toRequestBody(JSON_MEDIA))

        if (botToken.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer $botToken")
        }

        return builder.build() to client
    }

    /**
     * Generic API POST request. Returns response body text.
     * Corresponds to SDK apiFetch()
     */
    private fun apiFetch(endpoint: String, body: JSONObject, client: OkHttpClient, label: String): String? {
        val bodyStr = body.toString()
        val (request, httpClient) = buildApiRequest(endpoint, bodyStr, client)
        return try {
            val response = httpClient.newCall(request).execute()
            val code = response.code
            val rawText = response.body?.string() ?: ""
            response.close()
            if (code !in 200..299) {
                XLog.e(TAG, "$label: HTTP $code, body=${rawText.take(200)}")
                null
            } else {
                rawText
            }
        } catch (e: java.net.SocketTimeoutException) {
            XLog.d(TAG, "$label: timeout")
            null
        } catch (e: java.io.InterruptedIOException) {
            // Thread.interrupt() causes OkHttp to throw; must propagate up to let the monitor loop exit
            Thread.currentThread().interrupt()
            throw InterruptedException("interrupted by disconnect")
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("interrupted by disconnect")
            }
            XLog.e(TAG, "$label: exception", e)
            null
        }
    }

    // ==================== API Endpoints (api.ts) ====================

    /**
     * Long-poll for new messages.
     * Corresponds to SDK getUpdates()
     */
    fun getUpdates(getUpdatesBuf: String): GetUpdatesResp? {
        val body = JSONObject().apply {
            put("get_updates_buf", getUpdatesBuf)
            put("base_info", JSONObject().put("channel_version", CHANNEL_VERSION))
        }
        val rawText = apiFetch("ilink/bot/getupdates", body, longPollClient, "getUpdates")
        if (rawText == null) {
            // Timeout returns empty result (consistent with SDK AbortError handling)
            return GetUpdatesResp(ret = 0, msgs = emptyList(), getUpdatesBuf = getUpdatesBuf)
        }
        if (rawText.isEmpty()) return GetUpdatesResp(ret = 0, msgs = emptyList(), getUpdatesBuf = getUpdatesBuf)

        return try {
            val json = JSONObject(rawText)
            val msgs = mutableListOf<WeChatMessage>()
            json.optJSONArray("msgs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    WeChatInbound.parseMessage(arr.getJSONObject(i))?.let { msgs.add(it) }
                }
            }
            GetUpdatesResp(
                ret = json.optInt("ret", 0),
                errcode = if (json.has("errcode")) json.optInt("errcode", 0) else null,
                errmsg = json.optString("errmsg", "").ifEmpty { null },
                msgs = msgs,
                getUpdatesBuf = json.optString("get_updates_buf", getUpdatesBuf),
                longpollingTimeoutMs = if (json.has("longpolling_timeout_ms")) json.optLong("longpolling_timeout_ms") else null
            )
        } catch (e: Exception) {
            XLog.e(TAG, "getUpdates: parse error", e)
            null
        }
    }

    /**
     * Send a message.
     * Corresponds to SDK sendMessage()
     */
    fun sendMessage(msgBody: JSONObject): Boolean {
        val body = JSONObject().apply {
            put("msg", msgBody)
            put("base_info", JSONObject().put("channel_version", CHANNEL_VERSION))
        }
        val rawText = apiFetch("ilink/bot/sendmessage", body, apiClient, "sendMessage")
        if (rawText != null && rawText.isNotEmpty() && rawText != "{}") {
            XLog.i(TAG, "sendMessage response: $rawText")
        }
        return rawText != null
    }

    /**
     * Get upload URL.
     * Corresponds to SDK getUploadUrl(), returns upload_param.
     */
    fun getUploadUrl(
        filekey: String, mediaType: Int, toUserId: String,
        rawsize: Int, rawfilemd5: String, filesize: Int, aeskeyHex: String
    ): String? {
        val body = JSONObject().apply {
            put("filekey", filekey)
            put("media_type", mediaType)
            put("to_user_id", toUserId)
            put("rawsize", rawsize)
            put("rawfilemd5", rawfilemd5)
            put("filesize", filesize)
            put("no_need_thumb", true)
            put("aeskey", aeskeyHex)
            put("base_info", JSONObject().put("channel_version", CHANNEL_VERSION))
        }
        val rawText = apiFetch("ilink/bot/getuploadurl", body, apiClient, "getUploadUrl")
            ?: return null
        if (rawText.isEmpty()) return null
        return try {
            val json = JSONObject(rawText)
            val ret = json.optInt("ret", 0)
            if (ret != 0) {
                XLog.w(TAG, "getUploadUrl: ret=$ret, body=$rawText")
                return null
            }
            json.optString("upload_param", "").ifEmpty { null }
        } catch (e: Exception) {
            XLog.e(TAG, "getUploadUrl: parse error", e)
            null
        }
    }

    /**
     * Get bot config (typing_ticket, etc.).
     * Corresponds to SDK getConfig()
     */
    fun getConfig(ilinkUserId: String, contextToken: String?): String? {
        val body = JSONObject().apply {
            put("ilink_user_id", ilinkUserId)
            if (contextToken != null) put("context_token", contextToken)
            put("base_info", JSONObject().put("channel_version", CHANNEL_VERSION))
        }
        val rawText = apiFetch("ilink/bot/getconfig", body, configClient, "getConfig")
            ?: return null
        return try {
            val json = JSONObject(rawText)
            json.optString("typing_ticket", "").ifEmpty { null }
        } catch (e: Exception) {
            XLog.e(TAG, "getConfig: parse error", e)
            null
        }
    }

    /**
     * Send typing indicator.
     * Corresponds to SDK sendTyping()
     */
    fun sendTyping(ilinkUserId: String, typingTicket: String, status: Int = TypingStatus.TYPING) {
        val body = JSONObject().apply {
            put("ilink_user_id", ilinkUserId)
            put("typing_ticket", typingTicket)
            put("status", status)
            put("base_info", JSONObject().put("channel_version", CHANNEL_VERSION))
        }
        apiFetch("ilink/bot/sendtyping", body, configClient, "sendTyping")
    }

    // ==================== QR Code Login (login-qr.ts) ====================

    /**
     * Get the login QR code.
     */
    fun getQrCode(): QrCodeResult? {
        return try {
            val url = "$DEFAULT_BASE_URL/ilink/bot/get_bot_qrcode?bot_type=$DEFAULT_ILINK_BOT_TYPE"
            val request = Request.Builder().url(url).get().build()
            val response = apiClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()
            if (body.isEmpty()) return null
            val json = JSONObject(body)
            val qrcode = json.optString("qrcode", "")
            val imgContent = json.optString("qrcode_img_content", "")
            if (qrcode.isEmpty()) return null
            QrCodeResult(qrcode = qrcode, qrcodeImgContent = imgContent)
        } catch (e: Exception) {
            XLog.e(TAG, "getQrCode exception", e)
            null
        }
    }

    /**
     * Poll QR code scan status.
     * Corresponds to SDK pollQRStatus(), a long-polling interface.
     */
    fun pollQrCodeStatus(qrcode: String): AuthResult? {
        return try {
            val url = "$DEFAULT_BASE_URL/ilink/bot/get_qrcode_status?qrcode=$qrcode"
            val request = Request.Builder()
                .url(url)
                .addHeader("iLink-App-ClientVersion", "1")
                .get()
                .build()
            val response = longPollClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()
            if (body.isEmpty()) return null
            val json = JSONObject(body)
            val status = json.optString("status", "")
            XLog.d(TAG, "QR status: $status")
            if (status == "confirmed") {
                AuthResult(
                    botToken = json.getString("bot_token"),
                    baseUrl = json.optString("baseurl", DEFAULT_BASE_URL),
                    botId = json.optString("ilink_bot_id", "").ifEmpty { null },
                    userId = json.optString("ilink_user_id", "").ifEmpty { null }
                )
            } else null
        } catch (_: java.net.SocketTimeoutException) {
            XLog.d(TAG, "QR poll timeout, retrying")
            null
        } catch (e: Exception) {
            XLog.e(TAG, "pollQrCodeStatus exception", e)
            null
        }
    }
}
