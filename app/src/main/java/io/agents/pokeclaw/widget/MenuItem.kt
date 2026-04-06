// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.widget

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import io.agents.pokeclaw.R

/**
 * Menu item component - clickable row with leading icon, title, and trailing text/arrow
 */
class MenuItem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val ivLeading: ImageView
    private val tvTitle: TextView
    private val viewRedDot: View
    private val tvTrailing: TextView
    private val ivTrailing: ImageView
    private val divider: View

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.widget_menu_item, this, true)

        ivLeading = findViewById(R.id.ivLeading)
        tvTitle = findViewById(R.id.tvTitle)
        viewRedDot = findViewById(R.id.viewRedDot)
        tvTrailing = findViewById(R.id.tvTrailing)
        ivTrailing = findViewById(R.id.ivTrailing)
        divider = findViewById(R.id.divider)

        // Show trailing arrow by default
        setShowTrailingIcon(true)
    }

    /**
     * Set leading icon
     */
    fun setLeadingIcon(@DrawableRes iconRes: Int) {
        ivLeading.setImageResource(iconRes)
    }

    /**
     * Set leading icon color
     */
    fun setLeadingIconColor(color: Int) {
        ivLeading.setColorFilter(color)
    }

    /**
     * Set title
     */
    fun setTitle(title: CharSequence) {
        tvTitle.text = title
    }

    /**
     * Set title text color
     */
    fun setTitleColor(color: Int) {
        tvTitle.setTextColor(color)
    }

    /**
     * Set trailing text
     */
    fun setTrailingText(text: CharSequence?) {
        tvTrailing.isVisible = !text.isNullOrEmpty()
        tvTrailing.text = text
    }

    /**
     * Set trailing text color
     */
    fun setTrailingTextColor(color: Int) {
        tvTrailing.setTextColor(color)
    }

    /**
     * Set trailing icon
     */
    fun setTrailingIcon(@DrawableRes iconRes: Int) {
        ivTrailing.setImageResource(iconRes)
    }

    /**
     * Set trailing icon color
     */
    fun setTrailingIconColor(color: Int) {
        ivTrailing.setColorFilter(color)
    }

    /**
     * Set whether to show trailing icon
     */
    fun setShowTrailingIcon(show: Boolean) {
        ivTrailing.visibility = if (show) View.VISIBLE else View.GONE
        val lp = tvTrailing.layoutParams as MarginLayoutParams
        lp.marginEnd = if (show) 0 else TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_PT, 8f, resources.displayMetrics
        ).toInt()
        tvTrailing.layoutParams = lp
    }

    /**
     * Set whether to show divider
     */
    fun setShowDivider(show: Boolean) {
        divider.isVisible = show
    }

    /**
     * Get trailing text
     */
    fun getTrailingText(): CharSequence? {
        return tvTrailing.text
    }

    /**
     * Set whether to show red dot
     */
    fun setShowRedDot(show: Boolean) {
        viewRedDot.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Get title text
     */
    fun getTitle(): CharSequence? {
        return tvTitle.text
    }
}
