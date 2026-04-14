// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import android.os.Build
import android.os.Process
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

object LocalBackendHealth {

    private const val TAG = "LocalBackendHealth"
    private const val CRASH_MARKER_MAX_AGE_MS = 1000L * 60L * 60L * 24L * 30L
    private const val VERIFIED_GPU_CPU_SAFE_RETRY_COOLDOWN_MS = 1000L * 60L * 60L * 24L
    private val CONSERVATIVE_CPU_MANUFACTURERS = setOf("xiaomi", "redmi", "poco")
    private val CONSERVATIVE_CPU_MODELS = listOf(
        "xiaomi 15",
        "mi 15",
        "galaxy z fold4",
        "sm-f936",
        "z flip7",
        "sm-f766",
    )
    private val CONSERVATIVE_CPU_HARDWARE_HINTS = listOf(
        "mt",
        "mediatek",
        "dimensity",
    )

    fun currentDeviceKey(): String {
        val fingerprint = Build.FINGERPRINT?.trim().orEmpty()
        if (fingerprint.isNotEmpty()) return fingerprint
        return listOf(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.HARDWARE)
            .filter { !it.isNullOrBlank() }
            .joinToString("|")
    }

    fun shouldForceCpu(preferCpu: Boolean): Boolean {
        recoverPendingGpuCrashIfNeeded()
        maybeRearmVerifiedGpu()
        val forceCpu = preferCpu ||
            KVUtils.getLocalBackendPreference().equals("CPU", ignoreCase = true) ||
            isCpuSafeModeEnabled() ||
            shouldStartCpuConservatively()
        if (forceCpu && shouldStartCpuConservatively()) {
            XLog.w(TAG, "Using conservative CPU-first mode on ${deviceDescriptor()}")
        }
        return forceCpu
    }

    fun isCpuSafeModeEnabled(): Boolean {
        return KVUtils.getLocalCpuSafeDevice() == currentDeviceKey()
    }

    fun cpuSafeReason(): String = KVUtils.getLocalCpuSafeReason()

    fun hasVerifiedGpuSuccess(): Boolean {
        return KVUtils.getLocalGpuVerifiedDevice() == currentDeviceKey() &&
            KVUtils.getLocalGpuVerifiedAt() > 0L
    }

    fun debugStateSummary(): String {
        val pendingDevice = KVUtils.getPendingLocalGpuInitDevice().ifBlank { "-" }
        val pendingModel = KVUtils.getPendingLocalGpuInitModel().ifBlank { "-" }
        val pendingAt = KVUtils.getPendingLocalGpuInitAt()
        val pendingPid = KVUtils.getPendingLocalGpuInitPid()
        val cpuSafeDevice = KVUtils.getLocalCpuSafeDevice().ifBlank { "-" }
        val gpuVerifiedDevice = KVUtils.getLocalGpuVerifiedDevice().ifBlank { "-" }
        val gpuVerifiedAt = KVUtils.getLocalGpuVerifiedAt()
        val backendPreference = KVUtils.getLocalBackendPreference().ifBlank { "-" }
        val reason = cpuSafeReason().ifBlank { "-" }
        val cpuSafeAt = KVUtils.getLocalCpuSafeAt()
        return buildString {
            append("device=")
            append(currentDeviceKey())
            append(", cpuSafe=")
            append(isCpuSafeModeEnabled())
            append(", cpuSafeDevice=")
            append(cpuSafeDevice)
            append(", backendPreference=")
            append(backendPreference)
            append(", reason=")
            append(reason)
            append(", cpuSafeAt=")
            append(cpuSafeAt)
            append(", gpuVerified=")
            append(hasVerifiedGpuSuccess())
            append(", gpuVerifiedDevice=")
            append(gpuVerifiedDevice)
            append(", gpuVerifiedAt=")
            append(gpuVerifiedAt)
            append(", conservativeCpu=")
            append(shouldStartCpuConservatively())
            append(", pendingDevice=")
            append(pendingDevice)
            append(", pendingModel=")
            append(pendingModel)
            append(", pendingAt=")
            append(pendingAt)
            append(", pendingPid=")
            append(pendingPid)
        }
    }

    fun debugForceCpuSafe(reason: String = "debug") {
        enableCpuSafeMode(reason)
    }

    fun debugClearCpuSafeMode() {
        KVUtils.clearLocalCpuSafeMode()
        if (KVUtils.getLocalBackendPreference().equals("CPU", ignoreCase = true)) {
            KVUtils.setLocalBackendPreference("")
        }
    }

    fun debugClearGpuVerified() {
        KVUtils.clearLocalGpuVerified()
    }

    fun debugMarkPendingGpuInit(modelPath: String) {
        markGpuInitStarted(modelPath)
    }

    fun debugClearPendingGpuInit() {
        KVUtils.clearPendingLocalGpuInit()
    }

    fun noteRecoverableGpuFailure(modelPath: String, error: Throwable?) {
        val reason = buildReason("gpu_failure", modelPath, error?.message)
        enableCpuSafeMode(reason)
        KVUtils.clearPendingLocalGpuInit()
        XLog.w(TAG, "GPU backend marked unsafe for this device: $reason")
    }

    fun noteGpuInitSuccess(modelPath: String) {
        KVUtils.setLocalGpuVerifiedDevice(currentDeviceKey())
        KVUtils.setLocalGpuVerifiedAt(System.currentTimeMillis())
        KVUtils.clearPendingLocalGpuInit()
        XLog.i(TAG, "GPU backend verified healthy for ${modelPath.substringAfterLast('/')}")
    }

    fun markGpuInitStarted(modelPath: String) {
        KVUtils.setPendingLocalGpuInitDevice(currentDeviceKey())
        KVUtils.setPendingLocalGpuInitModel(modelPath)
        KVUtils.setPendingLocalGpuInitAt(System.currentTimeMillis())
        KVUtils.setPendingLocalGpuInitPid(Process.myPid())
        XLog.i(TAG, "Marked GPU init pending for ${modelPath.substringAfterLast('/')}")
    }

    fun markGpuInitFinished() {
        KVUtils.clearPendingLocalGpuInit()
    }

    fun recoverPendingGpuCrashIfNeeded(): Boolean {
        val pendingDevice = KVUtils.getPendingLocalGpuInitDevice()
        val pendingAt = KVUtils.getPendingLocalGpuInitAt()
        val pendingPid = KVUtils.getPendingLocalGpuInitPid()
        if (!shouldPromotePendingGpuCrash(currentDeviceKey(), pendingDevice, pendingAt, pendingPid, System.currentTimeMillis())) {
            return false
        }

        val modelPath = KVUtils.getPendingLocalGpuInitModel()
        val reason = buildReason("gpu_init_crash", modelPath, "previous GPU engine init died before cleanup")
        enableCpuSafeMode(reason)
        KVUtils.clearPendingLocalGpuInit()
        XLog.w(TAG, "Recovered pending GPU init crash; forcing CPU-safe mode for this device")
        return true
    }

    internal fun shouldPromotePendingGpuCrash(
        currentDeviceKey: String,
        pendingDeviceKey: String?,
        pendingAtMs: Long,
        pendingPid: Int,
        nowMs: Long,
        maxAgeMs: Long = CRASH_MARKER_MAX_AGE_MS,
    ): Boolean {
        if (pendingDeviceKey.isNullOrBlank()) return false
        if (pendingDeviceKey != currentDeviceKey) return false
        if (pendingAtMs <= 0L) return false
        if (pendingPid > 0 && pendingPid == Process.myPid()) return false
        return nowMs - pendingAtMs <= maxAgeMs
    }

    private fun enableCpuSafeMode(reason: String) {
        val now = System.currentTimeMillis()
        KVUtils.setLocalCpuSafeDevice(currentDeviceKey())
        KVUtils.setLocalCpuSafeReason(reason)
        KVUtils.setLocalCpuSafeAt(now)
        KVUtils.setLocalBackendPreference("CPU")
    }

    private fun maybeRearmVerifiedGpu(nowMs: Long = System.currentTimeMillis()) {
        if (!shouldRearmVerifiedGpu(
                isCpuSafeModeEnabled = isCpuSafeModeEnabled(),
                hasVerifiedGpuSuccess = hasVerifiedGpuSuccess(),
                hasPendingGpuInitMarker = hasPendingGpuInitMarker(),
                cpuSafeReason = cpuSafeReason(),
                cpuSafeAtMs = KVUtils.getLocalCpuSafeAt(),
                nowMs = nowMs,
            )) {
            return
        }

        XLog.w(
            TAG,
            "Re-arming verified GPU backend after stale CPU-safe quarantine on ${deviceDescriptor()}",
        )
        KVUtils.clearLocalCpuSafeMode()
        if (KVUtils.getLocalBackendPreference().equals("CPU", ignoreCase = true)) {
            KVUtils.setLocalBackendPreference("")
        }
    }

    private fun shouldStartCpuConservatively(): Boolean {
        val manufacturer = Build.MANUFACTURER?.trim()?.lowercase().orEmpty()
        val model = Build.MODEL?.trim()?.lowercase().orEmpty()
        val hardware = Build.HARDWARE?.trim()?.lowercase().orEmpty()
        return shouldConservativelyForceCpu(
            manufacturer = manufacturer,
            model = model,
            hardware = hardware,
            hasVerifiedGpuSuccess = hasVerifiedGpuSuccess(),
            isCpuSafeModeEnabled = isCpuSafeModeEnabled(),
        )
    }

    private fun deviceDescriptor(): String {
        return listOf(Build.MANUFACTURER, Build.MODEL, Build.HARDWARE)
            .filter { !it.isNullOrBlank() }
            .joinToString(" / ")
    }

    fun debugDeviceDescriptor(): String = deviceDescriptor()

    fun isConservativeCpuModeSuggested(): Boolean = shouldStartCpuConservatively()

    fun hasPendingGpuInitMarker(): Boolean {
        return shouldPromotePendingGpuCrash(
            currentDeviceKey = currentDeviceKey(),
            pendingDeviceKey = KVUtils.getPendingLocalGpuInitDevice(),
            pendingAtMs = KVUtils.getPendingLocalGpuInitAt(),
            pendingPid = KVUtils.getPendingLocalGpuInitPid(),
            nowMs = System.currentTimeMillis(),
        )
    }

    internal fun shouldConservativelyForceCpu(
        manufacturer: String,
        model: String,
        hardware: String,
        hasVerifiedGpuSuccess: Boolean,
        isCpuSafeModeEnabled: Boolean,
    ): Boolean {
        if (hasVerifiedGpuSuccess) return false
        if (isCpuSafeModeEnabled) return false
        if (manufacturer in CONSERVATIVE_CPU_MANUFACTURERS) return true
        if (CONSERVATIVE_CPU_MODELS.any { model.contains(it) }) return true
        return CONSERVATIVE_CPU_HARDWARE_HINTS.any { hint ->
            hardware.contains(hint) || model.contains(hint)
        }
    }

    private fun buildReason(prefix: String, modelPath: String, detail: String?): String {
        val modelName = modelPath.substringAfterLast('/')
        return listOf(prefix, modelName, detail?.take(120))
            .filter { !it.isNullOrBlank() }
            .joinToString(": ")
    }

    internal fun shouldRearmVerifiedGpu(
        isCpuSafeModeEnabled: Boolean,
        hasVerifiedGpuSuccess: Boolean,
        hasPendingGpuInitMarker: Boolean,
        cpuSafeReason: String,
        cpuSafeAtMs: Long,
        nowMs: Long,
        cooldownMs: Long = VERIFIED_GPU_CPU_SAFE_RETRY_COOLDOWN_MS,
    ): Boolean {
        if (!isCpuSafeModeEnabled) return false
        if (!hasVerifiedGpuSuccess) return false
        if (hasPendingGpuInitMarker) return false
        if (!cpuSafeReason.startsWith("gpu_init_crash")) return false
        if (cpuSafeAtMs <= 0L) return false
        return nowMs - cpuSafeAtMs >= cooldownMs
    }
}
