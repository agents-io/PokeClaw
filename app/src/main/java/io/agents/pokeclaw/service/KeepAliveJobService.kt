package io.agents.pokeclaw.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.agents.pokeclaw.utils.XLog

/**
 * 定时守护 JobService
 * 每 15 分钟检查前台服务是否存活，若被杀则重新拉起
 */
class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJob"
        private const val JOB_ID = 10086
        private const val INTERVAL_MS = 15 * 60 * 1000L // 15 分钟

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.getPendingJob(JOB_ID) != null) return

            val jobInfo = JobInfo.Builder(JOB_ID, ComponentName(context, KeepAliveJobService::class.java))
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .build()

            val result = scheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                XLog.i(TAG, "KeepAlive job scheduled")
            } else {
                XLog.e(TAG, "KeepAlive job schedule failed")
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        XLog.i(TAG, "KeepAlive job triggered, ForegroundService running: ${ForegroundService.isRunning()}")
        if (!ForegroundService.isRunning()) {
            val started = ForegroundService.start(applicationContext)
            XLog.i(TAG, "Restarted ForegroundService: $started")
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }
}
