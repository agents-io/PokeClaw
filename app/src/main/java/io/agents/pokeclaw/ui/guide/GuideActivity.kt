// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.guide

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import io.agents.pokeclaw.R
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.utils.KVUtils

class GuideActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        bindSection(
            findViewById(R.id.guideAccessibility),
            R.drawable.ic_accessibility,
            R.string.guide_title_accessibility,
            R.string.guide_desc_accessibility
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.home_enable_accessibility, Toast.LENGTH_LONG).show()
        }
        bindSection(
            findViewById(R.id.guideNotification),
            R.drawable.ic_notification,
            R.string.guide_title_notification,
            R.string.guide_desc_notification
        ) {
            ForegroundService.start(this)
        }
        bindSection(
            findViewById(R.id.guideOverlay),
            R.drawable.ic_window,
            R.string.guide_title_overlay,
            R.string.guide_desc_overlay
        ) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
            }
        }
        bindSection(
            findViewById(R.id.guideBattery),
            R.drawable.ic_battery,
            R.string.guide_title_battery,
            R.string.guide_desc_battery
        ) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            })
        }
        bindSection(
            findViewById(R.id.guideStorage),
            R.drawable.ic_storage,
            R.string.guide_title_storage,
            R.string.guide_desc_storage
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:$packageName".toUri()
                })
            }
        }

        findViewById<View>(R.id.btnStart).setOnClickListener { finishGuide() }
        findViewById<View>(R.id.tvSkip).setOnClickListener { finishGuide() }
    }

    private fun bindSection(view: View, iconRes: Int, titleRes: Int, descRes: Int, onClick: () -> Unit) {
        view.findViewById<ImageView>(R.id.ivIcon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.tvTitle).setText(titleRes)
        view.findViewById<TextView>(R.id.tvDescription).setText(descRes)
        view.setOnClickListener { onClick() }
    }

    private fun finishGuide() {
        KVUtils.setGuideShown(true)
        finish()
    }
}
