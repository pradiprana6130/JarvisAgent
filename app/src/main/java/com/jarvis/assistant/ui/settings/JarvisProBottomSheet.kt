package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.databinding.BottomSheetJarvisProBinding

class JarvisProBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "JarvisProBottomSheet"
        fun newInstance() = JarvisProBottomSheet()
    }

    private var _binding: BottomSheetJarvisProBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetJarvisProBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClosePro.setOnClickListener {
            dismiss()
        }

        binding.btnProOpenPersona.setOnClickListener {
            dismiss()
            startActivity(Intent(requireContext(), PersonaActivity::class.java))
        }

        binding.btnProOpenVoices.setOnClickListener {
            dismiss()
            startActivity(Intent(requireContext(), VoiceSelectionActivity::class.java))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
