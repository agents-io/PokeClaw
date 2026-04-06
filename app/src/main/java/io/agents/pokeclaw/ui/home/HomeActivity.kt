package io.agents.pokeclaw.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import io.agents.pokeclaw.R
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.channel.Channel as ChannelEnum
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.ui.chat.ChatActivity
import io.agents.pokeclaw.ui.guide.GuideActivity
import io.agents.pokeclaw.ui.settings.SettingsActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.KButton

class HomeActivity : BaseActivity() {

    private lateinit var btnCancelTask: KButton
    private lateinit var etTaskInput: EditText
    private lateinit var btnSendTask: KButton
    private lateinit var tvTaskStatus: TextView

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Toolbar
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitleCentered(false)
            setTitle(getString(R.string.app_name))
            setActionIcon(R.drawable.ic_settings) {
                startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))
            }
        }

        // Chat button
        findViewById<KButton>(R.id.btnChat).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        // Cancel task button
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
                Toast.makeText(this, "Please enable Accessibility Service first (Settings → Permissions)", Toast.LENGTH_LONG).show()
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

        showGuideIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateCancelTaskVisibility()
    }

    private fun showGuideIfNeeded() {
        if (!KVUtils.isGuideShown()) {
            startActivity(Intent(this, GuideActivity::class.java))
        }
    }

    private fun updateCancelTaskVisibility() {
        btnCancelTask.visibility = if (appViewModel.isTaskRunning()) View.VISIBLE else View.GONE
    }
}
