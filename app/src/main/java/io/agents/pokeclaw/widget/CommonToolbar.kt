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
 * 通用 Toolbar 组件
 * 支持：标题、左侧返回按钮、右侧操作按钮/图标
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

    // 标题是否居中（默认true）
    private var isTitleCentered = true

    init {
        LayoutInflater.from(context).inflate(R.layout.common_toolbar, this, true)

        tvTitle = findViewById(R.id.tvTitle)
        ivBack = findViewById(R.id.ivBack)
        ivAction = findViewById(R.id.ivAction)
        tvAction = findViewById(R.id.tvAction)

        // 默认隐藏返回按钮和右侧按钮
        ivBack.visibility = GONE
        ivAction.visibility = GONE
        tvAction.visibility = GONE

        // 点击事件
        ivBack.setOnClickListener { onBackClick?.invoke() }
        ivAction.setOnClickListener { onActionClick?.invoke() }
        tvAction.setOnClickListener { onActionClick?.invoke() }
    }

    /**
     * 设置标题
     */
    fun setTitle(title: CharSequence?) {
        tvTitle.text = title
    }

    /**
     * 设置标题文字颜色
     */
    fun setTitleColor(color: Int) {
        tvTitle.setTextColor(color)
    }

    /**
     * 设置标题是否居中
     * @param centered true: 标题完全居中（默认）; false: 标题在返回按钮右侧
     */
    fun setTitleCentered(centered: Boolean) {
        isTitleCentered = centered
        updateTitleLayout()
    }

    /**
     * 更新标题布局
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
            // 完全居中
            params.width = ConstraintLayout.LayoutParams.WRAP_CONTENT
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToEnd = View.NO_ID
            params.marginStart = margin56
            params.marginEnd = margin56
            tvTitle.gravity = android.view.Gravity.CENTER
        } else {
            // 左对齐，在返回按钮右侧
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
     * 显示返回按钮
     * @param show 是否显示
     * @param listener 点击监听，为空时使用 onBackClick
     */
    fun showBackButton(show: Boolean = true, listener: (() -> Unit)? = null) {
        ivBack.visibility = if (show) VISIBLE else GONE
        listener?.let { onBackClick = it }
    }

    /**
     * 设置返回按钮图标
     */
    fun setBackIcon(@DrawableRes iconRes: Int) {
        ivBack.setImageResource(iconRes)
    }

    /**
     * 设置右侧图标按钮
     */
    fun setActionIcon(@DrawableRes iconRes: Int, listener: (() -> Unit)? = null) {
        ivAction.setImageResource(iconRes)
        ivAction.visibility = VISIBLE
        tvAction.visibility = GONE
        listener?.let { onActionClick = it }
    }

    /**
     * 设置右侧文字按钮
     */
    fun setActionText(text: CharSequence?, listener: (() -> Unit)? = null) {
        tvAction.text = text
        tvAction.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        ivAction.visibility = GONE
        listener?.let { onActionClick = it }
    }

    /**
     * 设置右侧文字颜色
     */
    fun setActionTextColor(color: Int) {
        tvAction.setTextColor(color)
    }

    /**
     * 隐藏右侧按钮
     */
    fun hideAction() {
        ivAction.visibility = GONE
        tvAction.visibility = GONE
    }

    /**
     * 获取标题是否居中
     */
    fun isTitleCentered(): Boolean = isTitleCentered
}
