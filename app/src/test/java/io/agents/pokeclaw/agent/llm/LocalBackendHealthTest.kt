package io.agents.pokeclaw.agent.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackendHealthTest {

    @Test
    fun `promotes pending gpu init crash on same device within age window`() {
        val now = 1_000_000L
        assertTrue(
            LocalBackendHealth.shouldPromotePendingGpuCrash(
                currentDeviceKey = "device-a",
                pendingDeviceKey = "device-a",
                pendingAtMs = now - 5_000L,
                pendingPid = 0,
                nowMs = now,
            )
        )
    }

    @Test
    fun `does not promote pending gpu init crash for another device`() {
        val now = 1_000_000L
        assertFalse(
            LocalBackendHealth.shouldPromotePendingGpuCrash(
                currentDeviceKey = "device-a",
                pendingDeviceKey = "device-b",
                pendingAtMs = now - 5_000L,
                pendingPid = 0,
                nowMs = now,
            )
        )
    }

    @Test
    fun `does not promote stale pending gpu init crash`() {
        val now = 1_000_000L
        assertFalse(
            LocalBackendHealth.shouldPromotePendingGpuCrash(
                currentDeviceKey = "device-a",
                pendingDeviceKey = "device-a",
                pendingAtMs = now - 100_000L,
                pendingPid = 0,
                nowMs = now,
                maxAgeMs = 10_000L,
            )
        )
    }

    @Test
    fun `conservative cpu applies to xiaomi before gpu is verified`() {
        assertTrue(
            LocalBackendHealth.shouldConservativelyForceCpu(
                manufacturer = "xiaomi",
                model = "xiaomi 15",
                hardware = "kalama",
                device = "houji",
                hasVerifiedGpuSuccess = false,
                isCpuSafeModeEnabled = false,
            )
        )
    }

    @Test
    fun `conservative cpu applies to mediatek style hardware before gpu is verified`() {
        assertTrue(
            LocalBackendHealth.shouldConservativelyForceCpu(
                manufacturer = "vivo",
                model = "vivo y27",
                hardware = "mt6989",
                device = "mtk-device",
                hasVerifiedGpuSuccess = false,
                isCpuSafeModeEnabled = false,
            )
        )
    }

    @Test
    fun `conservative cpu applies to fold4 model before gpu is verified`() {
        assertTrue(
            LocalBackendHealth.shouldConservativelyForceCpu(
                manufacturer = "samsung",
                model = "sm-f936b",
                hardware = "qcom",
                device = "q4q",
                hasVerifiedGpuSuccess = false,
                isCpuSafeModeEnabled = false,
            )
        )
    }

    @Test
    fun `conservative cpu does not apply after gpu is verified`() {
        assertFalse(
            LocalBackendHealth.shouldConservativelyForceCpu(
                manufacturer = "xiaomi",
                model = "xiaomi 15",
                hardware = "kalama",
                device = "houji",
                hasVerifiedGpuSuccess = true,
                isCpuSafeModeEnabled = false,
            )
        )
    }

    @Test
    fun `conservative cpu applies to pixel tensor before gpu is verified`() {
        assertTrue(
            LocalBackendHealth.shouldConservativelyForceCpu(
                manufacturer = "google",
                model = "pixel 8 pro",
                hardware = "husky",
                device = "husky",
                hasVerifiedGpuSuccess = false,
                isCpuSafeModeEnabled = false,
            )
        )
    }

    @Test
    fun `rearms verified gpu after stale cpu safe quarantine`() {
        val now = 200_000_000L
        assertTrue(
            LocalBackendHealth.shouldRearmVerifiedGpu(
                isCpuSafeModeEnabled = true,
                hasVerifiedGpuSuccess = true,
                hasPendingGpuInitMarker = false,
                cpuSafeReason = "gpu_init_crash: gemma-4-E4B-it.litertlm: previous GPU engine init died before cleanup",
                cpuSafeAtMs = now - 90_000_000L,
                nowMs = now,
                cooldownMs = 1_000L,
            )
        )
    }

    @Test
    fun `does not rearm verified gpu during fresh cpu safe quarantine`() {
        val now = 2_000_000L
        assertFalse(
            LocalBackendHealth.shouldRearmVerifiedGpu(
                isCpuSafeModeEnabled = true,
                hasVerifiedGpuSuccess = true,
                hasPendingGpuInitMarker = false,
                cpuSafeReason = "gpu_init_crash: gemma-4-E4B-it.litertlm: previous GPU engine init died before cleanup",
                cpuSafeAtMs = now - 500L,
                nowMs = now,
                cooldownMs = 1_000L,
            )
        )
    }

    @Test
    fun `does not rearm verified gpu for non crash cpu safe reason`() {
        val now = 200_000_000L
        assertFalse(
            LocalBackendHealth.shouldRearmVerifiedGpu(
                isCpuSafeModeEnabled = true,
                hasVerifiedGpuSuccess = true,
                hasPendingGpuInitMarker = false,
                cpuSafeReason = "gpu_failure: gemma-4-E4B-it.litertlm: OpenCL init failed",
                cpuSafeAtMs = now - 90_000_000L,
                nowMs = now,
                cooldownMs = 1_000L,
            )
        )
    }
}
