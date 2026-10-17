package com.jarvis.assistant.ui.settings

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.GeminiVoicePreviewManager
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.GeminiVoiceCatalog
import com.jarvis.assistant.data.model.GeminiVoiceInfo
import com.jarvis.assistant.databinding.ActivityVoiceSelectionBinding
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Voice selection screen inspired by Jarvis sci-fi HUD styling:
 * - Dark navy background with cyan glow highlights
 * - Male / Female / All filter tabs
 * - Radio-style selection indicators
 * - Individual live Hindi preview / test button on every voice
 * - Real persistence across app restarts and seamless session sync
 */
class VoiceSelectionActivity : AppCompatActivity() {

    enum class FilterTab {
        ALL,
        MALE,
        FEMALE
    }

    private lateinit var binding: ActivityVoiceSelectionBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val previewManager by lazy { GeminiVoicePreviewManager(this, lifecycleScope) }

    private lateinit var adapter: VoiceSelectionAdapter
    private var activeFilterTab: FilterTab = FilterTab.ALL
    private var pendingSelectedVoiceId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        // Load initially saved voice
        val currentSavedVoice = preferences.voice
        val initialVoice = GeminiVoiceCatalog.findVoiceById(currentSavedVoice)
        pendingSelectedVoiceId = initialVoice.id

        setupRecyclerView()
        setupListeners()
        observePreviewState()

        // Apply initial filter
        switchFilterTab(FilterTab.ALL)
    }

    private fun setupRecyclerView() {
        adapter = VoiceSelectionAdapter(
            onVoiceSelected = { voice ->
                pendingSelectedVoiceId = voice.id
                adapter.setSelectedVoice(voice.id)
            },
            onPreviewTapped = { voice ->
                previewManager.togglePreview(voice.id)
            }
        )

        binding.rvVoiceList.layoutManager = LinearLayoutManager(this)
        binding.rvVoiceList.adapter = adapter
        adapter.setSelectedVoice(pendingSelectedVoiceId)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            previewManager.stopPreview()
            finish()
        }

        binding.btnSave.setOnClickListener {
            saveAndFinish()
        }

        binding.tabAllVoices.setOnClickListener {
            switchFilterTab(FilterTab.ALL)
        }

        binding.tabMaleVoices.setOnClickListener {
            switchFilterTab(FilterTab.MALE)
        }

        binding.tabFemaleVoices.setOnClickListener {
            switchFilterTab(FilterTab.FEMALE)
        }
    }

    private fun switchFilterTab(tab: FilterTab) {
        activeFilterTab = tab

        val cyanColor = Color.parseColor("#00E5FF")
        val mutedColor = Color.parseColor("#82A0AF")

        // 1. Tab All Voices
        val isAll = (tab == FilterTab.ALL)
        binding.tabAllVoices.setBackgroundResource(
            if (isAll) R.drawable.bg_voice_tab_selected else R.drawable.bg_voice_tab_unselected
        )
        binding.tvTabAllText.setTextColor(if (isAll) cyanColor else mutedColor)
        binding.ivTabAllIcon.imageTintList = ColorStateList.valueOf(if (isAll) cyanColor else mutedColor)

        // 2. Tab Male Voices
        val isMale = (tab == FilterTab.MALE)
        binding.tabMaleVoices.setBackgroundResource(
            if (isMale) R.drawable.bg_voice_tab_selected else R.drawable.bg_voice_tab_unselected
        )
        binding.tvTabMaleText.setTextColor(if (isMale) cyanColor else mutedColor)
        binding.ivTabMaleIcon.imageTintList = ColorStateList.valueOf(if (isMale) cyanColor else mutedColor)

        // 3. Tab Female Voices
        val isFemale = (tab == FilterTab.FEMALE)
        binding.tabFemaleVoices.setBackgroundResource(
            if (isFemale) R.drawable.bg_voice_tab_selected else R.drawable.bg_voice_tab_unselected
        )
        binding.tvTabFemaleText.setTextColor(if (isFemale) cyanColor else mutedColor)
        binding.ivTabFemaleIcon.imageTintList = ColorStateList.valueOf(if (isFemale) cyanColor else mutedColor)

        // Update list items
        val voicesToShow: List<GeminiVoiceInfo> = when (tab) {
            FilterTab.ALL -> GeminiVoiceCatalog.ALL_VOICES
            FilterTab.MALE -> GeminiVoiceCatalog.MALE_VOICES
            FilterTab.FEMALE -> GeminiVoiceCatalog.FEMALE_VOICES
        }
        adapter.setVoices(voicesToShow)
        adapter.setSelectedVoice(pendingSelectedVoiceId)
    }

    private fun observePreviewState() {
        lifecycleScope.launch {
            previewManager.state.collectLatest { state ->
                adapter.updatePreviewState(state)
                if (state is GeminiVoicePreviewManager.PreviewState.Error) {
                    Toast.makeText(this@VoiceSelectionActivity, state.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveAndFinish() {
        previewManager.stopPreview()

        val selectedInfo = GeminiVoiceCatalog.findVoiceById(pendingSelectedVoiceId)
        preferences.voice = selectedInfo.id

        // Seamlessly update live conversation service if active
        if (JarvisConversationService.isServiceRunning()) {
            JarvisConversationService.restartSession()
        }

        Toast.makeText(
            this,
            "Saved Assistant Voice: ${selectedInfo.displayName} (${selectedInfo.officialDescriptor})",
            Toast.LENGTH_SHORT
        ).show()

        setResult(Activity.RESULT_OK)
        finish()
    }

    override fun onPause() {
        super.onPause()
        previewManager.stopPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewManager.stopPreview()
    }
}
