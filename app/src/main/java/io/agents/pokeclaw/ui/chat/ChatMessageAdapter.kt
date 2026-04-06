// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.agents.pokeclaw.R

class ChatMessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val colors: ThemeManager.ChatColors get() = ThemeManager.getColors()

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_SYSTEM = 2
        private const val TYPE_TOOL_GROUP = 3
    }

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastAssistant(content: String) {
        val idx = messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (idx >= 0) {
            messages[idx] = messages[idx].copy(content = content)
            notifyItemChanged(idx)
        }
    }

    fun getAllMessages(): List<ChatMessage> = messages.toList()

    fun addAll(msgs: List<ChatMessage>) {
        messages.addAll(msgs)
        notifyItemRangeInserted(0, msgs.size)
    }

    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun getItemCount() = messages.size

    override fun getItemViewType(position: Int) = when (messages[position].role) {
        ChatMessage.Role.USER -> TYPE_USER
        ChatMessage.Role.ASSISTANT -> TYPE_ASSISTANT
        ChatMessage.Role.SYSTEM -> TYPE_SYSTEM
        ChatMessage.Role.TOOL_GROUP -> TYPE_TOOL_GROUP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserVH(inflater.inflate(R.layout.item_chat_user, parent, false))
            TYPE_ASSISTANT -> AssistantVH(inflater.inflate(R.layout.item_chat_assistant, parent, false))
            TYPE_SYSTEM -> SystemVH(inflater.inflate(R.layout.item_chat_system, parent, false))
            TYPE_TOOL_GROUP -> ToolGroupVH(inflater.inflate(R.layout.item_chat_tool_group, parent, false))
            else -> SystemVH(inflater.inflate(R.layout.item_chat_system, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        val c = ThemeManager.getColors()
        when (holder) {
            is UserVH -> {
                holder.tvMessage.text = msg.content
                holder.tvMessage.background = GradientDrawable().apply {
                    setColor(c.userBubble)
                    cornerRadii = floatArrayOf(54f,54f, 54f,54f, 12f,12f, 54f,54f)
                }
                holder.tvMessage.setTextColor(c.userText)
            }
            is AssistantVH -> {
                if (msg.content == "...") {
                    // Animated typing indicator
                    holder.tvMessage.text = "●  ●  ●"
                    holder.tvMessage.alpha = 1f
                    val anim = android.view.animation.AlphaAnimation(0.3f, 1.0f).apply {
                        duration = 600
                        repeatMode = android.view.animation.Animation.REVERSE
                        repeatCount = android.view.animation.Animation.INFINITE
                    }
                    holder.tvMessage.startAnimation(anim)
                } else {
                    holder.tvMessage.clearAnimation()
                    holder.tvMessage.alpha = 1f
                    holder.tvMessage.text = msg.content
                }
                holder.tvMessage.background = GradientDrawable().apply {
                    setColor(c.aiBubble)
                    setStroke(3, c.aiBubbleBorder)
                    cornerRadii = floatArrayOf(54f,54f, 54f,54f, 54f,54f, 12f,12f)
                }
                holder.tvMessage.setTextColor(c.aiText)
                holder.tvAvatar?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c.avatarBg)
                }
            }
            is SystemVH -> holder.tvMessage.text = msg.content
            is ToolGroupVH -> holder.bind(msg)
        }
    }

    class UserVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
    }

    class AssistantVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val tvAvatar: TextView? = view.findViewById(R.id.tvAvatar)
    }

    class SystemVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
    }

    class ToolGroupVH(view: View) : RecyclerView.ViewHolder(view) {
        private val layout: LinearLayout = view.findViewById(R.id.layoutTools)
        private val context = view.context

        fun bind(msg: ChatMessage) {
            layout.removeAllViews()
            msg.toolSteps?.forEach { step ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(2), 0, dp(2))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                // Dot
                val dot = View(context).apply {
                    val size = dp(5)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(6) }
                    val color = if (step.success) context.getColor(R.color.colorChatToolOk)
                                else context.getColor(R.color.colorChatToolDefault)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(color)
                    }
                }
                row.addView(dot)

                // Text
                val tv = TextView(context).apply {
                    text = "${step.toolName} → ${step.summary}"
                    textSize = 12f
                    setTextColor(context.getColor(R.color.colorTextTertiary))
                }
                row.addView(tv)

                layout.addView(row)
            }
        }

        private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    }
}
