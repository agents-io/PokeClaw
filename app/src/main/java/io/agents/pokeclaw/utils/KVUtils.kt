// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils

import android.content.Context
import com.tencent.mmkv.MMKV

/**
 * MMKV key-value storage utility
 *
 * Usage:
 *   // Initialize in Application.onCreate
 *   KVUtils.init(context)
 *
 *   // Read and write data
 *   KVUtils.putString("key", "value")
 *   val value = KVUtils.getString("key", "default")
 */
object KVUtils {


    // Discord bot config
    const val KEY_DISCORD_BOT_TOKEN = "DEFAULT_DISCORD_BOT_TOKEN"
    // Telegram bot config
    const val KEY_TELEGRAM_BOT_TOKEN = "DEFAULT_TELEGRAM_BOT_TOKEN"
    // WeChat iLink Bot config
    const val KEY_WECHAT_BOT_TOKEN = "DEFAULT_WECHAT_BOT_TOKEN"
    const val KEY_WECHAT_API_BASE_URL = "DEFAULT_WECHAT_API_BASE_URL"
    const val KEY_WECHAT_UPDATES_CURSOR = "DEFAULT_WECHAT_UPDATES_CURSOR"

    private lateinit var mmkv: MMKV

    private const val DEFAULT_INT = 0
    private const val DEFAULT_LONG = 0L
    private const val DEFAULT_BOOL = false
    private const val DEFAULT_FLOAT = 0f
    private const val DEFAULT_DOUBLE = 0.0

    /**
     * Call to initialize in Application.onCreate
     */
    fun init(context: Context) {
        MMKV.initialize(context)
        mmkv = MMKV.defaultMMKV()
    }

    // ==================== String ====================
    fun putString(key: String, value: String?): Boolean {
        return mmkv.encode(key, value)
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return mmkv.decodeString(key, defaultValue) ?: defaultValue
    }

    // ==================== Int ====================
    fun putInt(key: String, value: Int): Boolean {
        return mmkv.encode(key, value)
    }

    fun getInt(key: String, defaultValue: Int = DEFAULT_INT): Int {
        return mmkv.decodeInt(key, defaultValue)
    }

    // ==================== Long ====================
    fun putLong(key: String, value: Long): Boolean {
        return mmkv.encode(key, value)
    }

    fun getLong(key: String, defaultValue: Long = DEFAULT_LONG): Long {
        return mmkv.decodeLong(key, defaultValue)
    }

    // ==================== Boolean ====================
    fun putBoolean(key: String, value: Boolean): Boolean {
        return mmkv.encode(key, value)
    }

    fun getBoolean(key: String, defaultValue: Boolean = DEFAULT_BOOL): Boolean {
        return mmkv.decodeBool(key, defaultValue)
    }

    // ==================== Float ====================
    fun putFloat(key: String, value: Float): Boolean {
        return mmkv.encode(key, value)
    }

    fun getFloat(key: String, defaultValue: Float = DEFAULT_FLOAT): Float {
        return mmkv.decodeFloat(key, defaultValue)
    }

    // ==================== Double ====================
    fun putDouble(key: String, value: Double): Boolean {
        return mmkv.encode(key, value)
    }

    fun getDouble(key: String, defaultValue: Double = DEFAULT_DOUBLE): Double {
        return mmkv.decodeDouble(key, defaultValue)
    }

    // ==================== Bytes ====================
    fun putBytes(key: String, value: ByteArray?): Boolean {
        return mmkv.encode(key, value)
    }

    fun getBytes(key: String): ByteArray? {
        return mmkv.decodeBytes(key)
    }

    // ==================== Common Operations ====================
    fun contains(key: String): Boolean {
        return mmkv.containsKey(key)
    }

    fun remove(key: String) {
        mmkv.removeValueForKey(key)
    }

    fun remove(vararg keys: String) {
        mmkv.removeValuesForKeys(keys)
    }

    fun clear() {
        mmkv.clearAll()
    }

    fun getAllKeys(): Array<String> {
        return mmkv.allKeys() ?: emptyArray()
    }

    /**
     * Flush to disk synchronously (default is async)
     */
    fun sync() {
        mmkv.sync()
    }


    // ==================== Onboarding ====================
    private const val KEY_GUIDE_SHOWN = "KEY_GUIDE_SHOWN"

    fun isGuideShown(): Boolean = getBoolean(KEY_GUIDE_SHOWN, false)

    fun setGuideShown(shown: Boolean) = putBoolean(KEY_GUIDE_SHOWN, shown)

    // ==================== Discord Bot Config ====================
    fun getDiscordBotToken(): String = getString(KEY_DISCORD_BOT_TOKEN, "")
    fun setDiscordBotToken(value: String) = putString(KEY_DISCORD_BOT_TOKEN, value)

    // ==================== Telegram Bot Config ====================
    fun getTelegramBotToken(): String = getString(KEY_TELEGRAM_BOT_TOKEN, "")
    fun setTelegramBotToken(value: String) = putString(KEY_TELEGRAM_BOT_TOKEN, value)

    // ==================== WeChat iLink Bot Config ====================
    fun getWechatBotToken(): String = getString(KEY_WECHAT_BOT_TOKEN, "")
    fun setWechatBotToken(value: String) = putString(KEY_WECHAT_BOT_TOKEN, value)
    fun getWechatApiBaseUrl(): String = getString(KEY_WECHAT_API_BASE_URL, "")
    fun setWechatApiBaseUrl(value: String) = putString(KEY_WECHAT_API_BASE_URL, value)
    fun getWechatUpdatesCursor(): String = getString(KEY_WECHAT_UPDATES_CURSOR, "")
    fun setWechatUpdatesCursor(value: String) = putString(KEY_WECHAT_UPDATES_CURSOR, value)

    // ==================== LAN Config Service ====================
    private const val KEY_CONFIG_SERVER_ENABLED = "KEY_CONFIG_SERVER_ENABLED"
    fun isConfigServerEnabled(): Boolean = getBoolean(KEY_CONFIG_SERVER_ENABLED, false)
    fun setConfigServerEnabled(enabled: Boolean) = putBoolean(KEY_CONFIG_SERVER_ENABLED, enabled)

    private const val KEY_PENDING_ACCESSIBILITY_RETURN = "KEY_PENDING_ACCESSIBILITY_RETURN"
    private const val KEY_PENDING_ACCESSIBILITY_RETURN_AT = "KEY_PENDING_ACCESSIBILITY_RETURN_AT"
    private const val KEY_PENDING_NOTIFICATION_ACCESS_RETURN = "KEY_PENDING_NOTIFICATION_ACCESS_RETURN"
    private const val KEY_PENDING_NOTIFICATION_ACCESS_RETURN_AT = "KEY_PENDING_NOTIFICATION_ACCESS_RETURN_AT"
    private const val KEY_ACCESSIBILITY_LAST_CONNECTED_AT = "KEY_ACCESSIBILITY_LAST_CONNECTED_AT"
    private const val KEY_ACCESSIBILITY_LAST_HEARTBEAT_AT = "KEY_ACCESSIBILITY_LAST_HEARTBEAT_AT"
    private const val KEY_ACCESSIBILITY_LAST_INTERRUPTED_AT = "KEY_ACCESSIBILITY_LAST_INTERRUPTED_AT"
    private const val KEY_ACCESSIBILITY_LAST_DISCONNECTED_AT = "KEY_ACCESSIBILITY_LAST_DISCONNECTED_AT"
    private const val KEY_NOTIFICATION_LISTENER_LAST_CONNECTED_AT = "KEY_NOTIFICATION_LISTENER_LAST_CONNECTED_AT"
    private const val KEY_NOTIFICATION_LISTENER_LAST_DISCONNECTED_AT = "KEY_NOTIFICATION_LISTENER_LAST_DISCONNECTED_AT"

    fun markPendingAccessibilityReturn() {
        putBoolean(KEY_PENDING_ACCESSIBILITY_RETURN, true)
        putLong(KEY_PENDING_ACCESSIBILITY_RETURN_AT, System.currentTimeMillis())
    }

    fun hasPendingAccessibilityReturn(maxAgeMs: Long = 120_000L): Boolean {
        val pending = getBoolean(KEY_PENDING_ACCESSIBILITY_RETURN, false)
        val requestedAt = getLong(KEY_PENDING_ACCESSIBILITY_RETURN_AT, 0L)
        if (!pending || requestedAt <= 0L) return false
        return System.currentTimeMillis() - requestedAt <= maxAgeMs
    }

    fun consumePendingAccessibilityReturn(maxAgeMs: Long = 120_000L): Boolean {
        val pending = getBoolean(KEY_PENDING_ACCESSIBILITY_RETURN, false)
        val requestedAt = getLong(KEY_PENDING_ACCESSIBILITY_RETURN_AT, 0L)
        clearPendingAccessibilityReturn()
        if (!pending || requestedAt <= 0L) return false
        return System.currentTimeMillis() - requestedAt <= maxAgeMs
    }

    fun clearPendingAccessibilityReturn() {
        putBoolean(KEY_PENDING_ACCESSIBILITY_RETURN, false)
        putLong(KEY_PENDING_ACCESSIBILITY_RETURN_AT, 0L)
    }

    fun markPendingNotificationAccessReturn() {
        putBoolean(KEY_PENDING_NOTIFICATION_ACCESS_RETURN, true)
        putLong(KEY_PENDING_NOTIFICATION_ACCESS_RETURN_AT, System.currentTimeMillis())
    }

    fun hasPendingNotificationAccessReturn(maxAgeMs: Long = 120_000L): Boolean {
        val pending = getBoolean(KEY_PENDING_NOTIFICATION_ACCESS_RETURN, false)
        val requestedAt = getLong(KEY_PENDING_NOTIFICATION_ACCESS_RETURN_AT, 0L)
        if (!pending || requestedAt <= 0L) return false
        return System.currentTimeMillis() - requestedAt <= maxAgeMs
    }

    fun consumePendingNotificationAccessReturn(maxAgeMs: Long = 120_000L): Boolean {
        val pending = getBoolean(KEY_PENDING_NOTIFICATION_ACCESS_RETURN, false)
        val requestedAt = getLong(KEY_PENDING_NOTIFICATION_ACCESS_RETURN_AT, 0L)
        clearPendingNotificationAccessReturn()
        if (!pending || requestedAt <= 0L) return false
        return System.currentTimeMillis() - requestedAt <= maxAgeMs
    }

    fun clearPendingNotificationAccessReturn() {
        putBoolean(KEY_PENDING_NOTIFICATION_ACCESS_RETURN, false)
        putLong(KEY_PENDING_NOTIFICATION_ACCESS_RETURN_AT, 0L)
    }

    fun noteAccessibilityConnected() {
        putLong(KEY_ACCESSIBILITY_LAST_CONNECTED_AT, System.currentTimeMillis())
    }

    fun noteAccessibilityHeartbeat() {
        putLong(KEY_ACCESSIBILITY_LAST_HEARTBEAT_AT, System.currentTimeMillis())
    }

    fun noteAccessibilityInterrupted() {
        putLong(KEY_ACCESSIBILITY_LAST_INTERRUPTED_AT, System.currentTimeMillis())
    }

    fun noteAccessibilityDisconnected() {
        putLong(KEY_ACCESSIBILITY_LAST_DISCONNECTED_AT, System.currentTimeMillis())
    }

    fun getAccessibilityLastConnectedAt(): Long = getLong(KEY_ACCESSIBILITY_LAST_CONNECTED_AT, 0L)

    fun getAccessibilityLastHeartbeatAt(): Long = getLong(KEY_ACCESSIBILITY_LAST_HEARTBEAT_AT, 0L)

    fun getAccessibilityLastInterruptedAt(): Long = getLong(KEY_ACCESSIBILITY_LAST_INTERRUPTED_AT, 0L)

    fun getAccessibilityLastDisconnectedAt(): Long = getLong(KEY_ACCESSIBILITY_LAST_DISCONNECTED_AT, 0L)

    fun noteNotificationListenerConnected() {
        putLong(KEY_NOTIFICATION_LISTENER_LAST_CONNECTED_AT, System.currentTimeMillis())
    }

    fun noteNotificationListenerDisconnected() {
        putLong(KEY_NOTIFICATION_LISTENER_LAST_DISCONNECTED_AT, System.currentTimeMillis())
    }

    fun getNotificationListenerLastConnectedAt(): Long =
        getLong(KEY_NOTIFICATION_LISTENER_LAST_CONNECTED_AT, 0L)

    fun getNotificationListenerLastDisconnectedAt(): Long =
        getLong(KEY_NOTIFICATION_LISTENER_LAST_DISCONNECTED_AT, 0L)

    private const val KEY_LLM_API_KEY = "KEY_LLM_API_KEY"
    private const val KEY_LLM_BASE_URL = "KEY_LLM_BASE_URL"
    private const val KEY_LLM_MODEL_NAME = "KEY_LLM_MODEL_NAME"
    private const val KEY_LLM_PROVIDER = "KEY_LLM_PROVIDER"
    private const val KEY_LOCAL_MODEL_PATH = "KEY_LOCAL_MODEL_PATH"
    private const val KEY_LOCAL_BACKEND_PREFERENCE = "KEY_LOCAL_BACKEND_PREFERENCE"
    private const val KEY_LOCAL_CPU_SAFE_DEVICE = "KEY_LOCAL_CPU_SAFE_DEVICE"
    private const val KEY_LOCAL_CPU_SAFE_REASON = "KEY_LOCAL_CPU_SAFE_REASON"
    private const val KEY_LOCAL_CPU_SAFE_AT = "KEY_LOCAL_CPU_SAFE_AT"
    private const val KEY_LOCAL_GPU_VERIFIED_DEVICE = "KEY_LOCAL_GPU_VERIFIED_DEVICE"
    private const val KEY_LOCAL_GPU_VERIFIED_AT = "KEY_LOCAL_GPU_VERIFIED_AT"
    private const val KEY_PENDING_LOCAL_GPU_INIT_DEVICE = "KEY_PENDING_LOCAL_GPU_INIT_DEVICE"
    private const val KEY_PENDING_LOCAL_GPU_INIT_MODEL = "KEY_PENDING_LOCAL_GPU_INIT_MODEL"
    private const val KEY_PENDING_LOCAL_GPU_INIT_AT = "KEY_PENDING_LOCAL_GPU_INIT_AT"

    fun getLlmApiKey(): String = getString(KEY_LLM_API_KEY, "")
    fun setLlmApiKey(value: String) = putString(KEY_LLM_API_KEY, value)

    /** Per-provider API key storage — allows users to save keys for multiple providers simultaneously. */
    fun getApiKeyForProvider(provider: String): String =
        getString("KEY_LLM_API_KEY_${provider.uppercase()}", "")
    fun setApiKeyForProvider(provider: String, key: String) =
        putString("KEY_LLM_API_KEY_${provider.uppercase()}", key)
    fun getLlmBaseUrl(): String = getString(KEY_LLM_BASE_URL, "")
    fun setLlmBaseUrl(value: String) = putString(KEY_LLM_BASE_URL, value)
    fun getLlmModelName(): String = getString(KEY_LLM_MODEL_NAME, "")
    fun setLlmModelName(value: String) = putString(KEY_LLM_MODEL_NAME, value)
    fun getLlmProvider(): String = getString(KEY_LLM_PROVIDER, "OPENAI")
    fun setLlmProvider(value: String) = putString(KEY_LLM_PROVIDER, value)
    fun getLocalModelPath(): String = getString(KEY_LOCAL_MODEL_PATH, "")
    fun setLocalModelPath(value: String) = putString(KEY_LOCAL_MODEL_PATH, value)
    fun getLocalBackendPreference(): String = getString(KEY_LOCAL_BACKEND_PREFERENCE, "")
    fun setLocalBackendPreference(value: String) = putString(KEY_LOCAL_BACKEND_PREFERENCE, value)
    fun getLocalCpuSafeDevice(): String = getString(KEY_LOCAL_CPU_SAFE_DEVICE, "")
    fun setLocalCpuSafeDevice(value: String) = putString(KEY_LOCAL_CPU_SAFE_DEVICE, value)
    fun getLocalCpuSafeReason(): String = getString(KEY_LOCAL_CPU_SAFE_REASON, "")
    fun setLocalCpuSafeReason(value: String) = putString(KEY_LOCAL_CPU_SAFE_REASON, value)
    fun getLocalCpuSafeAt(): Long = getLong(KEY_LOCAL_CPU_SAFE_AT, 0L)
    fun setLocalCpuSafeAt(value: Long) = putLong(KEY_LOCAL_CPU_SAFE_AT, value)
    fun getLocalGpuVerifiedDevice(): String = getString(KEY_LOCAL_GPU_VERIFIED_DEVICE, "")
    fun setLocalGpuVerifiedDevice(value: String) = putString(KEY_LOCAL_GPU_VERIFIED_DEVICE, value)
    fun getLocalGpuVerifiedAt(): Long = getLong(KEY_LOCAL_GPU_VERIFIED_AT, 0L)
    fun setLocalGpuVerifiedAt(value: Long) = putLong(KEY_LOCAL_GPU_VERIFIED_AT, value)
    fun clearLocalCpuSafeMode() {
        remove(KEY_LOCAL_CPU_SAFE_DEVICE, KEY_LOCAL_CPU_SAFE_REASON, KEY_LOCAL_CPU_SAFE_AT)
    }
    fun clearLocalGpuVerified() {
        remove(KEY_LOCAL_GPU_VERIFIED_DEVICE, KEY_LOCAL_GPU_VERIFIED_AT)
    }
    fun getPendingLocalGpuInitDevice(): String = getString(KEY_PENDING_LOCAL_GPU_INIT_DEVICE, "")
    fun setPendingLocalGpuInitDevice(value: String) = putString(KEY_PENDING_LOCAL_GPU_INIT_DEVICE, value)
    fun getPendingLocalGpuInitModel(): String = getString(KEY_PENDING_LOCAL_GPU_INIT_MODEL, "")
    fun setPendingLocalGpuInitModel(value: String) = putString(KEY_PENDING_LOCAL_GPU_INIT_MODEL, value)
    fun getPendingLocalGpuInitAt(): Long = getLong(KEY_PENDING_LOCAL_GPU_INIT_AT, 0L)
    fun setPendingLocalGpuInitAt(value: Long) = putLong(KEY_PENDING_LOCAL_GPU_INIT_AT, value)
    fun clearPendingLocalGpuInit() {
        remove(
            KEY_PENDING_LOCAL_GPU_INIT_DEVICE,
            KEY_PENDING_LOCAL_GPU_INIT_MODEL,
            KEY_PENDING_LOCAL_GPU_INIT_AT,
        )
    }

    // ==================== Independent Default Models ====================
    // Local and Cloud each have their own default model config.
    // Switching tabs reads from these keys — they never overwrite each other.

    private const val KEY_DEFAULT_CLOUD_MODEL = "KEY_DEFAULT_CLOUD_MODEL"
    private const val KEY_DEFAULT_CLOUD_PROVIDER = "KEY_DEFAULT_CLOUD_PROVIDER"
    private const val KEY_DEFAULT_CLOUD_BASE_URL = "KEY_DEFAULT_CLOUD_BASE_URL"

    fun getDefaultCloudModel(): String = getString(KEY_DEFAULT_CLOUD_MODEL, "")
    fun setDefaultCloudModel(value: String) = putString(KEY_DEFAULT_CLOUD_MODEL, value)
    fun getDefaultCloudProvider(): String = getString(KEY_DEFAULT_CLOUD_PROVIDER, "")
    fun setDefaultCloudProvider(value: String) = putString(KEY_DEFAULT_CLOUD_PROVIDER, value)
    fun getDefaultCloudBaseUrl(): String = getString(KEY_DEFAULT_CLOUD_BASE_URL, "")
    fun setDefaultCloudBaseUrl(value: String) = putString(KEY_DEFAULT_CLOUD_BASE_URL, value)

    /** Returns true if a local default model is configured and the file exists. */
    fun hasDefaultLocalModel(): Boolean {
        val path = getLocalModelPath()
        return path.isNotEmpty() && java.io.File(path).exists()
    }

    /** Returns true if a cloud default model is configured (model + API key both present). */
    fun hasDefaultCloudModel(): Boolean {
        val model = getDefaultCloudModel()
        val provider = getDefaultCloudProvider().ifEmpty { "OPENAI" }
        val apiKey = getApiKeyForProvider(provider).ifEmpty { getLlmApiKey() }
        return model.isNotEmpty() && apiKey.isNotEmpty()
    }

    /** Returns true if LLM is configured (API key, base URL, or local model path is non-empty) */
    fun hasLlmConfig(): Boolean =
        getLlmApiKey().isNotEmpty() || getLlmBaseUrl().isNotEmpty() || getLocalModelPath().isNotEmpty()
}
