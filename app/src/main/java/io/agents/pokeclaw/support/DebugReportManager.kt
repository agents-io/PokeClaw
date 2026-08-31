// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.support

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.BuildConfig
import io.agents.pokeclaw.agent.llm.LocalBackendHealth
import io.agents.pokeclaw.agent.llm.LocalModelManager
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.service.AutoReplyManager
import io.agents.pokeclaw.utils.AppLogStore
import io.agents.pokeclaw.utils.KVUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DebugReportManager {

    private const val REPORT_DIR = "debug_reports"
    private const val LOGCAT_LINES = "400"
    private const val MAX_HTTP_LOGS = 5

    fun buildReport(context: Context): File {
        val reportDir = File(context.cacheDir, REPORT_DIR).apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = File(reportDir, "pokeclaw-debug-$timestamp.zip")

        ZipOutputStream(FileOutputStream(output)).use { zip ->
            addText(zip, "summary.txt", buildSummary(context))
            addText(zip, "bug-report-template.txt", buildBugReportTemplate(context))
            collectLogcat().takeIf { it.isNotBlank() }?.let { addText(zip, "app-logcat.txt", it) }
            addRecentAppLogs(zip, context)
            addRecentHttpLogs(zip, context.cacheDir)
        }

        return output
    }

    private fun buildSummary(context: Context): String {
        val capabilities = AppCapabilityCoordinator.snapshot(context)
        val config = ModelConfigRepository.snapshot()
        val httpDir = File(context.cacheDir, "http_logs")
        val httpLogs = httpDir.listFiles()?.size ?: 0
        val appLogs = AppLogStore.listLogFiles(context).size
        val modelStorage = LocalModelManager.storageDiagnostics(context)
        val autoReplyManager = AutoReplyManager.getInstance()
        val monitorTargets = autoReplyManager.monitoredTargets.joinToString(", ") { it.displayLabel }
        val cpuSafeAt = KVUtils.getLocalCpuSafeAt()
        val gpuVerifiedAt = KVUtils.getLocalGpuVerifiedAt()
        val gpuRearmEligible = LocalBackendHealth.shouldRearmVerifiedGpu(
            isCpuSafeModeEnabled = LocalBackendHealth.isCpuSafeModeEnabled(),
            hasVerifiedGpuSuccess = LocalBackendHealth.hasVerifiedGpuSuccess(),
            hasPendingGpuInitMarker = LocalBackendHealth.hasPendingGpuInitMarker(),
            cpuSafeReason = LocalBackendHealth.cpuSafeReason(),
            cpuSafeAtMs = cpuSafeAt,
            nowMs = System.currentTimeMillis(),
        )
        return buildString {
            appendLine("Saathi Debug Report")
            appendLine("Generated: ${Date()}")
            appendLine()
            appendLine("App")
            appendLine("- Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("- Debug build: ${BuildConfig.DEBUG}")
            appendLine("- Package: ${BuildConfig.APPLICATION_ID}")
            appendLine()
            appendLine("Device")
            appendLine("- Manufacturer: ${Build.MANUFACTURER}")
            appendLine("- Model: ${Build.MODEL}")
            appendLine("- Device: ${Build.DEVICE}")
            appendLine("- Hardware: ${Build.HARDWARE}")
            appendLine("- Fingerprint: ${Build.FINGERPRINT}")
            appendLine("- Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("- Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("- RAM (total): ${getDeviceRamGb(context)} GB")
            appendLine()
            appendLine("Local Inference Runtime (#41 / #14 diagnostics)")
            val openClPaths = detectOpenClLibraryPaths()
            appendLine("- OpenCL libraries found: ${if (openClPaths.isEmpty()) "(none) — GPU path will not work" else openClPaths.joinToString(", ")}")
            appendLine("- Backend health: ${LocalBackendHealth.debugStateSummary()}")
            appendLine()
            appendLine("Capabilities")
            appendLine("- Accessibility: ${capabilities.accessibilityStatusLabel}")
            appendLine("- Notification access: ${capabilities.notificationAccessStatusLabel}")
            appendLine("- Notification permission: ${capabilities.notificationPermissionStatusLabel}")
            appendLine("- Overlay: ${if (capabilities.overlayGranted) "Enabled" else "Disabled"}")
            appendLine("- Battery optimization: ${if (capabilities.batteryOptimizationIgnored) "Unrestricted" else "Restricted"}")
            appendLine("- Foreground service: ${if (capabilities.foregroundServiceRunning) "Running" else "Stopped"}")
            appendLine("- Accessibility last connected: ${formatEpoch(KVUtils.getAccessibilityLastConnectedAt())}")
            appendLine("- Accessibility last heartbeat: ${formatEpoch(KVUtils.getAccessibilityLastHeartbeatAt())}")
            appendLine("- Accessibility last interrupted: ${formatEpoch(KVUtils.getAccessibilityLastInterruptedAt())}")
            appendLine("- Accessibility last disconnected: ${formatEpoch(KVUtils.getAccessibilityLastDisconnectedAt())}")
            appendLine("- Notification listener last connected: ${formatEpoch(KVUtils.getNotificationListenerLastConnectedAt())}")
            appendLine("- Notification listener last disconnected: ${formatEpoch(KVUtils.getNotificationListenerLastDisconnectedAt())}")
            appendLine()
            appendLine("LLM")
            appendLine("- Active mode: ${config.activeMode}")
            appendLine("- Active cloud model: ${config.activeCloud.modelName.ifBlank { "(none)" }}")
            appendLine("- Default local model: ${config.local.displayName.ifBlank { "(none)" }}")
            appendLine("- Local path: ${config.local.modelPath.ifBlank { "(none)" }}")
            appendLine("- Local backend preference: ${config.local.backendPreference.ifBlank { "(default)" }}")
            appendLine("- Local backend device key: ${LocalBackendHealth.currentDeviceKey()}")
            appendLine("- Local backend device descriptor: ${LocalBackendHealth.debugDeviceDescriptor()}")
            appendLine("- CPU-safe mode: ${if (LocalBackendHealth.isCpuSafeModeEnabled()) "Enabled" else "Disabled"}")
            appendLine("- CPU-safe reason: ${LocalBackendHealth.cpuSafeReason().ifBlank { "(none)" }}")
            appendLine("- CPU-safe set at: ${formatEpoch(cpuSafeAt)}")
            appendLine("- Conservative CPU-first suggested: ${if (LocalBackendHealth.isConservativeCpuModeSuggested()) "Yes" else "No"}")
            appendLine("- GPU already verified healthy: ${if (LocalBackendHealth.hasVerifiedGpuSuccess()) "Yes" else "No"}")
            appendLine("- GPU verified at: ${formatEpoch(gpuVerifiedAt)}")
            appendLine("- GPU re-arm eligible now: ${if (gpuRearmEligible) "Yes" else "No"}")
            appendLine("- Pending GPU init marker: ${if (LocalBackendHealth.hasPendingGpuInitMarker()) "Present" else "None"}")
            appendLine()
            appendLine("Local model storage")
            appendLine("- Selected model dir: ${modelStorage.selectedDir ?: "(none)"}")
            appendLine("- Selected model dir available bytes: ${modelStorage.selectedAvailableBytes?.toString() ?: "(unknown)"}")
            appendLine("- Selected model dir error: ${modelStorage.selectedError ?: "(none)"}")
            appendLine("- External model dir: ${modelStorage.externalDir}")
            appendLine("- External model dir status: ${modelStorage.externalStatus}")
            appendLine("- Internal model dir: ${modelStorage.internalDir}")
            appendLine("- Internal model dir status: ${modelStorage.internalStatus}")
            appendLine()
            appendLine("Auto-reply")
            appendLine("- Enabled: ${if (autoReplyManager.isEnabled) "Yes" else "No"}")
            appendLine("- Targets: ${monitorTargets.ifBlank { "(none)" }}")
            appendLine()
            appendLine("Artifacts")
            appendLine("- App rolling logs present: $appLogs")
            appendLine("- HTTP log files present: $httpLogs")
        }
    }

    private fun buildBugReportTemplate(context: Context): String {
        return buildString {
            appendLine("PokeClaw Bug Report Template")
            appendLine()
            appendLine("Attach this ZIP and fill in the blanks below:")
            appendLine()
            appendLine("- What happened:")
            appendLine("- What you expected instead:")
            appendLine("- Exact steps to reproduce:")
            appendLine("- Does it happen every time, or only sometimes?")
            appendLine("- Device model + ROM/build:")
            appendLine("- App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            appendLine("If this looks device-specific and you have ADB available, add:")
            appendLine("adb logcat -c")
            appendLine("adb logcat -v time > pokeclaw-logcat.txt")
            appendLine("# reproduce once, then stop with Ctrl+C")
            appendLine("adb shell dumpsys activity top > pokeclaw-activity-top.txt")
            appendLine("adb shell dumpsys activity services io.agents.pokeclaw > pokeclaw-services.txt")
            appendLine()
            appendLine("Open a new GitHub issue: https://github.com/agents-io/PokeClaw/issues/new")
            appendLine("Built on: ${Date()}")
            appendLine("Package: ${context.packageName}")
        }
    }

    private fun collectLogcat(): String {
        return runCatching {
            val process = ProcessBuilder(
                "logcat",
                "-d",
                "-v",
                "threadtime",
                "-t",
                LOGCAT_LINES,
                "ClawA11yService:V",
                "ClawNotifListener:V",
                "AutoReplyManager:V",
                "ForegroundService:V",
                "LocalBackendHealth:V",
                "LocalModelManager:V",
                "EngineHolder:V",
                "LocalModelRuntime:V",
                "InputTextTool:V",
                "SendMessageTool:V",
                "*:S",
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { "Failed to collect logcat: ${it.message}" }
    }

    private fun addRecentHttpLogs(zip: ZipOutputStream, cacheDir: File) {
        val httpDir = File(cacheDir, "http_logs")
        val files = httpDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_HTTP_LOGS)
            ?: return
        for (file in files) {
            addFile(zip, "http_logs/${file.name}", file)
        }
    }

    private fun addRecentAppLogs(zip: ZipOutputStream, context: Context) {
        val files = AppLogStore.listLogFiles(context)
        for (file in files) {
            addFile(zip, "app_logs/${file.name}", file)
        }
    }

    private fun addText(zip: ZipOutputStream, entryName: String, content: String) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun addFile(zip: ZipOutputStream, entryName: String, file: File) {
        if (!file.exists() || !file.isFile) return
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun formatEpoch(value: Long): String {
        if (value <= 0L) return "(none)"
        return Date(value).toString()
    }

    /** Returns total device RAM in GB (rounded up). Used in debug summary for GPU/model RAM
     *  sizing diagnostics (#41 / #14 — OEM bug reporters need to know real RAM vs minRamGb). */
    private fun getDeviceRamGb(context: Context): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.totalMem / (1024L * 1024L * 1024L)).toInt() + 1
        } catch (_: Throwable) {
            -1
        }
    }

    /** Probes well-known Android OpenCL driver paths. If none exist the GPU LiteRT path will
     *  fail at first inference (this is the root cause for issue #14 and the Pixel-8-Pro
     *  GPU→CPU fallback documented in #41). Returning the actual paths found makes triage
     *  trivial for non-Pixel OEM reporters. */
    private fun detectOpenClLibraryPaths(): List<String> {
        val candidates = listOf(
            "/system/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib/libOpenCL.so",
            "/vendor/lib64/libOpenCL.so",
            "/vendor/lib/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
            "/system/lib/libOpenCL.so",
        )
        return candidates.filter {
            runCatching { File(it).exists() }.getOrDefault(false)
        }
    }
}
