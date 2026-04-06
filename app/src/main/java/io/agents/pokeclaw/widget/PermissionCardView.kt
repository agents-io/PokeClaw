// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import io.agents.pokeclaw.R

/**
 * Permission card component
 *
 * When disabled, shows a SwitchCompat (unchecked); when enabled, hides the Switch and shows an "Enabled" label.
 * Card background color switches based on state (success/error container color).
 */
class PermissionCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CardView(context, attrs, defStyleAttr) {

    private val ivIcon: ImageView
    private val tvTitle: TextView
    private val tvSubtitle: TextView
    private val switchStatus: SwitchCompat
    private val tvEnabled: TextView

    init {
        // Default card style
        radius = dpToPx(12f)
        cardElevation = 0f
        setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorContainerLow))

        LayoutInflater.from(context).inflate(R.layout.widget_permission_card, this, true)

        ivIcon = findViewById(R.id.ivIcon)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        switchStatus = findViewById(R.id.switchStatus)
        tvEnabled = findViewById(R.id.tvEnabled)

        // Switch is display-only
        switchStatus.isClickable = false
        switchStatus.isEnabled = false

        // Read XML attributes
        attrs?.let {
            val ta = context.obtainStyledAttributes(it, R.styleable.PermissionCardView)
            ta.getResourceId(R.styleable.PermissionCardView_cardIcon, 0).takeIf { it != 0 }
                ?.let { ivIcon.setImageResource(it) }
            ta.getString(R.styleable.PermissionCardView_cardTitle)?.let { tvTitle.text = it }
            ta.getString(R.styleable.PermissionCardView_cardSubtitle)?.let { tvSubtitle.text = it }
            ta.recycle()
        }
    }

    /**
     * Set permission enabled state; automatically switches UI style
     */
    fun setPermissionEnabled(enabled: Boolean) {
        if (enabled) {
            switchStatus.visibility = View.INVISIBLE
            tvEnabled.visibility = View.VISIBLE
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorSuccessContainer))
        } else {
            switchStatus.visibility = View.VISIBLE
            switchStatus.isChecked = false
            tvEnabled.visibility = View.INVISIBLE
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorErrorContainer))
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
