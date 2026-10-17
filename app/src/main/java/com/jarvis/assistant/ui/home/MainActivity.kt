package com.jarvis.assistant.ui.home

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.background.JarvisLiveError
import com.jarvis.assistant.data.model.Conversation
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.model.DateGroup
import com.jarvis.assistant.databinding.ActivityMainBinding
import com.jarvis.assistant.ui.chat.ChatHistoryAdapter
import com.jarvis.assistant.ui.chat.HudChatAdapter
import com.jarvis.assistant.ui.memory.MemoryActivity
import com.jarvis.assistant.ui.orb.OrbHelper
import com.jarvis.assistant.ui.settings.SettingsActivity
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.attachment.AttachmentItem
import com.jarvis.assistant.attachment.AttachmentParser
import com.jarvis.assistant.attachment.AttachmentType
import com.jarvis.assistant.databinding.LayoutPendingAttachmentChipBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Calendar
import android.provider.Settings

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_HOME = 0
        const val TAB_CHAT = 1
        const val TAB_SETTINGS = 2
        const val TAB_UPDATES = 3
    }

    enum class ScreenState {
        HOME,
        CHAT
    }

    fun selectTab(tab: Int) {
        when (tab) {
            TAB_HOME -> switchScreen(ScreenState.HOME)
            TAB_CHAT -> switchScreen(ScreenState.CHAT)
            TAB_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            TAB_UPDATES -> switchScreen(ScreenState.HOME)
        }
    }

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()
    private var activeSnackbar: Snackbar? = null

    private lateinit var orbHelper: OrbHelper
    private lateinit var chatAdapter: HudChatAdapter
    private lateinit var historyAdapter: ChatHistoryAdapter
    private var selectedFilterGroup: DateGroup? = null
    private var historySearchQuery: String = ""
    private var currentScreenState: ScreenState = ScreenState.HOME
    private var pulseAnimator: android.animation.ValueAnimator? = null
    private var screenPulseAnimator: android.animation.ValueAnimator? = null
    private val pendingAttachments = MutableStateFlow<List<AttachmentItem>>(emptyList())
    private var isSendingQuery = false

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkPermissionsAndStart()
        } else {
            Toast.makeText(
                this,
                "Microphone permission is required for voice conversation.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkPermissionsAndStart()
        } else {
            Toast.makeText(
                this,
                "Notification permission is required to run in the background.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    }

    private val documentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val parsedList = mutableListOf<AttachmentItem>()
            var errorCount = 0
            for (uri in uris) {
                try {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}

                    val item = AttachmentParser.parseUri(this@MainActivity, uri)
                    if (item != null) {
                        parsedList.add(item)
                    } else {
                        errorCount++
                    }
                } catch (e: Exception) {
                    errorCount++
                }
            }
            withContext(Dispatchers.Main) {
                if (parsedList.isNotEmpty()) {
                    val current = pendingAttachments.value.toMutableList()
                    for (item in parsedList) {
                        if (current.none { it.uri == item.uri }) {
                            current.add(item)
                        }
                    }
                    pendingAttachments.value = current
                    updatePendingAttachmentsUi()
                }
                if (errorCount > 0) {
                    Toast.makeText(
                        this@MainActivity,
                        "Some files could not be read or exceeded the 15MB limit.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            try {
                if (!JarvisConversationService.isServiceRunning()) {
                    checkPermissionsAndStart()
                }
                if (!JarvisConversationService.elevateToMediaProjection()) {
                    Toast.makeText(this, "Could not start Android's screen-capture service.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                val projection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val started = JarvisConversationService.startScreenCapture(projection, metrics)
                if (!started) {
                    JarvisConversationService.demoteFromMediaProjection()
                    Toast.makeText(this, "Android did not start screen capture. Please try again.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                Toast.makeText(this, "Screen sharing activated. Ask Jarvis about what's on your screen.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error starting MediaProjection: ${e.message}", e)
                JarvisConversationService.demoteFromMediaProjection()
                Toast.makeText(this, "Screen capture failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleScreenShare() {
        if (com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value) {
            JarvisConversationService.stopScreenCapture()
            Toast.makeText(this, "Screen sharing stopped.", Toast.LENGTH_SHORT).show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Share Screen with JARVIS")
                .setMessage("JARVIS can analyze visible content and answer questions based on your screen.\n\nA persistent notification and status chip will indicate when screen capture is active. You can stop sharing at any time.")
                .setPositiveButton("Start Sharing") { _, _ ->
                    if (!JarvisConversationService.isServiceRunning()) {
                        checkPermissionsAndStart()
                    }
                    screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Release Security Integrity Gate
        if (!com.jarvis.assistant.security.AppIdentityVerifier.isTrustedRelease(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("App integrity check failed")
                .setMessage("This JARVIS build is not signed with the configured official release certificate. Sensitive features have been blocked.")
                .setCancelable(false)
                .setPositiveButton("Close") { _, _ -> finish() }
                .show()
            return
        }

        // Bind hardware volume keys directly to media stream so TTS loudness is adjusted without interruption
        volumeControlStream = AudioManager.STREAM_MUSIC

        setupWindowInsets()
        setupOrb()
        setupChatRecyclerView()
        setupNavigationDrawer()
        setupTopBar()
        setupBottomNavigation()
        setupQuickCards()
        setupInputBars()
        updateGreetingHeader()
        observeViewModel()
        setupBackPressHandler()

        switchScreen(ScreenState.HOME)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val isKeyboardOpen = ime.bottom > 0

            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                systemBars.top + 6,
                binding.topBar.paddingRight,
                binding.topBar.paddingBottom
            )
            binding.layoutBottomNavContainer.setPadding(
                binding.layoutBottomNavContainer.paddingLeft,
                binding.layoutBottomNavContainer.paddingTop,
                binding.layoutBottomNavContainer.paddingRight,
                if (isKeyboardOpen) 4 else systemBars.bottom + 4
            )
            binding.layoutChatInputArea.setPadding(
                binding.layoutChatInputArea.paddingLeft,
                binding.layoutChatInputArea.paddingTop,
                binding.layoutChatInputArea.paddingRight,
                if (isKeyboardOpen) ime.bottom + 4 else 6
            )
            binding.layoutHomeComposerContainer.setPadding(
                binding.layoutHomeComposerContainer.paddingLeft,
                binding.layoutHomeComposerContainer.paddingTop,
                binding.layoutHomeComposerContainer.paddingRight,
                if (isKeyboardOpen) ime.bottom + 4 else 0
            )
            insets
        }
    }

    private fun setupOrb() {
        orbHelper = OrbHelper(binding.webViewOrb) {
            handleMicOrOrbClick()
        }
        orbHelper.setup()
    }

    private fun setupChatRecyclerView() {
        chatAdapter = HudChatAdapter(
            onEditUserMessage = { text ->
                binding.etChatInput.setText(text)
                binding.etChatInput.setSelection(text.length)
                binding.etChatInput.requestFocus()
            }
        )
        binding.rvChatMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = chatAdapter
        setupChatHistoryPanel()
    }

    private fun setupNavigationDrawer() {
        val displayMetrics = resources.displayMetrics
        val targetWidth = (displayMetrics.widthPixels * 0.78f).toInt()
            .coerceIn((270 * displayMetrics.density).toInt(), (340 * displayMetrics.density).toInt())
        val drawerParams = binding.drawerContainer.layoutParams
        drawerParams.width = targetWidth
        binding.drawerContainer.layoutParams = drawerParams

        binding.drawerLayout.setScrimColor(Color.parseColor("#B3010609"))

        binding.layoutDrawer.btnDrawerClose.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.layoutDrawer.itemHome.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            switchScreen(ScreenState.HOME)
        }

        binding.layoutDrawer.itemJarvisHome.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            switchScreen(ScreenState.HOME)
        }

        binding.layoutDrawer.itemChat.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            switchScreen(ScreenState.CHAT)
        }

        binding.layoutDrawer.itemMemories.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, MemoryActivity::class.java))
        }

        binding.layoutDrawer.itemOwnerMemory.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, MemoryActivity::class.java))
        }

        // Additional drawer settings items
        binding.layoutDrawer.itemSettings.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val prefs = JarvisApp.instance.preferences
        val userName = prefs.userName.ifBlank { "Sir" }
        binding.layoutDrawer.tvDrawerUserName.text = "$userName."
    }

    private fun setupTopBar() {
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.btnChatBack.setOnClickListener {
            if (binding.layoutHistoricalHeader.visibility == View.VISIBLE) {
                hideHistoricalHeader()
                viewModel.startNewConversation()
            } else {
                switchScreen(ScreenState.HOME)
            }
        }

        binding.btnNotifications.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.layoutTopProfile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnNewChat.setOnClickListener {
            viewModel.startNewConversation()
            hideHistoricalHeader()
            binding.layoutChatHistoryPanel.visibility = View.GONE
        }

        binding.btnChatHistory.setOnClickListener {
            val isVisible = binding.layoutChatHistoryPanel.visibility == View.VISIBLE
            binding.layoutChatHistoryPanel.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                applyHistoryFilters()
            }
        }
    }

    private fun setupBottomNavigation() {
        // Nav 1: Home
        binding.navHome.setOnClickListener {
            switchScreen(ScreenState.HOME)
        }

        // Nav 2: Vision
        binding.navVision.setOnClickListener {
            toggleScreenShare()
        }

        // Nav 4: Memory
        binding.navMemory.setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }

        // Nav 5: Chat
        binding.navChat.setOnClickListener {
            switchScreen(ScreenState.CHAT)
        }

        // Center Mic Button
        binding.btnCenterMic.setOnClickListener {
            handleMicOrOrbClick()
        }

        binding.btnCenterMic.setOnLongClickListener {
            handleHoldMicOrOrb()
            true
        }
    }

    private fun setupQuickCards() {
        binding.cardMemory.setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }

        binding.cardReasoning.setOnClickListener {
            switchScreen(ScreenState.CHAT)
        }

        binding.cardData.setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }

        binding.cardSystem.setOnClickListener {
            toggleScreenShare()
        }
    }

    private fun setupInputBars() {
        // 1. Home Composer
        binding.btnHomeAttach.setOnClickListener {
            documentPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnSendQuery.setOnClickListener {
            val text = binding.etQueryInput.text?.toString()?.trim() ?: ""
            sendUserQuery(text)
        }

        binding.etQueryInput.doAfterTextChanged { text ->
            JarvisConversationService.notifyDraftTyping(text)
        }

        binding.etQueryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = binding.etQueryInput.text?.toString()?.trim() ?: ""
                sendUserQuery(text)
                true
            } else false
        }

        binding.btnHomeScreenShare.setOnClickListener {
            toggleScreenShare()
        }

        binding.btnHomeStopScreenShare.setOnClickListener {
            JarvisConversationService.stopScreenCapture()
        }

        binding.btnInputSliders.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 2. Chat Screen Composer
        binding.btnChatAttachFile.setOnClickListener {
            documentPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnChatSend.setOnClickListener {
            val text = binding.etChatInput.text?.toString()?.trim() ?: ""
            sendUserQuery(text)
        }

        binding.etChatInput.doAfterTextChanged { text ->
            JarvisConversationService.notifyDraftTyping(text)
        }

        binding.etChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = binding.etChatInput.text?.toString()?.trim() ?: ""
                sendUserQuery(text)
                true
            } else false
        }

        binding.btnChatScreenShare.setOnClickListener {
            toggleScreenShare()
        }

        binding.btnChatStopScreenShare.setOnClickListener {
            JarvisConversationService.stopScreenCapture()
        }

        binding.btnChatSliders.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun updatePendingAttachmentsUi() {
        val items = pendingAttachments.value
        val isSharing = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value
        val hasItems = items.isNotEmpty()

        // Home
        binding.layoutHomePendingAttachments.removeAllViews()
        binding.scrollHomeComposerAttachments.visibility = if (hasItems || isSharing) View.VISIBLE else View.GONE

        // Chat
        binding.layoutChatPendingAttachments.removeAllViews()
        binding.scrollChatComposerAttachments.visibility = if (hasItems || isSharing) View.VISIBLE else View.GONE

        for (item in items) {
            val homeChipBinding = LayoutPendingAttachmentChipBinding.inflate(layoutInflater, binding.layoutHomePendingAttachments, false)
            bindPendingChip(homeChipBinding, item)
            binding.layoutHomePendingAttachments.addView(homeChipBinding.root)

            val chatChipBinding = LayoutPendingAttachmentChipBinding.inflate(layoutInflater, binding.layoutChatPendingAttachments, false)
            bindPendingChip(chatChipBinding, item)
            binding.layoutChatPendingAttachments.addView(chatChipBinding.root)
        }
    }

    private fun bindPendingChip(chipBinding: LayoutPendingAttachmentChipBinding, item: AttachmentItem) {
        val iconRes = when (item.attachmentType) {
            AttachmentType.PDF -> R.drawable.ic_picture_as_pdf
            AttachmentType.IMAGE -> R.drawable.ic_image
            AttachmentType.DATA -> R.drawable.ic_code
            else -> R.drawable.ic_description
        }
        chipBinding.ivChipIcon.setImageResource(iconRes)
        chipBinding.tvChipName.text = item.name
        chipBinding.tvChipSize.text = item.formattedSize
        chipBinding.btnChipRemove.setOnClickListener {
            val current = pendingAttachments.value.toMutableList()
            current.removeAll { it.uri == item.uri }
            pendingAttachments.value = current
            updatePendingAttachmentsUi()
        }
    }

    private fun startScreenSharePulseAnimation() {
        if (screenPulseAnimator?.isRunning == true) return
        screenPulseAnimator = android.animation.ValueAnimator.ofFloat(0.35f, 1.0f).apply {
            duration = 800
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val alpha = animator.animatedValue as Float
                binding.dotHomeScreenSharePulse.alpha = alpha
                binding.dotChatScreenSharePulse.alpha = alpha
            }
            start()
        }
    }

    private fun stopScreenSharePulseAnimation() {
        screenPulseAnimator?.cancel()
        screenPulseAnimator = null
        binding.dotHomeScreenSharePulse.alpha = 1.0f
        binding.dotChatScreenSharePulse.alpha = 1.0f
    }

    fun switchScreen(state: ScreenState) {
        currentScreenState = state
        when (state) {
            ScreenState.HOME -> {
                binding.homeDashboardContainer.visibility = View.VISIBLE
                binding.chatScreenContainer.visibility = View.GONE
                binding.btnMenu.visibility = View.VISIBLE
                binding.btnChatBack.visibility = View.GONE
                binding.btnNotifications.visibility = View.VISIBLE
                binding.btnNewChat.visibility = View.GONE
                binding.btnChatHistory.visibility = View.GONE
                binding.tvAssistantBadge.text = "JARVIS"
                binding.tvAssistantModeStatus.visibility = View.VISIBLE

                updateBottomNavSelection(isHome = true, isChat = false)
            }

            ScreenState.CHAT -> {
                binding.homeDashboardContainer.visibility = View.GONE
                binding.chatScreenContainer.visibility = View.VISIBLE
                binding.btnMenu.visibility = View.VISIBLE
                binding.btnChatBack.visibility = View.GONE
                binding.btnNotifications.visibility = View.GONE
                binding.btnNewChat.visibility = View.VISIBLE
                binding.btnChatHistory.visibility = View.VISIBLE
                binding.tvAssistantBadge.text = "CHAT"
                binding.tvAssistantModeStatus.visibility = View.GONE

                updateBottomNavSelection(isHome = false, isChat = true)
            }
        }
    }

    private fun updateBottomNavSelection(isHome: Boolean, isChat: Boolean) {
        val cyanColor = ContextCompat.getColor(this, R.color.primary_cyan)
        val mutedColor = ContextCompat.getColor(this, R.color.text_secondary)

        // Home
        binding.ivNavHomeIcon.imageTintList = ColorStateList.valueOf(if (isHome) cyanColor else mutedColor)
        binding.tvNavHomeLabel.setTextColor(if (isHome) cyanColor else mutedColor)
        binding.tvNavHomeLabel.setTypeface(null, if (isHome) Typeface.BOLD else Typeface.NORMAL)
        binding.dotNavHomeActive.visibility = if (isHome) View.VISIBLE else View.INVISIBLE

        // Chat
        binding.ivNavChatIcon.imageTintList = ColorStateList.valueOf(if (isChat) cyanColor else mutedColor)
        binding.tvNavChatLabel.setTextColor(if (isChat) cyanColor else mutedColor)
        binding.tvNavChatLabel.setTypeface(null, if (isChat) Typeface.BOLD else Typeface.NORMAL)
        binding.dotNavChatActive.visibility = if (isChat) View.VISIBLE else View.INVISIBLE
    }

    private fun sendUserQuery(text: String) {
        if (isSendingQuery) return

        val trimmedText = text.trim()
        val attachments = pendingAttachments.value
        val isSharing = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value

        if (trimmedText.isBlank() && attachments.isEmpty() && !isSharing) {
            return
        }

        isSendingQuery = true
        binding.btnSendQuery.isEnabled = false
        binding.btnChatSend.isEnabled = false

        lifecycleScope.launch {
            try {
                if (!JarvisConversationService.isServiceRunning()) {
                    checkPermissionsAndStart()
                }

                var screenSnapshot: ByteArray? = null
                if (isSharing) {
                    screenSnapshot = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()
                        .awaitFreshSnapshot(timeoutMs = 1500L, quality = 75)
                }

                // Clear composer and pending attachments
                binding.etQueryInput.text?.clear()
                binding.etChatInput.text?.clear()
                pendingAttachments.value = emptyList()
                updatePendingAttachmentsUi()

                // Submit prompt with attachments and screen frame
                val fullPrompt = AttachmentParser.buildModelPrompt(trimmedText, attachments)
                JarvisConversationService.submitUserPrompt(
                    promptWithDocs = fullPrompt,
                    displayUserText = trimmedText,
                    attachments = attachments,
                    screenFrameBytes = screenSnapshot
                )

                // Switch to chat screen to display turn
                switchScreen(ScreenState.CHAT)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to submit user prompt: ${e.message}", e)
                Toast.makeText(this@MainActivity, "Failed to send: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isSendingQuery = false
                binding.btnSendQuery.isEnabled = true
                binding.btnChatSend.isEnabled = true
            }
        }
    }

    fun handleMicOrOrbClick() {
        val isVoiceActive = JarvisConversationService.isSessionOn.value && !viewModel.isManualOff.value
        if (isVoiceActive) {
            viewModel.turnOffSession()
        } else {
            checkPermissionsAndStart()
        }
    }

    fun handleHoldMicOrOrb() {
        binding.btnCenterMic.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (JarvisConversationService.isSessionOn.value && !viewModel.isManualOff.value) {
            viewModel.turnOffSession()
        } else {
            checkPermissionsAndStart()
        }
    }

    private fun checkPermissionsAndStart() {
        if (!PermissionHelper.hasRecordAudioPermission(this)) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!PermissionHelper.hasNotificationPermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        if (!PermissionHelper.hasBatteryOptimizationPermission(this)) {
            showBatteryPermissionDialog()
            return
        }

        viewModel.toggleSession()
    }

    private fun showBatteryPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Unrestricted Battery Required")
            .setMessage("Jarvis requires Unrestricted Battery access so it can stay active in the background and respond to wake words.\n\nPlease allow it or open Settings.")
            .setPositiveButton("ALLOW") { _, _ ->
                PermissionHelper.requestBatteryOptimization(this)
            }
            .setNeutralButton("OPEN SETTINGS") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun updateGreetingHeader() {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 5..11 -> "Good morning,"
            in 12..16 -> "Good afternoon,"
            in 17..21 -> "Good evening,"
            else -> "Late night standby,"
        }
        binding.tvGreeting.text = greeting

        val prefs = JarvisApp.instance.preferences
        val userName = prefs.userName.ifBlank { "Sir" }
        binding.tvUserName.text = "$userName."
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // 1. Live Time & Clock
                launch {
                    viewModel.liveTime.collect { time ->
                        binding.tvRealTime.text = time
                    }
                }

                // 2. Connection Status
                launch {
                    viewModel.connectionStatus.collect { status ->
                        binding.tvAssistantModeStatus.text = "ASSISTANT MODE • $status"
                        val dotColor = when (status) {
                            "LIVE" -> ContextCompat.getColor(this@MainActivity, R.color.status_green)
                            "CONNECTING...", "RECONNECTING...", "STANDBY" -> ContextCompat.getColor(this@MainActivity, R.color.status_amber)
                            "OFF" -> ContextCompat.getColor(this@MainActivity, R.color.status_red)
                            else -> ContextCompat.getColor(this@MainActivity, R.color.text_muted)
                        }
                        binding.dotSyncStatus.backgroundTintList = ColorStateList.valueOf(dotColor)
                        binding.dotLiveStatusOverview.backgroundTintList = ColorStateList.valueOf(dotColor)
                        binding.tvReasoningStatus.text = if (status == "LIVE") "Connected" else status
                        binding.dotReasoningStatus.backgroundTintList = ColorStateList.valueOf(dotColor)
                    }
                }

                // 3. Conversation State & Orb Flow
                launch {
                    combine(
                        viewModel.isSessionOn,
                        JarvisConversationService.isStandby,
                        viewModel.isManualOff,
                        viewModel.liveError,
                        viewModel.conversationState
                    ) { isOn, isStandby, isManualOff, error, state ->
                        StateTuple(isOn, isStandby, isManualOff, error, state)
                    }.collect { tuple ->
                        updateOrbAndUiState(tuple.isOn, tuple.isStandby, tuple.isManualOff, tuple.error, tuple.state)
                    }
                }

                // 4. Audio Level Reactivity
                launch {
                    viewModel.audioLevel.collect { level ->
                        orbHelper.updateAudioLevel(level)
                        binding.hudVisualizer.setAudioLevel(level)
                    }
                }

                // 5. Chat Turns
                launch {
                    viewModel.chatTurns.collect { turns ->
                        val hasTurns = turns.isNotEmpty()
                        binding.layoutChatEmptyState.visibility = if (hasTurns) View.GONE else View.VISIBLE
                        binding.rvChatMessages.visibility = if (hasTurns) View.VISIBLE else View.GONE
                        chatAdapter.submitList(turns) {
                            if (hasTurns) {
                                binding.rvChatMessages.post {
                                    binding.rvChatMessages.smoothScrollToPosition(turns.size - 1)
                                }
                            }
                        }
                    }
                }

                // 6. Conversations & Chat History Panel
                launch {
                    viewModel.conversations.collect { convs ->
                        val total = convs.size
                        binding.tvHistoryTotalCount.text = "$total saved session${if (total == 1) "" else "s"}"
                        applyHistoryFilters()
                    }
                }

                launch {
                    viewModel.activeConversationId.collect { activeId ->
                        historyAdapter.setActiveConversationId(activeId)
                        if (activeId == null) {
                            hideHistoricalHeader()
                        } else {
                            val activeConv = viewModel.conversations.value.find { it.id == activeId }
                            if (activeConv != null && binding.layoutHistoricalHeader.visibility == View.VISIBLE) {
                                binding.tvHistoricalTitle.text = activeConv.title
                                binding.tvHistoricalTimestamp.text = activeConv.formattedDate
                            }
                        }
                    }
                }

                // 6. Actionable Snackbars (Filtering out mic toggle & wake noise)
                launch {
                    viewModel.eventFlow.collect { message ->
                        val isToggleOrWakeNoise = message.contains("Powered Off", ignoreCase = true) ||
                            message.contains("Microphone", ignoreCase = true) ||
                            message.contains("Wake word", ignoreCase = true) ||
                            message.contains("Mic error", ignoreCase = true) ||
                            message.contains("Standby", ignoreCase = true)
                        if (!isToggleOrWakeNoise) {
                            activeSnackbar?.dismiss()
                            activeSnackbar = Snackbar.make(binding.rootLayout, message, Snackbar.LENGTH_SHORT).apply {
                                setAnchorView(binding.layoutBottomNavContainer)
                                show()
                            }
                        }
                    }
                }

                // 7. Screen Sharing Status Observation
                launch {
                    com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.collect { isSharing ->
                        val chipVis = if (isSharing) View.VISIBLE else View.GONE
                        binding.chipHomeScreenShareActive.visibility = chipVis
                        binding.chipChatScreenShareActive.visibility = chipVis

                        val activeColor = ContextCompat.getColor(this@MainActivity, R.color.primary_cyan)
                        val defaultColor = ContextCompat.getColor(this@MainActivity, R.color.hud_text_secondary)
                        val tint = ColorStateList.valueOf(if (isSharing) activeColor else defaultColor)
                        binding.btnHomeScreenShare.imageTintList = tint
                        binding.btnChatScreenShare.imageTintList = tint

                        updatePendingAttachmentsUi()

                        if (isSharing) {
                            startScreenSharePulseAnimation()
                        } else {
                            stopScreenSharePulseAnimation()
                        }
                    }
                }
            }
        }
    }

    private fun setMicPulseAnimation(active: Boolean) {
        val durationScale = try {
            Settings.Global.getFloat(
                contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
        } catch (_: Exception) {
            1.0f
        }
        val isReducedMotion = durationScale == 0f

        if (!active) {
            pulseAnimator?.cancel()
            pulseAnimator = null
            binding.centerMicPulseRing.visibility = View.GONE
            binding.centerMicPulseRing.scaleX = 1.0f
            binding.centerMicPulseRing.scaleY = 1.0f
            binding.centerMicPulseRing.alpha = 0.0f
            return
        }

        binding.centerMicPulseRing.visibility = View.VISIBLE
        if (isReducedMotion) {
            pulseAnimator?.cancel()
            pulseAnimator = null
            binding.centerMicPulseRing.scaleX = 1.04f
            binding.centerMicPulseRing.scaleY = 1.04f
            binding.centerMicPulseRing.alpha = 0.6f
            return
        }

        if (pulseAnimator == null) {
            pulseAnimator = android.animation.ValueAnimator.ofFloat(1.0f, 1.15f).apply {
                duration = 1400L
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val scale = anim.animatedValue as Float
                    binding.centerMicPulseRing.scaleX = scale
                    binding.centerMicPulseRing.scaleY = scale
                    val progress = anim.animatedFraction
                    binding.centerMicPulseRing.alpha = 0.8f - (progress * 0.45f)
                }
                start()
            }
        }
    }

    private fun updateOrbAndUiState(
        isOn: Boolean,
        isStandby: Boolean,
        isManualOff: Boolean,
        error: JarvisLiveError?,
        state: ConversationState
    ) {
        val effectiveState = when {
            error != null -> ConversationState.ERROR
            isManualOff || state == ConversationState.OFF -> ConversationState.OFF
            !isOn && isStandby -> ConversationState.IDLE
            !isOn -> ConversationState.OFF
            state == ConversationState.STANDBY -> ConversationState.IDLE
            else -> state
        }

        orbHelper.setOrbState(effectiveState.orbKey)
        binding.hudVisualizer.setConversationState(effectiveState)

        // Update Center Mic Button Appearance & Status Text
        val cyanColor = ContextCompat.getColor(this, R.color.primary_cyan)
        val redColor = ContextCompat.getColor(this, R.color.status_red)
        val mutedColor = ContextCompat.getColor(this, R.color.text_muted)
        val amberColor = Color.parseColor("#F59E0B")

        when (effectiveState) {
            ConversationState.OFF -> {
                setMicPulseAnimation(false)
                binding.btnCenterMic.background = ContextCompat.getDrawable(this, R.drawable.bg_center_mic_outer_off)
                binding.ivCenterMicIcon.imageTintList = ColorStateList.valueOf(mutedColor)
                binding.waveformStatus.setActive(false)
                binding.tvSystemStatusText.text = "OFF"
                binding.tvSystemStatusText.setTextColor(mutedColor)
            }
            ConversationState.STANDBY, ConversationState.IDLE -> {
                setMicPulseAnimation(true)
                binding.btnCenterMic.background = ContextCompat.getDrawable(this, R.drawable.bg_center_mic_outer)
                binding.ivCenterMicIcon.imageTintList = ColorStateList.valueOf(cyanColor)
                binding.waveformStatus.setActive(true)
                binding.tvSystemStatusText.text = "IDLE"
                binding.tvSystemStatusText.setTextColor(cyanColor)
            }
            ConversationState.ERROR -> {
                setMicPulseAnimation(false)
                binding.btnCenterMic.background = ContextCompat.getDrawable(this, R.drawable.bg_center_mic_outer_off)
                binding.ivCenterMicIcon.imageTintList = ColorStateList.valueOf(redColor)
                binding.waveformStatus.setActive(false)
                binding.tvSystemStatusText.text = "ERROR"
                binding.tvSystemStatusText.setTextColor(redColor)
            }
            ConversationState.EXECUTING -> {
                setMicPulseAnimation(true)
                binding.btnCenterMic.background = ContextCompat.getDrawable(this, R.drawable.bg_center_mic_outer)
                binding.ivCenterMicIcon.imageTintList = ColorStateList.valueOf(amberColor)
                binding.waveformStatus.setActive(true)
                binding.tvSystemStatusText.text = "EXECUTING"
                binding.tvSystemStatusText.setTextColor(amberColor)
            }
            ConversationState.THINKING -> {
                setMicPulseAnimation(true)
                binding.btnCenterMic.background = ContextCompat.getDrawable(this, R.drawable.bg_center_mic_outer)
                binding.ivCenterMicIcon.imageTintList = ColorStateList.valueOf(amberColor)
                binding.waveformStatus.setActive(true)
                binding.tvSystemStatusText.text = "THINKING"
                binding.tvSystemStatusText.setTextColor(amberColor)
            }
            ConversationState.WAKE_UP, ConversationState.LISTENING -> {
                setMicPulseAnimation(true)
                binding.btnCenterMic.background = ContextCompat.getDrawable(this, R.drawable.bg_center_mic_outer)
                binding.ivCenterMicIcon.imageTintList = ColorStateList.valueOf(cyanColor)
                binding.waveformStatus.setActive(true)
                binding.tvSystemStatusText.text = "LISTENING"
                binding.tvSystemStatusText.setTextColor(cyanColor)
            }
            ConversationState.SPEAKING -> {
                setMicPulseAnimation(true)
                binding.btnCenterMic.background = ContextCompat.getDrawable(this, R.drawable.bg_center_mic_outer)
                binding.ivCenterMicIcon.imageTintList = ColorStateList.valueOf(cyanColor)
                binding.waveformStatus.setActive(true)
                binding.tvSystemStatusText.text = "SPEAKING"
                binding.tvSystemStatusText.setTextColor(cyanColor)
            }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.layoutChatHistoryPanel.visibility == View.VISIBLE) {
                    binding.layoutChatHistoryPanel.visibility = View.GONE
                } else if (binding.layoutHistoricalHeader.visibility == View.VISIBLE) {
                    hideHistoricalHeader()
                    viewModel.startNewConversation()
                } else if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else if (currentScreenState != ScreenState.HOME) {
                    switchScreen(ScreenState.HOME)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun setupChatHistoryPanel() {
        historyAdapter = ChatHistoryAdapter(
            onConversationSelected = { conv ->
                viewModel.selectConversation(conv.id)
                binding.layoutChatHistoryPanel.visibility = View.GONE
                showHistoricalHeader(conv)
            },
            onConversationDelete = { conv ->
                showDeleteConversationDialog(conv)
            }
        )
        binding.rvChatHistoryList.layoutManager = LinearLayoutManager(this)
        binding.rvChatHistoryList.adapter = historyAdapter

        binding.btnHistoryClose.setOnClickListener {
            binding.layoutChatHistoryPanel.visibility = View.GONE
        }

        binding.btnHistoryNewChat.setOnClickListener {
            viewModel.startNewConversation()
            binding.layoutChatHistoryPanel.visibility = View.GONE
            hideHistoricalHeader()
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etHistorySearch.text?.clear()
        }

        binding.etHistorySearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                historySearchQuery = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.visibility = if (historySearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                applyHistoryFilters()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // History Filter Chips
        binding.chipFilterAll.setOnClickListener { setHistoryDateFilter(null) }
        binding.chipFilterToday.setOnClickListener { setHistoryDateFilter(DateGroup.TODAY) }
        binding.chipFilterThisWeek.setOnClickListener { setHistoryDateFilter(DateGroup.THIS_WEEK) }
        binding.chipFilterThisMonth.setOnClickListener { setHistoryDateFilter(DateGroup.THIS_MONTH) }
    }

    private fun setHistoryDateFilter(group: DateGroup?) {
        selectedFilterGroup = group
        val activeBg = R.drawable.bg_filter_chip_active
        val inactiveBg = R.drawable.bg_filter_chip_inactive
        val cyanColor = ContextCompat.getColor(this, R.color.primary_cyan)
        val mutedColor = ContextCompat.getColor(this, R.color.hud_text_secondary)

        binding.chipFilterAll.setBackgroundResource(if (group == null) activeBg else inactiveBg)
        binding.chipFilterAll.setTextColor(if (group == null) cyanColor else mutedColor)

        binding.chipFilterToday.setBackgroundResource(if (group == DateGroup.TODAY) activeBg else inactiveBg)
        binding.chipFilterToday.setTextColor(if (group == DateGroup.TODAY) cyanColor else mutedColor)

        binding.chipFilterThisWeek.setBackgroundResource(if (group == DateGroup.THIS_WEEK) activeBg else inactiveBg)
        binding.chipFilterThisWeek.setTextColor(if (group == DateGroup.THIS_WEEK) cyanColor else mutedColor)

        binding.chipFilterThisMonth.setBackgroundResource(if (group == DateGroup.THIS_MONTH) activeBg else inactiveBg)
        binding.chipFilterThisMonth.setTextColor(if (group == DateGroup.THIS_MONTH) cyanColor else mutedColor)

        applyHistoryFilters()
    }

    private fun applyHistoryFilters() {
        val allConversations = viewModel.conversations.value
        val filtered = allConversations.filter { conv ->
            val matchesGroup = selectedFilterGroup == null || conv.dateGroup == selectedFilterGroup
            val matchesQuery = historySearchQuery.isBlank() ||
                conv.title.contains(historySearchQuery, ignoreCase = true) ||
                conv.turns.any { it.userTranscript.contains(historySearchQuery, ignoreCase = true) || it.jarvisResponse.contains(historySearchQuery, ignoreCase = true) }
            matchesGroup && matchesQuery
        }
        historyAdapter.submitList(filtered)
        val isEmpty = filtered.isEmpty()
        binding.layoutHistoryEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvChatHistoryList.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showHistoricalHeader(conv: Conversation) {
        binding.layoutHistoricalHeader.visibility = View.VISIBLE
        binding.tvHistoricalTitle.text = conv.title
        binding.tvHistoricalTimestamp.text = conv.formattedDate
        binding.layoutHistoricalActions.visibility = View.VISIBLE
        binding.btnChatBack.visibility = View.VISIBLE
        binding.btnHistoricalDelete.setOnClickListener {
            showDeleteConversationDialog(conv)
        }
    }

    private fun hideHistoricalHeader() {
        binding.layoutHistoricalHeader.visibility = View.GONE
        binding.layoutHistoricalActions.visibility = View.GONE
        binding.btnChatBack.visibility = View.GONE
    }

    private fun showDeleteConversationDialog(conv: Conversation) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Conversation")
            .setMessage("Are you sure you want to delete '${conv.title}'? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteConversation(conv.id)
                if (viewModel.activeConversationId.value == null || viewModel.activeConversationId.value == conv.id) {
                    hideHistoricalHeader()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            JarvisConversationService.notifyHardwareVolumeKey()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDestroy()
    }

    private data class StateTuple(
        val isOn: Boolean,
        val isStandby: Boolean,
        val isManualOff: Boolean,
        val error: JarvisLiveError?,
        val state: ConversationState
    )
}
