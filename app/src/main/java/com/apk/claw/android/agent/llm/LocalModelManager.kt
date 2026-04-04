package com.apk.claw.android.agent.llm

import android.content.Context
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
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
            id = "functiongemma-270m-mobile",
            displayName = "FunctionGemma 270M - Mobile Actions (289MB)",
            url = "https://huggingface.co/gue22/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/main/mobile-actions_q8_ekv1024.litertlm",
            fileName = "mobile-actions_q8_ekv1024.litertlm",
            sizeBytes = 289_000_000L,
            minRamGb = 6
        ),
        ModelInfo(
            id = "gemma3-1b-int4",
            displayName = "Gemma 3 1B INT4 (~500MB)",
            url = "https://huggingface.co/lotapa/gemma3-1b-it-int4.litertlm/resolve/main/gemma3-1b-it-int4.litertlm",
            fileName = "gemma3-1b-it-int4.litertlm",
            sizeBytes = 500_000_000L,
            minRamGb = 6
        ),
        ModelInfo(
            id = "qwen3-06b",
            displayName = "Qwen3 0.6B (~300MB)",
            url = "https://huggingface.co/emmkei/Qwen3-0.6B.litertlm/resolve/main/Qwen3-0.6B.litertlm",
            fileName = "Qwen3-0.6B.litertlm",
            sizeBytes = 300_000_000L,
            minRamGb = 6
        ),
        ModelInfo(
            id = "gemma4-e2b",
            displayName = "Gemma 4 E2B-IT (2.6GB, needs 8GB RAM)",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_580_000_000L,
            minRamGb = 8
        )
    )

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
        return file.exists() && file.length() > 0
    }

    /**
     * Get the path to a downloaded model.
     */
    fun getModelPath(context: Context, model: ModelInfo): String? {
        val file = File(getModelDir(context), model.fileName)
        return if (file.exists() && file.length() > 0) file.absolutePath else null
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

            val totalBytes = if (response.code == 206) {
                // Partial content — total size from Content-Range header
                val contentRange = response.header("Content-Range")
                contentRange?.substringAfterLast("/")?.toLongOrNull() ?: model.sizeBytes
            } else {
                response.body?.contentLength()?.let { it + existingBytes } ?: model.sizeBytes
            }

            val body = response.body ?: run {
                callback.onError("Empty response body")
                return
            }

            val outputStream = FileOutputStream(tempFile, existingBytes > 0)
            val buffer = ByteArray(8192)
            var downloadedBytes = existingBytes
            var lastReportTime = System.currentTimeMillis()
            var lastReportedBytes = existingBytes

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

            // Rename temp to final
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)

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
}
