package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.databinding.ItemChatMessageBinding

/**
 * High-performance RecyclerView Adapter that binds TARGET's [ChatTurn] items
 * into the authentic dark futuristic JARVIS HUD chat bubbles.
 */
class HudChatAdapter(
    private val onEditUserMessage: ((String) -> Unit)? = null
) : ListAdapter<ChatTurn, HudChatAdapter.MessageViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<ChatTurn>() {
        override fun areItemsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(
        private val binding: ItemChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(turn: ChatTurn) {
            val context = binding.root.context

            // 1. User Message Bubble
            val hasUserContent = turn.userTranscript.isNotBlank() || !turn.attachmentName.isNullOrBlank() || !turn.attachmentThumbnailBase64.isNullOrBlank()
            if (hasUserContent) {
                binding.layoutUserContainer.visibility = View.VISIBLE
                if (turn.userTranscript.isNotBlank()) {
                    binding.tvUserText.visibility = View.VISIBLE
                    binding.tvUserText.text = turn.userTranscript
                } else {
                    binding.tvUserText.visibility = View.GONE
                }
                binding.tvUserTime.text = turn.formattedTime

                binding.layoutUserBubble.setOnClickListener {
                    copyToClipboard(context, "User message", turn.userTranscript.ifBlank { turn.attachmentName ?: "" })
                }

                binding.layoutUserBubble.setOnLongClickListener {
                    if (turn.userTranscript.isNotBlank()) {
                        onEditUserMessage?.invoke(turn.userTranscript)
                    }
                    true
                }
            } else {
                binding.layoutUserContainer.visibility = View.GONE
            }

            // 2. Assistant JARVIS Bubble
            if (turn.jarvisResponse.isNotBlank()) {
                binding.layoutJarvisContainer.visibility = View.VISIBLE
                binding.tvJarvisText.text = turn.jarvisResponse
                binding.tvJarvisTime.text = turn.formattedTime
                binding.btnCopyJarvisResponse.visibility = View.VISIBLE

                binding.btnCopyJarvisResponse.setOnClickListener {
                    copyToClipboard(context, "JARVIS response", turn.jarvisResponse)
                }

                binding.layoutJarvisBubble.setOnLongClickListener {
                    copyToClipboard(context, "JARVIS response", turn.jarvisResponse)
                    true
                }
            } else {
                binding.layoutJarvisContainer.visibility = View.VISIBLE
                binding.tvJarvisText.text = "Thinking..."
                binding.tvJarvisTime.text = turn.formattedTime
                binding.btnCopyJarvisResponse.visibility = View.GONE
            }

            // 3. User Attachment File Pill
            if (!turn.attachmentName.isNullOrBlank()) {
                binding.layoutUserAttachment.visibility = View.VISIBLE
                binding.tvUserAttachmentName.text = turn.attachmentName
                binding.tvUserAttachmentSize.text = turn.attachmentSize ?: ""

                val iconRes = when (turn.attachmentType) {
                    "PDF" -> com.jarvis.assistant.R.drawable.ic_picture_as_pdf
                    "IMAGE" -> com.jarvis.assistant.R.drawable.ic_image
                    "SCREEN" -> com.jarvis.assistant.R.drawable.ic_screen_share
                    "DATA" -> com.jarvis.assistant.R.drawable.ic_code
                    else -> com.jarvis.assistant.R.drawable.ic_description
                }
                binding.ivUserAttachmentIcon.setImageResource(iconRes)
            } else {
                binding.layoutUserAttachment.visibility = View.GONE
            }

            // 4. User Image / Screen Thumbnail Preview
            if (!turn.attachmentThumbnailBase64.isNullOrBlank()) {
                try {
                    val bytes = android.util.Base64.decode(turn.attachmentThumbnailBase64, android.util.Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        binding.ivUserImagePreview.visibility = View.VISIBLE
                        binding.ivUserImagePreview.setImageBitmap(bitmap)
                    } else {
                        binding.ivUserImagePreview.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    binding.ivUserImagePreview.visibility = View.GONE
                }
            } else {
                binding.ivUserImagePreview.visibility = View.GONE
            }

            // Hide unused dynamic cards by default
            binding.layoutProgressCard.visibility = View.GONE
        }

        private fun copyToClipboard(context: Context, label: String, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }
}
