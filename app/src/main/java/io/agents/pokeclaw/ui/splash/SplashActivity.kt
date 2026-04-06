// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import io.agents.pokeclaw.R
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.ui.chat.ComposeChatActivity

/**
 * Splash screen - always navigates to the home screen; LLM does not need to be configured first, it can be set up in Settings
 */
class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* Back press disabled on splash screen */ }
        })

        val intent = Intent(this, ComposeChatActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        // Forward debug task extra
        getIntent()?.getStringExtra("task")?.let { intent.putExtra("task", it) }
        startActivity(intent)
        finish()
    }
}
