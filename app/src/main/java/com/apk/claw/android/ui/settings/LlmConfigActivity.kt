package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.agent.LlmProvider
import com.apk.claw.android.agent.llm.LocalModelManager
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import java.util.concurrent.Executors

class LlmConfigActivity : BaseActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private var isDownloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.llm_config_title))
            showBackButton(true) { finish() }
        }

        val rgProvider = findViewById<RadioGroup>(R.id.rgProvider)
        val layoutCloud = findViewById<View>(R.id.layoutCloud)
        val layoutLocal = findViewById<View>(R.id.layoutLocal)
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val etModelName = findViewById<EditText>(R.id.etModelName)
        val spinnerModel = findViewById<Spinner>(R.id.spinnerModel)
        val tvModelStatus = findViewById<TextView>(R.id.tvModelStatus)
        val progressDownload = findViewById<ProgressBar>(R.id.progressDownload)
        val tvDownloadProgress = findViewById<TextView>(R.id.tvDownloadProgress)
        val btnDownload = findViewById<KButton>(R.id.btnDownload)
        val btnSave = findViewById<KButton>(R.id.btnSave)

        // Populate cloud fields
        etApiKey.setText(KVUtils.getLlmApiKey())
        etBaseUrl.setText(KVUtils.getLlmBaseUrl())
        etModelName.setText(KVUtils.getLlmModelName())

        // Setup model spinner
        val models = LocalModelManager.AVAILABLE_MODELS
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            models.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerModel.adapter = adapter

        // Update model status
        fun updateModelStatus() {
            val selectedModel = models[spinnerModel.selectedItemPosition]
            val downloaded = LocalModelManager.isModelDownloaded(this, selectedModel)
            tvModelStatus.text = if (downloaded) "Downloaded" else "Not downloaded"
            btnDownload.text = if (downloaded) "Re-download" else "Download Model"
        }

        // Restore provider selection
        val currentProvider = KVUtils.getLlmProvider()
        if (currentProvider == "LOCAL") {
            rgProvider.check(R.id.rbLocal)
            layoutCloud.visibility = View.GONE
            layoutLocal.visibility = View.VISIBLE
        }

        // Toggle between cloud and local
        rgProvider.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbCloud -> {
                    layoutCloud.visibility = View.VISIBLE
                    layoutLocal.visibility = View.GONE
                }
                R.id.rbLocal -> {
                    layoutCloud.visibility = View.GONE
                    layoutLocal.visibility = View.VISIBLE
                    updateModelStatus()
                }
            }
        }

        // Download button
        btnDownload.setOnClickListener {
            if (isDownloading) return@setOnClickListener

            val selectedModel = models[spinnerModel.selectedItemPosition]
            isDownloading = true
            btnDownload.isEnabled = false
            progressDownload.visibility = View.VISIBLE
            tvDownloadProgress.visibility = View.VISIBLE
            progressDownload.progress = 0

            executor.submit {
                LocalModelManager.downloadModel(this, selectedModel, object : LocalModelManager.DownloadCallback {
                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                        val percent = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                        val downloadedMb = bytesDownloaded / 1_000_000
                        val totalMb = totalBytes / 1_000_000
                        val speedMb = bytesPerSecond / 1_000_000.0
                        runOnUiThread {
                            progressDownload.progress = percent
                            tvDownloadProgress.text = "${downloadedMb}MB / ${totalMb}MB (%.1f MB/s)".format(speedMb)
                        }
                    }

                    override fun onComplete(modelPath: String) {
                        runOnUiThread {
                            isDownloading = false
                            btnDownload.isEnabled = true
                            progressDownload.visibility = View.GONE
                            tvDownloadProgress.visibility = View.GONE
                            tvModelStatus.text = "Downloaded"
                            Toast.makeText(this@LlmConfigActivity, "Model downloaded", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onError(error: String) {
                        runOnUiThread {
                            isDownloading = false
                            btnDownload.isEnabled = true
                            tvDownloadProgress.text = error
                            Toast.makeText(this@LlmConfigActivity, error, Toast.LENGTH_LONG).show()
                        }
                    }
                })
            }
        }

        // Save button
        btnSave.setOnClickListener {
            val isLocal = rgProvider.checkedRadioButtonId == R.id.rbLocal

            if (isLocal) {
                val selectedModel = models[spinnerModel.selectedItemPosition]
                val modelPath = LocalModelManager.getModelPath(this, selectedModel)
                if (modelPath == null) {
                    Toast.makeText(this, "Please download a model first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                KVUtils.setLlmProvider("LOCAL")
                KVUtils.setLocalModelPath(modelPath)
                KVUtils.setLlmModelName(selectedModel.id)
            } else {
                val apiKey = etApiKey.text.toString().trim()
                val baseUrl = etBaseUrl.text.toString().trim()
                val modelName = etModelName.text.toString().trim().ifEmpty { "" }

                if (apiKey.isEmpty() && baseUrl.isEmpty()) {
                    Toast.makeText(this, getString(R.string.llm_config_api_key_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                KVUtils.setLlmProvider("OPENAI")
                KVUtils.setLlmApiKey(apiKey)
                KVUtils.setLlmBaseUrl(baseUrl)
                KVUtils.setLlmModelName(modelName)
            }

            ClawApplication.appViewModelInstance.updateAgentConfig()
            ClawApplication.appViewModelInstance.initAgent()
            ClawApplication.appViewModelInstance.afterInit()
            Toast.makeText(this, getString(R.string.llm_config_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
