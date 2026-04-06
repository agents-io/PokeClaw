package io.agents.pokeclaw.widget

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import io.agents.pokeclaw.R

/**
 * 菜单项组件 - 带图标、标题、尾部文字/箭头的可点击项
 */
class MenuItem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val ivLeading: ImageView
    private val tvTitle: TextView
    private val viewRedDot: View
    private val tvTrailing: TextView
    private val ivTrailing: ImageView
    private val divider: View

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.widget_menu_item, this, true)

        ivLeading = findViewById(R.id.ivLeading)
        tvTitle = findViewById(R.id.tvTitle)
        viewRedDot = findViewById(R.id.viewRedDot)
        tvTrailing = findViewById(R.id.tvTrailing)
        ivTrailing = findViewById(R.id.ivTrailing)
        divider = findViewById(R.id.divider)

        // 默认显示尾部箭头
        setShowTrailingIcon(true)
    }

    /**
     * 设置左侧图标
     */
    fun setLeadingIcon(@DrawableRes iconRes: Int) {
        ivLeading.setImageResource(iconRes)
    }

    /**
     * 设置左侧图标颜色
     */
    fun setLeadingIconColor(color: Int) {
        ivLeading.setColorFilter(color)
    }

    /**
     * 设置标题
     */
    fun setTitle(title: CharSequence) {
        tvTitle.text = title
    }

    /**
     * 设置标题文字颜色
     */
    fun setTitleColor(color: Int) {
        tvTitle.setTextColor(color)
    }

    /**
     * 设置尾部文字
     */
    fun setTrailingText(text: CharSequence?) {
        tvTrailing.isVisible = !text.isNullOrEmpty()
        tvTrailing.text = text
    }

    /**
     * 设置尾部文字颜色
     */
    fun setTrailingTextColor(color: Int) {
        tvTrailing.setTextColor(color)
    }

    /**
     * 设置尾部图标
     */
    fun setTrailingIcon(@DrawableRes iconRes: Int) {
        ivTrailing.setImageResource(iconRes)
    }

    /**
     * 设置尾部图标颜色
     */
    fun setTrailingIconColor(color: Int) {
        ivTrailing.setColorFilter(color)
    }

    /**
     * 设置是否显示尾部图标
     */
    fun setShowTrailingIcon(show: Boolean) {
        ivTrailing.visibility = if (show) View.VISIBLE else View.GONE
        val lp = tvTrailing.layoutParams as MarginLayoutParams
        lp.marginEnd = if (show) 0 else TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_PT, 8f, resources.displayMetrics
        ).toInt()
        tvTrailing.layoutParams = lp
    }

    /**
     * 设置是否显示分隔线
     */
    fun setShowDivider(show: Boolean) {
        divider.isVisible = show
    }

    /**
     * 获取尾部文字
     */
    fun getTrailingText(): CharSequence? {
        return tvTrailing.text
    }

    /**
     * 设置是否显示红点
     */
    fun setShowRedDot(show: Boolean) {
        viewRedDot.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * 获取标题文字
     */
    fun getTitle(): CharSequence? {
        return tvTitle.text
    }
}
