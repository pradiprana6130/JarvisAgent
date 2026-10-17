package com.jarvis.assistant.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.GeminiVoicePreviewManager
import com.jarvis.assistant.data.model.GeminiVoiceInfo
import com.jarvis.assistant.databinding.ItemVoiceCardBinding

class VoiceSelectionAdapter(
    private val onVoiceSelected: (GeminiVoiceInfo) -> Unit,
    private val onPreviewTapped: (GeminiVoiceInfo) -> Unit
) : RecyclerView.Adapter<VoiceSelectionAdapter.VoiceViewHolder>() {

    private var voices: List<GeminiVoiceInfo> = emptyList()
    private var selectedVoiceId: String = ""
    private var previewState: GeminiVoicePreviewManager.PreviewState = GeminiVoicePreviewManager.PreviewState.Idle

    fun setVoices(newList: List<GeminiVoiceInfo>) {
        voices = newList
        notifyDataSetChanged()
    }

    fun setSelectedVoice(voiceId: String) {
        val oldId = selectedVoiceId
        selectedVoiceId = voiceId
        // Update affected items
        val oldPos = voices.indexOfFirst { it.id.equals(oldId, ignoreCase = true) }
        val newPos = voices.indexOfFirst { it.id.equals(voiceId, ignoreCase = true) }
        if (oldPos != -1) notifyItemChanged(oldPos)
        if (newPos != -1) notifyItemChanged(newPos)
    }

    fun updatePreviewState(state: GeminiVoicePreviewManager.PreviewState) {
        val previousState = previewState
        previewState = state

        val affectedVoices = mutableSetOf<String>()
        when (previousState) {
            is GeminiVoicePreviewManager.PreviewState.Loading -> affectedVoices.add(previousState.voiceId)
            is GeminiVoicePreviewManager.PreviewState.Playing -> affectedVoices.add(previousState.voiceId)
            is GeminiVoicePreviewManager.PreviewState.Error -> affectedVoices.add(previousState.voiceId)
            else -> {}
        }
        when (state) {
            is GeminiVoicePreviewManager.PreviewState.Loading -> affectedVoices.add(state.voiceId)
            is GeminiVoicePreviewManager.PreviewState.Playing -> affectedVoices.add(state.voiceId)
            is GeminiVoicePreviewManager.PreviewState.Error -> affectedVoices.add(state.voiceId)
            else -> {}
        }

        for (voiceId in affectedVoices) {
            val idx = voices.indexOfFirst { it.id.equals(voiceId, ignoreCase = true) }
            if (idx != -1) notifyItemChanged(idx)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoiceViewHolder {
        val binding = ItemVoiceCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VoiceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VoiceViewHolder, position: Int) {
        holder.bind(voices[position])
    }

    override fun getItemCount(): Int = voices.size

    inner class VoiceViewHolder(
        private val binding: ItemVoiceCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(voice: GeminiVoiceInfo) {
            binding.tvVoiceName.text = voice.displayName
            binding.tvVoiceTags.text = voice.styleTags
            binding.tvVoiceDescription.text = voice.description

            val isSelected = voice.id.equals(selectedVoiceId, ignoreCase = true)
            if (isSelected) {
                binding.cardContainer.setBackgroundResource(R.drawable.bg_voice_card_selected)
                binding.ivRadioIndicator.setImageResource(R.drawable.bg_voice_radio_selected)
            } else {
                binding.cardContainer.setBackgroundResource(R.drawable.bg_voice_card_unselected)
                binding.ivRadioIndicator.setImageResource(R.drawable.bg_voice_radio_unselected)
            }

            // Preview button state
            when (val state = previewState) {
                is GeminiVoicePreviewManager.PreviewState.Loading -> {
                    if (state.voiceId.equals(voice.id, ignoreCase = true)) {
                        binding.ivPlayIcon.visibility = View.GONE
                        binding.progressBarLoading.visibility = View.VISIBLE
                        binding.ivStopIcon.visibility = View.GONE
                    } else {
                        showIdlePlayState()
                    }
                }
                is GeminiVoicePreviewManager.PreviewState.Playing -> {
                    if (state.voiceId.equals(voice.id, ignoreCase = true)) {
                        binding.ivPlayIcon.visibility = View.GONE
                        binding.progressBarLoading.visibility = View.GONE
                        binding.ivStopIcon.visibility = View.VISIBLE
                    } else {
                        showIdlePlayState()
                    }
                }
                else -> {
                    showIdlePlayState()
                }
            }

            binding.cardContainer.setOnClickListener {
                onVoiceSelected(voice)
            }

            binding.btnPreviewVoice.setOnClickListener {
                onPreviewTapped(voice)
            }
        }

        private fun showIdlePlayState() {
            binding.ivPlayIcon.visibility = View.VISIBLE
            binding.progressBarLoading.visibility = View.GONE
            binding.ivStopIcon.visibility = View.GONE
        }
    }
}
