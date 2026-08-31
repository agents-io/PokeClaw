// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.automation

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ExternalAutomationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        ExternalAutomationEntrypoint.handle(
            context = this,
            intent = intent,
            launchFlags = Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        finish()
    }
}
