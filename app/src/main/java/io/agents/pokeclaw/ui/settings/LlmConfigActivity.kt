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
import io.agents.pokeclaw.agent.llm.LocalModelManager
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
        val currentProvider = KVUtils.getLlmProvider()
        if (currentProvider == "LOCAL") {
            val localPath = KVUtils.getLocalModelPath()
            val localModel = models.find { localPath.endsWith(it.fileName) }
            if (localModel != null) {
                activeModelName.text = localModel.displayName
                activeModelMeta.text = "${localModel.fileName} · On-device"
                val downloaded = LocalModelManager.isModelDownloaded(this, localModel)
                activeModelStatus.text = if (downloaded) "● Ready" else "● Not downloaded"
                activeModelStatus.setTextColor(if (downloaded) getColor(R.color.colorSuccessPrimary) else getColor(R.color.colorWarningPrimary))
            } else if (localPath.isNotEmpty()) {
                activeModelName.text = java.io.File(localPath).nameWithoutExtension
                activeModelMeta.text = "On-device"
                activeModelStatus.text = "● Ready"
                activeModelStatus.setTextColor(getColor(R.color.colorSuccessPrimary))
            } else {
                activeModelName.text = "No model selected"
                activeModelMeta.text = "Download a model below"
                activeModelStatus.text = "● Not configured"
                activeModelStatus.setTextColor(Color.parseColor("#8b949e"))
            }
        } else {
            val cloudModel = KVUtils.getDefaultCloudModel().ifEmpty { KVUtils.getLlmModelName() }
            if (cloudModel.isNotEmpty()) {
                activeModelName.text = cloudModel
                val providerName = io.agents.pokeclaw.agent.CloudProvider.findProviderForModel(cloudModel)?.displayName ?: "Cloud"
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
        val currentModelId = if (currentProvider == "LOCAL") {
            val localPath = KVUtils.getLocalModelPath()
            models.find { localPath.endsWith(it.fileName) }?.id ?: ""
        } else KVUtils.getLlmModelName()

        // Build model list
        models.forEach { model ->
            val downloaded = LocalModelManager.isModelDownloaded(this, model)
            val isActive = model.id == currentModelId

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
                text = "${model.sizeBytes / 1_000_000} MB · ${model.minRamGb}GB+ RAM"
                textSize = 12f
                setTextColor(Color.parseColor("#8b949e"))
            }
            info.addView(descTV)

            row.addView(info)

            // Action button
            if (downloaded) {
                if (isActive) {
                    val check = TextView(this).apply {
                        text = "✓ Active"
                        textSize = 12f
                        setTextColor(getColor(R.color.colorSuccessPrimary))
                    }
                    row.addView(check)
                } else {
                    val useBtn = TextView(this).apply {
                        text = "Use"
                        textSize = 13f
                        setTextColor(getColor(R.color.colorBrandPrimary))
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        setOnClickListener {
                            val path = LocalModelManager.getModelPath(this@LlmConfigActivity, model)
                            if (path != null) {
                                // Save as default local model (independent of cloud config)
                                KVUtils.setLocalModelPath(path)
                                // Only switch active provider if currently on local tab
                                val currentProvider = KVUtils.getLlmProvider()
                                val shouldActivateLocal = currentProvider == "LOCAL" || !KVUtils.hasDefaultCloudModel()
                                if (shouldActivateLocal) {
                                    KVUtils.setLlmProvider("LOCAL")
                                    KVUtils.setLlmModelName(model.id)
                                }
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

        installKeyboardAwareScrolling(scrollView, listOf(etApiKey, etBaseUrl, etModelName))

        // Clear API key button
        btnClear.setOnClickListener {
            etApiKey.setText("")
            KVUtils.setApiKeyForProvider(selectedProvider.name, "")
            KVUtils.setLlmApiKey("")
            Toast.makeText(this, "API key cleared", Toast.LENGTH_SHORT).show()
        }

        // Determine current provider from saved config
        val savedProvider = KVUtils.getLlmProvider()
        val savedModel = if (savedProvider != "LOCAL") KVUtils.getLlmModelName() else ""
        selectedProvider = if (savedProvider == "LOCAL") CloudProvider.OPENAI
            else CloudProvider.findProviderForModel(savedModel) ?: CloudProvider.OPENAI
        selectedModelId = savedModel
        val providerKey = KVUtils.getApiKeyForProvider(selectedProvider.name)
        etApiKey.setText(providerKey.ifEmpty { KVUtils.getLlmApiKey() })

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
                etBaseUrl.setText(KVUtils.getLlmBaseUrl())
                etModelName.setText(savedModel)
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
            selectedModelId = provider.models.firstOrNull()?.id ?: ""
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
            KVUtils.setDefaultCloudModel(modelId)
            KVUtils.setDefaultCloudProvider(selectedProvider.name)
            KVUtils.setDefaultCloudBaseUrl(baseUrl)
            KVUtils.setLlmApiKey(apiKey)
            KVUtils.setApiKeyForProvider(selectedProvider.name, apiKey)
            // Only switch active provider to cloud if currently on cloud tab
            val currentProvider = KVUtils.getLlmProvider()
            if (currentProvider != "LOCAL") {
                KVUtils.setLlmProvider(selectedProvider.name)
                KVUtils.setLlmBaseUrl(baseUrl)
                KVUtils.setLlmModelName(modelId)
            }
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
