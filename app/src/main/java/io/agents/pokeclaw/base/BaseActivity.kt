// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.base

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.blankj.utilcode.util.AdaptScreenUtils
import com.blankj.utilcode.util.BarUtils
import io.agents.pokeclaw.R

/**
 *
 * 屏幕适配单位用的是pt, 如果用dp在有些设备上会导致toast换行位置不正确
 */
open class BaseActivity : AppCompatActivity() {

    override fun getResources(): Resources {
        val resources = super.getResources()
        return AdaptScreenUtils.adaptWidth(resources, getDesignWidth())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyStatusBarMode()

        // 统一处理状态栏高度 - 在布局加载后应用
        applyStatusBarPadding()
    }

    /**
     * 主题切换时自动回调，更新状态栏文字颜色
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStatusBarMode()
    }

    /**
     * 根据当前主题模式设置状态栏文字颜色
     * 浅色主题 → 深色文字（light mode）
     * 深色主题 → 浅色文字（dark mode）
     */
    private fun applyStatusBarMode() {
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        BarUtils.setStatusBarLightMode(this, !isNightMode)
    }

    /**
     * 为根视图添加状态栏高度的顶部 padding
     * 子类可通过重写 isApplyStatusBarPadding() 来禁用
     */
    private fun applyStatusBarPadding() {
        window.decorView.post {
            val rootView = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
            rootView?.let { applyPaddingToRootView(it) }
        }
    }

    /**
     * 为根视图应用状态栏高度 padding
     * 如果布局已经处理了 insets，可以重写此方法自定义行为
     */
    protected open fun applyPaddingToRootView(rootView: View) {
        if (!isApplyStatusBarPadding()) return

        val statusBarHeight = BarUtils.getStatusBarHeight()
        val existingPaddingTop = rootView.paddingTop

        // 只在没有 padding 或 padding 小于状态栏高度时才添加
        if (existingPaddingTop < statusBarHeight) {
            rootView.updatePadding(top = statusBarHeight)
        }
    }

    /**
     * 子类可重写此方法来禁用自动添加状态栏高度
     */
    protected open fun isApplyStatusBarPadding(): Boolean = true

    /**
     * 屏幕适配-设计稿尺寸
     */
    open fun getDesignWidth(): Int {
        return 402
    }
}