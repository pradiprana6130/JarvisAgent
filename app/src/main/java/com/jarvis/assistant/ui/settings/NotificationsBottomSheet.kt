package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.databinding.BottomSheetNotificationsBinding

class NotificationsBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "NotificationsBottomSheet"
        fun newInstance() = NotificationsBottomSheet()
    }

    private var _binding: BottomSheetNotificationsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCloseNotifications.setOnClickListener {
            dismiss()
        }

        // Live alert check
        val activeError = JarvisConversationService.liveError.value
        if (activeError != null) {
            binding.cardLiveAlert.visibility = View.VISIBLE
            binding.tvLiveAlertTitle.text = activeError.title
            binding.tvLiveAlertMessage.text = activeError.message
        } else {
            binding.cardLiveAlert.visibility = View.GONE
        }

        renderNotices()
    }

    private data class NotificationItem(
        val category: String,
        val title: String,
        val details: String,
        val badgeColor: Int
    )

    private fun renderNotices() {
        val list = binding.notificationsList
        list.removeAllViews()

        val items = listOf(
            NotificationItem(
                "SYSTEM ACTIVE",
                "JARVIS Neural Engine Ready",
                "Low-latency Gemini Live WebSocket engine calibrated. Voice standby detection listening for 'Jarvis'.",
                resolveThemeColor(R.attr.themeAccent)
            ),
            NotificationItem(
                "AUDIO PREFERENCES",
                "Hardware Playback & Rate Engine",
                "Speech response rate and volume multiplier actively configured with real-time AudioTrack DAC gain control.",
                ContextCompat.getColor(requireContext(), R.color.status_green)
            ),
            NotificationItem(
                "VOICE INTELLIGENCE",
                "30 Studio Neural Voices",
                "High-fidelity Gemini prebuilt voices with Hindi, English, and multilingual Indian language preservation.",
                resolveThemeColor(R.attr.themeAccent)
            ),
            NotificationItem(
                "AGENT AUTOMATION",
                "Verified Screen Actions",
                "Multi-step accessibility automation for WhatsApp messaging, YouTube search, and phone controls.",
                ContextCompat.getColor(requireContext(), R.color.status_purple)
            ),
            NotificationItem(
                "DATA SHIELD",
                "On-Device Biometric Calibration",
                "Owner voice vectors and AES-256 encrypted API storage ensure complete local hardware privacy.",
                resolveThemeColor(R.attr.themeAccent)
            )
        )

        items.forEach { item ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_jarvis_card)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            }

            card.addView(TextView(requireContext()).apply {
                text = item.category
                textSize = 10.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(item.badgeColor)
                letterSpacing = 0.06f
            })

            card.addView(TextView(requireContext()).apply {
                text = item.title
                textSize = 14.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(resolveThemeColor(R.attr.themeTextPrimary))
                setPadding(0, dp(4), 0, dp(4))
            })

            card.addView(TextView(requireContext()).apply {
                text = item.details
                textSize = 12.5f
                setTextColor(resolveThemeColor(R.attr.themeTextMuted))
                setLineSpacing(dp(2).toFloat(), 1f)
            })

            list.addView(card)
        }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
