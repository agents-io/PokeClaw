package com.apk.claw.android.ui.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.apk.claw.android.R

class ChatMessageAdapter : RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastAssistantMessage(content: String) {
        val lastIdx = messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (lastIdx >= 0) {
            messages[lastIdx] = messages[lastIdx].copy(content = content)
            notifyItemChanged(lastIdx)
        }
    }

    fun getMessages(): List<ChatMessage> = messages.toList()

    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun getItemCount() = messages.size

    override fun getItemViewType(position: Int) = messages[position].role.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        private val layoutBubble: FrameLayout = view.findViewById(R.id.layoutBubble)

        fun bind(message: ChatMessage) {
            tvMessage.text = message.content

            val params = layoutBubble.layoutParams as FrameLayout.LayoutParams

            when (message.role) {
                ChatMessage.Role.USER -> {
                    params.gravity = Gravity.END
                    params.marginStart = dp(56)
                    params.marginEnd = dp(12)
                    tvMessage.setBackgroundResource(R.drawable.bg_bubble_user)
                    tvMessage.setTextColor(0xFFFFFFFF.toInt())
                }
                ChatMessage.Role.ASSISTANT -> {
                    params.gravity = Gravity.START
                    params.marginStart = dp(12)
                    params.marginEnd = dp(56)
                    tvMessage.setBackgroundResource(R.drawable.bg_bubble_assistant)
                    tvMessage.setTextColor(0xFFE8E8E8.toInt())
                }
                ChatMessage.Role.TOOL -> {
                    params.gravity = Gravity.START
                    params.marginStart = dp(12)
                    params.marginEnd = dp(56)
                    tvMessage.setBackgroundResource(R.drawable.bg_bubble_tool)
                    tvMessage.setTextColor(0xFFB0BEC5.toInt())
                }
                ChatMessage.Role.SYSTEM -> {
                    params.gravity = Gravity.CENTER
                    params.marginStart = dp(32)
                    params.marginEnd = dp(32)
                    tvMessage.setBackgroundResource(R.drawable.bg_bubble_system)
                    tvMessage.setTextColor(0xFF9E9E9E.toInt())
                }
            }
            layoutBubble.layoutParams = params
        }

        private fun dp(value: Int): Int {
            return (value * itemView.resources.displayMetrics.density).toInt()
        }
    }
}
