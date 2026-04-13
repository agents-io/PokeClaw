// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.net.toUri
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
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.AppRequirement
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.server.ConfigServerManager
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.support.DebugReportManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var permNotifAccess: io.agents.pokeclaw.widget.MenuItem? = null
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
        val capabilities = AppCapabilityCoordinator.snapshot(this)
        permAccessibility?.setTrailingText(capabilities.accessibilityStatusLabel)
        permNotification?.setTrailingText(capabilities.notificationPermissionStatusLabel)
        permNotifAccess?.setTrailingText(capabilities.notificationAccessStatusLabel)
        permOverlay?.setTrailingText(if (capabilities.overlayGranted) "Enabled" else "Disabled")
        permBattery?.setTrailingText(if (capabilities.batteryOptimizationIgnored) "Unrestricted" else "Restricted")
        permStorage?.setTrailingText(if (capabilities.storageAccessGranted) "Enabled" else "Disabled")
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
                AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.ACCESSIBILITY)
                Toast.makeText(this, R.string.home_enable_accessibility, Toast.LENGTH_LONG).show()
            },
            showDivider = true
        )

        permNotification = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = getString(R.string.home_card_notification_title),
            onClick = {
                if (!AppCapabilityCoordinator.isNotificationPermissionGranted(this@SettingsActivity)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                    }
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
                }
            },
            showDivider = true
        ).apply {
            setTrailingText(
                if (AppCapabilityCoordinator.isNotificationPermissionGranted(this@SettingsActivity)) "Enabled" else "Disabled"
            )
        }

        permNotifAccess = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = "Notification Access",
            onClick = {
                AppCapabilityCoordinator.openSystemSettings(this, AppRequirement.NOTIFICATION_ACCESS)
            },
            showDivider = true
        )

        permOverlay = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_window,
            title = getString(R.string.home_card_system_window_title),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).overlayGranted) {
                    Toast.makeText(this@SettingsActivity, R.string.home_overlay_enabled, Toast.LENGTH_SHORT).show()
                } else {
                    AppCapabilityCoordinator.openSystemSettings(this@SettingsActivity, AppRequirement.OVERLAY)
                }
            },
            showDivider = true
        )

        permBattery = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_battery,
            title = getString(R.string.home_card_battery_title),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).batteryOptimizationIgnored) {
                    Toast.makeText(this@SettingsActivity, R.string.home_battery_ignored, Toast.LENGTH_SHORT).show()
                } else {
                    AppCapabilityCoordinator.openSystemSettings(this@SettingsActivity, AppRequirement.BATTERY_OPTIMIZATION)
                }
            },
            showDivider = true
        )

        permStorage = permissionsGroup.addMenuItem(
            leadingIcon = R.drawable.ic_storage,
            title = getString(R.string.home_card_storage_title),
            onClick = {
                if (AppCapabilityCoordinator.snapshot(this@SettingsActivity).storageAccessGranted) {
                    Toast.makeText(this@SettingsActivity, R.string.home_storage_enabled, Toast.LENGTH_SHORT).show()
                } else {
                    AppCapabilityCoordinator.openSystemSettings(this@SettingsActivity, AppRequirement.STORAGE)
                }
            },
            showDivider = false
        )

        // Channel (hidden)
        val channelGroup = findViewById<MenuGroup>(R.id.channelGroup)
        channelGroup.setTitle(getString(R.string.settings_group_channel))

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

        // Task Budget (inline in model group)
        modelGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_recent_history,
            title = "Task Budget",
            onClick = { showBudgetDialog() },
            showDivider = false
        ).apply {
            setTrailingText(io.agents.pokeclaw.agent.TaskBudget.describeCurrentBudget())
        }

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
            leadingIcon = android.R.drawable.ic_menu_upload,
            title = "Share Debug Report",
            onClick = { shareDebugReport() },
            showDivider = true
        ).apply {
            setTrailingText("ZIP logs + state")
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

    private fun shareDebugReport() {
        lifecycleScope.launch {
            Toast.makeText(this@SettingsActivity, "Preparing debug report…", Toast.LENGTH_SHORT).show()
            runCatching {
                withContext(Dispatchers.IO) {
                    DebugReportManager.buildReport(this@SettingsActivity)
                }
            }.onSuccess { report ->
                val uri = FileProvider.getUriForFile(
                    this@SettingsActivity,
                    "${packageName}.fileprovider",
                    report
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_SUBJECT, "PokeClaw debug report ${io.agents.pokeclaw.BuildConfig.VERSION_NAME}")
                    putExtra(Intent.EXTRA_TEXT, "Attach this debug report when reporting a PokeClaw issue.")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(Intent.createChooser(intent, "Share debug report"))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@SettingsActivity, "No app available to share the report", Toast.LENGTH_LONG).show()
                }
            }.onFailure { error ->
                XLog.e("SettingsActivity", "Failed to build debug report", error)
                Toast.makeText(this@SettingsActivity, "Failed to build debug report", Toast.LENGTH_LONG).show()
            }
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

    private fun showBudgetDialog() {
        val currentTokens = io.agents.pokeclaw.agent.TaskBudget.getConfiguredMaxTokens()
        val currentCost = io.agents.pokeclaw.agent.TaskBudget.getConfiguredMaxCost()

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val tokenLabel = android.widget.TextView(this).apply {
            text = "Max tokens per task"
            setTextColor(getColor(R.color.colorTextPrimary))
        }
        layout.addView(tokenLabel)

        val tokenOptions = arrayOf("Unlimited", "10K", "50K", "100K", "200K", "250K", "500K")
        val tokenValues = arrayOf<Int?>(null, 10_000, 50_000, 100_000, 200_000, 250_000, 500_000)
        val selectedTokenIndex = when (currentTokens) {
            null -> 0
            else -> tokenValues.indexOfFirst { it == currentTokens }.takeIf { it >= 0 }
                ?: tokenValues.indices
                    .filter { tokenValues[it] != null }
                    .minByOrNull { kotlin.math.abs((tokenValues[it] ?: 0) - currentTokens) }
                ?: 0
        }

        val tokenSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, tokenOptions)
            setSelection(selectedTokenIndex)
        }
        layout.addView(tokenSpinner)

        val costLabel = android.widget.TextView(this).apply {
            text = "\nMax cost per task (USD)"
            setTextColor(getColor(R.color.colorTextPrimary))
        }
        layout.addView(costLabel)

        val costInput = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Blank = no cost cap"
            setText(currentCost?.let { String.format("%.2f", it) } ?: "")
            setTextColor(getColor(R.color.colorTextPrimary))
        }
        layout.addView(costInput)

        android.app.AlertDialog.Builder(this)
            .setTitle("Task Budget")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newTokens = tokenValues[tokenSpinner.selectedItemPosition]
                val newCost = costInput.text.toString().trim().toDoubleOrNull()

                when (newTokens) {
                    null -> io.agents.pokeclaw.agent.TaskBudget.clearMaxTokens()
                    else -> io.agents.pokeclaw.agent.TaskBudget.saveMaxTokens(newTokens)
                }
                when {
                    newCost == null || newCost <= 0.0 -> io.agents.pokeclaw.agent.TaskBudget.clearMaxCost()
                    else -> io.agents.pokeclaw.agent.TaskBudget.saveMaxCost(newCost)
                }

                val summary = io.agents.pokeclaw.agent.TaskBudget.describeCurrentBudget()
                Toast.makeText(this, "Budget: $summary", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
