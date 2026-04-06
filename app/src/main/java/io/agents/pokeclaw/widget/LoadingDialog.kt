package io.agents.pokeclaw.widget

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import io.agents.pokeclaw.R

/**
 * 轻量加载弹窗，匹配项目圆角卡片风格
 *
 * 无文字（纯 spinner）：
 * ```
 * val loading = LoadingDialog.show(context)
 * loading.dismiss()
 * ```
 *
 * 带提示文字：
 * ```
 * val loading = LoadingDialog.show(context, "正在加载…")
 * loading.dismiss()
 * ```
 */
class LoadingDialog private constructor(context: Context) : Dialog(context, R.style.DialogStyle) {

    private var message: String? = null
    private var isDismissible: Boolean = false

    companion object {

        @JvmStatic
        fun show(
            context: Context,
            message: String? = null,
            cancelable: Boolean = false
        ): LoadingDialog {
            return LoadingDialog(context).apply {
                this.message = message
                this.isDismissible = cancelable
                show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_loading)

        setCancelable(isDismissible)
        setCanceledOnTouchOutside(isDismissible)

        window?.apply {
            setGravity(Gravity.CENTER)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            // 背景不变暗，让加载弹窗更轻量
            setDimAmount(0.3f)
        }

        val tvMessage = findViewById<TextView>(R.id.tvMessage)
        if (!message.isNullOrEmpty()) {
            tvMessage.text = message
            tvMessage.visibility = View.VISIBLE
        }
    }

    /**
     * 更新提示文字（弹窗已显示后也可调用）
     */
    fun setMessage(text: String?) {
        this.message = text
        val tvMessage = findViewById<TextView>(R.id.tvMessage) ?: return
        if (text.isNullOrEmpty()) {
            tvMessage.visibility = View.GONE
        } else {
            tvMessage.text = text
            tvMessage.visibility = View.VISIBLE
        }
    }
}
