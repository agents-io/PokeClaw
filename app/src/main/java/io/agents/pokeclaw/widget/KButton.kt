package io.agents.pokeclaw.widget

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import io.agents.pokeclaw.R
import androidx.core.content.withStyledAttributes

/**
 * 基础按钮
 * 纯文字按钮，支持自定义背景色、圆角、边框色
 *
 * XML 用法:
 *   <io.agents.pokeclaw.widget.KButton
 *       android:layout_width="match_parent"
 *       android:layout_height="48dp"
 *       android:layout_marginHorizontal="16dp"
 *       android:text="确定" />
 *
 * 代码用法:
 *   KButton(context).apply {
 *       text = "确定"
 *       setBgColor(Color.RED)
 *       setBorderColor(Color.GRAY)
 *       setCornerRadius(12f)
 *   }
 */
class KButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var bgColor: Int = context.getColor(R.color.colorBrandPrimary)
    private var borderColor: Int = 0x00000000 // transparent
    private var cornerRadiusPx: Float = pt(12f)

    init {
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.colorBrandOnPrimary))
        if (attrs?.getAttributeValue("http://schemas.android.com/apk/res/android", "textSize") == null) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        isClickable = true
        isFocusable = true

        attrs?.let {
            context.withStyledAttributes(it, R.styleable.KButton) {
                bgColor = getColor(R.styleable.KButton_btnBackground, bgColor)
                setTextColor(getColor(R.styleable.KButton_btnTextColor, currentTextColor))
                cornerRadiusPx = getDimension(R.styleable.KButton_btnCornerRadius, cornerRadiusPx)
                borderColor = getColor(R.styleable.KButton_btnBorderColor, borderColor)
            }
        }

        applyBackground()
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
        val shape = android.graphics.drawable.GradientDrawable().apply {
            setColor(bgColor)
            setCornerRadius(cornerRadiusPx)
            setStroke(pt(1f).toInt(), borderColor)
        }
        background = shape
    }

    private fun pt(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PT, value, resources.displayMetrics)
}
