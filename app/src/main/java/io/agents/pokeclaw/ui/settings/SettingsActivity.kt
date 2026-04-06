// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.agents.pokeclaw.R
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.widget.AlertDialog
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.MenuGroup
import io.agents.pokeclaw.widget.MenuItem
import kotlinx.coroutines.launch
import android.content.Intent
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.server.ConfigServerManager
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.ForegroundService
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Settings screen
 */
class SettingsActivity : BaseActivity() {

    // Poll permissions every second (same as original HomeActivity)
    private val handler = Handler(Looper.getMainLooper())
    private val permPoller = object : Runnable {
        override fun run() {
            refreshPermissions()
            handler.postDelayed(this, 1000)
        }
    }

    // Permission menu items — kept for onResume refresh
    private var permAccessibility: io.agents.pokeclaw.widget.MenuItem? = null
    private var permNotification: io.agents.pokeclaw.widget.MenuItem? = null
    private var permOverlay: io.agents.pokeclaw.widget.MenuItem? = null
    private var permBattery: io.agents.pokeclaw.widget.MenuItem? = null
    private var permStorage: io.agents.pokeclaw.widget.MenuItem? = null

    private val viewModel by lazy {
        ViewModelProvider(this)[SettingsViewModel::class.java]
    }

    // Keep MenuItem references for dynamic updates
    private val menuItems = mutableMapOf<String, MenuItem>()

    // Register launcher to refresh after returning from LLM config screen
    private val llmConfigLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        viewModel.refresh()
    }

    // Register channel config result callback
    private val channelConfigLauncher = ChannelConfigActivity.registerLauncher(this) { result ->
        result?.let {
            // Refresh settings after successful config (refresh "Bound"/"Unbound" status)
            viewModel.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force match theme from ThemeManager
        val themeColors = io.agents.pokeclaw.ui.chat.ThemeManager.getColors()
        window.statusBarColor = themeColors.toolbarBg
        window.decorView.setBackgroundColor(themeColors.bg)

        setContentView(R.layout.activity_settings)

        // Override XML backgrounds with ThemeManager colors
        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(themeColors.bg)
        // Root LinearLayout has android:background="@color/colorBgPrimary" — override it
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(themeColors.bg)

        initToolbar()
        initMenuGroups()
        applyThemeToGroups(themeColors)
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        refreshSettings()
        refreshPermissions()
        handler.removeCallbacks(permPoller)
        handler.postDelayed(permPoller, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(permPoller)
    }

    private fun refreshPermissions() {
        permAccessibility?.setTrailingText(if (ClawAccessibilityService.isRunning()) "Enabled" else "Disabled")
        permNotification?.setTrailingText(if (ForegroundService.isRunning()) "Enabled" else "Disabled")
        permOverlay?.setTrailingText(if (Settings.canDrawOverlays(this)) "Enabled" else "Disabled")
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        permBattery?.setTrailingText(if (pm.isIgnoringBatteryOptimizations(packageName)) "Unrestricted" else "Restricted")
        val storageOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
            else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        permStorage?.setTrailingText(if (storageOk) "Enabled" else "Disabled")
    }

    private fun initToolbar() {
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.settings_title))
            showBackButton(true) { finish() }
        }
    }

    private fun applyThemeToGroups(tc: io.agents.pokeclaw.ui.chat.ThemeManager.ChatColors) {
        val groups = listOf(
            R.id.permissionsGroup, R.id.channelGroup, R.id.modelGroup,
            R.id.appearanceGroup, R.id.toolsGroup, R.id.remoteGroup, R.id.aboutGroup
        )
        for (id in groups) {
            val g = findViewById<MenuGroup>(id) ?: continue
            g.setTitleColor(tc.aiText)
            g.setCardBackgroundColor(tc.toolbarBg)
            for (i in 0 until g.getMenuItemCount()) {
                g.getMenuItemAt(i)?.apply {
                    setTitleColor(tc.aiText)
                    setTrailingTextColor(tc.sendColor)
                    setLeadingIconColor(tc.aiText)
                    setTrailingIconColor(tc.aiText)
                }
            }
        }
        // Toolbar
        findViewById<CommonToolbar>(R.id.toolbar)?.apply {
            setBackgroundColor(tc.toolbarBg)
            setTitleColor(tc.aiText)
            findViewById<android.widget.ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }
    }

    private fun refreshSettings() {
        viewModel.refresh()
    }

    private fun initMenuGroups() {
        // Permissions
        val permissionsGroup = findViewById<MenuGroup>(R.id.permissionsGroup)
        permissionsGroup.setTitle("Permissions")

        permAccessibility = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_accessibility,
            title = getString(R.string.home_card_accessibility_title),
            onClick = {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, R.string.home_enable_accessibility, Toast.LENGTH_LONG).show()
            },
            showDivider = true
        )

        permNotification = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = getString(R.string.home_card_notification_title),
            onClick = {
                if (!ForegroundService.isRunning()) {
                    val started = ForegroundService.start(this@SettingsActivity)
                    if (!started && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                    }
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
                }
            },
            showDivider = true
        ).apply {
            setTrailingText(if (ForegroundService.isRunning()) "Enabled" else "Disabled")
        }

        permOverlay = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_window,
            title = getString(R.string.home_card_system_window_title),
            onClick = {
                if (!Settings.canDrawOverlays(this@SettingsActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.home_overlay_enabled, Toast.LENGTH_SHORT).show()
                }
            },
            showDivider = true
        ).apply {
            setTrailingText(if (Settings.canDrawOverlays(this@SettingsActivity)) "Enabled" else "Disabled")
        }

        permBattery = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_battery,
            title = getString(R.string.home_card_battery_title),
            onClick = {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:$packageName".toUri()
                    })
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.home_battery_ignored, Toast.LENGTH_SHORT).show()
                }
            },
            showDivider = true
        ).apply {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            setTrailingText(if (pm.isIgnoringBatteryOptimizations(packageName)) "Unrestricted" else "Restricted")
        }

        permStorage = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_storage,
            title = getString(R.string.home_card_storage_title),
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:$packageName".toUri()
                    })
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.home_storage_enabled, Toast.LENGTH_SHORT).show()
                }
            },
            showDivider = false
        ).apply {
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
                else ContextCompat.checkSelfPermission(this@SettingsActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            setTrailingText(if (granted) "Enabled" else "Disabled")
        }

        // Channel (hidden)
        val channelGroup = findViewById<MenuGroup>(R.id.channelGroup)
        channelGroup.setTitle(getString(R.string.settings_group_channel))

        menuItems[SettingsViewModel.MenuAction.DINGDING.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_dingtalk,
            title = getString(R.string.menu_dingtalk),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.DINGDING) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.FEISHU.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_feishu,
            title = getString(R.string.menu_feishu),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.FEISHU) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.QQ.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_qq,
            title = getString(R.string.menu_qq),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.QQ) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.DISCORD.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_discord,
            title = getString(R.string.menu_discord),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.DISCORD) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.TELEGRAM.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_telegram,
            title = getString(R.string.menu_telegram),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.TELEGRAM) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.WECHAT.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_wechat,
            title = getString(R.string.menu_wechat),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.WECHAT) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.LAN_CONFIG.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_lan_config,
            title = getString(R.string.menu_lan_config),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LAN_CONFIG) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.LAN_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))


        val modelGroup = findViewById<MenuGroup>(R.id.modelGroup)
        modelGroup.setTitle(getString(R.string.settings_group_model))

        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.icon_current_model,
            title = getString(R.string.menu_llm_config),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LLM_CONFIG) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        // Appearance
        val appearanceGroup = findViewById<MenuGroup>(R.id.appearanceGroup)
        appearanceGroup.setTitle("Appearance")

        appearanceGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_slideshow,
            title = "Theme",
            onClick = {
                startActivity(Intent(this, ThemeActivity::class.java))
            },
            showDivider = false
        ).apply {
            val themeId = KVUtils.getString("THEME_ID", "abyss_dark")
            val label = themeId.replace("_", " ").replaceFirstChar { it.uppercase() }
            setTrailingText(label)
        }

        // Tools
        val toolsGroup = findViewById<MenuGroup>(R.id.toolsGroup)
        toolsGroup.setTitle("Tools")

        toolsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_manage,
            title = "Manage Tools",
            onClick = {
                Toast.makeText(this, "12 tools enabled. Tool management coming soon.", Toast.LENGTH_SHORT).show()
            },
            showDivider = false
        ).apply {
            setTrailingText("12 enabled")
        }

        // Remote Control
        val remoteGroup = findViewById<MenuGroup>(R.id.remoteGroup)
        remoteGroup.setTitle("Remote Control")

        remoteGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_send,
            title = "Telegram Bot",
            onClick = {
                channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.TELEGRAM)
            },
            showDivider = true
        ).apply {
            val token = KVUtils.getTelegramBotToken()
            setTrailingText(if (token.isNotEmpty()) "Connected" else "Not connected")
        }

        remoteGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_call,
            title = "WhatsApp",
            onClick = { },
            showDivider = true
        ).apply {
            setTrailingText("Coming soon")
        }

        remoteGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_myplaces,
            title = "Web Dashboard",
            onClick = { },
            showDivider = false
        ).apply {
            setTrailingText("Coming soon")
        }

        // About
        val aboutGroup = findViewById<MenuGroup>(R.id.aboutGroup)
        aboutGroup.setTitle("About")

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_info_details,
            title = "PokeClaw",
            onClick = { },
            showDivider = true
        ).apply {
            setTrailingText("v${io.agents.pokeclaw.BuildConfig.VERSION_NAME}")
        }

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_share,
            title = "GitHub",
            onClick = {
                startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/agents-io/PokeClaw".toUri()))
            },
            showDivider = true
        ).apply {
            setTrailingText("agents-io/PokeClaw")
        }

        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_compass,
            title = "Built by",
            onClick = {
                startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/ithiria894".toUri()))
            },
            showDivider = false
        ).apply {
            setTrailingText("ithiria894")
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe settings changes and dynamically update UI
                launch {
                    viewModel.settingItems.collect { items ->
                        items.forEach { (key, value) ->
                            when (value) {
                                is SettingsViewModel.SettingValue.Text -> {
                                    menuItems[key]?.setTrailingText(value.text)
                                }
                                is SettingsViewModel.SettingValue.Switch -> {
                                    // Update switch state here if needed
                                }
                            }
                        }
                    }
                }

                // Observe H5 config changes (includes LLM/channel), refresh UI and re-initialize Agent and channels
                launch {
                    ConfigServerManager.configChanged.collect {
                        viewModel.refresh()
                        appViewModel.initAgent()
                        appViewModel.afterInit()
                    }
                }

                // Observe menu click events
                launch {
                    viewModel.menuClickEvent.collect { action ->
                        when (action) {
                            SettingsViewModel.MenuAction.DINGDING -> {
                                if (viewModel.isDingtalkBound()) {
                                    showUnbindDialog(getString(R.string.channel_dingtalk)) {
                                        viewModel.unbindDingtalk()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.DINGTALK)
                                }
                            }
                            SettingsViewModel.MenuAction.FEISHU -> {
                                if (viewModel.isFeishuBound()) {
                                    showUnbindDialog(getString(R.string.channel_feishu)) {
                                        viewModel.unbindFeishu()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.FEISHU)
                                }
                            }
                            SettingsViewModel.MenuAction.WECHAT -> {
                                if (viewModel.isWechatBound()) {
                                    showUnbindDialog(getString(R.string.channel_wechat)) {
                                        viewModel.unbindWeChat()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    viewModel.startWeChatQrLogin(this@SettingsActivity)
                                }
                            }
                            SettingsViewModel.MenuAction.QQ -> {
                                if (viewModel.isQqBound()) {
                                    showUnbindDialog(getString(R.string.channel_qq)) {
                                        viewModel.unbindQq()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.QQ)
                                }
                            }
                            SettingsViewModel.MenuAction.DISCORD -> {
                                if (viewModel.isDiscordBound()) {
                                    showUnbindDialog(getString(R.string.channel_discord)) {
                                        viewModel.unbindDiscord()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.DISCORD)
                                }
                            }
                            SettingsViewModel.MenuAction.TELEGRAM -> {
                                if (viewModel.isTelegramBound()) {
                                    showUnbindDialog(getString(R.string.channel_telegram)) {
                                        viewModel.unbindTelegram()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.TELEGRAM)
                                }
                            }
                            SettingsViewModel.MenuAction.LAN_CONFIG -> {
                                val result = viewModel.toggleConfigServer(this@SettingsActivity)
                                if (result == getString(R.string.lan_config_no_wifi)) {
                                    Toast.makeText(this@SettingsActivity, R.string.lan_config_no_wifi, Toast.LENGTH_SHORT).show()
                                }
                            }
                            SettingsViewModel.MenuAction.LLM_CONFIG -> {
                                llmConfigLauncher.launch(Intent(this@SettingsActivity, LlmConfigActivity::class.java))
                            }
                            null -> {}
                            else -> {}
                        }
                        viewModel.clearMenuClickEvent()
                    }
                }
            }
        }
    }

    /**
     * Show unbind confirmation dialog
     */
    private fun showUnbindDialog(channelName: String, onUnbind: () -> Unit) {
        AlertDialog.showWarm(
            context = this,
            title = getString(R.string.unbind_title),
            message = getString(R.string.unbind_message, channelName, channelName),
            actionTitle = getString(R.string.unbind_action),
            onAction = onUnbind
        )
    }
}
