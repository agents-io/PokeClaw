package io.agents.pokeclaw.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import androidx.core.view.isVisible
import io.agents.pokeclaw.R

/**
 * 菜单组组件 - 带标题的圆角卡片容器
 */
class MenuGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tvTitle: TextView
    private val cardContainer: MaterialCardView
    private val itemsContainer: LinearLayout

    init {
        LayoutInflater.from(context).inflate(R.layout.widget_menu_group, this, true)
        tvTitle = findViewById(R.id.tvTitle)
        cardContainer = findViewById(R.id.cardContainer)
        itemsContainer = findViewById(R.id.itemsContainer)
    }

    /**
     * 设置标题，null 或空字符串时隐藏标题
     */
    fun setTitle(title: CharSequence?) {
        tvTitle.isVisible = !title.isNullOrEmpty()
        tvTitle.text = title
    }

    /**
     * 设置标题文字颜色
     */
    fun setTitleColor(color: Int) {
        tvTitle.setTextColor(color)
    }

    /**
     * 设置卡片背景颜色
     */
    fun setCardBackgroundColor(color: Int) {
        cardContainer.setCardBackgroundColor(color)
    }

    /**
     * 添加菜单项
     */
    fun addMenuItem(item: MenuItem) {
        itemsContainer.addView(item)
    }

    /**
     * 添加菜单项（带配置）
     */
    fun addMenuItem(
        leadingIcon: Int,
        title: String,
        onClick: () -> Unit,
        trailingText: String? = null,
        trailingIcon: Int? = null,
        showTrailingIcon: Boolean = true,
        showDivider: Boolean = true
    ): MenuItem {
        val item = MenuItem(context).apply {
            setLeadingIcon(leadingIcon)
            setTitle(title)
            setOnClickListener { onClick() }
            trailingText?.let { setTrailingText(it) }
            trailingIcon?.let { setTrailingIcon(it) }
            setShowTrailingIcon(showTrailingIcon)
            setShowDivider(showDivider)
        }
        itemsContainer.addView(item)
        return item
    }

    /**
     * 移除所有菜单项
     */
    fun clearMenuItems() {
        itemsContainer.removeAllViews()
    }

    /**
     * 获取菜单项数量
     */
    fun getMenuItemCount(): Int = itemsContainer.childCount

    /**
     * 获取指定位置的菜单项
     */
    fun getMenuItemAt(index: Int): MenuItem? {
        return itemsContainer.getChildAt(index) as? MenuItem
    }
}
