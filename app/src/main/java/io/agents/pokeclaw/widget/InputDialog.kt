// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.widget

import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import io.agents.pokeclaw.R
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 通用输入底部弹窗
 *
 * 基本用法：
 * ```
 * InputDialog.show(context, "修改昵称") { text ->
 *     // 处理输入内容
 * }
 * ```
 *
 * 完整参数：
 * ```
 * InputDialog.show(
 *     context = this,
 *     title = "修改昵称",
 *     presetText = "当前昵称",
 *     hint = "请输入新昵称",
 *     minLength = 2,
 *     maxLength = 20,
 *     confirmText = "保存",
 *     onComplete = { text -> /* 处理 */ }
 * )
 * ```
 *
 * 数字输入：
 * ```
 * InputDialog.show(
 *     context = this,
 *     title = "设置数量",
 *     numberOnly = true,
 *     canZero = false,
 *     onComplete = { text -> /* 处理 */ }
 * )
 * ```
 *
 * 自定义校验：
 * ```
 * InputDialog.show(
 *     context = this,
 *     title = "输入邮箱",
 *     inputValidate = { text ->
 *         if (text.contains("@")) ValidateResult(true)
 *         else ValidateResult(false, "请输入有效的邮箱地址")
 *     },
 *     onComplete = { text -> /* 处理 */ }
 * )
 * ```
 */
class InputDialog private constructor(context: Context) : BottomSheetDialog(context) {

    private var title: String = ""
    private var presetText: String = ""
    private var hint: String = ""
    private var minLength: Int = -1
    private var maxLength: Int = -1
    private var numberOnly: Boolean = false
    private var canZero: Boolean = true
    private var confirmText: String? = null
    private var inputValidate: ((String) -> ValidateResult)? = null
    private var onComplete: ((String) -> Unit)? = null

    /** 校验结果 */
    data class ValidateResult(
        val isValid: Boolean,
        val message: String? = null
    )

    companion object {

        @JvmStatic
        fun show(
            context: Context,
            title: String,
            presetText: String = "",
            hint: String = "",
            minLength: Int = -1,
            maxLength: Int = -1,
            numberOnly: Boolean = false,
            canZero: Boolean = true,
            confirmText: String? = null,
            inputValidate: ((String) -> ValidateResult)? = null,
            onComplete: (String) -> Unit
        ): InputDialog {
            return InputDialog(context).apply {
                this.title = title
                this.presetText = presetText
                this.hint = hint
                this.minLength = minLength
                this.maxLength = maxLength
                this.numberOnly = numberOnly
                this.canZero = canZero
                this.confirmText = confirmText
                this.inputValidate = inputValidate
                this.onComplete = onComplete
                show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_input, null)
        setContentView(view)

        // 设置底部弹窗背景透明，使用布局自身的圆角背景
        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(context.getColor(R.color.colorBgPrimary))

        window?.apply {
            navigationBarColor = context.getColor(R.color.colorBgPrimary)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        initViews(view)
    }

    private fun initViews(view: View) {
        // 标题
        view.findViewById<TextView>(R.id.tvTitle).text = title

        // 关闭按钮
        view.findViewById<ImageView>(R.id.btnClose).setOnClickListener { dismiss() }

        val etInput = view.findViewById<EditText>(R.id.etInput)
        val btnClear = view.findViewById<ImageView>(R.id.btnClear)
        val inputContainer = view.findViewById<FrameLayout>(R.id.inputContainer)
        val btnConfirm = view.findViewById<KButton>(R.id.btnConfirm)

        // 预置文本和提示
        etInput.setText(presetText)
        etInput.hint = hint
        etInput.setSelection(etInput.text.length)

        // 数字输入模式
        if (numberOnly) {
            etInput.inputType = InputType.TYPE_CLASS_NUMBER
        }

        // 最大长度限制
        if (maxLength > 0) {
            etInput.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        }

        // 清除按钮显隐
        btnClear.visibility = if (presetText.isNotEmpty()) View.VISIBLE else View.GONE
        etInput.doAfterTextChanged { text ->
            btnClear.visibility = if (text?.isNotEmpty() == true) View.VISIBLE else View.GONE
        }

        // 清除按钮点击
        btnClear.setOnClickListener { etInput.text.clear() }

        // 焦点变化时切换输入框边框颜色
        etInput.setOnFocusChangeListener { _, hasFocus ->
            inputContainer.setBackgroundResource(
                if (hasFocus) R.drawable.bg_input_field_focused
                else R.drawable.bg_input_field
            )
        }

        // 键盘 Done 按钮触发确认
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performConfirm(etInput)
                true
            } else false
        }

        // 确认按钮
        confirmText?.let { btnConfirm.text = it }
        btnConfirm.setOnClickListener { performConfirm(etInput) }

        // 自动弹出键盘
        etInput.requestFocus()
    }

    private fun performConfirm(etInput: EditText) {
        val text = etInput.text.toString().trim()

        // 自定义校验优先
        inputValidate?.let { validate ->
            val result = validate(text)
            if (!result.isValid) {
                showToast(result.message ?: "")
                return
            }
            dismiss()
            onComplete?.invoke(text)
            return
        }

        // 默认校验
        if (minLength > 0 && text.length < minLength) {
            showToast(context.getString(R.string.input_dialog_need_more, minLength))
            return
        }
        if (maxLength > 0 && text.length > maxLength) {
            showToast(context.getString(R.string.input_dialog_need_less, maxLength))
            return
        }
        if (numberOnly && !canZero) {
            if ((text.toIntOrNull() ?: 0) == 0) {
                showToast(context.getString(R.string.input_dialog_no_zero))
                return
            }
        }

        dismiss()
        onComplete?.invoke(text)
    }

    private fun showToast(message: String) {
        if (message.isNotEmpty()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
