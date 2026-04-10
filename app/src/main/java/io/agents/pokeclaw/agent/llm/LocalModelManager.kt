// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import android.content.Context
import android.os.StatFs
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages on-device LLM model downloads and storage.
 *
 * Models are downloaded from HuggingFace and stored in the app's
 * external files directory for persistence across app restarts.
 */
object LocalModelManager {

    private const val TAG = "LocalModelManager"
    private const val SIZE_TOLERANCE_BYTES = 32L * 1024L * 1024L

    /** Available models for download */
    data class ModelInfo(
        val id: String,
        val displayName: String,
        val url: String,
        val fileName: String,
        val sizeBytes: Long,
        val minRamGb: Int
    )

    val AVAILABLE_MODELS = listOf(
        ModelInfo(
            id = "gemma4-e2b",
            displayName = "Gemma 4 E2B — 2.6GB",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_580_000_000L,
            minRamGb = 8
        ),
        ModelInfo(
            id = "gemma4-e4b",
            displayName = "Gemma 4 E4B — 3.6GB",
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_650_000_000L,
            minRamGb = 10
        ),
    )

    /**
     * Pick the best model for this device based on available RAM.
     * Devices with 12GB+ RAM get E4B, everyone else gets E2B.
     */
    fun recommendedModel(context: Context): ModelInfo {
        val totalRamGb = getDeviceRamGb(context)
        return if (totalRamGb >= 12) {
            AVAILABLE_MODELS.first { it.id == "gemma4-e4b" }
        } else {
            AVAILABLE_MODELS.first { it.id == "gemma4-e2b" }
        }
    }

    fun getDeviceRamGb(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024L * 1024L * 1024L)).toInt() + 1
    }

    fun bestSupportedModel(context: Context): ModelInfo? {
        val deviceRamGb = getDeviceRamGb(context)
        return AVAILABLE_MODELS
            .filter { it.minRamGb <= deviceRamGb }
            .maxByOrNull { it.minRamGb }
    }

    fun isModelSupportedOnDevice(context: Context, model: ModelInfo): Boolean {
        return getDeviceRamGb(context) >= model.minRamGb
    }

    interface DownloadCallback {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long)
        fun onComplete(modelPath: String)
        fun onError(error: String)
    }

    /**
     * Get the directory where models are stored.
     */
    fun getModelDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Check if a model is already downloaded.
     */
    fun isModelDownloaded(context: Context, model: ModelInfo): Boolean {
        val file = File(getModelDir(context), model.fileName)
        return isValidModelFile(file, model)
    }

    /**
     * Get the path to a downloaded model.
     */
    fun getModelPath(context: Context, model: ModelInfo): String? {
        val file = File(getModelDir(context), model.fileName)
        return if (isValidModelFile(file, model)) file.absolutePath else null
    }

    /**
     * Download a model from HuggingFace with progress reporting.
     * Supports resume via HTTP Range headers for partial downloads.
     *
     * Must be called from a background thread.
     */
    fun downloadModel(
        context: Context,
        model: ModelInfo,
        callback: DownloadCallback
    ) {
        val modelDir = getModelDir(context)
        val targetFile = File(modelDir, model.fileName)
        val tempFile = File(modelDir, "${model.fileName}.downloading")
        cleanupInvalidFiles(model, targetFile, tempFile)

        // Check free space before starting download
        try {
            val stat = StatFs(modelDir.absolutePath)
            val availableBytes = stat.availableBytes
            val existingTempBytes = if (tempFile.exists()) tempFile.length() else 0L
            val bytesNeeded = model.sizeBytes - existingTempBytes
            if (bytesNeeded > 0 && availableBytes < bytesNeeded) {
                val needGb = String.format("%.1f", bytesNeeded / 1_000_000_000.0)
                val haveGb = String.format("%.1f", availableBytes / 1_000_000_000.0)
                XLog.e(TAG, "Not enough storage: need ${needGb}GB, have ${haveGb}GB available")
                callback.onError("Not enough storage: need ${needGb} GB free, only ${haveGb} GB available")
                return
            }
            XLog.d(TAG, "Storage check passed: need ${bytesNeeded / 1_000_000}MB, have ${availableBytes / 1_000_000}MB")
        } catch (e: Exception) {
            XLog.w(TAG, "Could not check storage, proceeding anyway", e)
        }

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            // Support resume
            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

            val requestBuilder = Request.Builder().url(model.url)
            if (existingBytes > 0) {
                requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                XLog.i(TAG, "Resuming download from byte $existingBytes")
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful && response.code != 206) {
                callback.onError("Download failed: HTTP ${response.code}")
                return
            }

            val isResumedResponse = existingBytes > 0 && response.code == 206
            if (existingBytes > 0 && !isResumedResponse) {
                XLog.w(TAG, "Server ignored Range request for ${model.fileName}; restarting download from scratch")
                tempFile.delete()
            }

            val totalBytes = if (isResumedResponse) {
                // Partial content — total size from Content-Range header
                val contentRange = response.header("Content-Range")
                contentRange?.substringAfterLast("/")?.toLongOrNull() ?: model.sizeBytes
            } else {
                response.body?.contentLength() ?: model.sizeBytes
            }

            val body = response.body ?: run {
                callback.onError("Empty response body")
                return
            }

            val startingBytes = if (isResumedResponse) existingBytes else 0L
            val outputStream = FileOutputStream(tempFile, isResumedResponse)
            val buffer = ByteArray(8192)
            var downloadedBytes = startingBytes
            var lastReportTime = System.currentTimeMillis()
            var lastReportedBytes = startingBytes

            body.byteStream().use { input ->
                outputStream.use { output ->
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastReportTime >= 200) {
                            val elapsed = (now - lastReportTime) / 1000.0
                            val speed = ((downloadedBytes - lastReportedBytes) / elapsed).toLong()
                            callback.onProgress(downloadedBytes, totalBytes, speed)
                            lastReportTime = now
                            lastReportedBytes = downloadedBytes
                        }
                    }
                }
            }

            if (!isValidModelFile(tempFile, model)) {
                tempFile.delete()
                callback.onError("Downloaded file looks incomplete or corrupted. Please retry.")
                return
            }

            // Rename temp to final
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                callback.onError("Download finished but PokeClaw could not move the model into place")
                return
            }

            // Save model path to config
            KVUtils.setLocalModelPath(targetFile.absolutePath)

            XLog.i(TAG, "Model downloaded: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            callback.onComplete(targetFile.absolutePath)

        } catch (e: Exception) {
            XLog.e(TAG, "Download failed", e)
            callback.onError("Download failed: ${e.message}")
        }
    }

    /**
     * Delete a downloaded model to free space.
     */
    fun deleteModel(context: Context, model: ModelInfo): Boolean {
        val file = File(getModelDir(context), model.fileName)
        val tempFile = File(getModelDir(context), "${model.fileName}.downloading")
        tempFile.delete()
        return if (file.exists()) file.delete() else true
    }

    private fun cleanupInvalidFiles(model: ModelInfo, targetFile: File, tempFile: File) {
        if (targetFile.exists() && !isValidModelFile(targetFile, model)) {
            XLog.w(TAG, "Removing invalid completed model file: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            targetFile.delete()
        }
        if (tempFile.exists() && tempFile.length() > expectedUpperBound(model)) {
            XLog.w(TAG, "Removing oversized partial download: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            tempFile.delete()
        }
    }

    private fun isValidModelFile(file: File, model: ModelInfo): Boolean {
        if (!file.exists()) return false
        val length = file.length()
        if (length <= 0L) return false
        return length in expectedLowerBound(model)..expectedUpperBound(model)
    }

    private fun expectedLowerBound(model: ModelInfo): Long {
        return (model.sizeBytes - maxOf(SIZE_TOLERANCE_BYTES, model.sizeBytes / 20)).coerceAtLeast(1L)
    }

    private fun expectedUpperBound(model: ModelInfo): Long {
        return model.sizeBytes + maxOf(SIZE_TOLERANCE_BYTES, model.sizeBytes / 20)
    }
}
