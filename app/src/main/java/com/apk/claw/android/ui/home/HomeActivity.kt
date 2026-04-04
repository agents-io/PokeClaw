package com.apk.claw.android.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import android.Manifest
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.apk.claw.android.service.ForegroundService
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.apk.claw.android.R
import com.apk.claw.android.appViewModel
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.channel.Channel as ChannelEnum
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.ui.guide.GuideActivity
import com.apk.claw.android.ui.settings.SettingsActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.PermissionCardView
import com.apk.claw.android.widget.KButton
import androidx.core.net.toUri

/**
 * 首页 - 权限管理
 */
class HomeActivity : BaseActivity() {

    companion object {
        private const val TAG = "HomeActivity"
    }

    private lateinit var cardAccessibility: PermissionCardView
    private lateinit var cardNotification: PermissionCardView
    private lateinit var cardSystemWindow: PermissionCardView
    private lateinit var cardBattery: PermissionCardView
    private lateinit var cardStorage: PermissionCardView
    private lateinit var btnCancelTask: KButton
    private lateinit var etTaskInput: EditText
    private lateinit var btnSendTask: KButton
    private lateinit var tvTaskStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            updateAllPermissionStatus()
            handler.postDelayed(this, 1000)
        }
    }

    // Activity Result API - 存储权限请求 (Android 6~10)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Toast.makeText(this, R.string.home_storage_enabled, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.home_enable_storage, Toast.LENGTH_SHORT).show()
        }
        updateStorageStatus()
    }

    // Activity Result API - 通知权限请求
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 授权成功，启动前台服务
            startNotificationService()
        } else {
            Toast.makeText(this, R.string.home_need_notification_permission, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        initViews()
        showGuideIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateAllPermissionStatus()
        startStatusCheck()
    }

    override fun onPause() {
        super.onPause()
        stopStatusCheck()
    }

    private fun showGuideIfNeeded() {
        if (!KVUtils.isGuideShown()) {
            startActivity(Intent(this, GuideActivity::class.java))
        }
    }

    private fun initViews() {
        // Toolbar
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitleCentered(false)
            setTitle(getString(R.string.app_name))
            setActionIcon(R.drawable.ic_settings) {
                startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))
            }
        }

        // 卡片
        cardAccessibility = findViewById(R.id.cardAccessibility)
        cardNotification = findViewById(R.id.cardNotification)
        cardSystemWindow = findViewById(R.id.cardSystemWindow)
        cardBattery = findViewById(R.id.cardBattery)
        cardStorage = findViewById(R.id.cardStorage)

        // 结束会话按钮
        btnCancelTask = findViewById(R.id.btnCancelTask)
        btnCancelTask.setOnClickListener {
            if (appViewModel.isTaskRunning()) {
                appViewModel.cancelCurrentTask()
                Toast.makeText(this, R.string.home_cancel_task_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.home_no_task_running, Toast.LENGTH_SHORT).show()
            }
            updateCancelTaskVisibility()
        }

        // Task input
        etTaskInput = findViewById(R.id.etTaskInput)
        btnSendTask = findViewById(R.id.btnSendTask)
        tvTaskStatus = findViewById(R.id.tvTaskStatus)

        btnSendTask.setOnClickListener {
            val task = etTaskInput.text.toString().trim()
            if (task.isEmpty()) {
                Toast.makeText(this, "Please enter a task", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!KVUtils.hasLlmConfig()) {
                Toast.makeText(this, "Please configure LLM first (Settings → LLM Config)", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!ClawAccessibilityService.isRunning()) {
                Toast.makeText(this, "Please enable Accessibility Service first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (appViewModel.isTaskRunning()) {
                Toast.makeText(this, "A task is already running", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            tvTaskStatus.text = "Running: $task"
            tvTaskStatus.visibility = View.VISIBLE
            btnSendTask.isEnabled = false

            appViewModel.startNewTask(ChannelEnum.LOCAL, task, "local_${System.currentTimeMillis()}")
            etTaskInput.text.clear()

            handler.postDelayed({
                btnSendTask.isEnabled = true
                updateCancelTaskVisibility()
            }, 1000)
        }

        // 点击卡片申请权限
        cardAccessibility.setOnClickListener { requestAccessibilityPermission() }
        cardNotification.setOnClickListener { requestNotificationPermission() }
        cardSystemWindow.setOnClickListener { requestSystemWindowPermission() }
        cardBattery.setOnClickListener { requestBatteryPermission() }
        cardStorage.setOnClickListener { requestStoragePermission() }
    }

    private fun updateAllPermissionStatus() {
        updateAccessibilityStatus()
        updateNotificationStatus()
        updateSystemWindowStatus()
        updateBatteryStatus()
        updateStorageStatus()
        updateCancelTaskVisibility()
    }

    private fun updateCancelTaskVisibility() {
        btnCancelTask.visibility = if (appViewModel.isTaskRunning()) View.VISIBLE else View.GONE
    }

    private fun updateAccessibilityStatus() {
        cardAccessibility.setPermissionEnabled(ClawAccessibilityService.isRunning())
    }

    private fun updateNotificationStatus() {
        cardNotification.setPermissionEnabled(ForegroundService.isRunning())
    }

    private fun updateSystemWindowStatus() {
        val enabled = Settings.canDrawOverlays(this)
        cardSystemWindow.setPermissionEnabled(enabled)
        if (enabled) {
            appViewModel.showFloatingCircle()
        }
    }

    private fun updateBatteryStatus() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        cardBattery.setPermissionEnabled(powerManager.isIgnoringBatteryOptimizations(packageName))
    }

    private fun updateStorageStatus() {
        cardStorage.setPermissionEnabled(isStoragePermissionGranted())
    }

    private fun isStoragePermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAllPermissionsGranted(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return ClawAccessibilityService.isRunning() &&
                Settings.canDrawOverlays(this) &&
                powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    // ==================== 权限申请 ====================

    private fun requestAccessibilityPermission() {
        if (!ClawAccessibilityService.isRunning()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.home_enable_accessibility, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.home_accessibility_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission() {
        // Android 13+ 需要申请通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // 使用 Activity Result API 请求权限
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        startNotificationService()
    }

    private fun startNotificationService() {
        val started = ForegroundService.start(this)
        if (started) {
            cardNotification.setPermissionEnabled(true)
            Toast.makeText(this, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.home_need_notification_permission, Toast.LENGTH_SHORT).show()
            updateNotificationStatus()
        }
    }

    private fun requestSystemWindowPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.home_overlay_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestStoragePermission() {
        if (isStoragePermissionGranted()) {
            Toast.makeText(this, R.string.home_storage_enabled, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: 跳转到「所有文件访问」设置页
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = "package:$packageName".toUri()
            startActivity(intent)
            Toast.makeText(this, R.string.home_enable_storage, Toast.LENGTH_LONG).show()
        } else {
            // Android 6~10: 运行时请求读写权限
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun requestBatteryPermission() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = "package:$packageName".toUri()
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.home_battery_ignored, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startStatusCheck() {
        stopStatusCheck();
        handler.postDelayed(checkRunnable, 1000)
    }

    private fun stopStatusCheck() {
        handler.removeCallbacks(checkRunnable)
    }

}
