// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

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
 * Menu group component - rounded card container with a title
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
     * Set title; hides the title when null or empty
     */
    fun setTitle(title: CharSequence?) {
        tvTitle.isVisible = !title.isNullOrEmpty()
        tvTitle.text = title
    }

    /**
     * Set title text color
     */
    fun setTitleColor(color: Int) {
        tvTitle.setTextColor(color)
    }

    /**
     * Set card background color
     */
    fun setCardBackgroundColor(color: Int) {
        cardContainer.setCardBackgroundColor(color)
    }

    /**
     * Add a menu item
     */
    fun addMenuItem(item: MenuItem) {
        itemsContainer.addView(item)
    }

    /**
     * Add a menu item (with configuration)
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
     * Remove all menu items
     */
    fun clearMenuItems() {
        itemsContainer.removeAllViews()
    }

    /**
     * Get menu item count
     */
    fun getMenuItemCount(): Int = itemsContainer.childCount

    /**
     * Get menu item at given index
     */
    fun getMenuItemAt(index: Int): MenuItem? {
        return itemsContainer.getChildAt(index) as? MenuItem
    }
}
