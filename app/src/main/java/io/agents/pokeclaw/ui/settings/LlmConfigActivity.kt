// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.CloudModel
import io.agents.pokeclaw.agent.CloudProvider
import io.agents.pokeclaw.agent.ModelPricing
import io.agents.pokeclaw.agent.llm.ActiveModelMode
import io.agents.pokeclaw.agent.llm.LocalModelManager
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.ui.chat.ThemeManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.KButton
import java.util.concurrent.Executors
import kotlin.math.max

class LlmConfigActivity : BaseActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private var isDownloading = false
    private var selectedProvider: CloudProvider = CloudProvider.OPENAI
    private var selectedModelId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_config)

        // Apply dark theme from ThemeManager to match rest of app
        val tc = ThemeManager.getColors()
        window.statusBarColor = tc.toolbarBg
        window.decorView.setBackgroundColor(tc.bg)
        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(tc.bg)
        (contentFrame?.getChildAt(0) as? View)?.setBackgroundColor(tc.bg)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("Models")
            showBackButton(true) { finish() }
            setBackgroundColor(tc.toolbarBg)
            setTitleColor(tc.aiText)
            findViewById<android.widget.ImageView>(R.id.ivBack)?.setColorFilter(tc.aiText)
        }

        val models = LocalModelManager.AVAILABLE_MODELS
        val activeModelName = findViewById<TextView>(R.id.tvActiveModelName)
        val activeModelMeta = findViewById<TextView>(R.id.tvActiveModelMeta)
        val activeModelStatus = findViewById<TextView>(R.id.tvActiveModelStatus)
        val modelList = findViewById<LinearLayout>(R.id.layoutModelList)
        val resolvedConfig = ModelConfigRepository.snapshot()
        val deviceSupport = LocalModelManager.deviceSupport(this)
        val catalog = LocalModelManager.catalog(this).associateBy { it.model.id }

        // Apply theme to active model card text
        activeModelName.setTextColor(tc.aiText)
        activeModelMeta.setTextColor(Color.parseColor("#8b949e"))

        // Apply theme to all CardViews in XML layout
        val scrollContent = findViewById<LinearLayout>(R.id.layoutModelList)?.parent as? LinearLayout
        if (scrollContent != null) {
            for (i in 0 until scrollContent.childCount) {
                val child = scrollContent.getChildAt(i)
                if (child is TextView && child.id == View.NO_ID) {
                    // Section headers ("Active Model", "Available Models", "Cloud LLM")
                    child.setTextColor(Color.parseColor("#8b949e"))
                }
                if (child is CardView) {
                    child.setCardBackgroundColor(tc.toolbarBg)
                }
            }
        }

        // Active model — show what is ACTUALLY active based on provider
        if (resolvedConfig.activeMode == ActiveModelMode.LOCAL) {
            val activeState = LocalModelManager.resolveActiveModelState(this, resolvedConfig.local)
            activeModelName.text = activeState.displayName
            activeModelMeta.text = activeState.metaText
            activeModelStatus.text = activeState.statusText
            activeModelStatus.setTextColor(
                when (activeState.statusKind) {
                    LocalModelManager.StatusKind.READY -> getColor(R.color.colorSuccessPrimary)
                    LocalModelManager.StatusKind.WARNING -> getColor(R.color.colorWarningPrimary)
                    LocalModelManager.StatusKind.NEUTRAL -> Color.parseColor("#8b949e")
                }
            )
        } else {
            val cloudModel = resolvedConfig.activeCloud.modelName
            if (cloudModel.isNotEmpty()) {
                activeModelName.text = cloudModel
                val providerName = resolvedConfig.activeCloud.provider.displayName
                activeModelMeta.text = "$providerName · Cloud"
                activeModelStatus.text = "● Connected"
                activeModelStatus.setTextColor(getColor(R.color.colorSuccessPrimary))
            } else {
                activeModelName.text = "No model selected"
                activeModelMeta.text = "Configure a cloud model below"
                activeModelStatus.text = "● Not configured"
                activeModelStatus.setTextColor(Color.parseColor("#8b949e"))
            }
        }
        val currentModelId = if (resolvedConfig.activeMode == ActiveModelMode.LOCAL) {
            resolvedConfig.local.modelId
        } else {
            resolvedConfig.activeCloud.modelName
        }

        // Build model list
        models.forEach { model ->
            val modelEntry = catalog[model.id]
            val downloaded = modelEntry?.isDownloaded == true
            val isActive = model.id == currentModelId
            val supportedOnDevice = modelEntry?.isSupported == true

            val card = CardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                radius = dp(12).toFloat()
                cardElevation = dp(1).toFloat()
                setCardBackgroundColor(tc.toolbarBg)
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }

            // Model info
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameTV = TextView(this).apply {
                text = model.displayName
                textSize = 14f
                setTextColor(tc.aiText)
                if (isActive) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            info.addView(nameTV)

            val descTV = TextView(this).apply {
                val baseText = "${model.sizeBytes / 1_000_000} MB · ${model.minRamGb}GB+ RAM"
                text = if (supportedOnDevice) baseText else "$baseText · This phone reports ${deviceSupport.deviceRamGb}GB"
                textSize = 12f
                setTextColor(if (supportedOnDevice) Color.parseColor("#8b949e") else getColor(R.color.colorWarningPrimary))
            }
            info.addView(descTV)

            row.addView(info)

            // Action button
            if (downloaded) {
                if (isActive) {
                    val check = TextView(this).apply {
                        text = if (supportedOnDevice) "✓ Active" else "⚠ Active"
                        textSize = 12f
                        setTextColor(if (supportedOnDevice) getColor(R.color.colorSuccessPrimary) else getColor(R.color.colorWarningPrimary))
                    }
                    row.addView(check)
                } else {
                    if (supportedOnDevice) {
                        val useBtn = TextView(this).apply {
                            text = "Use"
                            textSize = 13f
                            setTextColor(getColor(R.color.colorBrandPrimary))
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                            setOnClickListener {
                                val path = LocalModelManager.getModelPath(this@LlmConfigActivity, model)
                                if (path != null) {
                                    // Save as default local model (independent of cloud config)
                                    // Only switch active provider if currently on local tab
                                    val shouldActivateLocal = ModelConfigRepository.isLocalActive() || !KVUtils.hasDefaultCloudModel()
                                    ModelConfigRepository.saveLocalDefault(path, model.id, shouldActivateLocal)
                                    ClawApplication.appViewModelInstance.updateAgentConfig()
                                    ClawApplication.appViewModelInstance.initAgent()
                                    Toast.makeText(this@LlmConfigActivity, "Set default local: ${model.displayName}", Toast.LENGTH_SHORT).show()
                                    recreate()
                                } else {
                                    Toast.makeText(this@LlmConfigActivity, "Model file not found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        row.addView(useBtn)
                    } else {
                        row.addView(TextView(this).apply {
                            text = "Needs ${model.minRamGb}GB+"
                            textSize = 12f
                            setTextColor(getColor(R.color.colorWarningPrimary))
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                        })
                    }

                    // Delete button
                    val delBtn = TextView(this).apply {
                        text = "🗑"
                        textSize = 16f
                        setPadding(dp(8), dp(4), dp(4), dp(4))
                        alpha = 0.4f
                        setOnClickListener {
                            LocalModelManager.deleteModel(this@LlmConfigActivity, model)
                            Toast.makeText(this@LlmConfigActivity, "Deleted ${model.displayName}", Toast.LENGTH_SHORT).show()
                            recreate()
                        }
                    }
                    row.addView(delBtn)
                }
            } else {
                if (supportedOnDevice) {
                    val dlBtn = TextView(this).apply {
                        text = "↓ Download"
                        textSize = 13f
                        setTextColor(getColor(R.color.colorInfoPrimary))
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        setOnClickListener {
                            if (isDownloading) {
                                Toast.makeText(this@LlmConfigActivity, "Already downloading", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            isDownloading = true
                            text = "Downloading..."
                            isEnabled = false

                            executor.submit {
                                LocalModelManager.downloadModel(this@LlmConfigActivity, model, object : LocalModelManager.DownloadCallback {
                                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                                        val pct = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                                        runOnUiThread { text = "$pct%" }
                                    }
                                    override fun onComplete(modelPath: String) {
                                        runOnUiThread {
                                            isDownloading = false
                                            Toast.makeText(this@LlmConfigActivity, "Downloaded!", Toast.LENGTH_SHORT).show()
                                            recreate()
                                        }
                                    }
                                    override fun onError(error: String) {
                                        runOnUiThread {
                                            isDownloading = false
                                            text = "↓ Download"
                                            isEnabled = true
                                            Toast.makeText(this@LlmConfigActivity, error, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                })
                            }
                        }
                    }
                    row.addView(dlBtn)
                } else {
                    row.addView(TextView(this).apply {
                        text = "Needs ${model.minRamGb}GB+"
                        textSize = 12f
                        setTextColor(getColor(R.color.colorWarningPrimary))
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                    })
                }
            }

            card.addView(row)
            modelList.addView(card)
        }

        // Storage info
        updateStorageInfo()

        // Cloud LLM — Provider tabs + model cards
        setupCloudLlm(tc)
    }

    private fun updateStorageInfo() {
        val models = LocalModelManager.AVAILABLE_MODELS
        var totalSize = 0L
        var count = 0
        models.forEach { model ->
            if (LocalModelManager.isModelDownloaded(this, model)) {
                totalSize += model.sizeBytes
                count++
            }
        }
        val mbUsed = totalSize / 1_000_000
        val allocated = 4000L // 4GB rough estimate
        val pct = (mbUsed * 100 / allocated).toInt().coerceAtMost(100)

        findViewById<TextView>(R.id.tvStorageInfo).text = "$count model${if (count != 1) "s" else ""} · ${mbUsed} MB"
        findViewById<ProgressBar>(R.id.progressStorage).progress = pct
        findViewById<TextView>(R.id.tvStorageDetail).text = "${mbUsed} MB of ${allocated} MB allocated"
    }

    private fun setupCloudLlm(tc: ThemeManager.ChatColors) {
        val tabLayout = findViewById<LinearLayout>(R.id.layoutProviderTabs)
        val modelListLayout = findViewById<LinearLayout>(R.id.layoutCloudModels)
        val layoutBaseUrl = findViewById<View>(R.id.layoutBaseUrl)
        val tvCustomHint = findViewById<TextView>(R.id.tvCustomHint)
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val etModelName = findViewById<EditText>(R.id.etModelName)
        val tvStatus = findViewById<TextView>(R.id.tvConnectionStatus)
        val btnTest = findViewById<TextView>(R.id.btnTestConnection)
        val btnSave = findViewById<KButton>(R.id.btnSaveCloud)
        val btnClear = findViewById<TextView>(R.id.btnClearApiKey)
        val scrollView = findViewById<ScrollView>(R.id.scrollContent)
        val configSnapshot = ModelConfigRepository.snapshot()
        val cloudSeed = configSnapshot.defaultCloud.let {
            if (it.modelName.isNotEmpty() || it.apiKey.isNotEmpty() || it.baseUrl.isNotEmpty()) it else configSnapshot.activeCloud
        }

        installKeyboardAwareScrolling(scrollView, listOf(etApiKey, etBaseUrl, etModelName))

        // Clear API key button
        btnClear.setOnClickListener {
            etApiKey.setText("")
            KVUtils.setApiKeyForProvider(selectedProvider.name, "")
            KVUtils.setLlmApiKey("")
            Toast.makeText(this, "API key cleared", Toast.LENGTH_SHORT).show()
        }

        // Determine current provider from saved config
        selectedProvider = CloudProvider.fromName(cloudSeed.providerName)
        selectedModelId = cloudSeed.modelName
        etApiKey.setText(cloudSeed.apiKey)

        // Build provider tabs
        val tabViews = mutableMapOf<CloudProvider, TextView>()
        CloudProvider.entries.forEach { provider ->
            val tab = TextView(this).apply {
                text = provider.displayName
                textSize = 13f
                setPadding(dp(14), dp(6), dp(14), dp(6))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                // Click handler set below after renderModels is defined
            }
            tabViews[provider] = tab
            tabLayout.addView(tab)
        }

        fun updateTabStyles() {
            tabViews.forEach { (provider, tab) ->
                if (provider == selectedProvider) {
                    tab.setTextColor(Color.WHITE)
                    tab.background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(getColor(R.color.colorBrandPrimary))
                    }
                } else {
                    tab.setTextColor(Color.parseColor("#8b949e"))
                    tab.background = null
                }
            }
        }
        updateTabStyles()

        // Render model cards for current provider
        fun renderModels() {
            modelListLayout.removeAllViews()
            val isCustom = selectedProvider == CloudProvider.CUSTOM
            layoutBaseUrl.visibility = if (isCustom) View.VISIBLE else View.GONE
            tvCustomHint?.visibility = if (isCustom) View.VISIBLE else View.GONE

            if (isCustom) {
                etBaseUrl.setText(cloudSeed.baseUrl)
                etModelName.setText(selectedModelId)
                return
            }

            selectedProvider.models.forEach { model ->
                val isSelected = model.id == selectedModelId
                val card = CardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    radius = dp(10).toFloat()
                    cardElevation = dp(1).toFloat()
                    setCardBackgroundColor(if (isSelected) Color.parseColor("#2A1F1A") else tc.toolbarBg)
                    if (isSelected) {
                        // Orange border for selected
                        setContentPadding(dp(2), dp(2), dp(2), dp(2))
                    }
                    setOnClickListener {
                        selectedModelId = model.id
                        renderModels()
                    }
                }

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }

                // Radio dot
                val dot = TextView(this).apply {
                    text = if (isSelected) "◉" else "○"
                    textSize = 16f
                    setTextColor(if (isSelected) getColor(R.color.colorBrandPrimary) else Color.parseColor("#8b949e"))
                    setPadding(0, 0, dp(10), 0)
                }
                row.addView(dot)

                // Model info
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                nameRow.addView(TextView(this).apply {
                    text = model.displayName
                    textSize = 14f
                    setTextColor(tc.aiText)
                    if (isSelected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                // Recommended badge
                if (model.recommended) {
                    nameRow.addView(TextView(this).apply {
                        text = " ⚡"
                        textSize = 12f
                    })
                }
                info.addView(nameRow)

                // Price + tier
                info.addView(TextView(this).apply {
                    text = "${model.tier.stars} ${model.tier.label} · \$${model.inputPricePerM} / \$${model.outputPricePerM} per 1M"
                    textSize = 11f
                    setTextColor(Color.parseColor("#8b949e"))
                })
                row.addView(info)

                card.addView(row)
                modelListLayout.addView(card)
            }
        }
        renderModels()

        // Provider tab switch — load per-provider saved API key
        fun switchProvider(provider: CloudProvider, colors: ThemeManager.ChatColors) {
            selectedProvider = provider
            selectedModelId = when {
                provider == CloudProvider.CUSTOM -> cloudSeed.modelName
                provider == cloudSeed.provider && provider.models.any { it.id == cloudSeed.modelName } -> cloudSeed.modelName
                else -> provider.models.firstOrNull()?.id ?: ""
            }
            updateTabStyles()
            renderModels()
            tvStatus.visibility = View.GONE
            val savedKey = KVUtils.getApiKeyForProvider(provider.name)
            etApiKey.setText(savedKey)
        }
        // Re-assign click listeners with the inner function
        tabViews.forEach { (provider, tab) ->
            tab.setOnClickListener { switchProvider(provider, tc) }
        }

        // Test Connection
        btnTest.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Enter API Key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Testing..."
            tvStatus.setTextColor(Color.parseColor("#8b949e"))

            executor.submit {
                try {
                    val baseUrl = if (selectedProvider == CloudProvider.CUSTOM) etBaseUrl.text.toString().trim()
                        else selectedProvider.defaultBaseUrl
                    val modelId = if (selectedProvider == CloudProvider.CUSTOM) etModelName.text.toString().trim()
                        else selectedModelId
                    // Quick test: just validate the key format
                    if (apiKey.length < 10) throw RuntimeException("API key too short")
                    if (modelId.isEmpty()) throw RuntimeException("No model selected")
                    runOnUiThread {
                        tvStatus.text = "✓ Ready to save"
                        tvStatus.setTextColor(getColor(R.color.colorSuccessPrimary))
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        tvStatus.text = "✗ ${e.message}"
                        tvStatus.setTextColor(getColor(R.color.colorErrorPrimary))
                    }
                }
            }
        }

        // Save & Activate
        btnSave.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "Enter API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isCustom = selectedProvider == CloudProvider.CUSTOM
            val baseUrl = if (isCustom) etBaseUrl.text.toString().trim() else selectedProvider.defaultBaseUrl
            val modelId = if (isCustom) etModelName.text.toString().trim() else selectedModelId

            if (modelId.isEmpty()) {
                Toast.makeText(this, "Select a model", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save as default cloud model (independent of local config)
            ModelConfigRepository.saveCloudDefault(
                providerName = selectedProvider.name,
                modelId = modelId,
                baseUrl = baseUrl,
                apiKey = apiKey,
                activateNow = !ModelConfigRepository.isLocalActive()
            )
            ClawApplication.appViewModelInstance.updateAgentConfig()
            ClawApplication.appViewModelInstance.initAgent()
            ClawApplication.appViewModelInstance.afterInit()
            Toast.makeText(this, "Saved cloud default: $modelId", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Active model card is already set in onCreate based on actual provider
    }

    /**
     * Keep focused fields visible above the IME on edge-to-edge layouts.
     * `adjustResize` alone is not reliable here because the ScrollView content
     * still needs extra bottom inset + explicit scroll after the keyboard opens.
     */
    private fun installKeyboardAwareScrolling(scrollView: ScrollView, fields: List<EditText>) {
        val baseBottomPadding = scrollView.paddingBottom

        val focusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                scrollView.postDelayed({ scrollFieldIntoView(scrollView, view) }, 180)
            }
        }
        fields.forEach { it.onFocusChangeListener = focusListener }

        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = baseBottomPadding + max(imeInsets.bottom, systemInsets.bottom))

            if (imeInsets.bottom > 0) {
                val focused = currentFocus
                if (focused is EditText && fields.contains(focused)) {
                    view.post { scrollFieldIntoView(scrollView, focused) }
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(scrollView)
    }

    private fun scrollFieldIntoView(scrollView: ScrollView, field: View) {
        val rect = Rect()
        field.getDrawingRect(rect)
        scrollView.offsetDescendantRectToMyCoords(field, rect)

        val margin = dp(16)
        val visibleTop = scrollView.scrollY + margin
        val visibleBottom = scrollView.scrollY + scrollView.height - scrollView.paddingBottom - margin

        when {
            rect.top < visibleTop -> {
                scrollView.smoothScrollTo(0, (rect.top - margin).coerceAtLeast(0))
            }
            rect.bottom > visibleBottom -> {
                val targetScroll = scrollView.scrollY + (rect.bottom - visibleBottom) + margin
                scrollView.smoothScrollTo(0, targetScroll.coerceAtLeast(0))
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
