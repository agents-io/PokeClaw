// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.agents.pokeclaw.R
import io.agents.pokeclaw.utils.XLog

/**
 * Foreground service for active task / monitor notifications only.
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        const val CHANNEL_ID = "PokeClaw_foreground_channel"
        const val NOTIFICATION_ID = 1001
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"
        private const val DEFAULT_TASK_TITLE = "PokeClaw · Task in progress"
        private const val DEFAULT_TASK_TEXT = "Running task..."
        private const val DEFAULT_MONITOR_TITLE = "PokeClaw · Monitoring"

        @Volatile
        private var _isRunning = false

        /**
         * Check whether the foreground service is running
         */
        fun isRunning(): Boolean = _isRunning

        /**
         * Update the foreground notification with task progress text.
         * Safe to call from any thread — posts to NotificationManager directly.
         */
        fun updateTaskStatus(context: Context, statusText: String) {
            showNotification(context, DEFAULT_TASK_TITLE, statusText)
        }

        /**
         * Show monitor state if auto-reply is active, otherwise stop the foreground service.
         */
        fun resetToIdle(context: Context) {
            syncToBackgroundState(context)
        }

        fun showMonitorStatus(context: Context): Boolean {
            val manager = AutoReplyManager.getInstance()
            if (!manager.isEnabled || manager.monitoredContacts.isEmpty()) {
                stop(context)
                return false
            }
            val contacts = manager.monitoredContacts.toList()
            val text = when (contacts.size) {
                0 -> "Monitoring in background"
                1 -> "Monitoring ${contacts.first()}"
                else -> "Monitoring ${contacts.size} chats"
            }
            return showNotification(context, DEFAULT_MONITOR_TITLE, text)
        }

        fun syncToBackgroundState(context: Context): Boolean {
            val manager = AutoReplyManager.getInstance()
            val monitorCount = manager.monitoredContacts.size
            val missedCallActive = MissedCallFollowUpManager.isEnabled()
            return when {
                monitorCount > 0 && missedCallActive -> {
                    showNotification(
                        context,
                        "PokeClaw · Background active",
                        "${monitorCount + 1} background automations active"
                    )
                }
                monitorCount > 0 -> {
                showMonitorStatus(context)
                }
                missedCallActive -> {
                    showNotification(
                        context,
                        "PokeClaw · Background active",
                        "Missed-call follow-up via SMS is armed"
                    )
                }
                else -> {
                    stop(context)
                    false
                }
            }
        }

        private fun showNotification(context: Context, title: String, text: String): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    return false
                }
            }

            try {
                if (_isRunning) {
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(context, title, text))
                    return true
                }
                start(context, title, text)
                return true
            } catch (e: Exception) {
                XLog.w(TAG, "Foreground notification update failed", e)
                return false
            }
        }

        /**
         * Start the foreground service
         * @param context Context
         * @return true if started successfully, false if notification permission is missing
         */
        fun start(
            context: Context,
            title: String = context.getString(R.string.notification_content_title),
            text: String = context.getString(R.string.notification_content_text)
        ): Boolean {
            // Android 13+ requires notification permission check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }

            return try {
                val intent = Intent(context, ForegroundService::class.java).apply {
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, text)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                XLog.w(TAG, "Foreground service start blocked or failed", e)
                false
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ForegroundService::class.java)
            context.stopService(intent)
            runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_ID)
            }
        }

        private fun buildNotification(context: Context, title: String, text: String): Notification {
            val intent = Intent(context, io.agents.pokeclaw.ui.chat.ComposeChatActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        _isRunning = true
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning = false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification(intent)
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(intent: Intent?): Notification {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TASK_TITLE
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TASK_TEXT
        return buildNotification(this, title, text)
    }
}
