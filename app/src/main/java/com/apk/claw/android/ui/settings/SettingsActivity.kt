package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.widget.AlertDialog
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.MenuGroup
import com.apk.claw.android.widget.MenuItem
import kotlinx.coroutines.launch
import android.content.Intent
import com.apk.claw.android.appViewModel
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.service.ClawAccessibilityService
import androidx.core.net.toUri

/**
 * 设置页面
 */
class SettingsActivity : BaseActivity() {

    private val viewModel by lazy {
        ViewModelProvider(this)[SettingsViewModel::class.java]
    }

    // 保存 MenuItem 引用以便动态更新
    private val menuItems = mutableMapOf<String, MenuItem>()

    // 注册 LLM 配置页返回后刷新
    private val llmConfigLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        viewModel.refresh()
    }

    // 注册通道配置结果回调
    private val channelConfigLauncher = ChannelConfigActivity.registerLauncher(this) { result ->
        result?.let {
            // 配置成功后刷新设置项（刷新"已绑定"/"未绑定"状态）
            viewModel.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initToolbar()
        initMenuGroups()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        refreshSettings()
    }

    private fun initToolbar() {
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.settings_title))
            showBackButton(true) { finish() }
        }
    }

    private fun refreshSettings() {
        viewModel.refresh()
    }

    private fun initMenuGroups() {
        // 通道
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


        // Permissions
        val permissionsGroup = findViewById<MenuGroup>(R.id.permissionsGroup)
        permissionsGroup.setTitle("Permissions")

        permissionsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_manage,
            title = "Accessibility Service",
            onClick = {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Enable ApkClaw accessibility service", Toast.LENGTH_LONG).show()
            },
            showDivider = true
        ).apply {
            setTrailingText(if (ClawAccessibilityService.isRunning()) "Enabled" else "Disabled")
        }

        permissionsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_view,
            title = "Display Over Other Apps",
            onClick = {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
                } else {
                    Toast.makeText(this, "Already enabled", Toast.LENGTH_SHORT).show()
                }
            },
            showDivider = true
        ).apply {
            setTrailingText(if (Settings.canDrawOverlays(this@SettingsActivity)) "Enabled" else "Disabled")
        }

        permissionsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_lock_idle_low_battery,
            title = "Battery Optimization",
            onClick = {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:$packageName".toUri()
                    })
                } else {
                    Toast.makeText(this, "Already disabled", Toast.LENGTH_SHORT).show()
                }
            },
            showDivider = false
        ).apply {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            setTrailingText(if (pm.isIgnoringBatteryOptimizations(packageName)) "Unrestricted" else "Restricted")
        }

        val modelGroup = findViewById<MenuGroup>(R.id.modelGroup)
        modelGroup.setTitle(getString(R.string.settings_group_model))

        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.icon_current_model,
            title = getString(R.string.menu_llm_config),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LLM_CONFIG) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 监听设置项变化，动态更新 UI
                launch {
                    viewModel.settingItems.collect { items ->
                        items.forEach { (key, value) ->
                            when (value) {
                                is SettingsViewModel.SettingValue.Text -> {
                                    menuItems[key]?.setTrailingText(value.text)
                                }
                                is SettingsViewModel.SettingValue.Switch -> {
                                    // 如果有开关，可以在这里更新
                                }
                            }
                        }
                    }
                }

                // 监听 H5 页面配置变更（含 LLM/通道），刷新 UI 并重新初始化 Agent 与通道
                launch {
                    ConfigServerManager.configChanged.collect {
                        viewModel.refresh()
                        appViewModel.initAgent()
                        appViewModel.afterInit()
                    }
                }

                // 监听菜单点击事件
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
     * 显示解除绑定确认弹窗
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
