package com.jarvis.assistant.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.Conversation
import com.jarvis.assistant.databinding.ItemChatHistoryBinding

class ChatHistoryAdapter(
    private val onConversationSelected: (Conversation) -> Unit,
    private val onConversationDelete: (Conversation) -> Unit
) : ListAdapter<Conversation, ChatHistoryAdapter.ViewHolder>(DiffCallback) {

    private var activeConversationId: String? = null

    fun setActiveConversationId(id: String?) {
        activeConversationId = id
        notifyDataSetChanged()
    }

    companion object DiffCallback : DiffUtil.ItemCallback<Conversation>() {
        override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean =
            oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChatHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemChatHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(conversation: Conversation) {
            val context = binding.root.context
            val isActive = conversation.id == activeConversationId

            binding.layoutHistoryItemRoot.background = ContextCompat.getDrawable(
                context,
                if (isActive) R.drawable.bg_history_card_active else R.drawable.bg_history_card
            )

            binding.tvHistoryItemTitle.text = conversation.title
            binding.tvHistoryItemSnippet.text = conversation.snippet

            val msgCount = conversation.turns.size
            val turnText = if (msgCount == 1) "1 message" else "$msgCount messages"
            binding.tvHistoryItemMeta.text = "${conversation.dateGroup.displayTitle} • ${conversation.formattedTime} • $turnText"

            binding.layoutHistoryItemRoot.setOnClickListener {
                onConversationSelected(conversation)
            }

            binding.btnHistoryItemDelete.setOnClickListener {
                onConversationDelete(conversation)
            }
        }
    }
}
