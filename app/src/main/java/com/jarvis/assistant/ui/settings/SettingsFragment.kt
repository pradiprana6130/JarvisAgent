package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.data.model.GeminiVoiceCatalog
import com.jarvis.assistant.databinding.FragmentSettingsBinding
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        loadUserData()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    private fun loadUserData() {
        // Owner Profile
        val userName = preferences.userName.ifBlank { "Boss" }
        binding.tvProfileName.text = userName
        binding.tvProfileSubtitle.text = "JARVIS OPERATOR • PRIMARY USER"

        // Owner Voice Profile Status
        val hasVoiceProfile = preferences.hasOwnerVoiceProfile()
        if (hasVoiceProfile) {
            binding.tvOwnerVoiceBadge.text = "Active"
            binding.tvOwnerVoiceBadge.setTextColor(Color.parseColor("#10B981"))
            binding.tvOwnerVoiceSubtitle.text = "Voice Profile Active • Only you can speak"
        } else {
            binding.tvOwnerVoiceBadge.text = "Setup"
            binding.tvOwnerVoiceBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.tvOwnerVoiceSubtitle.text = "Not enrolled • Tap to record voice profile"
        }

        // Conversation Language
        val currentLang = preferences.language
        binding.tvCurrentLanguage.text = when {
            currentLang.equals("Hinglish", ignoreCase = true) -> "Hinglish (Hindi + English)"
            currentLang.equals("Auto", ignoreCase = true) -> "Auto (All Indian Languages)"
            else -> currentLang
        }

        // Assistant Wake Name
        binding.tvAssistantWakeName.text = preferences.assistantName

        // AI Persona & Voice
        val currentPersona = preferences.personality
        val voiceInfo = GeminiVoiceCatalog.findVoiceById(preferences.voice)
        binding.tvCurrentPersona.text = if (currentPersona.equals("Jarvis Pro", ignoreCase = true)) {
            "Jarvis Pro (Advanced personality)"
        } else {
            currentPersona
        }
        val genderStr = voiceInfo.gender.name.lowercase().replaceFirstChar { it.uppercase() }
        binding.tvCurrentVoice.text = "${voiceInfo.displayName} • $genderStr • ${voiceInfo.officialDescriptor}"

        // Silent Execution Mode
        binding.switchSilentMode.isChecked = preferences.isSilentExecutionMode

        // Audio Preferences: Speed
        val speed = preferences.voiceSpeed
        val (speedLabel, speedPill) = when {
            speed <= 0.85f -> Pair("Slow (0.8x)", "0.8x")
            speed in 0.95f..1.05f -> Pair("Normal", "Normal")
            speed in 1.15f..1.35f -> Pair("Fast (1.25x)", "1.25x")
            else -> Pair("Very Fast (1.5x)", "1.5x")
        }
        binding.tvCurrentSpeed.text = speedLabel
        binding.tvSpeedPillText.text = "$speedPill"

        // Audio Preferences: Volume
        val volume = preferences.voiceVolume
        binding.sliderVoiceVolume.progress = volume
        binding.tvVoiceVolumePercent.text = "$volume%"

        // Conversation Behaviour: Response Style
        val style = preferences.responseStyle
        val (styleDesc, stylePill) = when (style.lowercase()) {
            "concise" -> Pair("Concise (Direct & Brief)", "Concise")
            "detailed" -> Pair("Detailed (Comprehensive & Analytical)", "Detailed")
            else -> Pair("Balanced (Helpful + Natural)", "Balanced")
        }
        binding.tvCurrentResponseStyle.text = styleDesc
        binding.tvResponseStylePillText.text = stylePill

        // Advanced Settings Info
        val modelDisplay = preferences.aiModel.removePrefix("models/")
        binding.tvCurrentModel.text = "$modelDisplay • Live WebSocket"
        binding.tvCurrentTheme.text = when (preferences.appTheme) {
            "dark" -> "Dark Theme"
            "red" -> "Red Theme"
            "sunset" -> "Sunset Theme"
            "custom" -> "Custom (${preferences.customThemeColor})"
            else -> "Light Theme"
        }
    }

    private fun setupClickListeners() {
        // Navigation: Back Button
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // Header: Notifications Bell Button
        binding.btnNotifications.setOnClickListener {
            NotificationsBottomSheet.newInstance().show(childFragmentManager, NotificationsBottomSheet.TAG)
        }

        // Header: JARVIS Emblem
        binding.layoutJarvisEmblem.setOnClickListener {
            JarvisProBottomSheet.newInstance().show(childFragmentManager, JarvisProBottomSheet.TAG)
        }

        // Profile: Edit Operator Name
        binding.btnEditProfile.setOnClickListener {
            showEditNameDialog()
        }
        binding.cardProfile.setOnClickListener {
            showEditNameDialog()
        }

        // Profile: Enroll Voice Button
        binding.btnEnrollVoice.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        // Profile: JARVIS Pro Button
        binding.btnJarvisPro.setOnClickListener {
            JarvisProBottomSheet.newInstance().show(childFragmentManager, JarvisProBottomSheet.TAG)
        }

        // Section 1: Voice & Neural Intelligence
        binding.rowOwnerVoice.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        binding.rowLanguage.setOnClickListener {
            showLanguageDialog()
        }

        binding.rowAssistantName.setOnClickListener {
            showAssistantNameDialog()
        }

        binding.rowPersonaVoice.setOnClickListener {
            startActivity(Intent(requireContext(), PersonaActivity::class.java))
        }

        binding.rowAssistantVoice.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceSelectionActivity::class.java))
        }

        binding.switchSilentMode.setOnCheckedChangeListener { _, isChecked ->
            preferences.isSilentExecutionMode = isChecked
            val msg = if (isChecked) "Silent Execution Mode Enabled" else "Silent Execution Mode Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // Section 2: Audio Preferences
        binding.rowResponseSpeed.setOnClickListener {
            showResponseSpeedDialog()
        }

        binding.sliderVoiceVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvVoiceVolumePercent.text = "$progress%"
                if (fromUser) {
                    preferences.voiceVolume = progress
                    JarvisConversationService.updateAudioPreferences(progress, preferences.voiceSpeed)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    preferences.voiceVolume = it.progress
                    JarvisConversationService.updateAudioPreferences(it.progress, preferences.voiceSpeed)
                }
            }
        })

        // Section 3: Conversation Behaviour
        binding.rowResponseStyle.setOnClickListener {
            showResponseStyleDialog()
        }

        binding.rowMemory.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryActivity::class.java))
        }

        // Section 4: Advanced System & Tools (Preserved)
        binding.rowApiConnection.setOnClickListener {
            startActivity(Intent(requireContext(), ApiConnectionActivity::class.java))
        }

        binding.rowPermissions.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }

        binding.rowOverlayAvatars.setOnClickListener {
            startActivity(Intent(requireContext(), OverlayAvatarActivity::class.java))
        }

        binding.rowApplications.setOnClickListener {
            startActivity(Intent(requireContext(), HandsFreeActivity::class.java))
        }

        binding.rowAppTheme.setOnClickListener {
            ThemeSelectionBottomSheet.newInstance().show(childFragmentManager, ThemeSelectionBottomSheet.TAG)
        }

        binding.rowPrivacyPolicy.setOnClickListener {
            startActivity(Intent(requireContext(), PrivacyShieldActivity::class.java))
        }

        binding.rowAppIdentity.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("JARVIS App Identity")
                .setMessage(com.jarvis.assistant.security.AppIdentityVerifier.report(requireContext()))
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun showLanguageDialog() {
        val options = listOf(
            Pair("Hinglish (Hindi + English)", "Hinglish"),
            Pair("Auto (All Indian Languages)", "Auto"),
            Pair("English", "English"),
            Pair("Hindi", "Hindi"),
            Pair("Telugu", "Telugu"),
            Pair("Tamil", "Tamil"),
            Pair("Kannada", "Kannada"),
            Pair("Malayalam", "Malayalam"),
            Pair("Bengali", "Bengali"),
            Pair("Marathi", "Marathi"),
            Pair("Gujarati", "Gujarati"),
            Pair("Bhojpuri", "Bhojpuri"),
            Pair("Punjabi", "Punjabi"),
            Pair("Odia", "Odia"),
            Pair("Urdu", "Urdu")
        )
        val displayNames = options.map { it.first }.toTypedArray()
        val current = preferences.language
        val currentIndex = options.indexOfFirst { it.second.equals(current, ignoreCase = true) }
            .let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Conversation Language")
            .setSingleChoiceItems(displayNames, currentIndex) { dialog, which ->
                val chosen = options[which].second
                preferences.language = chosen
                loadUserData()
                if (JarvisConversationService.isServiceRunning()) {
                    JarvisConversationService.restartSession()
                }
                Toast.makeText(requireContext(), "Language set to ${options[which].first}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAssistantNameDialog() {
        val input = EditText(requireContext()).apply {
            setText(preferences.assistantName)
            setSelection(text.length)
            hint = "e.g. Jarvis or Friday"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Assistant Wake Name")
            .setMessage("Choose a single English/romanized word. After saving, restart standby listening for the new wake name to take effect.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (!newName.matches(Regex("[A-Za-z][A-Za-z0-9_-]{1,19}"))) {
                    Toast.makeText(requireContext(), "Use one word: 2–20 letters/numbers", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                preferences.assistantName = newName
                loadUserData()
                Toast.makeText(requireContext(), "Wake name saved: $newName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResponseSpeedDialog() {
        val options = listOf(
            Pair("Slow (0.8x)", 0.8f),
            Pair("Normal (1.0x)", 1.0f),
            Pair("Fast (1.25x)", 1.25f),
            Pair("Very Fast (1.5x)", 1.5f)
        )
        val labels = options.map { it.first }.toTypedArray()
        val currentSpeed = preferences.voiceSpeed
        val currentIndex = options.indexOfFirst { kotlin.math.abs(it.second - currentSpeed) < 0.08f }
            .let { if (it >= 0) it else 1 }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Voice Response Speed")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val chosen = options[which].second
                preferences.voiceSpeed = chosen
                JarvisConversationService.updateAudioPreferences(preferences.voiceVolume, chosen)
                loadUserData()
                Toast.makeText(requireContext(), "Speech rate set to ${labels[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResponseStyleDialog() {
        val options = listOf(
            Pair("Balanced (Helpful + Natural)", "Balanced"),
            Pair("Concise (Direct & Brief)", "Concise"),
            Pair("Detailed (Comprehensive & Analytical)", "Detailed")
        )
        val labels = options.map { it.first }.toTypedArray()
        val currentStyle = preferences.responseStyle
        val currentIndex = options.indexOfFirst { it.second.equals(currentStyle, ignoreCase = true) }
            .let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Assistant Response Style")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val chosen = options[which].second
                preferences.responseStyle = chosen
                loadUserData()
                if (JarvisConversationService.isServiceRunning()) {
                    JarvisConversationService.restartSession()
                }
                Toast.makeText(requireContext(), "Response style set to $chosen", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditNameDialog() {
        val input = EditText(requireContext()).apply {
            setText(preferences.userName.ifBlank { "Boss" })
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Operator Name")
            .setMessage("Enter the name Jarvis should use to address you:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    preferences.userName = newName
                    loadUserData()
                    Toast.makeText(requireContext(), "Operator name updated to $newName", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
