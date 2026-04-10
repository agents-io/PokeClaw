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
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import io.agents.pokeclaw.R
import io.agents.pokeclaw.server.ConfigServerManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

/**
 * Foreground service - persistent notification
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        const val CHANNEL_ID = "PokeClaw_foreground_channel"
        const val NOTIFICATION_ID = 1001

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
            try {
                val intent = Intent(context, io.agents.pokeclaw.ui.chat.ComposeChatActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("PokeClaw · Task in progress")
                    .setContentText(statusText)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                // Non-critical — don't crash if notification update fails
            }
        }

        /**
         * Reset notification back to idle state.
         */
        fun resetToIdle(context: Context) {
            try {
                val intent = Intent(context, io.agents.pokeclaw.ui.chat.ComposeChatActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.notification_content_title))
                    .setContentText(context.getString(R.string.notification_content_text))
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build()
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                // Non-critical
            }
        }

        /**
         * Start the foreground service
         * @param context Context
         * @return true if started successfully, false if notification permission is missing
         */
        fun start(context: Context): Boolean {
            // Android 13+ requires notification permission check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }

            return try {
                val intent = Intent(context, ForegroundService::class.java)
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
        ConfigServerManager.stop()
        if (KVUtils.hasLlmConfig()) {
            scheduleRestart(0)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (KVUtils.hasLlmConfig()) {
            scheduleRestart(1)
        }
    }

    private fun scheduleRestart(requestCode: Int) {
        val restartIntent = Intent(applicationContext, ForegroundService::class.java)
        val pendingRestart = PendingIntent.getService(
            applicationContext, requestCode, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pendingRestart)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, io.agents.pokeclaw.ui.chat.ComposeChatActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_content_title))
            .setContentText(getString(R.string.notification_content_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }
}
