// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import io.agents.pokeclaw.R
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.server.ConfigServerManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.KButton

/**
 * 通道配置页面（钉钉/飞书 AppKey、AppSecret 配置）
 */
class ChannelConfigActivity : BaseActivity() {

    private lateinit var etInput1: EditText
    private lateinit var etInput2: EditText
    private var isPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_config)

        val channelType = intent.getSerializableExtra(EXTRA_CHANNEL_TYPE) as? ChannelType
            ?: run {
                finish()
                return
            }

        initToolbar(channelType)
        initViews(channelType)
    }

    private fun initToolbar(channelType: ChannelType) {
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(when (channelType) {
                ChannelType.DINGTALK -> getString(R.string.channel_config_dingtalk_title)
                ChannelType.FEISHU -> getString(R.string.channel_config_feishu_title)
                ChannelType.QQ -> getString(R.string.channel_config_qq_title)
                ChannelType.DISCORD -> getString(R.string.channel_config_discord_title)
                ChannelType.TELEGRAM -> getString(R.string.channel_config_telegram_title)
            })
            showBackButton(true) { finish() }
        }
    }

    private fun initViews(channelType: ChannelType) {
        val tvTip = findViewById<TextView>(R.id.tvTip)
        val tvLabel1 = findViewById<TextView>(R.id.tvLabel1)
        val tvLabel2 = findViewById<TextView>(R.id.tvLabel2)
        etInput1 = findViewById(R.id.etInput1)
        etInput2 = findViewById(R.id.etInput2)
        val ivToggle = findViewById<ImageView>(R.id.ivTogglePassword)

        when (channelType) {
            ChannelType.DINGTALK -> {
                tvTip.text = getString(R.string.channel_config_dingtalk_tip)
                tvLabel1.text = "Client ID"
                tvLabel2.text = "Client Secret"
                etInput1.hint = getString(R.string.channel_config_dingtalk_hint1)
                etInput2.hint = getString(R.string.channel_config_dingtalk_hint2)
                etInput1.setText(KVUtils.getDingtalkAppKey())
                etInput2.setText(KVUtils.getDingtalkAppSecret())
            }
            ChannelType.FEISHU -> {
                tvTip.text = getString(R.string.channel_config_feishu_tip)
                tvLabel1.text = "App ID"
                tvLabel2.text = "App Secret"
                etInput1.hint = getString(R.string.channel_config_feishu_hint1)
                etInput2.hint = getString(R.string.channel_config_feishu_hint2)
                etInput1.setText(KVUtils.getFeishuAppId())
                etInput2.setText(KVUtils.getFeishuAppSecret())
            }
            ChannelType.QQ -> {
                tvTip.text = getString(R.string.channel_config_qq_tip)
                tvLabel1.text = "App ID"
                tvLabel2.text = "App Secret"
                etInput1.hint = getString(R.string.channel_config_qq_hint1)
                etInput2.hint = getString(R.string.channel_config_qq_hint2)
                etInput1.setText(KVUtils.getQqAppId())
                etInput2.setText(KVUtils.getQqAppSecret())
            }
            ChannelType.DISCORD -> {
                tvTip.text = getString(R.string.channel_config_discord_tip)
                tvLabel1.text = "Bot Token"
                tvLabel2.visibility = View.GONE
                (etInput2.parent.parent as View).visibility = View.GONE
                etInput1.hint = getString(R.string.channel_config_discord_hint1)
                etInput1.setText(KVUtils.getDiscordBotToken())
            }
            ChannelType.TELEGRAM -> {
                tvTip.text = getString(R.string.channel_config_telegram_tip)
                tvLabel1.text = "Bot Token"
                tvLabel2.visibility = View.GONE
                (etInput2.parent.parent as View).visibility = View.GONE
                etInput1.hint = getString(R.string.channel_config_telegram_hint1)
                etInput1.setText(KVUtils.getTelegramBotToken())
            }
        }

        // 密码可见切换
        ivToggle.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                etInput2.transformationMethod = HideReturnsTransformationMethod.getInstance()
                ivToggle.setImageResource(R.drawable.ic_visibility_on)
            } else {
                etInput2.transformationMethod = PasswordTransformationMethod.getInstance()
                ivToggle.setImageResource(R.drawable.ic_visibility_off)
            }
            etInput2.setSelection(etInput2.text.length)
        }

        findViewById<KButton>(R.id.btnSave).setOnClickListener {
            onSaveClicked(channelType)
        }

        // 局域网配置提示
        val tvLanHint = findViewById<TextView>(R.id.tvLanConfigHint)
        val ivCopy = findViewById<ImageView>(R.id.ivCopyAddress)
        val address = ConfigServerManager.getAddress()
        if (ConfigServerManager.isRunning() && address != null) {
            val url = "http://$address"
            tvLanHint.text = getString(R.string.channel_config_lan_hint_running, url)
            ivCopy.visibility = View.VISIBLE
            ivCopy.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("lan_config_url", url))
                Toast.makeText(this, R.string.channel_config_lan_copied, Toast.LENGTH_SHORT).show()
            }
        } else {
            tvLanHint.text = getString(R.string.channel_config_lan_hint)
            ivCopy.visibility = View.GONE
        }
    }

    private fun onSaveClicked(channelType: ChannelType) {
        val value1 = etInput1.text.toString().trim()
        val value2 = etInput2.text.toString().trim()

        when (channelType) {
            ChannelType.DINGTALK -> {
                KVUtils.setDingtalkAppKey(value1)
                KVUtils.setDingtalkAppSecret(value2)
            }
            ChannelType.FEISHU -> {
                KVUtils.setFeishuAppId(value1)
                KVUtils.setFeishuAppSecret(value2)
            }
            ChannelType.QQ -> {
                KVUtils.setQqAppId(value1)
                KVUtils.setQqAppSecret(value2)
            }
            ChannelType.DISCORD -> {
                KVUtils.setDiscordBotToken(value1)
            }
            ChannelType.TELEGRAM -> {
                KVUtils.setTelegramBotToken(value1)
            }
        }

        // 只重连对应的通道，不影响其他通道
        when (channelType) {
            ChannelType.DINGTALK -> ChannelManager.reinitDingTalkFromStorage()
            ChannelType.FEISHU -> ChannelManager.reinitFeiShuFromStorage()
            ChannelType.QQ -> ChannelManager.reinitQQFromStorage()
            ChannelType.DISCORD -> ChannelManager.reinitDiscordFromStorage()
            ChannelType.TELEGRAM -> ChannelManager.reinitTelegramFromStorage()
        }

        // 返回配置结果
        val isConfigured = when (channelType) {
            ChannelType.DISCORD, ChannelType.TELEGRAM -> value1.isNotEmpty()
            else -> value1.isNotEmpty() && value2.isNotEmpty()
        }
        val result = ChannelConfigResult(
            channelType = channelType,
            isConfigured = isConfigured
        )
        val intent = Intent().apply {
            putExtra(EXTRA_RESULT_CONFIG, result)
        }
        setResult(RESULT_OK, intent)
        Toast.makeText(this, R.string.channel_config_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    enum class ChannelType {
        DINGTALK, FEISHU, QQ, DISCORD, TELEGRAM
    }

    /**
     * 通道配置结果
     */
    data class ChannelConfigResult(
        val channelType: ChannelType,
        val isConfigured: Boolean
    ) : java.io.Serializable

    /**
     * ActivityResultContract 用于 registerForActivityResult
     */
    class ChannelConfigContract : ActivityResultContract<ChannelType, ChannelConfigResult?>() {
        override fun createIntent(context: Context, input: ChannelType): Intent {
            return Intent(context, ChannelConfigActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_TYPE, input)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): ChannelConfigResult? {
            return if (resultCode == RESULT_OK && intent != null) {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra(EXTRA_RESULT_CONFIG) as? ChannelConfigResult
            } else {
                null
            }
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_TYPE = "extra_channel_type"
        private const val EXTRA_RESULT_CONFIG = "extra_result_config"

        /**
         * 注册通道配置 Activity Result Launcher
         * 使用方式：
         * ```
         * private val channelConfigLauncher = ChannelConfigActivity.registerLauncher(this) { result ->
         *     result?.let {
         *         // 处理配置结果
         *         println("Channel: ${it.channelType}, Configured: ${it.isConfigured}")
         *     }
         * }
         *
         * // 启动配置页面
         * channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.DINGTALK)
         * ```
         */
        fun registerLauncher(
            caller: ActivityResultCaller,
            onResult: (ChannelConfigResult?) -> Unit
        ): ActivityResultLauncher<ChannelType> {
            return caller.registerForActivityResult(ChannelConfigContract()) { result ->
                onResult(result)
            }
        }

        fun start(context: Context, channelType: ChannelType) {
            val intent = Intent(context, ChannelConfigActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_TYPE, channelType)
            }
            context.startActivity(intent)
        }
    }
}
