// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.wechat

import io.agents.pokeclaw.utils.XLog
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * CDN 加密上传/下载。
 * 严格对应官方 @tencent-weixin/openclaw-weixin@1.0.2 的:
 * - src/cdn/aes-ecb.ts
 * - src/cdn/cdn-url.ts
 * - src/cdn/cdn-upload.ts
 * - src/cdn/upload.ts
 * - src/cdn/pic-decrypt.ts
 */
object WeChatCdn {

    private const val TAG = "WeChatCdn"
    private const val UPLOAD_MAX_RETRIES = 3

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ==================== AES-128-ECB (aes-ecb.ts) ====================

    fun encryptAesEcb(plaintext: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plaintext)
    }

    fun decryptAesEcb(ciphertext: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(ciphertext)
    }

    /** PKCS7 填充后的密文大小 */
    fun aesEcbPaddedSize(plaintextSize: Int): Int {
        return ((plaintextSize + 1 + 15) / 16) * 16
    }

    // ==================== CDN URL (cdn-url.ts) ====================

    fun buildCdnUploadUrl(cdnBaseUrl: String, uploadParam: String, filekey: String): String {
        return "$cdnBaseUrl/upload?encrypted_query_param=${
            java.net.URLEncoder.encode(uploadParam, "UTF-8")
        }&filekey=${java.net.URLEncoder.encode(filekey, "UTF-8")}"
    }

    fun buildCdnDownloadUrl(encryptedQueryParam: String, cdnBaseUrl: String): String {
        return "$cdnBaseUrl/download?encrypted_query_param=${
            java.net.URLEncoder.encode(encryptedQueryParam, "UTF-8")
        }"
    }

    // ==================== CDN 上传 (cdn-upload.ts) ====================

    /**
     * 加密并上传到 CDN，返回 downloadParam（x-encrypted-param 响应头）。
     * 最多重试 UPLOAD_MAX_RETRIES 次，4xx 立即中止。
     */
    fun uploadBufferToCdn(
        plaintext: ByteArray,
        uploadParam: String,
        filekey: String,
        cdnBaseUrl: String,
        aesKey: ByteArray
    ): String? {
        val ciphertext = encryptAesEcb(plaintext, aesKey)
        val cdnUrl = buildCdnUploadUrl(cdnBaseUrl, uploadParam, filekey)
        XLog.d(TAG, "CDN POST: size=${ciphertext.size}")

        var downloadParam: String? = null
        for (attempt in 1..UPLOAD_MAX_RETRIES) {
            try {
                val request = Request.Builder()
                    .url(cdnUrl)
                    .post(ciphertext.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    .build()
                val response = httpClient.newCall(request).execute()
                val code = response.code
                val param = response.header("x-encrypted-param")
                val errMsg = response.header("x-error-message")
                response.close()

                if (code in 400..499) {
                    XLog.e(TAG, "CDN 4xx 错误, 中止: code=$code, err=$errMsg")
                    return null
                }
                if (code != 200) {
                    XLog.e(TAG, "CDN 服务器错误 attempt=$attempt: code=$code, err=$errMsg")
                    continue
                }
                if (param.isNullOrEmpty()) {
                    XLog.e(TAG, "CDN 缺少 x-encrypted-param attempt=$attempt")
                    continue
                }
                downloadParam = param
                XLog.d(TAG, "CDN 上传成功 attempt=$attempt")
                break
            } catch (e: Exception) {
                XLog.e(TAG, "CDN 上传异常 attempt=$attempt", e)
                if (attempt == UPLOAD_MAX_RETRIES) return null
            }
        }
        return downloadParam
    }

    // ==================== 上传流水线 (upload.ts uploadMediaToCdn) ====================

    /**
     * 完整的 CDN 上传流水线：
     * hash → genKey → getUploadUrl → encrypt+POST → return UploadedFileInfo
     */
    fun uploadMedia(
        plaintext: ByteArray,
        toUserId: String,
        mediaType: Int,
        apiClient: WeChatApiClient
    ): UploadedFileInfo? {
        val rawsize = plaintext.size
        val rawfilemd5 = md5Hex(plaintext)
        val filesize = aesEcbPaddedSize(rawsize)
        val filekey = randomHex(16)
        val aesKey = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val aeskeyHex = aesKey.toHexString()

        XLog.d(TAG, "uploadMedia: rawsize=$rawsize, filesize=$filesize, mediaType=$mediaType")

        // 1. getUploadUrl
        val uploadParam = apiClient.getUploadUrl(
            filekey = filekey,
            mediaType = mediaType,
            toUserId = toUserId,
            rawsize = rawsize,
            rawfilemd5 = rawfilemd5,
            filesize = filesize,
            aeskeyHex = aeskeyHex
        )
        if (uploadParam == null) {
            XLog.e(TAG, "getUploadUrl 未返回 upload_param")
            return null
        }

        // 2. 加密上传到 CDN
        val downloadParam = uploadBufferToCdn(
            plaintext = plaintext,
            uploadParam = uploadParam,
            filekey = filekey,
            cdnBaseUrl = CDN_BASE_URL,
            aesKey = aesKey
        )
        if (downloadParam == null) {
            XLog.e(TAG, "CDN 上传失败")
            return null
        }

        return UploadedFileInfo(
            filekey = filekey,
            downloadEncryptedQueryParam = downloadParam,
            aeskeyHex = aeskeyHex,
            fileSize = rawsize,
            fileSizeCiphertext = filesize
        )
    }

    // ==================== 下载解密 (pic-decrypt.ts) ====================

    /**
     * 解析 aes_key（兼容两种格式）。
     * 对应 pic-decrypt.ts parseAesKey:
     * - base64(raw 16 bytes) → images
     * - base64(hex string of 16 bytes) → file / voice / video
     */
    fun parseAesKey(aesKeyBase64: String): ByteArray? {
        return try {
            val decoded = android.util.Base64.decode(aesKeyBase64, android.util.Base64.DEFAULT)
            when {
                decoded.size == 16 -> decoded
                decoded.size == 32 && String(decoded).matches(Regex("[0-9a-fA-F]{32}")) -> {
                    hexToBytes(String(decoded))
                }
                else -> {
                    XLog.e(TAG, "parseAesKey: 无效长度 ${decoded.size}")
                    null
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "parseAesKey 异常", e)
            null
        }
    }

    /**
     * 下载并解密 CDN 媒体文件。
     */
    fun downloadAndDecrypt(
        encryptedQueryParam: String,
        aesKeyBase64: String,
        cdnBaseUrl: String
    ): ByteArray? {
        val key = parseAesKey(aesKeyBase64) ?: return null
        val url = buildCdnDownloadUrl(encryptedQueryParam, cdnBaseUrl)
        return try {
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            val encrypted = response.body?.bytes()
            response.close()
            if (encrypted == null) return null
            decryptAesEcb(encrypted, key)
        } catch (e: Exception) {
            XLog.e(TAG, "CDN 下载解密失败", e)
            null
        }
    }

    // ==================== 工具方法 ====================

    fun md5Hex(data: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(data).toHexString()
    }

    fun randomHex(bytes: Int): String {
        return ByteArray(bytes).also { SecureRandom().nextBytes(it) }.toHexString()
    }

    /** 对应 SDK 的 generateId(prefix) → "prefix:{timestamp}-{8-char-hex}" */
    fun generateId(prefix: String): String {
        val ts = System.currentTimeMillis()
        val hex = randomHex(4) // 4 bytes = 8 hex chars
        return "$prefix:$ts-$hex"
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
