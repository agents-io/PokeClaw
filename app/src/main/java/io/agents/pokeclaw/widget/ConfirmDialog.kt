package io.agents.pokeclaw.widget

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.agents.pokeclaw.R

class ConfirmDialog private constructor(context: Context) : Dialog(context, R.style.DialogStyle) {

    private var title: String = ""
    private var message: String = ""
    private var actionTitle: String = ""
    private var cancelTitle: String? = null
    private var checkboxLabel: String? = null
    private var checkboxHint: String? = null
    private var checkboxDefault: Boolean = false
    private var isDismissible: Boolean = true
    private var isWarm: Boolean = false

    private var onAction: ((isChecked: Boolean) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    private var isChecked = false

    companion object {

        @JvmStatic
        fun show(
            context: Context,
            title: String,
            message: String = "",
            actionTitle: String = context.getString(R.string.common_confirm),
            cancelTitle: String? = null,
            checkboxLabel: String? = null,
            checkboxHint: String? = null,
            checkboxDefault: Boolean = false,
            isDismissible: Boolean = true,
            onAction: ((isChecked: Boolean) -> Unit)? = null,
            onCancel: (() -> Unit)? = null
        ): ConfirmDialog {
            return ConfirmDialog(context).apply {
                this.title = title
                this.message = message
                this.actionTitle = actionTitle
                this.cancelTitle = cancelTitle
                this.checkboxLabel = checkboxLabel
                this.checkboxHint = checkboxHint
                this.checkboxDefault = checkboxDefault
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
            checkboxLabel: String? = null,
            checkboxHint: String? = null,
            checkboxDefault: Boolean = false,
            isDismissible: Boolean = true,
            onAction: ((isChecked: Boolean) -> Unit)? = null,
            onCancel: (() -> Unit)? = null
        ): ConfirmDialog {
            return ConfirmDialog(context).apply {
                this.title = title
                this.message = message
                this.actionTitle = actionTitle
                this.cancelTitle = cancelTitle
                this.checkboxLabel = checkboxLabel
                this.checkboxHint = checkboxHint
                this.checkboxDefault = checkboxDefault
                this.isDismissible = isDismissible
                this.isWarm = true
                this.onAction = onAction
                this.onCancel = onCancel
                show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_confirm)

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

        // 勾选区域
        val layoutCheckbox = findViewById<LinearLayout>(R.id.layoutCheckbox)
        val ivCheckbox = findViewById<ImageView>(R.id.ivCheckbox)
        val tvCheckboxLabel = findViewById<TextView>(R.id.tvCheckboxLabel)
        val tvCheckboxHint = findViewById<TextView>(R.id.tvCheckboxHint)

        if (checkboxLabel != null) {
            layoutCheckbox.visibility = View.VISIBLE
            tvCheckboxLabel.text = checkboxLabel
            isChecked = checkboxDefault
            updateCheckboxIcon(ivCheckbox)

            // 点击整行切换
            val toggleCheckbox = View.OnClickListener {
                isChecked = !isChecked
                updateCheckboxIcon(ivCheckbox)
            }
            ivCheckbox.setOnClickListener(toggleCheckbox)
            tvCheckboxLabel.setOnClickListener(toggleCheckbox)

            if (checkboxHint != null) {
                tvCheckboxHint.text = checkboxHint
                tvCheckboxHint.visibility = View.VISIBLE
            }
        }

        // 确认按钮
        val btnAction = findViewById<KButton>(R.id.btnAction)
        val btnCancel = findViewById<KButton>(R.id.btnCancel)
        val btnSpacer = findViewById<View>(R.id.btnSpacer)

        btnAction.text = actionTitle
        if (isWarm) {
            btnAction.setBgColor(context.getColor(R.color.colorErrorPrimary))
            btnAction.setBorderColor(context.getColor(R.color.colorErrorPrimary))
        }
        btnAction.setOnClickListener {
            dismiss()
            onAction?.invoke(isChecked)
        }

        // 取消按钮
        if (cancelTitle != null) {
            btnCancel.visibility = View.VISIBLE
            btnSpacer.visibility = View.VISIBLE
            btnCancel.text = cancelTitle
            btnCancel.setBgColor(context.getColor(R.color.colorContainerBase))
            btnCancel.setTextColor(context.getColor(R.color.colorTextSecondary))
            btnCancel.setBorderColor(context.getColor(R.color.colorBorderBase))
            btnCancel.setOnClickListener {
                dismiss()
                onCancel?.invoke()
            }
        }
    }

    private fun updateCheckboxIcon(iv: ImageView) {
        iv.setImageResource(
            if (isChecked) R.drawable.icon_radio_selected
            else R.drawable.icon_radio_unselected
        )
    }
}
