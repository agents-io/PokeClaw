package io.agents.pokeclaw.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import io.agents.pokeclaw.R

/**
 * 权限卡片组件
 *
 * 未开启时显示 SwitchCompat（unchecked），开启后隐藏 Switch 显示 "已开启" 文字。
 * 卡片背景色随状态切换（成功/错误容器色）。
 */
class PermissionCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CardView(context, attrs, defStyleAttr) {

    private val ivIcon: ImageView
    private val tvTitle: TextView
    private val tvSubtitle: TextView
    private val switchStatus: SwitchCompat
    private val tvEnabled: TextView

    init {
        // 默认卡片样式
        radius = dpToPx(12f)
        cardElevation = 0f
        setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorContainerLow))

        LayoutInflater.from(context).inflate(R.layout.widget_permission_card, this, true)

        ivIcon = findViewById(R.id.ivIcon)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        switchStatus = findViewById(R.id.switchStatus)
        tvEnabled = findViewById(R.id.tvEnabled)

        // Switch 仅用于展示状态
        switchStatus.isClickable = false
        switchStatus.isEnabled = false

        // 读取 XML 属性
        attrs?.let {
            val ta = context.obtainStyledAttributes(it, R.styleable.PermissionCardView)
            ta.getResourceId(R.styleable.PermissionCardView_cardIcon, 0).takeIf { it != 0 }
                ?.let { ivIcon.setImageResource(it) }
            ta.getString(R.styleable.PermissionCardView_cardTitle)?.let { tvTitle.text = it }
            ta.getString(R.styleable.PermissionCardView_cardSubtitle)?.let { tvSubtitle.text = it }
            ta.recycle()
        }
    }

    /**
     * 设置权限启用状态，自动切换 UI 样式
     */
    fun setPermissionEnabled(enabled: Boolean) {
        if (enabled) {
            switchStatus.visibility = View.INVISIBLE
            tvEnabled.visibility = View.VISIBLE
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorSuccessContainer))
        } else {
            switchStatus.visibility = View.VISIBLE
            switchStatus.isChecked = false
            tvEnabled.visibility = View.INVISIBLE
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorErrorContainer))
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
