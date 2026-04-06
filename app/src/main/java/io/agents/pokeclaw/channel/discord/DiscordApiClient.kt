package io.agents.pokeclaw.channel.discord

import io.agents.pokeclaw.utils.XLog
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Discord REST API 客户端
 * 用于发送消息、上传图片和文件
 */
class DiscordApiClient private constructor() {

    companion object {
        private const val TAG = "DiscordApiClient"
        private const val MAX_RETRIES = 2

        @Volatile
        private var instance: DiscordApiClient? = null

        @JvmStatic
        fun getInstance(): DiscordApiClient {
            return instance ?: synchronized(this) {
                instance ?: DiscordApiClient().also { instance = it }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val gson = Gson()
    private var botToken: String? = null

    fun init(token: String) {
        this.botToken = token
    }

    fun hasCredentials(): Boolean = !botToken.isNullOrEmpty()

    private fun authHeader(): String = "${DiscordConstants.AUTH_PREFIX}$botToken"

    /**
     * 发送文本消息到指定频道
     */
    fun sendMessage(channelId: String, content: String, callback: DiscordCallback<String>?) {
        val json = JsonObject().apply {
            addProperty("content", content)
        }
        val requestBody = gson.toJson(json)
            .toRequestBody(DiscordConstants.CONTENT_TYPE_JSON.toMediaType())

        val request = Request.Builder()
            .url("${DiscordConstants.API_BASE_URL}/channels/$channelId/messages")
            .post(requestBody)
            .addHeader(DiscordConstants.HEADER_AUTHORIZATION, authHeader())
            .addHeader(DiscordConstants.HEADER_CONTENT_TYPE, DiscordConstants.CONTENT_TYPE_JSON)
            .build()

        executeRequest(request, callback)
    }

    /**
     * 发送图片到指定频道（multipart 上传）
     */
    fun sendImage(channelId: String, imageBytes: ByteArray, filename: String = "image.png", callback: DiscordCallback<String>?) {
        val imageBody = imageBytes.toRequestBody("image/png".toMediaType())

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("files[0]", filename, imageBody)
            .build()

        val request = Request.Builder()
            .url("${DiscordConstants.API_BASE_URL}/channels/$channelId/messages")
            .post(multipartBody)
            .addHeader(DiscordConstants.HEADER_AUTHORIZATION, authHeader())
            .build()

        executeRequest(request, callback)
    }

    /**
     * 发送文件到指定频道（multipart 上传）
     */
    fun sendFile(channelId: String, fileBytes: ByteArray, filename: String, mimeType: String = "application/octet-stream", callback: DiscordCallback<String>?) {
        val fileBody = fileBytes.toRequestBody(mimeType.toMediaType())

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("files[0]", filename, fileBody)
            .build()

        val request = Request.Builder()
            .url("${DiscordConstants.API_BASE_URL}/channels/$channelId/messages")
            .post(multipartBody)
            .addHeader(DiscordConstants.HEADER_AUTHORIZATION, authHeader())
            .build()

        executeRequest(request, callback)
    }

    private fun executeRequest(request: Request, callback: DiscordCallback<String>?) {
        executeRequestWithRetry(request, callback, 0)
    }

    private fun executeRequestWithRetry(request: Request, callback: DiscordCallback<String>?, attempt: Int) {
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (attempt < MAX_RETRIES) {
                    val delay = (attempt + 1) * 1000L
                    XLog.w(TAG, "请求失败(${attempt + 1}/$MAX_RETRIES): ${e.message}，${delay}ms 后重试")
                    try { Thread.sleep(delay) } catch (_: InterruptedException) {}
                    executeRequestWithRetry(request, callback, attempt + 1)
                } else {
                    XLog.e(TAG, "请求失败(已达最大重试): ${e.message}")
                    callback?.onFailure(e.message ?: "请求失败")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    callback?.onSuccess(responseBody)
                } else {
                    XLog.e(TAG, "请求失败: HTTP ${response.code} $responseBody")
                    callback?.onFailure("HTTP ${response.code}: $responseBody")
                }
            }
        })
    }
}

interface DiscordCallback<T> {
    fun onSuccess(result: T)
    fun onFailure(error: String)
}
