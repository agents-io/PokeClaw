// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.llm.LocalModelManager
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.KButton
import java.util.concurrent.Executors

class LlmConfigActivity : BaseActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private var isDownloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("Models")
            showBackButton(true) { finish() }
        }

        val models = LocalModelManager.AVAILABLE_MODELS
        val activeModelName = findViewById<TextView>(R.id.tvActiveModelName)
        val activeModelMeta = findViewById<TextView>(R.id.tvActiveModelMeta)
        val activeModelStatus = findViewById<TextView>(R.id.tvActiveModelStatus)
        val modelList = findViewById<LinearLayout>(R.id.layoutModelList)

        // Active model
        val currentModelId = KVUtils.getLlmModelName()
        val currentModel = models.find { it.id == currentModelId }
        if (currentModel != null) {
            activeModelName.text = currentModel.displayName
            activeModelMeta.text = "${currentModel.fileName} · On-device"
            val downloaded = LocalModelManager.isModelDownloaded(this, currentModel)
            activeModelStatus.text = if (downloaded) "● Ready" else "● Not downloaded"
            activeModelStatus.setTextColor(if (downloaded) getColor(R.color.colorSuccessPrimary) else getColor(R.color.colorWarningPrimary))
        } else {
            activeModelName.text = "No model selected"
            activeModelMeta.text = "Download a model below"
            activeModelStatus.text = "● Not configured"
            activeModelStatus.setTextColor(getColor(R.color.colorTextTertiary))
        }

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
                setCardBackgroundColor(getColor(R.color.colorContainerBrighten))
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
                setTextColor(getColor(R.color.colorTextPrimary))
                if (isActive) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            info.addView(nameTV)

            val descTV = TextView(this).apply {
                text = "${model.sizeBytes / 1_000_000} MB · ${model.minRamGb}GB+ RAM"
                textSize = 12f
                setTextColor(getColor(R.color.colorTextTertiary))
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
                                KVUtils.setLlmProvider("LOCAL")
                                KVUtils.setLocalModelPath(path)
                                KVUtils.setLlmModelName(model.id)
                                ClawApplication.appViewModelInstance.updateAgentConfig()
                                ClawApplication.appViewModelInstance.initAgent()
                                Toast.makeText(this@LlmConfigActivity, "Switched to ${model.displayName}", Toast.LENGTH_SHORT).show()
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

        // Cloud LLM
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val etModelName = findViewById<EditText>(R.id.etModelName)
        etApiKey.setText(KVUtils.getLlmApiKey())
        etBaseUrl.setText(KVUtils.getLlmBaseUrl())
        etModelName.setText(if (KVUtils.getLlmProvider() != "LOCAL") KVUtils.getLlmModelName() else "")

        findViewById<KButton>(R.id.btnSaveCloud).setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            val baseUrl = etBaseUrl.text.toString().trim()
            val modelName = etModelName.text.toString().trim()

            if (apiKey.isEmpty() && baseUrl.isEmpty()) {
                Toast.makeText(this, "Enter API Key or Base URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            KVUtils.setLlmProvider("OPENAI")
            KVUtils.setLlmApiKey(apiKey)
            KVUtils.setLlmBaseUrl(baseUrl)
            KVUtils.setLlmModelName(modelName)
            ClawApplication.appViewModelInstance.updateAgentConfig()
            ClawApplication.appViewModelInstance.initAgent()
            ClawApplication.appViewModelInstance.afterInit()
            Toast.makeText(this, "Cloud LLM saved", Toast.LENGTH_SHORT).show()
            finish()
        }
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
