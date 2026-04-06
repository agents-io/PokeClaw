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
 * General-purpose input bottom sheet dialog
 *
 * Basic usage:
 * ```
 * InputDialog.show(context, "Edit nickname") { text ->
 *     // handle input
 * }
 * ```
 *
 * Full parameters:
 * ```
 * InputDialog.show(
 *     context = this,
 *     title = "Edit nickname",
 *     presetText = "current nickname",
 *     hint = "Enter new nickname",
 *     minLength = 2,
 *     maxLength = 20,
 *     confirmText = "Save",
 *     onComplete = { text -> /* handle */ }
 * )
 * ```
 *
 * Numeric input:
 * ```
 * InputDialog.show(
 *     context = this,
 *     title = "Set quantity",
 *     numberOnly = true,
 *     canZero = false,
 *     onComplete = { text -> /* handle */ }
 * )
 * ```
 *
 * Custom validation:
 * ```
 * InputDialog.show(
 *     context = this,
 *     title = "Enter email",
 *     inputValidate = { text ->
 *         if (text.contains("@")) ValidateResult(true)
 *         else ValidateResult(false, "Please enter a valid email address")
 *     },
 *     onComplete = { text -> /* handle */ }
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

    /** Validation result */
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

        // Make bottom sheet background transparent; use the layout's own rounded background
        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(context.getColor(R.color.colorBgPrimary))

        window?.apply {
            navigationBarColor = context.getColor(R.color.colorBgPrimary)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        initViews(view)
    }

    private fun initViews(view: View) {
        // Title
        view.findViewById<TextView>(R.id.tvTitle).text = title

        // Close button
        view.findViewById<ImageView>(R.id.btnClose).setOnClickListener { dismiss() }

        val etInput = view.findViewById<EditText>(R.id.etInput)
        val btnClear = view.findViewById<ImageView>(R.id.btnClear)
        val inputContainer = view.findViewById<FrameLayout>(R.id.inputContainer)
        val btnConfirm = view.findViewById<KButton>(R.id.btnConfirm)

        // Preset text and hint
        etInput.setText(presetText)
        etInput.hint = hint
        etInput.setSelection(etInput.text.length)

        // Numeric input mode
        if (numberOnly) {
            etInput.inputType = InputType.TYPE_CLASS_NUMBER
        }

        // Max length limit
        if (maxLength > 0) {
            etInput.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        }

        // Clear button visibility
        btnClear.visibility = if (presetText.isNotEmpty()) View.VISIBLE else View.GONE
        etInput.doAfterTextChanged { text ->
            btnClear.visibility = if (text?.isNotEmpty() == true) View.VISIBLE else View.GONE
        }

        // Clear button click
        btnClear.setOnClickListener { etInput.text.clear() }

        // Toggle input field border color on focus change
        etInput.setOnFocusChangeListener { _, hasFocus ->
            inputContainer.setBackgroundResource(
                if (hasFocus) R.drawable.bg_input_field_focused
                else R.drawable.bg_input_field
            )
        }

        // Keyboard Done button triggers confirm
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performConfirm(etInput)
                true
            } else false
        }

        // Confirm button
        confirmText?.let { btnConfirm.text = it }
        btnConfirm.setOnClickListener { performConfirm(etInput) }

        // Auto-show keyboard
        etInput.requestFocus()
    }

    private fun performConfirm(etInput: EditText) {
        val text = etInput.text.toString().trim()

        // Custom validation takes priority
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

        // Default validation
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
