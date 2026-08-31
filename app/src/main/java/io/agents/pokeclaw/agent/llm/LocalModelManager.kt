// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import android.content.Context
import android.os.StatFs
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
        val minRamGb: Int,
        /** True when this model came from the user-supplied custom URL (#36).
         *  Custom models skip strict size-bound validation since we don't know
         *  the expected size up front. */
        val isCustom: Boolean = false
    )

    data class DeviceSupport(
        val deviceRamGb: Int,
        val minimumBuiltInRamGb: Int,
        val bestSupportedModel: ModelInfo?,
    )

    data class CatalogEntry(
        val model: ModelInfo,
        val isDownloaded: Boolean,
        val isSupported: Boolean,
        val path: String?,
    )

    data class ActiveModelState(
        val displayName: String,
        val metaText: String,
        val statusText: String,
        val statusKind: StatusKind,
    )

    enum class AvailabilitySource {
        MANAGED_DOWNLOAD,
        LINKED_FILE,
        MISSING,
    }

    data class ModelAvailability(
        val isAvailable: Boolean,
        val source: AvailabilitySource,
    )

    enum class StatusKind {
        READY,
        WARNING,
        NEUTRAL,
    }

    val AVAILABLE_MODELS = listOf(
        ModelInfo(
            id = "gemma4-e2b",
            displayName = "Gemma 4 E2B — 2.6GB",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_qualcomm_qcs8275.litertlm",
            fileName = "gemma-4-E2B-it_qualcomm_qcs8275.litertlm",
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

    fun deviceSupport(context: Context): DeviceSupport {
        val deviceRamGb = getDeviceRamGb(context)
        return DeviceSupport(
            deviceRamGb = deviceRamGb,
            minimumBuiltInRamGb = AVAILABLE_MODELS.minOf { it.minRamGb },
            bestSupportedModel = AVAILABLE_MODELS
                .filter { it.minRamGb <= deviceRamGb }
                .maxByOrNull { it.minRamGb }
        )
    }

    fun bestSupportedModel(context: Context): ModelInfo? {
        return deviceSupport(context).bestSupportedModel
    }

    fun isModelSupportedOnDevice(context: Context, model: ModelInfo): Boolean {
        return deviceSupport(context).deviceRamGb >= model.minRamGb
    }

    fun catalog(context: Context): List<CatalogEntry> {
        val support = deviceSupport(context)
        val builtIns = AVAILABLE_MODELS.map { model ->
            CatalogEntry(
                model = model,
                isDownloaded = isModelDownloaded(context, model),
                isSupported = model.minRamGb <= support.deviceRamGb,
                path = getModelPath(context, model),
            )
        }
        val custom = customModel()?.let { model ->
            listOf(
                CatalogEntry(
                    model = model,
                    isDownloaded = isModelDownloaded(context, model),
                    isSupported = true, // user opted in — don't gate on RAM heuristic
                    path = getModelPath(context, model),
                )
            )
        } ?: emptyList()
        return builtIns + custom
    }

    /** Returns a synthetic ModelInfo for the user's custom URL (#36), or null if not set.
     *  fileName is derived from the URL's last path segment; sizeBytes is unknown (0)
     *  and validation falls back to a 1MB minimum (see isValidModelFile). */
    fun customModel(): ModelInfo? {
        val url = io.agents.pokeclaw.utils.KVUtils.getCustomLocalModelUrl()
        if (url.isBlank()) return null
        val fileName = url.substringAfterLast('/').ifBlank { "custom-model.bin" }
            .let { name ->
                // Defensive: strip query string if present
                val q = name.indexOf('?')
                if (q > 0) name.substring(0, q) else name
            }
        return ModelInfo(
            id = "custom-local",
            displayName = "Custom: $fileName",
            url = url,
            fileName = fileName,
            sizeBytes = 0L,
            minRamGb = 0,
            isCustom = true,
        )
    }

    fun configuredBuiltInModel(localConfig: LocalModelConfig): ModelInfo? {
        return AVAILABLE_MODELS.find { matchesConfiguredModel(it, localConfig) }
    }

    fun availabilityForModel(
        context: Context,
        model: ModelInfo,
        localConfig: LocalModelConfig? = null
    ): ModelAvailability {
        if (isModelDownloaded(context, model)) {
            return ModelAvailability(
                isAvailable = true,
                source = AvailabilitySource.MANAGED_DOWNLOAD,
            )
        }

        val config = localConfig ?: return ModelAvailability(
            isAvailable = false,
            source = AvailabilitySource.MISSING,
        )

        val linkedFileExists = config.modelPath.isNotBlank() && File(config.modelPath).exists()
        if (linkedFileExists && matchesConfiguredModel(model, config)) {
            return ModelAvailability(
                isAvailable = true,
                source = AvailabilitySource.LINKED_FILE,
            )
        }

        return ModelAvailability(
            isAvailable = false,
            source = AvailabilitySource.MISSING,
        )
    }

    fun resolveActiveModelState(context: Context, localConfig: LocalModelConfig): ActiveModelState {
        val modelPath = localConfig.modelPath
        if (modelPath.isBlank()) {
            return ActiveModelState(
                displayName = "No model selected",
                metaText = "Download a model below",
                statusText = "● Not configured",
                statusKind = StatusKind.NEUTRAL,
            )
        }

        val matchedModel = configuredBuiltInModel(localConfig)
        if (matchedModel != null) {
            val availability = availabilityForModel(context, matchedModel, localConfig)
            return ActiveModelState(
                displayName = matchedModel.displayName,
                metaText = "${matchedModel.fileName} · On-device",
                statusText = when (availability.source) {
                    AvailabilitySource.MANAGED_DOWNLOAD -> "● Ready"
                    AvailabilitySource.LINKED_FILE -> "● Ready"
                    AvailabilitySource.MISSING -> "● Missing file"
                },
                statusKind = if (availability.isAvailable) StatusKind.READY else StatusKind.WARNING,
            )
        }

        return ActiveModelState(
            displayName = localConfig.displayName.ifBlank { File(modelPath).nameWithoutExtension },
            metaText = "On-device",
            statusText = if (File(modelPath).exists()) "● Ready" else "● Missing file",
            statusKind = if (File(modelPath).exists()) StatusKind.READY else StatusKind.WARNING,
        )
    }

    interface DownloadCallback {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long)
        fun onComplete(modelPath: String)
        fun onError(error: String)
    }

    data class ModelStorageDiagnostics(
        val selectedDir: String?,
        val selectedAvailableBytes: Long?,
        val selectedError: String?,
        val externalDir: String,
        val externalStatus: String,
        val internalDir: String,
        val internalStatus: String,
    )

    /**
     * Get the directory where models are stored.
     */
    fun getModelDir(context: Context): File {
        return resolveUsableModelDir(
            externalRoot = context.getExternalFilesDir(null),
            internalRoot = context.filesDir,
        )
    }

    internal fun resolveUsableModelDir(
        externalRoot: File?,
        internalRoot: File,
        canWriteDirectory: (File) -> Boolean = ::canWriteToDirectory,
    ): File {
        val externalDir = externalRoot?.let { File(it, "models") }
        if (externalDir != null && prepareModelDirectory(externalDir, canWriteDirectory)) {
            return externalDir
        }

        val internalDir = File(internalRoot, "models")
        if (prepareModelDirectory(internalDir, canWriteDirectory)) {
            return internalDir
        }

        throw IllegalStateException(
            "Could not create model storage directory at ${externalDir?.absolutePath ?: "(no external dir)"} or ${internalDir.absolutePath}"
        )
    }

    fun storageDiagnostics(context: Context): ModelStorageDiagnostics {
        val externalDir = context.getExternalFilesDir(null)?.let { File(it, "models") }
        val internalDir = File(context.filesDir, "models")
        val selected = runCatching { getModelDir(context) }

        return ModelStorageDiagnostics(
            selectedDir = selected.getOrNull()?.absolutePath,
            selectedAvailableBytes = selected.getOrNull()?.let { availableBytes(it) },
            selectedError = selected.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" },
            externalDir = externalDir?.absolutePath ?: "(no external files dir)",
            externalStatus = describeModelDirectory(externalDir),
            internalDir = internalDir.absolutePath,
            internalStatus = describeModelDirectory(internalDir),
        )
    }

    private fun prepareModelDirectory(dir: File, canWriteDirectory: (File) -> Boolean): Boolean {
        if (!ensureDirectory(dir)) {
            logWarning("Model directory is not usable: could not create ${dir.absolutePath}")
            return false
        }
        if (!canWriteDirectory(dir)) {
            logWarning("Model directory is not usable: write probe failed for ${dir.absolutePath}")
            return false
        }
        return true
    }

    private fun ensureDirectory(dir: File): Boolean {
        return dir.isDirectory || dir.mkdirs() || dir.isDirectory
    }

    private fun canWriteToDirectory(dir: File): Boolean {
        val probe = File(dir, ".pokeclaw-write-probe")
        return runCatching {
            FileOutputStream(probe, false).use { output ->
                output.write(1)
            }
            if (probe.exists() && !probe.delete()) {
                logWarning("Could not delete model storage probe: ${probe.absolutePath}")
            }
            true
        }.getOrElse { e ->
            logWarning("Model storage write probe failed: ${dir.absolutePath}", e)
            false
        }
    }

    private fun logWarning(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable == null) {
                XLog.w(TAG, message)
            } else {
                XLog.w(TAG, message, throwable)
            }
        }
    }

    private fun describeModelDirectory(dir: File?): String {
        if (dir == null) return "unavailable"
        val stat = runCatching { StatFs(dir.absolutePath).availableBytes }
        return listOf(
            "exists=${dir.exists()}",
            "isDirectory=${dir.isDirectory}",
            "canRead=${dir.canRead()}",
            "canWrite=${dir.canWrite()}",
            "availableBytes=${stat.getOrNull() ?: "(unknown)"}",
            "statError=${stat.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "(none)"}",
        ).joinToString(", ")
    }

    private fun availableBytes(dir: File): Long? {
        return runCatching { StatFs(dir.absolutePath).availableBytes }.getOrNull()
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

    private fun matchesConfiguredModel(model: ModelInfo, localConfig: LocalModelConfig): Boolean {
        if (localConfig.modelId.equals(model.id, ignoreCase = true)) return true

        val modelPath = localConfig.modelPath.lowercase()
        if (modelPath.endsWith(model.fileName.lowercase())) return true

        val display = localConfig.displayName.lowercase()
        return builtInAliases(model).any { alias ->
            modelPath.contains(alias) || display.contains(alias)
        }
    }

    private fun builtInAliases(model: ModelInfo): List<String> {
        return when (model.id) {
            "gemma4-e2b" -> listOf(
                "gemma4-e2b",
                "gemma-4-e2b",
                "gemma 4 e2b",
                "gemma4_2b",
                "gemma-4-2b",
                "gemma 4 2b",
                "qualcomm_qcs8275",
                "qcs8275",
            )
            "gemma4-e4b" -> listOf(
                "gemma4-e4b",
                "gemma-4-e4b",
                "gemma 4 e4b",
                "gemma4_4b",
                "gemma-4-4b",
                "gemma 4 4b",
            )
            else -> emptyList()
        }
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
        val modelDir = try {
            getModelDir(context)
        } catch (e: Exception) {
            XLog.e(TAG, "Could not prepare model storage", e)
            callback.onError("Could not prepare model storage: ${e.message}")
            return
        }
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
        // Custom user-supplied models have unknown expected size — accept anything
        // larger than 1MB (anything smaller is almost certainly not a real model).
        if (model.isCustom) return length >= 1_048_576L
        return length in expectedLowerBound(model)..expectedUpperBound(model)
    }

    private fun expectedLowerBound(model: ModelInfo): Long {
        return (model.sizeBytes - maxOf(SIZE_TOLERANCE_BYTES, model.sizeBytes / 20)).coerceAtLeast(1L)
    }

    private fun expectedUpperBound(model: ModelInfo): Long {
        return model.sizeBytes + maxOf(SIZE_TOLERANCE_BYTES, model.sizeBytes / 20)
    }
}
