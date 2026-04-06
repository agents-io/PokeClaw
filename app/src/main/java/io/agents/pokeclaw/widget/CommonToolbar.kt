// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.widget

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import io.agents.pokeclaw.R

/**
 * Common Toolbar component
 * Supports: title, left back button, right action button/icon
 */
class CommonToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tvTitle: TextView
    private val ivBack: ImageView
    private val ivAction: ImageView
    private val tvAction: TextView

    var onBackClick: (() -> Unit)? = null
    var onActionClick: (() -> Unit)? = null

    // Whether the title is centered (default: true)
    private var isTitleCentered = true

    init {
        LayoutInflater.from(context).inflate(R.layout.common_toolbar, this, true)

        tvTitle = findViewById(R.id.tvTitle)
        ivBack = findViewById(R.id.ivBack)
        ivAction = findViewById(R.id.ivAction)
        tvAction = findViewById(R.id.tvAction)

        // Hide back button and right button by default
        ivBack.visibility = GONE
        ivAction.visibility = GONE
        tvAction.visibility = GONE

        // Click listeners
        ivBack.setOnClickListener { onBackClick?.invoke() }
        ivAction.setOnClickListener { onActionClick?.invoke() }
        tvAction.setOnClickListener { onActionClick?.invoke() }
    }

    /**
     * Set title
     */
    fun setTitle(title: CharSequence?) {
        tvTitle.text = title
    }

    /**
     * Set title text color
     */
    fun setTitleColor(color: Int) {
        tvTitle.setTextColor(color)
    }

    /**
     * Set whether the title is centered
     * @param centered true: fully centered (default); false: title to the right of the back button
     */
    fun setTitleCentered(centered: Boolean) {
        isTitleCentered = centered
        updateTitleLayout()
    }

    /**
     * Update title layout
     */
    private fun updateTitleLayout() {
        val margin56 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_PT, 56f, context.resources.displayMetrics
        ).toInt()
        val margin8 = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_PT, 8f, context.resources.displayMetrics
        ).toInt()

        val params = tvTitle.layoutParams as ConstraintLayout.LayoutParams
        if (isTitleCentered) {
            // Fully centered
            params.width = ConstraintLayout.LayoutParams.WRAP_CONTENT
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToEnd = View.NO_ID
            params.marginStart = margin56
            params.marginEnd = margin56
            tvTitle.gravity = android.view.Gravity.CENTER
        } else {
            // Left-aligned, to the right of the back button
            params.width = 0
            params.startToStart = View.NO_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToEnd = R.id.ivBack
            params.marginStart = margin8
            params.marginEnd = margin8
            tvTitle.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        tvTitle.layoutParams = params
    }

    /**
     * Show or hide the back button
     * @param show whether to show
     * @param listener click listener; falls back to onBackClick when null
     */
    fun showBackButton(show: Boolean = true, listener: (() -> Unit)? = null) {
        ivBack.visibility = if (show) VISIBLE else GONE
        listener?.let { onBackClick = it }
    }

    /**
     * Set back button icon
     */
    fun setBackIcon(@DrawableRes iconRes: Int) {
        ivBack.setImageResource(iconRes)
    }

    /**
     * Set right-side icon button
     */
    fun setActionIcon(@DrawableRes iconRes: Int, listener: (() -> Unit)? = null) {
        ivAction.setImageResource(iconRes)
        ivAction.visibility = VISIBLE
        tvAction.visibility = GONE
        listener?.let { onActionClick = it }
    }

    /**
     * Set right-side text button
     */
    fun setActionText(text: CharSequence?, listener: (() -> Unit)? = null) {
        tvAction.text = text
        tvAction.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        ivAction.visibility = GONE
        listener?.let { onActionClick = it }
    }

    /**
     * Set right-side text color
     */
    fun setActionTextColor(color: Int) {
        tvAction.setTextColor(color)
    }

    /**
     * Hide right-side button
     */
    fun hideAction() {
        ivAction.visibility = GONE
        tvAction.visibility = GONE
    }

    /**
     * Get whether the title is centered
     */
    fun isTitleCentered(): Boolean = isTitleCentered
}
