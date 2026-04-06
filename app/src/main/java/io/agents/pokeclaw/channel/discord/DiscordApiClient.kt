// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

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
 * Discord REST API client
 * Used for sending messages, uploading images and files
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
     * Send a text message to the specified channel
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
     * Send an image to the specified channel (multipart upload)
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
     * Send a file to the specified channel (multipart upload)
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
                    XLog.w(TAG, "Request failed (${attempt + 1}/$MAX_RETRIES): ${e.message}, retrying in ${delay}ms")
                    try { Thread.sleep(delay) } catch (_: InterruptedException) {}
                    executeRequestWithRetry(request, callback, attempt + 1)
                } else {
                    XLog.e(TAG, "Request failed (max retries reached): ${e.message}")
                    callback?.onFailure(e.message ?: "Request failed")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    callback?.onSuccess(responseBody)
                } else {
                    XLog.e(TAG, "Request failed: HTTP ${response.code} $responseBody")
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
