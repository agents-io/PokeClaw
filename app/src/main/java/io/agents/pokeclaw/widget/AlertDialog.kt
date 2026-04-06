// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.widget

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.ColorInt
import io.agents.pokeclaw.R

/**
 * 通用弹窗组件
 *
 * 单按钮模式（只有确认）：
 * ```
 * AlertDialog.show(context, "提示", "操作成功")
 * ```
 *
 * 双按钮模式（取消 + 确认）：
 * ```
 * AlertDialog.show(context, "提示", "确定要删除吗？",
 *     cancelTitle = "取消",
 *     onAction = { /* 确认 */ },
 *     onCancel = { /* 取消 */ }
 * )
 * ```
 */
class AlertDialog private constructor(context: Context) : Dialog(context, R.style.DialogStyle) {

    private var title: String = ""
    private var message: String = ""
    private var actionTitle: String = ""
    private var cancelTitle: String? = null
    private var isDismissible: Boolean = true

    @ColorInt private var actionBgColor: Int? = null
    @ColorInt private var actionTextColor: Int? = null
    @ColorInt private var actionBorderColor: Int? = null
    @ColorInt private var cancelBgColor: Int? = null
    @ColorInt private var cancelTextColor: Int? = null
    @ColorInt private var cancelBorderColor: Int? = null

    private var onAction: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    companion object {

        @JvmStatic
        fun show(
            context: Context,
            title: String,
            message: String = "",
            actionTitle: String = context.getString(R.string.common_confirm),
            cancelTitle: String? = null,
            @ColorInt actionBgColor: Int? = null,
            @ColorInt actionTextColor: Int? = null,
            @ColorInt actionBorderColor: Int? = null,
            @ColorInt cancelBgColor: Int? = null,
            @ColorInt cancelTextColor: Int? = null,
            @ColorInt cancelBorderColor: Int? = null,
            isDismissible: Boolean = true,
            onAction: (() -> Unit)? = null,
            onCancel: (() -> Unit)? = null
        ): AlertDialog {
            return AlertDialog(context).apply {
                this.title = title
                this.message = message
                this.actionTitle = actionTitle
                this.cancelTitle = cancelTitle
                this.actionBgColor = actionBgColor
                this.actionTextColor = actionTextColor
                this.actionBorderColor = actionBorderColor
                this.cancelBgColor = cancelBgColor
                this.cancelTextColor = cancelTextColor
                this.cancelBorderColor = cancelBorderColor
                this.isDismissible = isDismissible
                this.onAction = onAction
                this.onCancel = onCancel
                show()
            }
        }

        /** 危险操作弹窗：确认按钮为红色 */
        @JvmStatic
        fun showWarm(
            context: Context,
            title: String,
            message: String = "",
            actionTitle: String = context.getString(R.string.common_confirm),
            cancelTitle: String = context.getString(R.string.common_cancel),
            isDismissible: Boolean = true,
            onAction: (() -> Unit)? = null,
            onCancel: (() -> Unit)? = null
        ): AlertDialog {
            return show(
                context = context,
                title = title,
                message = message,
                actionTitle = actionTitle,
                cancelTitle = cancelTitle,
                actionBgColor = context.getColor(R.color.colorErrorPrimary),
                actionBorderColor = context.getColor(R.color.colorErrorPrimary),
                isDismissible = isDismissible,
                onAction = onAction,
                onCancel = onCancel
            )
        }

        /** 危险操作弹窗（反转）：确认在左边，取消在右边，用于不希望用户轻易点确认的场景 */
        @JvmStatic
        fun showWarmReverse(
            context: Context,
            title: String,
            message: String = "",
            actionTitle: String = context.getString(R.string.common_confirm),
            cancelTitle: String = context.getString(R.string.common_cancel),
            isDismissible: Boolean = true,
            onAction: (() -> Unit)? = null,
            onCancel: (() -> Unit)? = null
        ): AlertDialog {
            // 左右互换：cancel 位置放 action 样式，action 位置放 cancel 样式
            return show(
                context = context,
                title = title,
                message = message,
                actionTitle = cancelTitle,
                cancelTitle = actionTitle,
                actionBgColor = context.getColor(R.color.colorBgSecondary),
                actionTextColor = context.getColor(R.color.colorTextPrimary),
                actionBorderColor = context.getColor(R.color.colorBorderBase),
                cancelBgColor = context.getColor(R.color.colorErrorPrimary),
                cancelTextColor = context.getColor(R.color.colorTextInverse),
                cancelBorderColor = context.getColor(R.color.colorErrorPrimary),
                isDismissible = isDismissible,
                onAction = onCancel,
                onCancel = onAction
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_alert)

        setCancelable(isDismissible)
        setCanceledOnTouchOutside(isDismissible)

        window?.apply {
            setGravity(Gravity.CENTER)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.8).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        initViews()
    }

    private fun initViews() {
        // 标题
        findViewById<TextView>(R.id.tvTitle).text = title

        // 内容
        findViewById<TextView>(R.id.tvMessage).apply {
            if (message.isNotEmpty()) {
                text = message
                visibility = View.VISIBLE
            }
        }

        val btnAction = findViewById<KButton>(R.id.btnAction)
        val btnCancel = findViewById<KButton>(R.id.btnCancel)
        val btnSpacer = findViewById<View>(R.id.btnSpacer)

        // 确认按钮
        btnAction.text = actionTitle
        actionBgColor?.let { btnAction.setBgColor(it) }
        actionTextColor?.let { btnAction.setTextColor(it) }
        actionBorderColor?.let { btnAction.setBorderColor(it) }
        btnAction.setOnClickListener {
            dismiss()
            onAction?.invoke()
        }

        // 取消按钮
        if (cancelTitle != null) {
            btnCancel.visibility = View.VISIBLE
            btnSpacer.visibility = View.VISIBLE
            btnCancel.text = cancelTitle
            btnCancel.setBgColor(cancelBgColor ?: context.getColor(R.color.colorContainerBase))
            btnCancel.setTextColor(cancelTextColor ?: context.getColor(R.color.colorTextSecondary))
            btnCancel.setBorderColor(cancelBorderColor ?: context.getColor(R.color.colorBorderBase))
            btnCancel.setOnClickListener {
                dismiss()
                onCancel?.invoke()
            }
        }
    }
}
