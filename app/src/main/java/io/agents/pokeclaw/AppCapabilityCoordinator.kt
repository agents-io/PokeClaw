// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.ClawNotificationListener
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.utils.KVUtils

enum class ServiceBindingState {
    DISABLED,
    CONNECTING,
    READY,
    DEGRADED,
}

enum class AppRequirement(val label: String) {
    ACCESSIBILITY("Accessibility"),
    NOTIFICATION_PERMISSION("Notifications"),
    NOTIFICATION_ACCESS("Notification Access"),
    OVERLAY("Overlay"),
    BATTERY_OPTIMIZATION("Battery"),
    STORAGE("Storage"),
}

data class AppCapabilitySnapshot(
    val accessibilityState: ServiceBindingState,
    val notificationAccessState: ServiceBindingState,
    val notificationPermissionGranted: Boolean,
    val foregroundServiceRunning: Boolean,
    val overlayGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val storageAccessGranted: Boolean,
) {
    val canRunInteractiveTask: Boolean
        get() = accessibilityState == ServiceBindingState.READY

    val canRunMonitor: Boolean
        get() = accessibilityState == ServiceBindingState.READY &&
            notificationAccessState == ServiceBindingState.READY

    val accessibilityStatusLabel: String
        get() = when (accessibilityState) {
            ServiceBindingState.READY -> "Enabled"
            ServiceBindingState.CONNECTING -> "Connecting"
            ServiceBindingState.DEGRADED -> "Disconnected"
            ServiceBindingState.DISABLED -> "Disabled"
        }

    val notificationAccessStatusLabel: String
        get() = when (notificationAccessState) {
            ServiceBindingState.READY -> "Connected"
            ServiceBindingState.CONNECTING -> "Connecting"
            ServiceBindingState.DEGRADED -> "Disconnected"
            ServiceBindingState.DISABLED -> "Disabled"
        }

    val notificationPermissionStatusLabel: String
        get() = if (notificationPermissionGranted) "Enabled" else "Disabled"
}

object AppCapabilityCoordinator {
    private const val SERVICE_REBIND_GRACE_MS = 15_000L
    private const val ACCESSIBILITY_INTERRUPT_GRACE_MS = 4_000L

    fun snapshot(context: Context): AppCapabilitySnapshot {
        return AppCapabilitySnapshot(
            accessibilityState = accessibilityState(context),
            notificationAccessState = notificationAccessState(context),
            notificationPermissionGranted = isNotificationPermissionGranted(context),
            foregroundServiceRunning = ForegroundService.isRunning(),
            overlayGranted = Settings.canDrawOverlays(context),
            batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context),
            storageAccessGranted = hasStorageAccess(context),
        )
    }

    fun accessibilityState(context: Context): ServiceBindingState {
        return bindingState(
            enabled = ClawAccessibilityService.isEnabledInSettings(context),
            running = ClawAccessibilityService.isRunning(),
            pendingRepair = KVUtils.hasPendingAccessibilityReturn(),
            lastConnectedAt = KVUtils.getAccessibilityLastConnectedAt(),
            lastHeartbeatAt = KVUtils.getAccessibilityLastHeartbeatAt(),
            lastInterruptedAt = KVUtils.getAccessibilityLastInterruptedAt(),
            lastDisconnectedAt = KVUtils.getAccessibilityLastDisconnectedAt(),
        )
    }

    fun notificationAccessState(context: Context): ServiceBindingState {
        return bindingState(
            enabled = ClawNotificationListener.isEnabledInSettings(context),
            running = ClawNotificationListener.isConnected(),
            pendingRepair = KVUtils.hasPendingNotificationAccessReturn(),
            lastConnectedAt = KVUtils.getNotificationListenerLastConnectedAt(),
            lastDisconnectedAt = KVUtils.getNotificationListenerLastDisconnectedAt(),
        )
    }

    private fun bindingState(
        enabled: Boolean,
        running: Boolean,
        pendingRepair: Boolean,
        lastConnectedAt: Long,
        lastHeartbeatAt: Long = 0L,
        lastInterruptedAt: Long = 0L,
        lastDisconnectedAt: Long,
    ): ServiceBindingState {
        if (!enabled) return ServiceBindingState.DISABLED
        if (pendingRepair) return ServiceBindingState.CONNECTING

        val now = System.currentTimeMillis()
        val lastHealthyAt = maxOf(lastConnectedAt, lastHeartbeatAt)

        if (running) {
            if (lastInterruptedAt > lastHealthyAt) {
                return if (now - lastInterruptedAt <= ACCESSIBILITY_INTERRUPT_GRACE_MS) {
                    ServiceBindingState.CONNECTING
                } else {
                    ServiceBindingState.DEGRADED
                }
            }
            return ServiceBindingState.READY
        }

        if (lastHealthyAt <= 0L) return ServiceBindingState.CONNECTING

        if (now - lastHealthyAt <= SERVICE_REBIND_GRACE_MS) {
            return ServiceBindingState.CONNECTING
        }
        if (lastDisconnectedAt > 0L && lastDisconnectedAt >= lastHealthyAt) {
            return ServiceBindingState.DEGRADED
        }
        if (lastInterruptedAt > 0L && lastInterruptedAt >= lastHealthyAt) {
            return ServiceBindingState.DEGRADED
        }
        return ServiceBindingState.DEGRADED
    }

    fun missingMonitorRequirements(context: Context): List<AppRequirement> {
        val capabilities = snapshot(context)
        val missing = mutableListOf<AppRequirement>()
        if (capabilities.accessibilityState != ServiceBindingState.READY) {
            missing.add(AppRequirement.ACCESSIBILITY)
        }
        if (capabilities.notificationAccessState != ServiceBindingState.READY) {
            missing.add(AppRequirement.NOTIFICATION_ACCESS)
        }
        return missing
    }

    fun isNotificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun openSystemSettings(context: Context, requirement: AppRequirement) {
        when (requirement) {
            AppRequirement.ACCESSIBILITY -> {
                if (accessibilityState(context) == ServiceBindingState.READY) {
                    KVUtils.clearPendingAccessibilityReturn()
                } else {
                    KVUtils.markPendingAccessibilityReturn()
                }
                launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            AppRequirement.NOTIFICATION_ACCESS -> {
                if (notificationAccessState(context) == ServiceBindingState.READY) {
                    KVUtils.clearPendingNotificationAccessReturn()
                } else {
                    KVUtils.markPendingNotificationAccessReturn()
                }
                launch(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            AppRequirement.OVERLAY -> {
                launch(
                    context,
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
                )
            }
            AppRequirement.BATTERY_OPTIMIZATION -> {
                launch(
                    context,
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                )
            }
            AppRequirement.STORAGE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    launch(
                        context,
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = "package:${context.packageName}".toUri()
                        }
                    )
                }
            }
            AppRequirement.NOTIFICATION_PERMISSION -> Unit
        }
    }

    private fun hasStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun launch(context: Context, intent: Intent) {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
