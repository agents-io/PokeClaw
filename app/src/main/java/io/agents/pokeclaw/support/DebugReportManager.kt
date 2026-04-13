// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.support

import android.content.Context
import android.os.Build
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.BuildConfig
import io.agents.pokeclaw.agent.llm.LocalBackendHealth
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.service.AutoReplyManager
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
            collectLogcat().takeIf { it.isNotBlank() }?.let { addText(zip, "app-logcat.txt", it) }
            addRecentHttpLogs(zip, context.cacheDir)
        }

        return output
    }

    private fun buildSummary(context: Context): String {
        val capabilities = AppCapabilityCoordinator.snapshot(context)
        val config = ModelConfigRepository.snapshot()
        val httpDir = File(context.cacheDir, "http_logs")
        val httpLogs = httpDir.listFiles()?.size ?: 0
        val autoReplyManager = AutoReplyManager.getInstance()
        val monitorTargets = autoReplyManager.monitoredTargets.joinToString(", ") { it.displayLabel }
        return buildString {
            appendLine("PokeClaw Debug Report")
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
            appendLine("- Conservative CPU-first suggested: ${if (LocalBackendHealth.isConservativeCpuModeSuggested()) "Yes" else "No"}")
            appendLine("- GPU already verified healthy: ${if (LocalBackendHealth.hasVerifiedGpuSuccess()) "Yes" else "No"}")
            appendLine("- Pending GPU init marker: ${if (LocalBackendHealth.hasPendingGpuInitMarker()) "Present" else "None"}")
            appendLine()
            appendLine("Auto-reply")
            appendLine("- Enabled: ${if (autoReplyManager.isEnabled) "Yes" else "No"}")
            appendLine("- Targets: ${monitorTargets.ifBlank { "(none)" }}")
            appendLine()
            appendLine("Artifacts")
            appendLine("- HTTP log files present: $httpLogs")
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
}
