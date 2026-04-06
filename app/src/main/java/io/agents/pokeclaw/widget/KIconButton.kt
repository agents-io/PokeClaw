// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.agents.pokeclaw.R

/**
 * Icon + text button
 * Left icon + right text, horizontally centered
 *
 * XML usage:
 *   <io.agents.pokeclaw.widget.KIconButton
 *       android:layout_width="match_parent"
 *       android:layout_height="48dp"
 *       android:layout_marginHorizontal="16dp" />
 *
 * Code usage:
 *   KIconButton(context).apply {
 *       setIcon(R.drawable.ic_xxx)
 *       setTitle("Start")
 *       setBgColor(Color.RED)
 *   }
 */
class KIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val ivIcon: ImageView
    private val tvTitle: TextView

    private var bgColor: Int = context.getColor(R.color.colorBrandPrimary)
    private var borderColor: Int = 0x00000000
    private var cornerRadiusPx: Float = pt(12f)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true

        val iconSize = pt(24f).toInt()
        val spacing = pt(8f).toInt()

        ivIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(ContextCompat.getColor(context, R.color.colorBrandOnPrimary))
        }
        addView(ivIcon, LayoutParams(iconSize, iconSize))

        tvTitle = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.colorBrandOnPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        val textParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = spacing
        }
        addView(tvTitle, textParams)

        applyBackground()
    }

    fun setTitle(title: CharSequence) {
        tvTitle.text = title
    }

    fun setTitleColor(color: Int) {
        tvTitle.setTextColor(color)
    }

    fun setIcon(@DrawableRes resId: Int) {
        ivIcon.setImageResource(resId)
    }

    fun setIconColor(color: Int) {
        ivIcon.setColorFilter(color)
    }

    fun setBgColor(color: Int) {
        bgColor = color
        applyBackground()
    }

    fun setBorderColor(color: Int) {
        borderColor = color
        applyBackground()
    }

    fun setCornerRadius(radiusPt: Float) {
        cornerRadiusPx = pt(radiusPt)
        applyBackground()
    }

    private fun applyBackground() {
        background = GradientDrawable().apply {
            setColor(bgColor)
            setCornerRadius(cornerRadiusPx)
            setStroke(pt(1f).toInt(), borderColor)
        }
    }

    private fun pt(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PT, value, resources.displayMetrics)
}
