package io.agents.pokeclaw.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.agents.pokeclaw.utils.XLog

/**
 * 开机自启动广播接收器
 * 收到 BOOT_COMPLETED 后启动前台服务，保持后台运行
 * 不启动 Activity，避免与用户手动打开 App 冲突
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            XLog.i(TAG, "收到开机广播，启动前台服务")
            ForegroundService.start(context)
        }
    }
}
