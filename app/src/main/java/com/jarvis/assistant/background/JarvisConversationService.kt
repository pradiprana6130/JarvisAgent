package com.jarvis.assistant.background

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import com.jarvis.assistant.audio.JarvisAudioPolicy
import android.os.SystemClock
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.audio.VoskModelManager
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService
import com.jarvis.assistant.audio.AudioOutputRouteType
import com.jarvis.assistant.audio.AudioPlayer
import com.jarvis.assistant.audio.AudioRecorder
import com.jarvis.assistant.audio.EchoGuardConfig
import com.jarvis.assistant.audio.EchoGuardState
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.network.GeminiLiveWebSocket
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.PromptGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.Locale

data class JarvisLiveError(
    val title: String,
    val message: String,
    val errorCode: String,
    val actionText: String = "FIX IN SETTINGS"
)

/**
 * Owns the *entire* Jarvis conversation lifecycle: the microphone, the audio
 * player, the Gemini Live WebSocket, the 120-second inactivity timer, and the
 * wake-word standby listener.
 *
 * This intentionally does NOT live inside the Activity/ViewModel. When the
 * user removes the app from Recents (or the screen turns off), Android
 * destroys the Activity and its ViewModel - if the live session lived there,
 * the conversation would die with it. Because everything here is owned by a
 * foreground service instead, the mic stays on and Jarvis keeps talking
 * normally even with the app closed. The Activity/ViewModel only *observes*
 * this service's state through the companion StateFlows below; they never
 * own the audio pipeline.
 */
class JarvisConversationService : Service() {

    companion object {
        const val ACTION_START_CONVERSATION = "com.jarvis.assistant.START_CONVERSATION"
        const val ACTION_STOP_ALL = "com.jarvis.assistant.STOP_ALL"
        const val ACTION_ENTER_STANDBY = "com.jarvis.assistant.ENTER_STANDBY"
        const val ACTION_MANUAL_OFF = "com.jarvis.assistant.MANUAL_OFF"
        const val ACTION_MANUAL_ON = "com.jarvis.assistant.MANUAL_ON"

        enum class WakeReason {
            VOICE_WAKE,
            CHAT_WAKE
        }

        private const val CHANNEL_ID = "jarvis_conversation"
        private const val NOTIFICATION_ID = 41
        const val INACTIVITY_TIMEOUT_MS = 120_000L // 120 seconds (2 minutes) before idle standby
        private const val STANDBY_COOLDOWN_MS = 2500L

        private val pendingLaunchTextMessage = java.util.concurrent.atomic.AtomicReference<String?>(null)

        @Volatile private var _lastHardwareVolumeKeyTime = 0L
        val lastHardwareVolumeKeyTime: Long get() = _lastHardwareVolumeKeyTime
        fun notifyHardwareVolumeKey() {
            _lastHardwareVolumeKeyTime = SystemClock.elapsedRealtime()
        }

        @Volatile private var activeInstance: JarvisConversationService? = null

        private val _isSessionOn = MutableStateFlow(false)
        val isSessionOn: StateFlow<Boolean> = _isSessionOn.asStateFlow()

        private val _isStandby = MutableStateFlow(false)
        val isStandby: StateFlow<Boolean> = _isStandby.asStateFlow()

        private val _isManualOff = MutableStateFlow(false)
        val isManualOff: StateFlow<Boolean> = _isManualOff.asStateFlow()

        private val _liveError = MutableStateFlow<JarvisLiveError?>(null)
        val liveError: StateFlow<JarvisLiveError?> = _liveError.asStateFlow()

        fun clearLiveError() {
            _liveError.value = null
        }

        fun setLiveError(error: JarvisLiveError) {
            _liveError.value = error
        }

        fun updateAudioPreferences(volume: Int, speed: Float) {
            activeInstance?.let { service ->
                service.audioPlayer?.setVolume(volume)
                service.audioPlayer?.setPlaybackSpeed(speed)
            }
        }

        private val _conversationState = MutableStateFlow(ConversationState.IDLE)
        val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()

        private val _connectionStatus = MutableStateFlow("READY")
        val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

        private val _audioLevel = MutableStateFlow(0f)
        val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

        private val _audioFrequency = MutableStateFlow(com.jarvis.assistant.audio.AudioFrequencyData())
        val audioFrequency: StateFlow<com.jarvis.assistant.audio.AudioFrequencyData> = _audioFrequency.asStateFlow()

        private val _liveUserTranscript = MutableStateFlow("")
        val liveUserTranscript: StateFlow<String> = _liveUserTranscript.asStateFlow()

        private val _liveAssistantResponse = MutableStateFlow("")
        val liveAssistantResponse: StateFlow<String> = _liveAssistantResponse.asStateFlow()

        private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val events: SharedFlow<String> = _events.asSharedFlow()

        /** True once a wake word has just brought the conversation back, for a one-off UI toast. */
        private val _wakeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val wakeEvents: SharedFlow<Unit> = _wakeEvents.asSharedFlow()

        fun setMicMuted(muted: Boolean) {
            activeInstance?.audioRecorder?.setMuted(muted)
        }

        fun interruptPlayback() {
            activeInstance?.let { service ->
                if (_conversationState.value == ConversationState.SPEAKING) {
                    Log.i("JarvisService", "Manual interruption: stopping audio playback")
                    service.audioPlayer?.flush()
                    service.suppressAudioUntilTurnComplete = true
                    _conversationState.value = ConversationState.LISTENING
                }
            }
        }

        fun restartSession() {
            activeInstance?.let { service ->
                if (_isSessionOn.value) {
                    service.stopActiveConversation()
                    service.beginActiveConversation()
                }
            }
        }

        val isScreenSharing: StateFlow<Boolean> = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing

        fun elevateToMediaProjection(): Boolean {
            return activeInstance?.elevateToMediaProjection() == true
        }

        fun demoteFromMediaProjection() {
            activeInstance?.demoteFromMediaProjection()
        }

        fun startScreenCapture(projection: android.media.projection.MediaProjection, metrics: android.util.DisplayMetrics): Boolean {
            val instance = activeInstance ?: run {
                projection.stop()
                return false
            }

            val captureManager = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()
            captureManager.onStoppedListener = {
                activeInstance?.demoteFromMediaProjection()
            }
            return captureManager.startCapture(projection, metrics) { jpegBytes ->
                val frameInstance = activeInstance ?: return@startCapture
                if (frameInstance.canSendVideoFrame()) {
                    frameInstance.liveWebSocket?.sendMediaChunk(jpegBytes)
                }
            }
        }

        private fun liveWebSocketReady(instance: JarvisConversationService): Boolean =
            _isSessionOn.value && instance.liveWebSocket?.isSessionReady() == true

        fun sendImmediateVideoFrame(jpegBytes: ByteArray) {
            val instance = activeInstance ?: return
            val ws = instance.liveWebSocket ?: return
            // Screen imagery is opt-in. Gemini Live accepts concurrent real-time modalities,
            // including while a response or tool action is in progress.
            if (!com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value ||
                !ws.isSessionReady() || jpegBytes.isEmpty()) return
            ws.sendMediaChunk(jpegBytes)
        }

        fun stopScreenCapture() {
            com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().stopCapture()
            activeInstance?.demoteFromMediaProjection()
        }

        /** True while a foreground instance of this service exists in any mode. */
        fun isServiceRunning(): Boolean = activeInstance != null

        fun notifyDraftTyping(text: CharSequence?) {
            activeInstance?.idleController?.notifyDraftTyping(text)
        }

        fun sendTextMessage(text: String) {
            submitUserPrompt(promptWithDocs = text, displayUserText = text)
        }

        fun submitUserPrompt(
            promptWithDocs: String,
            displayUserText: String,
            attachments: List<com.jarvis.assistant.attachment.AttachmentItem> = emptyList(),
            screenFrameBytes: ByteArray? = null
        ) {
            val clean = promptWithDocs.trim()
            if (clean.isBlank() && screenFrameBytes == null && attachments.isEmpty()) return

            val instance = activeInstance
            if (instance != null) {
                instance.onUserSubmitPrompt(clean, displayUserText, attachments, screenFrameBytes)
            } else {
                val context = JarvisApp.instance
                pendingLaunchTextMessage.set(clean)
                val intent = Intent(context, JarvisConversationService::class.java).apply {
                    action = ACTION_START_CONVERSATION
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun getWakeAliasesForName(name: String): List<String> {
            val clean = name.trim().lowercase(Locale.ROOT)
            if (clean.isBlank()) return listOf("jarvis", "जार्विस")
            return when (clean) {
                "jarvis" -> listOf("jarvis", "जार्विस")
                "ark" -> listOf("ark", "arc", "a r k", "आर्क")
                "maya" -> listOf("maya", "माया")
                else -> listOf(clean)
            }
        }

        fun getVoskAliasesForName(name: String): List<String> {
            val clean = name.trim().lowercase(Locale.ROOT)
            return when (clean) {
                "ark" -> listOf("ark", "arc", "a r k")
                "maya" -> listOf("maya")
                else -> listOf(clean.replace("[^a-z0-9\\s]".toRegex(), " ").trim())
            }.filter { it.isNotBlank() }.ifEmpty { listOf("jarvis") }
        }

        fun buildVoskGrammar(name: String): String {
            val aliases = getVoskAliasesForName(name)
            val prefixes = listOf("", "hey ", "hi ", "hello ", "ok ")
            val offActions = listOf(
                "off ho jao", "off ho ja", "sojao", "so jao", "so ja",
                "band ho jao", "band ho ja", "band ho",
                "turn off", "shut down", "go to sleep", "power off"
            )

            val phrases = mutableListOf<String>()

            // 1. Standby wake phrases: e.g. "jarvis", "hey jarvis"
            for (alias in aliases) {
                for (prefix in prefixes) {
                    val phrase = "${prefix}${alias}".trim()
                    if (phrase.isNotEmpty() && !phrases.contains(phrase)) {
                        phrases.add(phrase)
                    }
                }
            }

            // 2. Standby Hard Off phrases with name: e.g. "jarvis off ho jao", "hey jarvis shut down"
            for (alias in aliases) {
                for (prefix in prefixes) {
                    for (action in offActions) {
                        val phrase = "${prefix}${alias} ${action}".trim()
                        if (phrase.isNotEmpty() && !phrases.contains(phrase)) {
                            phrases.add(phrase)
                        }
                    }
                }
            }

            // 3. Standalone Hard Off phrases: e.g. "off ho jao", "turn off"
            for (action in offActions) {
                if (!phrases.contains(action)) {
                    phrases.add(action)
                }
            }

            phrases.add("[unk]")
            return phrases.joinToString(separator = ", ", prefix = "[", postfix = "]") { "\"$it\"" }
        }

        fun containsWakeWord(phrase: String, assistantName: String): Boolean {
            val words = phrase.lowercase(Locale.ROOT).trim()
            if (words.isBlank()) return false
            val aliases = getWakeAliasesForName(assistantName)
            val aliasRegexParts = aliases.map { alias ->
                val tokens = alias.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                tokens.joinToString("\\s+") { Regex.escape(it) }
            }
            val aliasPattern = aliasRegexParts.joinToString("|")
            val regex = Regex("(?:^|\\b)((?:hey|hi|hello|ok)\\s+)?($aliasPattern)(?:\\b|$)", RegexOption.IGNORE_CASE)
            return regex.containsMatchIn(words)
        }

        fun isManualOffCommand(text: String, assistantName: String): Boolean {
            val clean = text.lowercase(Locale.ROOT)
                .replace("[^\\p{L}\\p{M}\\p{N}\\s]".toRegex(), " ")
                .replace("\\s+".toRegex(), " ")
                .trim()
            if (clean.isBlank()) return false

            val aliases = getWakeAliasesForName(assistantName)
            val aliasRegexParts = aliases.map { alias ->
                val tokens = alias.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                tokens.joinToString("\\s+") { Regex.escape(it) }
            }
            val namePattern = aliasRegexParts.joinToString("|")

            val latinOffActions = "(?:off\\s*ho\\s*jao|off\\s*ho\\s*jaao|off\\s*hojao|off\\s*ho\\s*ja|off\\s*ho|sojao|so\\s*jao|so\\s*jaao|so\\s*ja|soja|band\\s*ho\\s*jao|band\\s*ho\\s*jaao|band\\s*hojao|band\\s*ho\\s*ja|band\\s*ho|shut\\s*down|turn\\s*off|go\\s*to\\s*sleep|power\\s*off)"
            val devanagariOffActions = "(?:ऑ[फफ़]\\s*हो\\s*जाओ|ऑ[फफ़]\\s*हो\\s*जा|ऑ[फफ़]\\s*हो|सो\\s*जाओ|सो\\s*जा|सोजाओ|सोजा|बंद\\s*हो\\s*जाओ|बंद\\s*हो\\s*जा|बंद\\s*हो|टर्न\\s*ऑ[फफ़]|शट\\s*डाउन)"
            val offActionPattern = "(?:$latinOffActions|$devanagariOffActions)"

            val politePrefix = "(?:hey|hi|hello|ok|please|kripya|can\\s+you(?:\\s+please)?)"
            val politeSuffix = "(?:ab|bhi|please|kripya|now|sir|na|ji)"

            // Pattern 1: Name First (e.g., "Jarvis, off ho jao", "Jarvis, sojao", "Hey Jarvis please turn off")
            val patternNameFirst = Regex(
                "^(?:$politePrefix\\s+)?(?:$namePattern)\\s+(?:ab\\s+|bhi\\s+|please\\s+|kripya\\s+|now\\s+)?$offActionPattern(?:\\s+$politeSuffix)?$",
                RegexOption.IGNORE_CASE
            )
            if (patternNameFirst.matches(clean)) {
                return true
            }

            // Pattern 2: Action First (e.g., "off ho jao Jarvis", "turn off please Jarvis")
            val patternActionFirst = Regex(
                "^(?:$politePrefix\\s+)?$offActionPattern\\s+(?:ab\\s+|please\\s+|kripya\\s+|now\\s+)?(?:$namePattern)(?:\\s+$politeSuffix)?$",
                RegexOption.IGNORE_CASE
            )
            if (patternActionFirst.matches(clean)) {
                return true
            }

            // Pattern 3: Standalone (Strong intent phrases without name)
            val standalonePattern = Regex(
                "^(?:$politePrefix\\s+)?$offActionPattern(?:\\s+$politeSuffix)?$",
                RegexOption.IGNORE_CASE
            )
            if (standalonePattern.matches(clean)) {
                return true
            }

            return false
        }

        fun cancelActiveTaskFromOverlay() {
            val service = activeInstance
            if (service != null) service.cancelAllActiveTools()
            else com.jarvis.assistant.automation.agent.AgentTaskEngine.requestCancellation()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var lifecycleToken: Long = 0L
    private var taskProgressOverlay: com.jarvis.assistant.ui.agent.TaskProgressOverlay? = null
    private var taskProgressOverlayJob: Job? = null
    private var overlayPermissionWarningTaskId: String? = null
    private val preferences by lazy { (applicationContext as JarvisApp).preferences }

    @Volatile
    private var lastFinalizedAssistantText = ""

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                notifyHardwareVolumeKey()
            }
        }
    }

    // --- Authoritative Idle / Lifecycle Controller ---
    val idleController: JarvisIdleController by lazy {
        JarvisIdleController(
            scope = serviceScope,
            inactivityTimeoutMs = INACTIVITY_TIMEOUT_MS,
            onEnterIdle = {
                serviceScope.launch(Dispatchers.Main) {
                    enterStandby(announce = false)
                }
            }
        ).apply {
            isSessionActive = { _isSessionOn.value }
            isStandbyActive = { _isStandby.value }
            isManualOff = { _isManualOff.value || preferences.isManualOff }
            getConversationState = { _conversationState.value }
            isAudioPlaying = { (audioPlayer?.isPlaying() == true) || (audioPlayer?.hasPendingAudio() == true) }
            isTaskExecuting = { isAutonomousTaskRunning || isExecutingTool || activeToolJobs.isNotEmpty() }
        }
    }

    private val preConnectTextQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /**
     * Authoritative single transition for both voice wake and chat wake.
     */
    fun wakeUp(reason: WakeReason, textPayload: String? = null) {
        if (reason == WakeReason.VOICE_WAKE && preferences.isManualOff) return

        idleController.cancelPendingIdleTransition()
        exitStandbyListening()

        _isStandby.value = false
        _isSessionOn.value = true

        when (reason) {
            WakeReason.VOICE_WAKE -> {
                requestAudioDuckFocus()
                vibrateBriefly()
                setConversationState(ConversationState.LISTENING)
                serviceScope.launch { _wakeEvents.emit(Unit) }
                beginActiveConversation(wakeTriggered = true)
            }
            WakeReason.CHAT_WAKE -> {
                setConversationState(ConversationState.THINKING)
                val textOnly = preferences.isManualOff
                beginActiveConversation(wakeTriggered = false, forTextOnly = textOnly)
                if (!textPayload.isNullOrBlank()) {
                    sendTextMessageInternal(textPayload)
                }
            }
        }
    }

    fun onUserSubmitTextMessage(clean: String) {
        onUserSubmitPrompt(promptWithDocs = clean, displayUserText = clean)
    }

    fun onUserSubmitPrompt(
        promptWithDocs: String,
        displayUserText: String,
        attachments: List<com.jarvis.assistant.attachment.AttachmentItem> = emptyList(),
        screenFrameBytes: ByteArray? = null
    ) {
        idleController.cancelPendingIdleTransition()
        idleController.notifyUserActivity(JarvisIdleController.ActivityType.CHAT_SUBMISSION)

        if (_isStandby.value || _isManualOff.value || preferences.isManualOff || !_isSessionOn.value) {
            wakeUp(WakeReason.CHAT_WAKE, textPayload = null)
        }
        sendUserPromptInternal(promptWithDocs, displayUserText, attachments, screenFrameBytes)
    }

    fun sendTextMessageInternal(clean: String) {
        sendUserPromptInternal(promptWithDocs = clean, displayUserText = clean)
    }

    fun sendUserPromptInternal(
        promptWithDocs: String,
        displayUserText: String,
        attachments: List<com.jarvis.assistant.attachment.AttachmentItem> = emptyList(),
        screenFrameBytes: ByteArray? = null
    ) {
        audioPlayer?.flush()
        suppressAudioUntilTurnComplete = false
        setConversationState(ConversationState.THINKING)
        val displayText = displayUserText.ifBlank { promptWithDocs }
        currentTurnUserText.clear()
        currentTurnUserText.append(displayText)
        _liveUserTranscript.value = displayText

        // If it's a pure text message with no media, check creator response and memory
        if (attachments.isEmpty() && screenFrameBytes == null) {
            val creatorResponse = PromptGenerator.resolveCreatorResponse(displayText, preferences.language)
            if (creatorResponse != null) {
                JarvisApp.instance.chatRepository.addTurn(displayText, creatorResponse)
                announce(creatorResponse)
                currentTurnId = null
                setConversationState(ConversationState.IDLE)
                idleController.notifyInteractionCompleted()
                return
            }

            // --- Deterministic Long-Term Memory Handling for Text Chat ---
            val memoryIntent = com.jarvis.assistant.data.memory.MemoryIntentParser.parse(displayText)
            when (memoryIntent) {
                is com.jarvis.assistant.data.memory.MemoryIntent.RecallBirthday -> {
                    serviceScope.launch {
                        val reply = JarvisApp.instance.memoryManager.getBirthdayResponse()
                        announce(reply)
                        currentTurnId = null
                        setConversationState(ConversationState.IDLE)
                        idleController.notifyInteractionCompleted()
                    }
                    return
                }
                is com.jarvis.assistant.data.memory.MemoryIntent.SaveBirthday -> {
                    serviceScope.launch {
                        val saveRes = JarvisApp.instance.memoryManager.saveBirthday(
                            birthday = memoryIntent.normalizedDate,
                            wishRequested = memoryIntent.wishRequested,
                            rawText = displayText
                        )
                        val reply = when (saveRes) {
                            is com.jarvis.assistant.data.memory.SaveMemoryResult.Success -> saveRes.confirmation
                            is com.jarvis.assistant.data.memory.SaveMemoryResult.Failure -> saveRes.errorMessage
                        }
                        announce(reply)
                        currentTurnId = null
                        setConversationState(ConversationState.IDLE)
                        idleController.notifyInteractionCompleted()
                    }
                    return
                }
                is com.jarvis.assistant.data.memory.MemoryIntent.AmbiguousDate -> {
                    val reply = memoryIntent.clarificationPrompt
                    JarvisApp.instance.chatRepository.addTurn(displayText, reply)
                    announce(reply)
                    currentTurnId = null
                    setConversationState(ConversationState.IDLE)
                    idleController.notifyInteractionCompleted()
                    return
                }
                is com.jarvis.assistant.data.memory.MemoryIntent.RecallFavoriteSong -> {
                    val reply = JarvisApp.instance.memoryManager.getFavoriteSongResponse()
                    announce(reply)
                    currentTurnId = null
                    setConversationState(ConversationState.IDLE)
                    idleController.notifyInteractionCompleted()
                    return
                }
                is com.jarvis.assistant.data.memory.MemoryIntent.RecallRoutine -> {
                    serviceScope.launch {
                        val reply = JarvisApp.instance.memoryManager.getRoutineResponse()
                        announce(reply)
                        currentTurnId = null
                        setConversationState(ConversationState.IDLE)
                        idleController.notifyInteractionCompleted()
                    }
                    return
                }
                is com.jarvis.assistant.data.memory.MemoryIntent.WhatDoYouRemember -> {
                    serviceScope.launch {
                        val reply = JarvisApp.instance.memoryManager.getWhatDoYouRemember()
                        announce(reply)
                        currentTurnId = null
                        setConversationState(ConversationState.IDLE)
                        idleController.notifyInteractionCompleted()
                    }
                    return
                }
                is com.jarvis.assistant.data.memory.MemoryIntent.Forget -> {
                    serviceScope.launch {
                        val reply = JarvisApp.instance.memoryManager.forgetFact(memoryIntent.query)
                        announce(reply)
                        currentTurnId = null
                        setConversationState(ConversationState.IDLE)
                        idleController.notifyInteractionCompleted()
                    }
                    return
                }
                is com.jarvis.assistant.data.memory.MemoryIntent.RememberFact -> {
                    serviceScope.launch {
                        val reply = JarvisApp.instance.memoryManager.rememberFact(memoryIntent.fact)
                        announce(reply)
                        currentTurnId = null
                        setConversationState(ConversationState.IDLE)
                        idleController.notifyInteractionCompleted()
                    }
                    return
                }
                com.jarvis.assistant.data.memory.MemoryIntent.None -> {
                    // Not a deterministic memory command; continue to model
                }
            }
        }

        val primaryAttachment = attachments.firstOrNull()
        val attachmentName = primaryAttachment?.name ?: (if (screenFrameBytes != null) "Screen Snapshot" else null)
        val attachmentSize = primaryAttachment?.formattedSize ?: (if (screenFrameBytes != null) "${screenFrameBytes.size / 1024} KB" else null)
        val attachmentType = primaryAttachment?.attachmentType?.name ?: (if (screenFrameBytes != null) "SCREEN" else null)
        val attachmentThumbnailBase64 = primaryAttachment?.thumbnailBase64

        val turnId = JarvisApp.instance.chatRepository.startTurn(
            userText = displayText,
            attachmentName = attachmentName,
            attachmentSize = attachmentSize,
            attachmentType = attachmentType,
            attachmentThumbnailBase64 = attachmentThumbnailBase64
        )
        currentTurnId = turnId

        val imageBytesToSend = screenFrameBytes ?: attachments.firstOrNull { it.imageBytes != null }?.imageBytes

        val ws = liveWebSocket
        if (ws != null && ws.isSessionReady()) {
            if (imageBytesToSend != null && imageBytesToSend.isNotEmpty()) {
                ws.sendMediaChunk(imageBytesToSend)
            }
            ws.sendClientContent(promptWithDocs)
        } else {
            // REST fallback for when Live WebSocket is not connected or in text-only mode
            serviceScope.launch(Dispatchers.IO) {
                val result = if (imageBytesToSend != null && imageBytesToSend.isNotEmpty()) {
                    // Multimodal Vision route (images, screen frames, scanned PDFs)
                    com.jarvis.assistant.network.GeminiRestClient.generateVision(
                        apiKey = preferences.apiKey,
                        model = preferences.visionModel,
                        prompt = promptWithDocs,
                        imageBytes = imageBytesToSend
                    )
                } else {
                    // Standard Text route (.txt, text PDF, .docx, code, pure text chat)
                    com.jarvis.assistant.network.GeminiRestClient.generateText(
                        apiKey = preferences.apiKey,
                        model = preferences.textModel,
                        prompt = promptWithDocs
                    )
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess { reply ->
                        JarvisApp.instance.chatRepository.updateAssistantResponse(turnId, reply, isComplete = true)
                        if (!preferences.isManualOff) {
                            announce(reply)
                        }
                        currentTurnId = null
                        setConversationState(ConversationState.IDLE)
                        idleController.notifyInteractionCompleted()
                    }.onFailure { err ->
                        val errMsg = err.message ?: "Failed to get response"
                        JarvisApp.instance.chatRepository.updateAssistantResponse(turnId, "Error: $errMsg", isComplete = true)
                        currentTurnId = null
                        setConversationState(ConversationState.IDLE)
                        idleController.notifyInteractionCompleted()
                    }
                }
            }
        }
    }


    // --- Active-conversation components ---
    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null
    private var liveWebSocket: GeminiLiveWebSocket? = null
    private var inactivityJob: Job? = null
    private val currentTurnAssistantText = StringBuilder()
    private val currentTurnUserText = StringBuilder()
    private var currentTurnId: String? = null
    private var lastUserSpeechDetectedTime = 0L
    private var lastPlaybackEndTime = 0L
    private var hasSpeechInCurrentUtterance = false
    private var hasSignalledUtteranceEnd = false
    private var consecutiveVoicedFrames = 0
    private var totalVoicedFramesInCurrentUtterance = 0
    private var ambientNoiseFloor = 0.03f
    private var thinkingTimeoutJob: Job? = null
    private var isPendingBackgroundTransition = false
    @Volatile
    private var suppressAudioUntilTurnComplete = false
    @Volatile
    private var isExecutingTool = false
    private val currentUtteranceAudioStream = java.io.ByteArrayOutputStream()
    @Volatile
    private var isCurrentTurnOwnerVoiceVerified = false
    @Volatile
    private var lastOwnerVerifiedTimestamp = 0L
    private val preSpeechRingBuffer = java.util.ArrayDeque<ByteArray>(12)
    private val preConnectAudioQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Volatile
    private var lastCompletedTaskSummary: String? = null
    @Volatile private var lastVerifiedCompletionAt = 0L
    @Volatile
    private var lastOpenedWebUrl: String? = null
    @Volatile
    private var lastOpenedWebTime: Long = 0L

    // Autonomous Multi-Step Chaining Engine
    @Volatile
    private var isAutonomousTaskRunning = false
    @Volatile
    private var autonomousTaskGoal = ""
    @Volatile
    private var bulkEndOfListVerified = false
    @Volatile
    private var bulkNoProgressScrollCount = 0
    private var lastBulkScreenSignature: com.jarvis.assistant.automation.agent.ScreenSignature? = null
    @Volatile
    private var autonomousStepCount = 0
    @Volatile
    private var autonomousContinuationAttempts = 0
    @Volatile
    private var autonomousWaitingForUser = false
    @Volatile
    private var autoResumeAwaitingResponse = false
    @Volatile
    private var isAutonomousContinuationTurn = false
    private val autonomousAudioLock = Any()
    private val autonomousAudioBuffer = mutableListOf<ByteArray>()
    private var autonomousAudioBufferBytes = 0
    private val autonomousSpokenFingerprints = mutableListOf<String>()
    private var autonomousSpokenUpdateCount = 0
    @Volatile
    private var lastResponseTimedOut = false
    @Volatile
    private var isGoogleFlowCreationTask = false
    @Volatile
    private var flowPromptVerified = false
    @Volatile
    private var flowGenerationVerified = false
    @Volatile
    private var lastVerifiedFlowPrompt = ""
    private var autonomousLoopJob: Job? = null
    private val MAX_AUTONOMOUS_STEPS = 25
    // Mic voice-activity tuning: sensitive to natural conversational speech while rejecting room noise.
    private val VAD_MIN_THRESHOLD = 0.035f
    private val VAD_NOISE_MULTIPLIER = 1.8f
    private val VAD_START_FRAMES = 4
    private val VAD_MIN_UTTERANCE_FRAMES = 8
    private val VAD_SILENCE_STREAM_END_MS = 1200L
    // Loop guard: once tripped, ALL automation tools are refused until the user speaks again.
    @Volatile
    private var autonomousHardStop = false
    @Volatile
    private var consecutiveToolFailures = 0
    private val MAX_CONSECUTIVE_FAILURES = 4
    private var lastToolSignature = ""
    private var sameToolRepeatCount = 0
    private val toolExecutionMutex = Mutex()
    private val activeToolJobs = CopyOnWriteArrayList<Job>()
    private var activeToolJob: Job? = null

    private fun isInstagramBulkTask(): Boolean {
        val goal = autonomousTaskGoal.lowercase(Locale.ROOT)
        return com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(goal).isBulkAction &&
            goal.contains("instagram") &&
            (goal.contains("like") || goal.contains("liked") || goal.contains("reel"))
    }

    private fun automationToolTimeoutMs(): Long {
        val goal = autonomousTaskGoal.ifBlank { currentTurnUserText.toString() }.lowercase(Locale.ROOT)
        val bulk = com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(goal).isBulkAction
        return when {
            bulk && goal.contains("instagram") -> 480_000L
            goal.contains("spotify") && listOf("play", "bajao", "chalao").any(goal::contains) -> 120_000L
            else -> 25_000L
        }
    }

    private fun autonomousTaskStepLimit(): Int = if (isInstagramBulkTask()) 400 else MAX_AUTONOMOUS_STEPS

    private fun expectedPackageForTask(goal: String): String? {
        val text = goal.lowercase(Locale.ROOT)
        return when {
            "instagram" in text -> "instagram"
            "spotify" in text -> "spotify"
            "youtube" in text -> "youtube"
            "amazon" in text -> "amazon"
            "gmail" in text || "email" in text -> "com.google.android.gm"
            "whatsapp" in text -> "whatsapp"
            "where is my train" in text || ("train" in text && ("station" in text || "route" in text || "secunderabad" in text || "kagaznagar" in text)) -> "whereismytrain"
            "google photos" in text || "photos app" in text -> "photos"
            "chrome" in text || "browser" in text || "github" in text || "repository" in text || "google search" in text -> "chrome"
            else -> null
        }
    }

    private fun isExplicitContinuationUtterance(text: String): Boolean {
        val clean = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (clean in setOf("yes", "yeah", "yep", "haan", "han", "ok", "okay", "theek hai", "continue", "resume", "go on")) {
            return true
        }
        val hasContinuation = listOf("continue", "resume", "go on", "keep going", "aage badho", "aage karo")
            .any { containsPhrase(clean, it) }
        val startsNewTask = listOf("open", "search", "send", "play", "unlike", "delete", "call", "message", "type")
            .any { containsPhrase(clean, it) }
        return hasContinuation && !startsNewTask
    }

    /** Do not block an authorized task merely because the model phrased an update as a question. */
    private fun assistantNeedsUserDecision(response: String): Boolean {
        val text = response.lowercase(Locale.ROOT)
        return listOf(
            "need your permission", "need permission", "please confirm", "please provide",
            "which account", "which one", "what should i", "enter the code",
            "enter otp", "verification code", "approve this"
        ).any(text::contains)
    }

    /** Keeps automatic task narration useful and prevents duplicate spoken blockers/statuses. */
    private fun rememberAutonomousSpokenUpdate(text: String) {
        val fingerprint = autonomousSpeechFingerprint(text)
        if (fingerprint.isBlank()) return
        synchronized(autonomousSpokenFingerprints) {
            if (autonomousSpokenFingerprints.none { areSimilarAutonomousUpdates(it, fingerprint) }) {
                autonomousSpokenFingerprints += fingerprint
                if (autonomousSpokenFingerprints.size > 8) autonomousSpokenFingerprints.removeAt(0)
                autonomousSpokenUpdateCount++
            }
        }
    }

    private fun shouldSpeakAutonomousUpdate(text: String, isFinalResult: Boolean): Boolean {
        val candidate = text.ifBlank { lastCompletedTaskSummary.orEmpty() }.trim()
        if (candidate.isBlank()) return false
        val fingerprint = autonomousSpeechFingerprint(candidate)
        if (fingerprint.isBlank()) return false
        val needsDecision = assistantNeedsUserDecision(candidate)
        synchronized(autonomousSpokenFingerprints) {
            val repeated = autonomousSpokenFingerprints.any { areSimilarAutonomousUpdates(it, fingerprint) }
            if (repeated && !isFinalResult) return false
            if (autonomousSpokenUpdateCount >= 3 && !isFinalResult && !needsDecision) return false
            if (!repeated) {
                autonomousSpokenFingerprints += fingerprint
                if (autonomousSpokenFingerprints.size > 8) autonomousSpokenFingerprints.removeAt(0)
                autonomousSpokenUpdateCount++
            }
        }
        return true
    }

    private fun autonomousSpeechFingerprint(text: String): String {
        val clean = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return ""
        if (clean.contains("screen") && (clean.contains("share") || clean.contains("sharing"))) return "screen share status"
        if (clean.contains("accessibility") || clean.contains("permission")) return "permission status"
        if (clean.contains("not visible") || clean.contains("can't see") || clean.contains("cannot see") || clean.contains("nothing is showing")) return "screen visibility status"
        val ignored = setOf("the", "a", "an", "i", "am", "is", "are", "it", "to", "and", "now", "just", "please", "your", "this", "that", "on", "in", "for", "with")
        return clean.split(' ').filter { it.length > 2 && it !in ignored }.distinct().sorted().joinToString(" ")
    }

    private fun areSimilarAutonomousUpdates(first: String, second: String): Boolean {
        if (first == second) return true
        val a = first.split(' ').filter(String::isNotBlank).toSet()
        val b = second.split(' ').filter(String::isNotBlank).toSet()
        if (a.isEmpty() || b.isEmpty()) return false
        val overlap = a.intersect(b).size.toFloat() / a.union(b).size.toFloat()
        return overlap >= 0.62f
    }

    private fun setConversationState(state: ConversationState) {
        if (_conversationState.value != state) {
            _conversationState.value = state
            updateNotification()
        }
    }

    /**
     * Immediately aborts all running and pending tool executions, resetting the automation loop.
     */
    fun cancelAllActiveTools() {
        Log.i("JarvisService", "cancelAllActiveTools: Aborting all active and pending tool calls (${activeToolJobs.size} jobs)")
        com.jarvis.assistant.automation.agent.AgentTaskEngine.requestCancellation()
        isAutonomousContinuationTurn = false
        synchronized(autonomousAudioLock) {
            autonomousAudioBuffer.clear()
            autonomousAudioBufferBytes = 0
        }
        isAutonomousTaskRunning = false
        autonomousStepCount = 0
        isGoogleFlowCreationTask = false
        flowPromptVerified = false
        flowGenerationVerified = false
        lastVerifiedFlowPrompt = ""
        autonomousLoopJob?.cancel()
        autonomousLoopJob = null
        for (job in activeToolJobs) {
            job.cancel()
        }
        activeToolJobs.clear()
        activeToolJob = null
        isExecutingTool = false
        com.jarvis.assistant.automation.agent.SerializedActionExecutor.cancelAllPendingActions()
    }

    // Pending tool call queue: tool calls are queued when owner voice verification hasn't completed yet
    private data class PendingToolCall(val callId: String, val name: String, val args: JSONObject)
    private val pendingToolCalls = java.util.concurrent.ConcurrentLinkedQueue<PendingToolCall>()

    /** Real-time video and audio are concurrent input modalities in Gemini Live. */
    fun canSendVideoFrame(): Boolean {
        val ws = liveWebSocket ?: return false
        return com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value && ws.isSessionReady()
    }

    // --- Standby / wake-word components (Pure Vosk) ---
    private var voskSpeechService: SpeechService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var standbyActivatedTime = 0L
    @Volatile
    private var isPendingWakeGreeting = false
    private var headsetAudioRouter: com.jarvis.assistant.audio.HeadsetAudioRouter? = null

    private fun currentAudioRoute(): AudioOutputRouteType {
        return if (headsetAudioRouter?.isHeadsetConnected() == true) {
            AudioOutputRouteType.ISOLATED_HEADSET
        } else {
            AudioOutputRouteType.EXTERNAL_SPEAKER
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        _isManualOff.value = preferences.isManualOff
        if (preferences.isManualOff) {
            _conversationState.value = ConversationState.OFF
        }
        createChannel()
        try {
            val volumeFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(volumeChangeReceiver, volumeFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(volumeChangeReceiver, volumeFilter)
            }
        } catch (e: Exception) {
            Log.w("JarvisService", "Could not register volumeChangeReceiver: ${e.message}")
        }
        headsetAudioRouter = com.jarvis.assistant.audio.HeadsetAudioRouter(applicationContext).apply { start() }
        taskProgressOverlay = com.jarvis.assistant.ui.agent.TaskProgressOverlay(this)
        taskProgressOverlayJob = serviceScope.launch(Dispatchers.Main.immediate) {
            combine(
                com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState,
                JarvisApp.isAppForeground,
                _isSessionOn,
                _isStandby
            ) { snapshot, appForeground, sessionOn, standbyOn ->
                snapshot to (JarvisApp.instance.preferences.taskOverlayMode == "always" || (!appForeground && (sessionOn || standbyOn)))
            }.collect { (snapshot, showIdleAvatar) ->
                val shown = taskProgressOverlay?.update(snapshot, showIdleAvatar) == true
                if (snapshot != null && !shown && overlayPermissionWarningTaskId != snapshot.taskId) {
                    overlayPermissionWarningTaskId = snapshot.taskId
                    _events.emit("Task progress is shown inside JARVIS Home. Allow Display over other apps if you want the floating task panel over Instagram, Spotify, and Chrome.")
                }
            }
        }
        serviceScope.launch(Dispatchers.Main.immediate) {
            var previousAvailability: List<Any>? = null
            while (isActive) {
                delay(900L)
                if (_isSessionOn.value &&
                    (!PermissionHelper.hasRecordAudioPermission(this@JarvisConversationService) ||
                        !PermissionHelper.hasNotificationPermission(this@JarvisConversationService))) {
                    Log.w("JarvisService", "A required runtime permission was revoked during the live session; stopping the session")
                    stopActiveConversation()
                    serviceScope.launch { _events.emit("Jarvis paused because a required permission was turned off. Re-enable it in System Permissions to continue.") }
                }
                if (com.jarvis.assistant.automation.agent.AgentTaskEngine.current() != null &&
                    !PermissionHelper.isAccessibilityServiceEnabled(this@JarvisConversationService)) {
                    Log.w("JarvisService", "Accessibility was disabled during an active task; clearing task progress")
                    isAutonomousTaskRunning = false
                    com.jarvis.assistant.automation.agent.AgentTaskEngine.resetForSession()
                }
                val snapshot = com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.value
                val prefs = (application as JarvisApp).preferences
                val appForeground = JarvisApp.isAppForeground.value
                val showIdleAvatar = prefs.taskOverlayMode == "always" || (!appForeground && (_isSessionOn.value || _isStandby.value))
                val availability = listOf(
                    prefs.isTaskOverlayEnabled,
                    PermissionHelper.hasOverlayPermission(this@JarvisConversationService),
                    prefs.taskOverlayAvatar,
                    prefs.taskOverlayMode,
                    prefs.taskOverlayEdge,
                    appForeground,
                    _isSessionOn.value,
                    _isStandby.value
                )
                if (availability != previousAvailability) {
                    previousAvailability = availability
                    taskProgressOverlay?.update(snapshot, showIdleAvatar)
                }
            }
        }
    }

    private fun ensureMicrophoneForegroundService() {
        val notification = buildNotification()
        var combinedTypes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value) {
            combinedTypes = combinedTypes or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, notification, combinedTypes)
                Log.d("JarvisService", "startForeground elevated to types=$combinedTypes")
            } catch (e: Exception) {
                Log.w("JarvisService", "startForeground with combined types failed: ${e.message}")
                try {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } catch (_: Exception) {
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                    } catch (_: Exception) {}
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun elevateToMediaProjection(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val notification = buildNotification()
            val types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            try {
                startForeground(NOTIFICATION_ID, notification, types)
                Log.i("JarvisService", "Foreground service elevated to include MEDIA_PROJECTION")
                return true
            } catch (e: Exception) {
                Log.w("JarvisService", "Failed to elevate to MEDIA_PROJECTION: ${e.message}")
                return false
            }
        }
        return true
    }

    fun demoteFromMediaProjection() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val notification = buildNotification()
            val types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            try {
                startForeground(NOTIFICATION_ID, notification, types)
                Log.i("JarvisService", "Foreground service demoted from MEDIA_PROJECTION")
            } catch (e: Exception) {
                Log.w("JarvisService", "Failed to demote from MEDIA_PROJECTION: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!com.jarvis.assistant.security.AppIdentityVerifier.isTrustedRelease(this)) {
            Log.e("JarvisService", "Blocked service start: release signing certificate did not match the configured pin")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_MANUAL_OFF, ACTION_STOP_ALL -> {
                // Persist the hard-off choice before any asynchronous cleanup can run.
                preferences.isManualOff = true
                _isManualOff.value = true
                triggerManualOff()
                return START_NOT_STICKY
            }
            ACTION_MANUAL_ON, ACTION_START_CONVERSATION -> {
                // An explicit user ON action is the only way to clear hard-off.
                preferences.isManualOff = false
                _isManualOff.value = false
                ensureMicrophoneForegroundService()
                acquireWakeLock()
                lifecycleToken++
                beginActiveConversation()
            }
            ACTION_ENTER_STANDBY -> {
                if (preferences.isManualOff) {
                    _isManualOff.value = true
                    _conversationState.value = ConversationState.OFF
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                ensureMicrophoneForegroundService()
                acquireWakeLock()
                enterStandby(announce = false)
            }
            else -> {
                // A sticky/null-intent restart must never override the user's hard-off choice.
                if (preferences.isManualOff) {
                    _isManualOff.value = true
                    _isSessionOn.value = false
                    _isStandby.value = false
                    _conversationState.value = ConversationState.OFF
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                ensureMicrophoneForegroundService()
                acquireWakeLock()
                if (!_isSessionOn.value && !_isStandby.value) enterStandby(announce = false)
            }
        }
        val pendingText = pendingLaunchTextMessage.getAndSet(null)
        if (!pendingText.isNullOrBlank()) {
            serviceScope.launch(Dispatchers.Main) {
                onUserSubmitTextMessage(pendingText)
            }
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------------
    // Active conversation
    // ---------------------------------------------------------------------

    private suspend fun generateCurrentSystemPrompt(): String {
        val app = application as? JarvisApp ?: return ""
        return withContext(Dispatchers.IO) {
            val memoryContext = app.memoryManager.getSystemPromptContext()
            val favSong = app.memoryManager.resolveSongQuery("my favorite song")
            com.jarvis.assistant.util.PromptGenerator.generateSystemPrompt(
                personality = preferences.personality,
                userName = preferences.userName,
                assistantName = preferences.assistantName,
                memoryContext = memoryContext,
                favoriteSong = favSong,
                userBirthday = preferences.userBirthday,
                userLocation = preferences.userLocation,
                isTorchOn = app.phoneControlManager.isTorchOn,
                language = preferences.language,
                isSilentExecutionMode = preferences.isSilentExecutionMode,
                responseStyle = preferences.responseStyle
            )
        }
    }

    private fun beginActiveConversation(wakeTriggered: Boolean = false, forTextOnly: Boolean = false) {
        if (!forTextOnly && preferences.isManualOff) return
        if (_isSessionOn.value && audioRecorder != null && !forTextOnly) return
        com.jarvis.assistant.automation.agent.AgentTaskEngine.resetForSession()
        isAutonomousContinuationTurn = false
        synchronized(autonomousAudioLock) {
            autonomousAudioBuffer.clear()
            autonomousAudioBufferBytes = 0
        }
        synchronized(autonomousSpokenFingerprints) {
            autonomousSpokenFingerprints.clear()
            autonomousSpokenUpdateCount = 0
        }
        exitStandbyListening()
        isPendingWakeGreeting = false

        val app = application as JarvisApp
        val preferences = app.preferences
        val apiKey = preferences.apiKey.trim()
        if (apiKey.isBlank()) {
            _liveError.value = JarvisLiveError(
                title = "API KEY MISSING",
                message = "No Gemini API Key configured. Please enter your API Key in Settings to activate Jarvis.",
                errorCode = "API_KEY_MISSING",
                actionText = "OPEN SETTINGS"
            )
            serviceScope.launch { _events.emit("Please configure your Gemini API Key in Settings first.") }
            fullStop()
            return
        }

        if (!forTextOnly && !PermissionHelper.hasAllRequiredPermissions(this)) {
            _liveError.value = JarvisLiveError(
                title = "PERMISSIONS REQUIRED",
                message = "Jarvis requires Microphone and Notification permissions to run. Please grant them in Authorizations.",
                errorCode = "PERMISSIONS_MISSING",
                actionText = "GRANT PERMISSIONS"
            )
            serviceScope.launch { _events.emit("Please allow all required permissions in Settings to use Jarvis.") }
            fullStop()
            return
        }

        _liveError.value = null

        _isSessionOn.value = true
        _isStandby.value = false
        lastResponseTimedOut = false
        val now = System.currentTimeMillis()
        lastUserSpeechDetectedTime = SystemClock.elapsedRealtime()
        lastOwnerVerifiedTimestamp = now
        isCurrentTurnOwnerVoiceVerified = !preferences.isOwnerVoiceEnabled || !preferences.hasOwnerVoiceProfile()
        pendingToolCalls.clear()
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false
        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        idleController.startTimer()
        setConversationState(if (wakeTriggered) ConversationState.LISTENING else if (forTextOnly) ConversationState.THINKING else ConversationState.IDLE)
        updateNotification()
        headsetAudioRouter?.start()

        audioPlayer = AudioPlayer(
            onPlaybackStateChanged = { isPlaying ->
                if (isPlaying) {
                    setConversationState(ConversationState.SPEAKING)
                } else {
                    lastPlaybackEndTime = System.currentTimeMillis()
                    // Race condition guard: if user barged in and state is LISTENING, do not wipe turn!
                    if (_conversationState.value != ConversationState.LISTENING) {
                        finalizeTurn()
                        if (_isSessionOn.value) {
                            setConversationState(ConversationState.IDLE)
                        }
                        idleController.notifyInteractionCompleted()
                    }
                }
            },
            onPlaybackAmplitude = { amp ->
                if (_conversationState.value == ConversationState.SPEAKING) {
                    _audioLevel.value = amp
                    _audioFrequency.value = com.jarvis.assistant.audio.AudioFrequencyData(
                        amplitude = amp,
                        pitchHz = 180f,
                        bass = (amp * 0.7f).coerceIn(0f, 1f),
                        mid = (amp * 0.9f).coerceIn(0f, 1f),
                        high = (amp * 0.4f).coerceIn(0f, 1f)
                    )
                }
            }
        ).apply {
            setVolume(preferences.voiceVolume)
            setPlaybackSpeed(preferences.voiceSpeed)
            start()
        }

        serviceScope.launch(Dispatchers.IO) {
            val systemPrompt = generateCurrentSystemPrompt()

            withContext(Dispatchers.Main) {
                if (!_isSessionOn.value) return@withContext
                liveWebSocket = GeminiLiveWebSocket(
                    apiKey = apiKey,
                    model = com.jarvis.assistant.network.GeminiModelManager.resolveLiveModel(preferences.aiModel),
                    voiceName = preferences.voice,
                    systemPrompt = systemPrompt,
                    listener = object : GeminiLiveWebSocket.Listener {
                        override fun onConnectionStateChanged(status: String) {
                            _connectionStatus.value = status
                            updateNotification()
                            if (status == "LIVE") {
                                _liveError.value = null
                                // Flush any speech chunks captured during the connection handshake
                                serviceScope.launch(Dispatchers.Default) {
                                    while (!preConnectAudioQueue.isEmpty()) {
                                        val chunk = preConnectAudioQueue.poll() ?: break
                                        liveWebSocket?.sendAudioChunk(chunk)
                                    }
                                }
                                // Flush any text messages submitted during the connection handshake
                                serviceScope.launch(Dispatchers.Main) {
                                    while (!preConnectTextQueue.isEmpty()) {
                                        val textMsg = preConnectTextQueue.poll() ?: break
                                        sendTextMessageInternal(textMsg)
                                    }
                                }
                                // Reset autonomous task state on fresh connection
                                isAutonomousTaskRunning = false
                                isAutonomousContinuationTurn = false
                                synchronized(autonomousAudioLock) {
                                    autonomousAudioBuffer.clear()
                                    autonomousAudioBufferBytes = 0
                                }
                                autonomousStepCount = 0
                                autonomousLoopJob?.cancel()
                                autonomousLoopJob = null
                                if (_conversationState.value != ConversationState.SPEAKING && _conversationState.value != ConversationState.THINKING) {
                                    setConversationState(if (wakeTriggered) ConversationState.LISTENING else ConversationState.IDLE)
                                }
                                // Wake detection only opens the session. Do not inject a fake
                                // user utterance; preserve the user's actual first utterance only.
                            }
                        }

                        private var audioChunksReceivedInTurn = 0

                        override fun onAudioDataReceived(pcmData: ByteArray) {
                            thinkingTimeoutJob?.cancel()
                            thinkingTimeoutJob = null
                            lastResponseTimedOut = false
                            if (suppressAudioUntilTurnComplete) {
                                Log.w("JarvisService", "onAudioDataReceived: Audio suppressed (suppressAudioUntilTurnComplete=true)")
                                return
                            }
                            // Silent execution applies to task narration, while leaving ordinary
                            // conversation audible. Read the preference dynamically so toggling
                            // it during a session takes effect immediately.
                            if (preferences.isSilentExecutionMode &&
                                (isAutonomousTaskRunning || hasExecutedCommandInCurrentTurn || !lastCompletedTaskSummary.isNullOrBlank())
                            ) {
                                audioPlayer?.flush()
                                return
                            }
                            if (isAutonomousContinuationTurn) {
                                synchronized(autonomousAudioLock) {
                                    if (autonomousAudioBufferBytes + pcmData.size <= 2_000_000) {
                                        autonomousAudioBuffer += pcmData.copyOf()
                                        autonomousAudioBufferBytes += pcmData.size
                                    }
                                }
                                return
                            }
                            audioChunksReceivedInTurn++
                            if (audioChunksReceivedInTurn % 25 == 1) {
                                Log.d("JarvisService", "Streaming audio chunk #$audioChunksReceivedInTurn (${pcmData.size} bytes) to AudioPlayer")
                            }
                            setConversationState(ConversationState.SPEAKING)
                            audioPlayer?.enqueueAudio(pcmData)
                        }

                        override fun onAssistantTextReceived(textChunk: String) {
                            if (textChunk.isEmpty()) return
                            thinkingTimeoutJob?.cancel()
                            thinkingTimeoutJob = null
                            lastResponseTimedOut = false
                            val cleanChunk = if (textChunk.contains("Rehaan", ignoreCase = true) || textChunk.contains("Rehan", ignoreCase = true)) {
                                textChunk.replace(Regex("Reha?an\\s+Sir", RegexOption.IGNORE_CASE), "Pradip Sir")
                            } else {
                                textChunk
                            }
                            synchronized(currentTurnAssistantText) {
                                val chunkToAppend = if (currentTurnAssistantText.isNotEmpty() && currentTurnAssistantText.last() == ' ') {
                                    cleanChunk.trimStart()
                                } else {
                                    cleanChunk
                                }
                                if (chunkToAppend.isNotEmpty()) {
                                    if (currentTurnAssistantText.isNotEmpty()) {
                                        val lastChar = currentTurnAssistantText.last()
                                        val firstChar = chunkToAppend.first()
                                        if (!Character.isWhitespace(lastChar) && !Character.isWhitespace(firstChar)) {
                                            if (Character.isLetterOrDigit(firstChar) || firstChar == '(' || firstChar == '[') {
                                                currentTurnAssistantText.append(' ')
                                            }
                                        }
                                    }
                                    currentTurnAssistantText.append(chunkToAppend)
                                }
                            }
                            _liveAssistantResponse.value = currentTurnAssistantText.toString().trim()
                            val activeTurn = currentTurnId
                            if (activeTurn != null) {
                                (application as? JarvisApp)?.chatRepository?.updateAssistantResponse(
                                    activeTurn,
                                    _liveAssistantResponse.value,
                                    isComplete = false
                                )
                            }
                            val assistantText = currentTurnAssistantText.toString().lowercase(Locale.ROOT)
                            Log.d("JarvisService", "Assistant text so far: '$assistantText'")
                        }

                        override fun onUserTextReceived(textChunk: String) {
                            lastUserSpeechDetectedTime = SystemClock.elapsedRealtime()
                            val trimmed = textChunk.trim()
                            if (trimmed.isNotEmpty()) {
                                val isSpeakingOrRecent = (_conversationState.value == ConversationState.SPEAKING) ||
                                        (System.currentTimeMillis() - lastPlaybackEndTime < 1500L) ||
                                        (audioPlayer?.isPlaying() == true)
                                if (isSpeakingOrRecent && lastFinalizedAssistantText.isNotBlank()) {
                                    val candidate = trimmed.lowercase(Locale.ROOT)
                                    val assistantLower = lastFinalizedAssistantText.lowercase(Locale.ROOT)
                                    if (assistantLower.contains(candidate) || candidate.contains(assistantLower)) {
                                        Log.w("JarvisService", "Echo transcript guard: Suppressed echo of assistant speech: '$trimmed'")
                                        return
                                    }
                                }
                                val startsNewUserTurn = currentTurnUserText.isEmpty()
                                val answeringTaskBlocker = startsNewUserTurn && isAutonomousTaskRunning && autonomousWaitingForUser
                                if (answeringTaskBlocker) {
                                    autonomousWaitingForUser = false
                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.resumeAfterUserInput()
                                }
                                if (startsNewUserTurn && isAutonomousTaskRunning && !isExplicitContinuationUtterance(trimmed) && !answeringTaskBlocker) {
                                    Log.i("JarvisService", "New user request superseded the previous automation task; cancelling stale actions.")
                                    cancelAllActiveTools()
                                    autonomousTaskGoal = ""
                                    autonomousContinuationAttempts = 0
                                    bulkEndOfListVerified = false
                                    bulkNoProgressScrollCount = 0
                                    lastBulkScreenSignature = null
                                    autoResumeAwaitingResponse = false
                                }
                                if (startsNewUserTurn && autonomousHardStop) {
                                    val resumeExistingTask = isExplicitContinuationUtterance(trimmed) && autonomousTaskGoal.isNotBlank() &&
                                        com.jarvis.assistant.automation.agent.AgentTaskEngine.current() != null
                                    Log.i("JarvisService", if (resumeExistingTask) "User requested continuation; preserving the original task and resuming from fresh UI." else "New user request received: clearing automation hard stop.")
                                    autonomousHardStop = false
                                    autonomousContinuationAttempts = 0
                                    consecutiveToolFailures = 0
                                    sameToolRepeatCount = 0
                                    lastToolSignature = ""
                                    if (resumeExistingTask) {
                                        isAutonomousTaskRunning = true
                                        autonomousStepCount = 0
                                        com.jarvis.assistant.automation.agent.AgentTaskEngine.resumeAfterUserInput()
                                    } else {
                                        isAutonomousTaskRunning = false
                                        autonomousStepCount = 0
                                        autonomousTaskGoal = ""
                                    }
                                }
                                if (currentTurnUserText.isNotEmpty() && !currentTurnUserText.endsWith(" ")) {
                                    currentTurnUserText.append(" ")
                                }
                                currentTurnUserText.append(trimmed)
                                _liveUserTranscript.value = currentTurnUserText.toString().trim()

                                val app = application as? JarvisApp
                                if (currentTurnId == null) {
                                    currentTurnId = app?.chatRepository?.startTurn(_liveUserTranscript.value)
                                } else {
                                    app?.chatRepository?.updateUserTranscript(currentTurnId!!, _liveUserTranscript.value)
                                }

                                if (_conversationState.value == ConversationState.IDLE) {
                                    setConversationState(ConversationState.LISTENING)
                                }
                            }
                            lastResponseTimedOut = false
                            updateNotification()
                            val userText = currentTurnUserText.toString().lowercase(Locale.ROOT)
                            if (isManualOffCommand(userText, preferences.assistantName)) {
                                Log.i("JarvisService", "Detected explicit manual off command in user speech: '$userText'")
                                triggerManualOff()
                                return
                            }
                            if (isStopCommand(userText)) {
                                Log.i("JarvisService", "Detected explicit stop/cancel in user speech: '$userText'. Aborting all tasks.")
                                cancelAllActiveTools()
                            }
                            if (isExplicitStandbyCommand(userText)) {
                                Log.i("JarvisService", "Detected explicit background / standby command in user speech: '$userText'")
                                minimizeToHomeScreen()
                                enterStandby(announce = true)
                                return
                            }
                            app.memoryManager.learnPreferencesFromConversation(currentTurnUserText.toString())
                            // Do not run checkAndExecuteCommand on streaming chunks mid-speech!
                            // Gemini Live onToolCall is the authoritative neural intent engine.
                        }

                        override fun onInterrupted() {
                            val hasHeadset = headsetAudioRouter?.isHeadsetConnected() == true
                            Log.w("JarvisService", "onInterrupted received from server (hasHeadset=$hasHeadset, state=${_conversationState.value})")
                            val userTurnInProgress = hasSpeechInCurrentUtterance || currentTurnUserText.isNotBlank()
                            val isOwnAutoResumeInterrupt = autoResumeAwaitingResponse &&
                                !userTurnInProgress
                            if (isOwnAutoResumeInterrupt) {
                                Log.i("JarvisService", "Preserving task state for the assistant's automatic continuation")
                                autoResumeAwaitingResponse = false
                            } else {
                                isAutonomousContinuationTurn = false
                                synchronized(autonomousAudioLock) {
                                    autonomousAudioBuffer.clear()
                                    autonomousAudioBufferBytes = 0
                                }
                                cancelAllActiveTools()
                                if (userTurnInProgress && !isExplicitContinuationUtterance(currentTurnUserText.toString())) {
                                    autonomousTaskGoal = ""
                                    autonomousContinuationAttempts = 0
                                    autoResumeAwaitingResponse = false
                                }
                            }
                            thinkingTimeoutJob?.cancel()
                            thinkingTimeoutJob = null
                            suppressAudioUntilTurnComplete = false
                            if (hasHeadset || _conversationState.value != ConversationState.SPEAKING) {
                                audioPlayer?.flush()
                                currentTurnAssistantText.clear()
                                _liveAssistantResponse.value = ""
                            }
                            if (!userTurnInProgress) {
                                currentTurnUserText.clear()
                                _liveUserTranscript.value = ""
                            }
                            hasSpeechInCurrentUtterance = false
                            hasSignalledUtteranceEnd = false
                            consecutiveVoicedFrames = 0
                            totalVoicedFramesInCurrentUtterance = 0
                            synchronized(currentUtteranceAudioStream) { currentUtteranceAudioStream.reset() }
                            isCurrentTurnOwnerVoiceVerified = !preferences.isOwnerVoiceEnabled || !preferences.hasOwnerVoiceProfile()
                            if (_conversationState.value != ConversationState.SPEAKING) {
                                setConversationState(if (userTurnInProgress) ConversationState.LISTENING else ConversationState.IDLE)
                            }
                            updateNotification()
                        }

                        override fun onTurnCompleted() {
                            autoResumeAwaitingResponse = false
                            thinkingTimeoutJob?.cancel()
                            thinkingTimeoutJob = null
                            val assistantTurnText = synchronized(currentTurnAssistantText) { currentTurnAssistantText.toString().trim() }
                            if (isAutonomousContinuationTurn) {
                                isAutonomousContinuationTurn = false
                                val bufferedAudio = synchronized(autonomousAudioLock) {
                                    autonomousAudioBuffer.toList().also {
                                        autonomousAudioBuffer.clear()
                                        autonomousAudioBufferBytes = 0
                                    }
                                }
                                val finalResult = !isAutonomousTaskRunning || !lastCompletedTaskSummary.isNullOrBlank()
                                val speakUpdate = shouldSpeakAutonomousUpdate(assistantTurnText, finalResult)
                                if (speakUpdate && bufferedAudio.isNotEmpty()) {
                                    bufferedAudio.forEach { audioPlayer?.enqueueAudio(it) }
                                    Log.i("JarvisService", "Playing a new autonomous milestone; buffered repeated narration is discarded")
                                } else {
                                    Log.i("JarvisService", "Suppressing repeated or routine speech from automatic task continuation")
                                }
                            } else if (isAutonomousTaskRunning && assistantTurnText.isNotBlank()) {
                                rememberAutonomousSpokenUpdate(assistantTurnText)
                            }
                            audioPlayer?.notifyTurnComplete()
                            audioChunksReceivedInTurn = 0
                            suppressAudioUntilTurnComplete = false
                            hasSpeechInCurrentUtterance = false
                            hasSignalledUtteranceEnd = false
                            consecutiveVoicedFrames = 0
                            totalVoicedFramesInCurrentUtterance = 0
                            if (_conversationState.value == ConversationState.THINKING && (audioPlayer == null || !audioPlayer!!.isPlaying())) {
                                setConversationState(ConversationState.IDLE)
                            }
                            if (audioPlayer == null || (!audioPlayer!!.isPlaying() && !audioPlayer!!.hasPendingAudio())) {
                                idleController.notifyInteractionCompleted()
                            }
                            lastResponseTimedOut = false
                            updateNotification()

                            // Owner voice gating: if owner-only mode is active and speaker was not verified, ignore turn completely
                            val hasActualSpeechInThisTurn = hasSpeechInCurrentUtterance || currentTurnUserText.isNotEmpty()
                            val isLeaseActive = (System.currentTimeMillis() - lastOwnerVerifiedTimestamp) < 120_000L

                            if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile() && !isCurrentTurnOwnerVoiceVerified && !isLeaseActive && hasActualSpeechInThisTurn) {
                                val nonOwnerUtterance = currentTurnUserText.toString().trim()
                                Log.w("JarvisService", "Turn completed by NON-OWNER: Suppressing response and logging ignored.")
                                val app = application as? JarvisApp
                                val loggedQuery = if (nonOwnerUtterance.isNotEmpty()) nonOwnerUtterance else "(Unrecognized voice)"
                                app?.chatRepository?.addTurn(
                                    userText = "[Non-Owner Voice]: $loggedQuery",
                                    jarvisText = "[IGNORED] Voice not recognized as owner"
                                )
                                audioPlayer?.flush()
                                currentTurnAssistantText.clear()
                                currentTurnUserText.clear()
                                _liveAssistantResponse.value = ""
                                _liveUserTranscript.value = ""
                                processPendingToolCalls(verified = false)
                                return
                            }

                            val userUtterance = currentTurnUserText.toString().trim()
                            if (userUtterance.isNotEmpty()) {
                                app.memoryManager.learnPreferencesFromConversation(userUtterance)
                            }
                            val assistantText = currentTurnAssistantText.toString().lowercase(Locale.ROOT)
                            val isConfused = assistantText.contains("not understand") ||
                                    assistantText.contains("didn't understand") ||
                                    assistantText.contains("don't understand") ||
                                    assistantText.contains("couldn't understand") ||
                                    assistantText.contains("sorry, i can't") ||
                                    assistantText.contains("i'm not sure")

                            // Only run fallback if Gemini Live did NOT call a tool AND did not express confusion
                            if (!hasExecutedCommandInCurrentTurn && !isConfused) {
                                checkAndExecuteCommand(userUtterance)
                                checkAssistantTextForCommands(currentTurnAssistantText.toString())
                            }

                            // Reset verification flag for the next conversational turn
                            if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile()) {
                                // Keep verification active if within the owner session lease window.
                                // This prevents the owner from being re-challenged every single turn.
                                val leaseStillActive = (System.currentTimeMillis() - lastOwnerVerifiedTimestamp) < 120_000L
                                if (!leaseStillActive) {
                                    isCurrentTurnOwnerVoiceVerified = false
                                }
                            }
                            pendingToolCalls.clear()

                            if (!lastCompletedTaskSummary.isNullOrBlank()) {
                                val summaryToSpeak = lastCompletedTaskSummary
                                lastCompletedTaskSummary = null
                                val isLive = liveWebSocket?.isSessionReady() == true
                                if (!preferences.isSilentExecutionMode && !isLive) {
                                    Log.i("JarvisService", "Offline task completion summary available in app: '$summaryToSpeak'")
                                    serviceScope.launch { _events.emit("Task completed: $summaryToSpeak") }
                                }
                            }

                            val engineStatus = com.jarvis.assistant.automation.agent.AgentTaskEngine.current()?.status
                            if (isAutonomousTaskRunning && engineStatus == com.jarvis.assistant.automation.agent.AgentTaskEngine.State.COMPLETED) {
                                Log.i("JarvisService", "Task engine already verified completion; skipping another automatic continuation")
                                isAutonomousTaskRunning = false
                                autonomousStepCount = 0
                                autonomousLoopJob?.cancel()
                                autonomousLoopJob = null
                            }

                            // Do NOT reset autonomous task state on turn completion!
                            // Autonomous tasks span across multiple turns (e.g. download -> open -> onboarding -> phone entry -> OTP -> setup complete).
                            // The autonomous task ONLY completes when:
                            // 1. Gemini explicitly calls `task_completed(summary=...)`
                            // 2. The user explicitly gives a cancel/stop command ("stop", "ruko", "cancel")
                            // 3. Safety cap of MAX_AUTONOMOUS_STEPS (25) is reached.
                            if (isAutonomousTaskRunning && autonomousStepCount >= autonomousTaskStepLimit()) {
                                Log.w("JarvisService", "Autonomous task reached its step safety cap (${autonomousTaskStepLimit()}). Terminating loop safety.")
                                autonomousHardStop = true
                                isAutonomousTaskRunning = false
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.recordCompletionEvidence(
                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.CompletionEvidence.SAFETY_LIMIT,
                                    "Reached the current action batch limit; the original task is preserved and can resume from a fresh screen."
                                )
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.failCurrentStep(
                                    "Reached the current action batch limit. Say continue to resume this same task from a fresh screen.",
                                    blocked = true
                                )
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.setState(
                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.State.BLOCKED
                                )
                                autonomousStepCount = 0
                                autonomousLoopJob?.cancel()
                                autonomousLoopJob = null
                            } else if (isAutonomousTaskRunning) {
                                val response = currentTurnAssistantText.toString().trim().ifBlank { "The Instagram bulk task is still in progress." }
                                val needsUserDecision = assistantNeedsUserDecision(response)
                                val continuationLimit = 25
                                if (!autonomousWaitingForUser && !needsUserDecision && autonomousContinuationAttempts < continuationLimit) {
                                    autonomousContinuationAttempts++
                                    val attempt = autonomousContinuationAttempts
                                    autonomousLoopJob?.cancel()
                                    val speechAtTurnEnd = lastUserSpeechDetectedTime
                                    autonomousLoopJob = serviceScope.launch {
                                        // The model can finish its turn before the device finishes playing the queued audio.
                                        // Sending a continuation during playback makes Gemini interrupt its own speech.
                                        var quietSince = 0L
                                        val waitStartedAt = System.currentTimeMillis()
                                        while (isActive && System.currentTimeMillis() - waitStartedAt < 90_000L) {
                                            if (!isAutonomousTaskRunning || lastUserSpeechDetectedTime > speechAtTurnEnd ||
                                                liveWebSocket?.isSessionReady() != true) {
                                                Log.i("JarvisService", "Auto-resume cancelled because the task/session changed or the user spoke")
                                                return@launch
                                            }
                                            val playbackActive = audioPlayer?.isPlaying() == true ||
                                                _conversationState.value == ConversationState.SPEAKING
                                            if (playbackActive) {
                                                quietSince = 0L
                                            } else if (quietSince == 0L) {
                                                quietSince = System.currentTimeMillis()
                                            } else if (System.currentTimeMillis() - quietSince >= 350L) {
                                                break
                                            }
                                            kotlinx.coroutines.delay(100L)
                                        }
                                        if (!isActive || System.currentTimeMillis() - waitStartedAt >= 90_000L) {
                                            Log.w("JarvisService", "Auto-resume timed out waiting for speech playback to finish")
                                            return@launch
                                        }
                                        if (com.jarvis.assistant.automation.agent.AgentTaskEngine.current()?.status in setOf(
                                                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.COMPLETED,
                                                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.FAILED,
                                                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.CANCELLED,
                                                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.BLOCKED
                                            )) {
                                            isAutonomousTaskRunning = false
                                            return@launch
                                        }
                                        if (isAutonomousTaskRunning && !isExecutingTool && activeToolJobs.isEmpty() &&
                                            pendingToolCalls.isEmpty() && lastUserSpeechDetectedTime <= speechAtTurnEnd &&
                                            liveWebSocket?.isSessionReady() == true) {
                                            var uiWait: com.jarvis.assistant.vision.UiWaitResult? = null
                                            val uiWaitDeadline = System.currentTimeMillis() + 45_000L
                                            while (isActive && isAutonomousTaskRunning && System.currentTimeMillis() < uiWaitDeadline &&
                                                lastUserSpeechDetectedTime <= speechAtTurnEnd && uiWait?.ready != true) {
                                                if (!com.jarvis.assistant.automation.agent.AgentTaskEngine.awaitIfRunnable()) return@launch
                                                val expectedPackage = expectedPackageForTask(autonomousTaskGoal)
                                                uiWait = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                                    .waitForUiState(expectedPackage = expectedPackage, timeoutMs = 8_000L)
                                                if (!uiWait.ready) {
                                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.updateActivity(
                                                        "Waiting for a stable screen before continuing: ${uiWait.reason}",
                                                        com.jarvis.assistant.automation.agent.AgentTaskEngine.State.WAITING_FOR_UI
                                                    )
                                                }
                                            }
                                            if (!isActive || !isAutonomousTaskRunning || lastUserSpeechDetectedTime > speechAtTurnEnd) return@launch
                                            val observedScreen = uiWait?.let { wait ->
                                                com.jarvis.assistant.vision.UiGroundingEngine().formatElementsSummary(wait.elements, wait.root)
                                            }.orEmpty()
                                            if (uiWait?.ready != true) {
                                                com.jarvis.assistant.automation.agent.AgentTaskEngine.failCurrentStep(
                                                    "The UI did not become stable within 45 seconds; task needs a fresh strategy."
                                                )
                                            }
                                            Log.i("JarvisService", "Auto-resuming task after speech playback ended (attempt $attempt)")
                                            autoResumeAwaitingResponse = true
                                            isAutonomousContinuationTurn = true
                                            synchronized(autonomousAudioLock) {
                                                autonomousAudioBuffer.clear()
                                                autonomousAudioBufferBytes = 0
                                            }
                                            liveWebSocket?.sendClientContent(
                                                "Continue the existing task from the fresh phone state; this is an automatic continuation, not a new user command. " +
                                                    "Do not quote, paraphrase, repeat, or summarize previous assistant messages; use task state and this fresh screen only. " +
                                                    "UI readiness: ${uiWait?.reason ?: "not observed"}. " +
                                                    "Latest accessibility observation: ${observedScreen.take(5000)}. " +
                                                    "Use the current observation to select the next safe plan step or recover/replan if the screen changed. " +
                                                    "Do not repeat verified steps, do not ask the user to say continue, and never claim completion without local verification. " +
                                                "Keep the phone quiet while continuing: do not narrate each click, inspection, scroll, or repeated blocker. The task start was already acknowledged. Speak only for a new material milestone, a genuine user decision that is required, or one final verified result. Never repeat the same status wording. " +
                                                    "If a genuine clarification or permission is required, explain the blocker and wait for the user's answer."
                                            )
                                        }
                                    }
                                } else if (autonomousWaitingForUser || needsUserDecision) {
                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.failCurrentStep(
                                        "The assistant requested user clarification or confirmation.", blocked = true
                                    )
                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.setState(
                                        com.jarvis.assistant.automation.agent.AgentTaskEngine.State.BLOCKED
                                    )
                                } else {
                                    Log.w("JarvisService", "Auto-resume limit reached; stopping task to avoid an unattended loop")
                                    isAutonomousTaskRunning = false
                                    com.jarvis.assistant.automation.agent.AgentTaskEngine.failCurrentStep(
                                        "Automatic continuation limit reached without verified completion.", blocked = true
                                    )
                                    serviceScope.launch { _events.emit("The task is blocked because it did not reach a verified result within the automatic recovery limit.") }
                                }
                            }
                        }

                        override fun onError(message: String, isFatal: Boolean, errorCode: String?) {
                            Log.e("JarvisService", "GeminiLive onError: fatal=$isFatal, code=$errorCode, msg=$message")
                            updateNotification()
                            serviceScope.launch { _events.emit(message) }
                            when (errorCode) {
                                "API_KEY_INVALID" -> {
                                    _liveError.value = JarvisLiveError(
                                        title = "API KEY INVALID",
                                        message = "Google rejected your Gemini API key (400/403). Please verify and enter a valid API key in Settings.",
                                        errorCode = "API_KEY_INVALID",
                                        actionText = "OPEN SETTINGS"
                                    )
                                }
                                "API_KEY_MISSING" -> {
                                    _liveError.value = JarvisLiveError(
                                        title = "API KEY MISSING",
                                        message = "No Gemini API key found. Enter your BYOK key in Settings to activate Jarvis.",
                                        errorCode = "API_KEY_MISSING",
                                        actionText = "OPEN SETTINGS"
                                    )
                                }
                                "QUOTA_EXCEEDED" -> {
                                    _liveError.value = JarvisLiveError(
                                        title = "API QUOTA EXHAUSTED",
                                        message = "Gemini API quota or rate limit exceeded. Please check your Google AI Studio plan.",
                                        errorCode = "QUOTA_EXCEEDED",
                                        actionText = "CHECK PLAN"
                                    )
                                }
                                else -> {
                                    if (isFatal) {
                                        _liveError.value = JarvisLiveError(
                                            title = "CONNECTION FAILED",
                                            message = message,
                                            errorCode = errorCode ?: "FATAL_ERROR",
                                            actionText = "RETRY"
                                        )
                                    } else if (_liveError.value == null) {
                                        _liveError.value = JarvisLiveError(
                                            title = "CONNECTION DROP",
                                            message = message,
                                            errorCode = "CONNECTION_ERROR",
                                            actionText = "RECONNECT"
                                        )
                                    }
                                }
                            }
                        }

                        override fun onToolCall(callId: String, name: String, args: JSONObject) {
                            val isLeaseActive = (System.currentTimeMillis() - lastOwnerVerifiedTimestamp) < 120_000L
                            val hasNoIncomingSpeech = !hasSpeechInCurrentUtterance && currentTurnUserText.isEmpty()
                            val isOwnerVerifiedOrAutonomous = isCurrentTurnOwnerVoiceVerified || isLeaseActive || hasNoIncomingSpeech || isAutonomousTaskRunning

                            if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile() && !isOwnerVerifiedOrAutonomous) {
                                Log.w("JarvisService", "QUEUED tool call '$name' (callId=$callId): Owner verification pending, will execute after silence detection confirms speaker.")
                                pendingToolCalls.add(PendingToolCall(callId, name, args))
                                return
                            }
                            handleToolCallExecution(callId, name, args)
                        }
                    }
                ).apply {
                    // System prompt is already baked into the constructor; no need to regenerate on reconnect.
                    // promptProvider removed to eliminate double-generation latency.
                    connect(serviceScope)
                }
            }
        }

        if (!forTextOnly && !preferences.isManualOff) {
            audioRecorder = AudioRecorder { chunk, amplitude, frequencyData ->
            if (_isSessionOn.value) {
                val now = System.currentTimeMillis()
                val route = currentAudioRoute()
                val cooldownMs = EchoGuardConfig.getEchoCooldownMs(route)

                // Acoustic Echo Decay Protection (AEDP):
                // For cooldownMs after speaker audio stops, discard mic chunks so room reverberation
                // does not bounce into Gemini Live and cause false self-interruption.
                if (now - lastPlaybackEndTime < cooldownMs) {
                    consecutiveVoicedFrames = 0
                    return@AudioRecorder
                }

                // Assistant is speaking / AudioTrack or TTS is actively playing:
                val isSpeakerActive = (audioPlayer?.hasPendingAudio() == true) ||
                        (audioPlayer?.isPlaying() == true) ||
                        (_conversationState.value == ConversationState.SPEAKING)

                val timeSinceVolumeKey = SystemClock.elapsedRealtime() - _lastHardwareVolumeKeyTime
                if (timeSinceVolumeKey < 800L) {
                    consecutiveVoicedFrames = 0
                    if (isSpeakerActive) {
                        return@AudioRecorder
                    }
                }

                if (isSpeakerActive) {
                    val threshold = EchoGuardConfig.getBargeInThreshold(route)
                    val requiredFrames = EchoGuardConfig.getPersistenceFrames(route)

                    if (amplitude >= threshold) {
                        consecutiveVoicedFrames++
                        if (consecutiveVoicedFrames >= requiredFrames) {
                            Log.i("JarvisService", "Voice barge-in confirmed (route=$route, frames=$consecutiveVoicedFrames, amp=$amplitude)")
                            audioPlayer?.flush()
                            suppressAudioUntilTurnComplete = true
                            setConversationState(ConversationState.LISTENING)
                            lastUserSpeechDetectedTime = SystemClock.elapsedRealtime()
                            idleController.notifyUserActivity(JarvisIdleController.ActivityType.VOICE_SPEECH_START)
                            hasSpeechInCurrentUtterance = true
                            hasSignalledUtteranceEnd = false
                            currentTurnUserText.clear()
                            currentTurnAssistantText.clear()
                            _liveUserTranscript.value = ""
                            _liveAssistantResponse.value = ""
                            hasExecutedCommandInCurrentTurn = false
                            totalVoicedFramesInCurrentUtterance = requiredFrames
                            synchronized(currentUtteranceAudioStream) {
                                currentUtteranceAudioStream.reset()
                                currentUtteranceAudioStream.write(chunk)
                            }
                            isCurrentTurnOwnerVoiceVerified = !preferences.isOwnerVoiceEnabled || !preferences.hasOwnerVoiceProfile()
                            liveWebSocket?.sendAudioChunk(chunk)
                            return@AudioRecorder
                        }
                    } else {
                        consecutiveVoicedFrames = 0
                    }
                    // Suppress mic audio from streaming to Gemini while assistant is physically speaking
                    return@AudioRecorder
                }

                // Stream user speech to Gemini Live
                if (liveWebSocket?.isSessionReady() == true) {
                    while (!preConnectAudioQueue.isEmpty()) {
                        val queued = preConnectAudioQueue.poll() ?: break
                        liveWebSocket?.sendAudioChunk(queued)
                    }
                    liveWebSocket?.sendAudioChunk(chunk)
                } else {
                    // Connection still warming up: buffer up to 60 chunks (~1.2s of audio) so early speech is never lost
                    if (preConnectAudioQueue.size < 60) {
                        preConnectAudioQueue.offer(chunk.copyOf())
                    }
                }

                // Track ambient noise floor adaptively when quiet
                if (amplitude < 0.10f) {
                    ambientNoiseFloor = (ambientNoiseFloor * 0.97f + amplitude * 0.03f).coerceIn(0.015f, 0.10f)
                }

                // Maintain pre-speech buffer of recent chunks (for smooth onset capture)
                synchronized(preSpeechRingBuffer) {
                    if (preSpeechRingBuffer.size >= 10) {
                        preSpeechRingBuffer.pollFirst()
                    }
                    preSpeechRingBuffer.addLast(chunk.copyOf())
                }

                // Dynamic voice threshold: responsive to normal conversational volume (0.035f)
                val voiceThreshold = maxOf(VAD_MIN_THRESHOLD, ambientNoiseFloor * VAD_NOISE_MULTIPLIER)

                if (amplitude > voiceThreshold) {
                    consecutiveVoicedFrames++
                    lastUserSpeechDetectedTime = SystemClock.elapsedRealtime()
                    _audioLevel.value = amplitude
                    _audioFrequency.value = frequencyData

                    // Declare speech start only after several consecutive voiced frames (not a short noise blip)
                    if (consecutiveVoicedFrames >= VAD_START_FRAMES) {
                        if (!hasSpeechInCurrentUtterance) {
                            idleController.notifyUserActivity(JarvisIdleController.ActivityType.VOICE_SPEECH_START)
                            currentTurnUserText.clear()
                            currentTurnAssistantText.clear()
                            _liveUserTranscript.value = ""
                            _liveAssistantResponse.value = ""
                            hasExecutedCommandInCurrentTurn = false
                            totalVoicedFramesInCurrentUtterance = 0
                            synchronized(currentUtteranceAudioStream) {
                                currentUtteranceAudioStream.reset()
                                synchronized(preSpeechRingBuffer) {
                                    for (preChunk in preSpeechRingBuffer) {
                                        currentUtteranceAudioStream.write(preChunk)
                                    }
                                }
                            }
                            isCurrentTurnOwnerVoiceVerified = !preferences.isOwnerVoiceEnabled || !preferences.hasOwnerVoiceProfile()
                        } else {
                            idleController.notifyUserActivity(JarvisIdleController.ActivityType.VOICE_SPEECH_CONTINUE)
                        }
                        hasSpeechInCurrentUtterance = true
                        hasSignalledUtteranceEnd = false
                        totalVoicedFramesInCurrentUtterance++
                        // EARLY VOICE VERIFICATION: Verify owner as soon as we have enough voiced audio (300ms)
                        // This ensures verification completes BEFORE Gemini starts sending response audio
                        if (!isCurrentTurnOwnerVoiceVerified && preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile() && totalVoicedFramesInCurrentUtterance == 15) {
                            val audioSnapshot = synchronized(currentUtteranceAudioStream) { currentUtteranceAudioStream.toByteArray() }
                            if (audioSnapshot.size >= 16000 * 2 * 0.25) {
                                serviceScope.launch(Dispatchers.Default) {
                                    val match = com.jarvis.assistant.audio.OwnerVoiceManager.verifySpeakerAudio(audioSnapshot, preferences)
                                    if (match.isMatch || match.distance < match.threshold * 1.35f) {
                                        lastOwnerVerifiedTimestamp = System.currentTimeMillis()
                                        isCurrentTurnOwnerVoiceVerified = true
                                        Log.i("JarvisService", "EARLY voice verification PASSED: Owner confirmed during speech (dist=${match.distance}, sim=${match.similarityPercent}%)")
                                        processPendingToolCalls(verified = true)
                                    } else if (match.hasExtractedVector) {
                                        Log.w("JarvisService", "EARLY voice verification FAILED: Non-owner detected (dist=${match.distance}, thr=${match.threshold})")
                                        // Don't reject yet - let silence handler do final check with more audio
                                    }
                                }
                            }
                        }
                        synchronized(currentUtteranceAudioStream) {
                            if (currentUtteranceAudioStream.size() < 480000) {
                                currentUtteranceAudioStream.write(chunk)
                            }
                        }
                        if (_conversationState.value != ConversationState.LISTENING) {
                            setConversationState(ConversationState.LISTENING)
                        }
                    }
                } else {
                    // Gradual decrement instead of instant reset: one quiet 20ms frame won't kill voiced speech tracking
                    if (consecutiveVoicedFrames > 0) consecutiveVoicedFrames--
                    val silentDuration = SystemClock.elapsedRealtime() - lastUserSpeechDetectedTime

                    if (hasSpeechInCurrentUtterance && silentDuration < VAD_SILENCE_STREAM_END_MS) {
                        // Keep buffering inter-word pauses so Vosk receives natural continuous audio
                        synchronized(currentUtteranceAudioStream) {
                            if (currentUtteranceAudioStream.size() < 480000) {
                                currentUtteranceAudioStream.write(chunk)
                            }
                        }
                    }

                    if (_conversationState.value == ConversationState.LISTENING) {
                        _audioLevel.value = amplitude
                        _audioFrequency.value = frequencyData

                        // Conversational pause reached (1200ms — allows natural speech with inter-word pauses without cutting off)
                        if (silentDuration >= VAD_SILENCE_STREAM_END_MS) {
                            if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile()) {
                                val isLeaseActive = (now - lastOwnerVerifiedTimestamp) < 120_000L
                                if (isLeaseActive) {
                                    // Ultra-low latency: Active owner lease immediately confirms turn with 0ms delay
                                    lastOwnerVerifiedTimestamp = now
                                    isCurrentTurnOwnerVoiceVerified = true
                                    processPendingToolCalls(verified = true)
                                } else if (!isCurrentTurnOwnerVoiceVerified) {
                                    val audioBytes = synchronized(currentUtteranceAudioStream) {
                                        currentUtteranceAudioStream.toByteArray()
                                    }
                                    if (audioBytes.size >= 16000 * 2 * 0.3) {
                                        val match = com.jarvis.assistant.audio.OwnerVoiceManager.verifySpeakerAudio(audioBytes, preferences)
                                        if (match.isMatch || match.distance < match.threshold * 1.35f) {
                                            lastOwnerVerifiedTimestamp = now
                                            isCurrentTurnOwnerVoiceVerified = true
                                            Log.i("JarvisService", "Speech verification passed: Owner confirmed (dist: ${match.distance}, sim: ${match.similarityPercent}%)")
                                            processPendingToolCalls(verified = true)
                                        } else {
                                            isCurrentTurnOwnerVoiceVerified = false
                                            audioPlayer?.flush()
                                            currentTurnAssistantText.clear()
                                            _liveAssistantResponse.value = ""
                                            Log.w("JarvisService", "Speech verification rejected: Non-owner (dist: ${match.distance}, thr: ${match.threshold})")
                                            processPendingToolCalls(verified = false)
                                        }
                                    } else {
                                        isCurrentTurnOwnerVoiceVerified = !preferences.isOwnerVoiceEnabled || !preferences.hasOwnerVoiceProfile()
                                        if (!isCurrentTurnOwnerVoiceVerified) {
                                            processPendingToolCalls(verified = false)
                                        }
                                    }
                                }
                            }

                            if (totalVoicedFramesInCurrentUtterance >= VAD_MIN_UTTERANCE_FRAMES || currentTurnUserText.trim().isNotEmpty()) {
                                if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile() && !isCurrentTurnOwnerVoiceVerified) {
                                    val nonOwnerQuery = currentTurnUserText.toString().trim()
                                    Log.w("JarvisService", "Non-owner speech ended: '$nonOwnerQuery'; IGNORING utterance, suppressing reply, and recording to history.")
                                    val app = application as? JarvisApp
                                    val loggedQuery = if (nonOwnerQuery.isNotEmpty()) nonOwnerQuery else "(Unrecognized voice)"
                                    app?.chatRepository?.addTurn(
                                        userText = "[Non-Owner Voice]: $loggedQuery",
                                        jarvisText = "[IGNORED] Voice not recognized as owner"
                                    )
                                    audioPlayer?.flush()
                                    currentTurnAssistantText.clear()
                                    currentTurnUserText.clear()
                                    _liveUserTranscript.value = ""
                                    _liveAssistantResponse.value = ""
                                    hasSpeechInCurrentUtterance = false
                                    hasSignalledUtteranceEnd = false
                                    consecutiveVoicedFrames = 0
                                    totalVoicedFramesInCurrentUtterance = 0
                                    synchronized(currentUtteranceAudioStream) { currentUtteranceAudioStream.reset() }
                                    setConversationState(ConversationState.IDLE)
                                    processPendingToolCalls(verified = false)
                                } else {
                                    if (!hasSignalledUtteranceEnd) {
                                        hasSignalledUtteranceEnd = true
                                        // If screen capture is active, send one fresh snapshot right with the question
                                        if (com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value) {
                                            val snapshot = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().captureSnapshotJpeg(60)
                                            if (snapshot != null && snapshot.isNotEmpty()) {
                                                liveWebSocket?.sendMediaChunk(snapshot)
                                            }
                                        }
                                    }
                                    setConversationState(ConversationState.THINKING)
                                    thinkingTimeoutJob?.cancel()
                                    thinkingTimeoutJob = serviceScope.launch {
                                        delay(15000)
                                        if (_conversationState.value == ConversationState.THINKING && (audioPlayer == null || !audioPlayer!!.isPlaying())) {
                                            Log.i("JarvisService", "Thinking timeout safety; returning to IDLE")
                                            setConversationState(ConversationState.IDLE)
                                            lastResponseTimedOut = true
                                            updateNotification()
                                            serviceScope.launch { _events.emit("Jarvis did not receive a reply. Check the connection and repeat your request.") }
                                        }
                                    }
                                }
                            } else {
                                // Short acoustic pop / ambient noise - return cleanly to IDLE
                                hasSpeechInCurrentUtterance = false
                                hasSignalledUtteranceEnd = false
                                setConversationState(ConversationState.IDLE)
                            }
                        }
                    } else if (_conversationState.value == ConversationState.IDLE) {
                        _audioLevel.value = 0f
                    }
                }
            }
        }.apply {
            setMuted(preferences.isMicMuted)
            start(serviceScope)
        }
        }
    }

    private var lastHandledCommandText = ""
    private var lastHandledAssistantCommand = ""
    @Volatile
    private var hasExecutedCommandInCurrentTurn = false
    @Volatile
    private var lastCommandExecutionTime = 0L

    // Holds a command that Jarvis heard but isn't confident enough about to run blindly
    // (e.g. a YouTube title that sounded too short/garbled). Cleared once the user
    // confirms or cancels it in a follow-up turn.
    @Volatile
    private var pendingGenericCommand: com.jarvis.assistant.automation.command.JarvisCommand? = null

    /**
     * Executes a tool call from Gemini. Called both from the direct onToolCall path
     * (when owner is already verified) and from processPendingToolCalls (after verification completes).
     */
    private fun handleToolCallExecution(callId: String, name: String, args: JSONObject) {
        val job = serviceScope.launch(Dispatchers.IO) {
            val thisJob = coroutineContext[Job]
            if (thisJob != null) activeToolJobs.add(thisJob)
            try {
                toolExecutionMutex.withLock {
                    if (!isActive) return@withLock
                    isExecutingTool = true
                    setConversationState(ConversationState.EXECUTING)
                    try {
                        withTimeout(automationToolTimeoutMs()) {
                            val app = application as? JarvisApp ?: return@withTimeout

                hasExecutedCommandInCurrentTurn = true
                lastCommandExecutionTime = System.currentTimeMillis()
                suppressAudioUntilTurnComplete = false

                val isAutomationTool = name in setOf(
                    "open_app", "open_website", "search_in_chrome",
                    "click_ui_element", "type_text", "scroll_screen", "press_key"
                )
                if (isAutomationTool && autonomousHardStop) {
                    Log.w("JarvisService", "Automation tool '$name' refused: hard stop is active.")
                    liveWebSocket?.sendToolResponse(callId, name, JSONObject().apply {
                        put("success", false)
                        put("autonomous_status", "STOPPED")
                        put("message", "STOPPED: automation was halted (repeated failures or too many steps). Do NOT call any more tools. Tell the user honestly, in one or two short sentences, what you actually completed and where you got stuck, then wait for their next instruction.")
                    })
                    return@withTimeout
                }
                if (isAutomationTool) {
                    if (preferences.isSilentExecutionMode) audioPlayer?.flush()
                    autonomousContinuationAttempts = 0
                        autonomousWaitingForUser = false
                    if (!isAutonomousTaskRunning) {
                        synchronized(autonomousSpokenFingerprints) {
                            autonomousSpokenFingerprints.clear()
                            autonomousSpokenUpdateCount = 0
                        }
                        isAutonomousTaskRunning = true
                        autonomousStepCount = 1
                        isGoogleFlowCreationTask = false
                        flowPromptVerified = false
                        flowGenerationVerified = false
                        lastVerifiedFlowPrompt = ""
                        val userText = currentTurnUserText.toString().trim()
                        autonomousTaskGoal = if (userText.isNotEmpty()) userText else "Complete $name automation task"
                        com.jarvis.assistant.automation.agent.AgentTaskEngine.begin(autonomousTaskGoal, "gemini-live-tools")
                        bulkEndOfListVerified = false
                        bulkNoProgressScrollCount = 0
                        lastBulkScreenSignature = null
                        val activeRoot = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance?.getActiveRootNode()
                        val activeElements = com.jarvis.assistant.vision.UiGroundingEngine().scanScreen(activeRoot)
                        isGoogleFlowCreationTask = com.jarvis.assistant.vision.UiGroundingEngine().isGoogleFlowSurface(activeElements) &&
                            isFlowCreationIntent(autonomousTaskGoal)
                        Log.i("JarvisService", "Started autonomous task loop with goal: '$autonomousTaskGoal'")
                    } else {
                        autonomousStepCount++
                        Log.i("JarvisService", "Autonomous task advancing to step $autonomousStepCount")
                    }
                }

                if (name in setOf("click_ui_element", "type_text", "scroll_screen", "press_key")) {
                    val expectedPackage = expectedPackageForTask(autonomousTaskGoal)
                    val activePackage = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                        ?.getActiveRootNode()?.packageName?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    if (expectedPackage != null && !activePackage.contains(expectedPackage)) {
                        Log.w("JarvisService", "Blocked stale cross-app action '$name': task expects '$expectedPackage', foreground is '$activePackage'.")
                        val screenSummary = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance().inspectScreen(waitForContent = true)
                        val mismatchResult = JSONObject().apply {
                            put("success", false)
                            put("verified", false)
                            put("message", "Action blocked because the foreground app does not match the user's current task. Expected an app matching '$expectedPackage', but the current app is '$activePackage'. Reopen the intended app and inspect it before continuing.")
                            put("current_screen_elements", screenSummary)
                        }
                        liveWebSocket?.sendToolResponse(callId, name, mismatchResult)
                        return@withTimeout
                    }
                }

                Log.i("JarvisService", "Handling native toolCall: $name with args: $args")
                val result = JSONObject()

                when (name) {
                    "open_app" -> {
                        val appName = args.optString("app_name", "")
                        val appNumber = if (args.has("app_number")) args.optInt("app_number", 1) else null

                        // 1. FAST PATH: Web platform tools (Google Flow, Runway, etc.) -> open directly in Chrome without scanning apps!
                        val directWebUrl = app.phoneControlManager.parseTargetUrl(appName)
                        if (directWebUrl != null) {
                            if (directWebUrl.contains("flow.google", ignoreCase = true) && isFlowCreationIntent(autonomousTaskGoal)) {
                                isGoogleFlowCreationTask = true
                            }
                            Log.i("JarvisService", "open_app: '$appName' is web platform, target URL: $directWebUrl")
                            val a11y = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                            val currentPkg = a11y?.getActiveRootNode()?.packageName?.toString() ?: ""
                            val isAlreadyInBrowser = currentPkg.contains("chrome", ignoreCase = true) || currentPkg.contains("browser", ignoreCase = true)
                            val now = System.currentTimeMillis()

                            // ANTI-RELOAD GUARD: If Chrome is ALREADY in foreground and website was opened within 20s, do not reload!
                            if (isAlreadyInBrowser && lastOpenedWebUrl == directWebUrl && (now - lastOpenedWebTime < 20_000L)) {
                                Log.i("JarvisService", "open_app: already on $directWebUrl in Chrome. Suppressing redundant re-open.")
                                val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                val (_, summary) = waitForWebDestination(orchestrator, directWebUrl)
                                result.put("success", true)
                                result.put("verified", true)
                                result.put("message", "Already on $directWebUrl in Chrome. The page is currently open on screen. DO NOT call open_app or open_website again! Locate UI elements on screen (e.g. '+ New project' or prompt box) and proceed.")
                                if (summary.isNotBlank()) result.put("current_screen_elements", summary)
                            } else {
                                lastOpenedWebUrl = directWebUrl
                                lastOpenedWebTime = now
                                val webLaunched = executeSerializedTaskMutation("open-url:$directWebUrl") {
                                    app.phoneControlManager.openUrlsInChromeTabs(listOf(directWebUrl))
                                }
                                result.put("success", webLaunched)
                                result.put("message", if (webLaunched) "Opened $directWebUrl in Chrome." else "Failed to open $directWebUrl.")
                                val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                val (destinationReady, summary) = waitForWebDestination(orchestrator, directWebUrl)
                                if (summary.isNotBlank()) {
                                    result.put("current_screen_elements", summary)
                                } else {
                                    result.put("current_screen_elements", "Navigated to $directWebUrl. Use the latest available screen context; inspect_screen returns accessibility text unless Screen Share has a confirmed frame.")
                                }
                                if (!destinationReady) {
                                    result.put("message", "Navigation was requested, but Chrome has not yet confirmed the destination page. Wait and inspect the current screen before tapping or typing.")
                                }
                                result.put("verified", destinationReady)
                            }
                        } else {
                            // 2. Native app launch via AppLauncher
                            val launched = executeSerializedTaskMutation("open-app:$appName") {
                                com.jarvis.assistant.util.AppLauncher.launchApp(this@JarvisConversationService, appName, appNumber)
                            }
                            if (launched) {
                                Log.i("JarvisService", "open_app: launched native app '$appName'")
                                result.put("success", true)
                                result.put("message", "Opened $appName app.")
                                val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                val startupWait = orchestrator.waitForUiState(
                                    expectedPackage = expectedPackageForTask(autonomousTaskGoal),
                                    timeoutMs = 12_000L
                                )
                                val summary = com.jarvis.assistant.vision.UiGroundingEngine().formatElementsSummary(startupWait.elements, startupWait.root)
                                if (summary.isNotBlank()) {
                                    result.put("current_screen_elements", summary)
                                } else {
                                    result.put("current_screen_elements", "Opened $appName. Use the latest available screen context; inspect_screen returns accessibility text unless Screen Share has a confirmed frame.")
                                }
                                val foregroundPackage = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                                    ?.getActiveRootNode()?.packageName?.toString().orEmpty()
                                val expectedPackage = expectedPackageForTask(autonomousTaskGoal)
                                val appVerified = startupWait.ready &&
                                    (expectedPackage?.let { foregroundPackage.contains(it, ignoreCase = true) } ?: foregroundPackage.isNotBlank())
                                result.put("verified", appVerified)
                                val spotifyPlaybackRequest = expectedPackageForTask(autonomousTaskGoal) == "spotify" &&
                                    listOf("play", "bajao", "chalao", "gaana chala", "gana chala")
                                        .any { autonomousTaskGoal.contains(it, ignoreCase = true) }
                                if (launched && spotifyPlaybackRequest && appName.contains("spotify", ignoreCase = true)) {
                                    val playback = orchestrator.runSpotifySmartSearch(autonomousTaskGoal)
                                    result.put("success", playback.success)
                                    result.put("verified", playback.verified ?: false)
                                    result.put("message", playback.message)
                                    if (playback.currentStateSummary.isNotBlank()) {
                                        result.put("current_screen_elements", playback.currentStateSummary)
                                    }
                                }
                            } else {
                                result.put("success", false)
                                result.put("message", "Could not find or open $appName.")
                            }
                        }
                    }

                    "open_website" -> {
                        val targetUrls = mutableListOf<String>()
                        val jsonUrls = args.optJSONArray("urls")
                        if (jsonUrls != null) {
                            for (i in 0 until jsonUrls.length()) {
                                val u = jsonUrls.getString(i).trim()
                                if (u.isNotEmpty()) targetUrls.add(u)
                            }
                        }
                        val query = args.optString("query", "").trim()
                        if (query.isNotEmpty()) {
                            val extracted = app.phoneControlManager.extractUrlsFromText(query)
                            for (u in extracted) {
                                if (!targetUrls.contains(u)) targetUrls.add(u)
                            }
                            if (targetUrls.isEmpty()) {
                                val single = app.phoneControlManager.parseTargetUrl(query)
                                if (single != null) targetUrls.add(single)
                            }
                        }
                        if (targetUrls.isEmpty() && query.isNotBlank()) {
                            targetUrls.add("https://www.google.com/search?q=${Uri.encode(query)}")
                        }
                        val primaryUrl = targetUrls.firstOrNull() ?: ""
                        if (targetUrls.any { it.contains("flow.google", ignoreCase = true) } && isFlowCreationIntent(autonomousTaskGoal)) {
                            isGoogleFlowCreationTask = true
                        }
                        val a11y = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                        val currentPkg = a11y?.getActiveRootNode()?.packageName?.toString() ?: ""
                        val isAlreadyInBrowser = currentPkg.contains("chrome", ignoreCase = true) || currentPkg.contains("browser", ignoreCase = true)
                        val now = System.currentTimeMillis()

                        // ANTI-RELOAD LOOP GUARD: If Chrome is ALREADY in foreground and website was launched within 20 seconds,
                        // do NOT re-fire Intent to reload Chrome! Give Chrome time to render and inspect the screen!
                        if (isAlreadyInBrowser && primaryUrl.isNotBlank() && lastOpenedWebUrl == primaryUrl && (now - lastOpenedWebTime < 20_000L)) {
                            Log.i("JarvisService", "open_website: already on $primaryUrl in Chrome. Suppressing redundant reload.")
                            val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                            val (_, summary) = waitForWebDestination(orchestrator, primaryUrl)
                            result.put("success", true)
                            result.put("message", "Already on $primaryUrl in Chrome. The web page is currently loading/open on screen. DO NOT call open_website again! Look at current_screen_elements or video feed to type prompt or click buttons.")
                            if (summary.isNotBlank()) result.put("current_screen_elements", summary)
                        } else {
                            if (primaryUrl.isNotBlank()) {
                                lastOpenedWebUrl = primaryUrl
                                lastOpenedWebTime = now
                            }
                            val ok = executeSerializedTaskMutation("open-urls") {
                                app.phoneControlManager.openUrlsInChromeTabs(targetUrls)
                            }
                            result.put("success", ok)
                            if (ok) {
                                result.put("message", if (targetUrls.size > 1) "Opened ${targetUrls.size} websites in separate Chrome tabs: ${targetUrls.joinToString(", ")}." else "Opened ${targetUrls.firstOrNull() ?: "website"} in Chrome.")
                                val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                                val (destinationReady, summary) = waitForWebDestination(orchestrator, primaryUrl)
                                if (summary.isNotBlank()) {
                                    result.put("current_screen_elements", summary)
                                }
                                if (!destinationReady) {
                                    result.put("message", "Navigation was requested, but Chrome has not confirmed the destination page yet. Wait and inspect before acting.")
                                }
                            } else {
                                result.put("message", "Could not open the requested websites.")
                            }
                        }
                    }

                    "search_in_chrome" -> {
                        val query = args.optString("query", "").trim()
                        val extracted = app.phoneControlManager.extractUrlsFromText(query)
                        if (extracted.isNotEmpty()) {
                            val ok = executeSerializedTaskMutation("open-search-results") {
                                app.phoneControlManager.openUrlsInChromeTabs(extracted)
                            }
                            result.put("success", ok)
                            result.put("message", if (extracted.size > 1) "Opened ${extracted.size} websites in separate Chrome tabs: ${extracted.joinToString(", ")}." else "Opened ${extracted.first()} in Chrome.")
                            delay(200)
                            val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                            val summary = orchestrator.inspectScreen(waitForContent = true)
                            if (summary.isNotBlank()) {
                                result.put("current_screen_elements", summary)
                            }
                        } else {
                            val targetUrl = app.phoneControlManager.parseTargetUrl(query)
                            val searchUrl = targetUrl ?: "https://www.google.com/search?q=${Uri.encode(query)}"
                            val ok = executeSerializedTaskMutation("open-search-url") {
                                app.phoneControlManager.openUrlsInChromeTabs(listOf(searchUrl))
                            }
                            result.put("success", ok)
                            result.put("message", if (targetUrl != null) "Opened $targetUrl in Chrome." else "Searched \"$query\" in Chrome.")
                            delay(200)
                            val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                            val summary = orchestrator.inspectScreen(waitForContent = true)
                            if (summary.isNotBlank()) {
                                result.put("current_screen_elements", summary)
                            }
                            if (targetUrl == null) {
                                result.put("action_required", "CRITICAL NEXT STEP: You are on the Google Search results page for \"$query\". Look at the search result links in current_screen_elements and IMMEDIATELY call click_ui_element with the link name or element_id to open the website! Do NOT claim you refined or created the video until you enter the website and submit the generation!")
                            }
                        }
                    }

                    "search_and_play_youtube" -> {
                        val query = args.optString("query", "").trim()
                        audioPlayer?.flush()
                        val apiKey = app.youTubePreferences.youtubeApiKey
                        val effectiveQuery = if (query.isBlank() || query.equals("video", ignoreCase = true) || query.equals("song", ignoreCase = true) || query.equals("a song", ignoreCase = true) || query.equals("songs", ignoreCase = true)) {
                            "my favorite song"
                        } else {
                            query
                        }
                        val resolvedSong = app.memoryManager.resolveSongQuery(effectiveQuery)
                        app.memoryManager.trackSongPlay(resolvedSong)
                        val playResult = com.jarvis.assistant.automation.youtube.YouTubeController.searchAndPlay(this@JarvisConversationService, resolvedSong, apiKey)
                        result.put("success", playResult.success)
                        result.put("message", playResult.message ?: if (playResult.success) "Playing $resolvedSong on YouTube." else "Could not play on YouTube.")
                    }

                    "search_youtube" -> {
                        val query = args.optString("query", "").trim()
                        val lower = query.lowercase()
                        val isPlayIntent = lower.startsWith("play ") ||
                                lower == "play" || lower == "video" || lower == "play video" ||
                                lower == "a video" || lower == "song" || lower == "play song" ||
                                lower == "a song" || lower == "music" ||
                                lower.contains(" chalao") || lower.contains(" bajao")
                        if (isPlayIntent) {
                            Log.i("JarvisService", "STRICT RULE INTERCEPTION: search_youtube called with play intent ('$query') -> rerouting to search_and_play_youtube!")
                            audioPlayer?.flush()
                            val apiKey = app.youTubePreferences.youtubeApiKey
                            val effectiveQuery = if (query.isBlank() || query.equals("video", ignoreCase = true) || query.equals("play video", ignoreCase = true) || query.equals("song", ignoreCase = true) || query.equals("play song", ignoreCase = true) || query.equals("a song", ignoreCase = true) || query.equals("songs", ignoreCase = true)) {
                                "my favorite song"
                            } else {
                                query.replace(Regex("(?i)^play\\s+"), "")
                            }
                            val resolvedSong = app.memoryManager.resolveSongQuery(effectiveQuery)
                            app.memoryManager.trackSongPlay(resolvedSong)
                            val playResult = com.jarvis.assistant.automation.youtube.YouTubeController.searchAndPlay(this@JarvisConversationService, resolvedSong, apiKey)
                            result.put("success", playResult.success)
                            result.put("message", playResult.message ?: if (playResult.success) "Playing $resolvedSong on YouTube." else "Could not play on YouTube.")
                        } else {
                            val searchResult = com.jarvis.assistant.automation.youtube.YouTubeController.searchYouTube(this@JarvisConversationService, query)
                            result.put("success", searchResult.success)
                            result.put("message", searchResult.message ?: "Opened YouTube search results.")
                        }
                    }

                    "youtube_accessibility_action" -> {
                        val action = args.optString("action", "").trim()
                        val a11y = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
                        if (a11y != null) {
                            val actionOk = when (action) {
                                "skip_ad" -> a11y.skipAd()
                                "like" -> a11y.likeVideo()
                                "subscribe" -> a11y.subscribeChannel()
                                "open_channel" -> a11y.openChannel()
                                "fullscreen" -> a11y.toggleFullscreen()
                                "click_first" -> a11y.clickFirstVideoResult()
                                else -> false
                            }
                            result.put("success", actionOk)
                            result.put("message", if (actionOk) "Action '$action' executed." else "Could not perform action '$action'.")
                        } else {
                            result.put("success", false)
                            result.put("message", "Accessibility service is not enabled. Please enable it in Settings.")
                        }
                    }

                    "media_playback_control" -> {
                        val action = args.optString("action", "").trim()
                        val ok = com.jarvis.assistant.automation.youtube.YouTubeController.sendMediaKey(this@JarvisConversationService, action)
                        result.put("success", ok)
                        result.put("message", "Media action '$action' performed.")
                    }

                    "send_whatsapp_message" -> {
                        val recipient = args.optString("recipient_name", "").trim()
                        val message = args.optString("message", "").trim()
                        val appNumber = if (args.has("app_number")) args.optInt("app_number", 1) else null
                        
                        // Security (SEC-01): Model-supplied confirmed=true is NEVER accepted as user consent.
                        // Require explicit user confirmation through PendingSensitiveActionManager.
                        val isApproved = com.jarvis.assistant.security.PendingSensitiveActionManager.consumeApprovedAction(
                            com.jarvis.assistant.security.SensitiveActionType.WHATSAPP_SEND,
                            recipient,
                            message
                        ) != null

                        if (!isApproved) {
                            com.jarvis.assistant.security.PendingSensitiveActionManager.stageAction(
                                com.jarvis.assistant.security.SensitiveActionType.WHATSAPP_SEND,
                                recipient,
                                null,
                                message
                            )
                            result.put("success", false)
                            result.put("requires_confirmation", true)
                            result.put("message", "Awaiting human confirmation to send WhatsApp message to $recipient: \"$message\".")
                            announce("Sir, I have prepared a WhatsApp message for $recipient: \"$message\". Should I send it?")
                        } else {
                            audioPlayer?.flush()
                            val waResult = com.jarvis.assistant.util.WhatsAppMessenger.sendMessage(this@JarvisConversationService, recipient, message, appNumber, confirmed = true)
                            when (waResult) {
                                is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.Success -> {
                                    result.put("success", true)
                                    result.put("sent", false)
                                    result.put("message", "Opened WhatsApp for ${waResult.contactName} and queued the send action.")
                                }
                                is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.RequiresConfirmation -> {
                                    result.put("success", false)
                                    result.put("requires_confirmation", true)
                                    result.put("message", "Please confirm sending the WhatsApp message to ${waResult.contactName}.")
                                }
                                is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MultipleAppsFound -> {
                                    result.put("success", false)
                                    result.put("message", "Multiple WhatsApp apps found. Please specify WhatsApp 1 or 2.")
                                }
                                is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.ContactNotFound -> {
                                    result.put("success", false)
                                    result.put("message", "Could not find contact ${waResult.name}.")
                                }
                                is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MultipleContactsFound -> {
                                    result.put("success", false)
                                    result.put("message", "Multiple contacts found for ${waResult.name}.")
                                }
                                is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.MissingPermission -> {
                                    result.put("success", false)
                                    result.put("message", "Contacts permission not granted.")
                                }
                                is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.Error -> {
                                    result.put("success", false)
                                    result.put("message", waResult.reason)
                                }
                            }
                        }
                    }

                    "whatsapp_call" -> {
                        val recipient = args.optString("recipient_name", "").trim()
                        val callType = args.optString("call_type", "voice").trim()
                        val appNumber = if (args.has("app_number")) args.optInt("app_number", 1) else null
                        
                        // Security (SEC-01): Explicit confirmation required
                        val isApproved = com.jarvis.assistant.security.PendingSensitiveActionManager.consumeApprovedAction(
                            com.jarvis.assistant.security.SensitiveActionType.WHATSAPP_CALL,
                            recipient,
                            callType
                        ) != null

                        if (!isApproved) {
                            com.jarvis.assistant.security.PendingSensitiveActionManager.stageAction(
                                com.jarvis.assistant.security.SensitiveActionType.WHATSAPP_CALL,
                                recipient,
                                null,
                                callType
                            )
                            result.put("success", false)
                            result.put("requires_confirmation", true)
                            result.put("message", "Awaiting human confirmation to place WhatsApp $callType call to $recipient.")
                            announce("Sir, would you like me to make a WhatsApp $callType call to $recipient? Please confirm yes or no.")
                        } else {
                            audioPlayer?.flush()
                            val callResult = com.jarvis.assistant.util.WhatsAppMessenger.placeCall(this@JarvisConversationService, recipient, callType, appNumber, confirmed = true)
                            val (ok, msg) = when (callResult) {
                                is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.Success -> Pair(true, "Calling ${callResult.contactName} on WhatsApp.")
                                is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.RequiresConfirmation -> Pair(false, "Please confirm WhatsApp call to ${callResult.contactName}.")
                                is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MultipleAppsFound -> Pair(false, "Multiple WhatsApp apps found. Please specify WhatsApp 1 or 2.")
                                is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.ContactNotFound -> Pair(false, "Could not find contact ${callResult.name}.")
                                is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MultipleContactsFound -> Pair(false, "Multiple contacts found for ${callResult.name}.")
                                is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.MissingPermission -> Pair(false, "Contacts permission not granted.")
                                is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.Error -> Pair(false, callResult.reason)
                            }
                            result.put("success", ok)
                            result.put("message", msg)
                        }
                    }

                    "call_contact" -> {
                        val contact = args.optString("contact_name", "").trim()
                        val isApproved = com.jarvis.assistant.security.PendingSensitiveActionManager.consumeApprovedAction(
                            com.jarvis.assistant.security.SensitiveActionType.PHONE_CALL,
                            contact,
                            "phone_call"
                        ) != null

                        audioPlayer?.flush()
                        val callResult = com.jarvis.assistant.util.ContactCaller.callContact(this@JarvisConversationService, contact, confirmed = isApproved)
                        when (callResult) {
                            is com.jarvis.assistant.util.ContactCaller.CallResult.RequiresConfirmation -> {
                                com.jarvis.assistant.security.PendingSensitiveActionManager.stageAction(
                                    com.jarvis.assistant.security.SensitiveActionType.PHONE_CALL,
                                    callResult.contact.name,
                                    callResult.contact.number,
                                    "phone_call"
                                )
                                result.put("success", false)
                                result.put("requires_confirmation", true)
                                val suffix = if (callResult.contact.number.length >= 4) callResult.contact.number.takeLast(4) else callResult.contact.number
                                result.put("message", "Awaiting human confirmation to call ${callResult.contact.name} ending in $suffix.")
                                announce("Sir, would you like me to call ${callResult.contact.name} on number ending in $suffix? Say yes to place the call.")
                            }
                            is com.jarvis.assistant.util.ContactCaller.CallResult.Success -> {
                                result.put("success", true)
                                result.put("message", "Calling ${callResult.contact.name}.")
                            }
                            is com.jarvis.assistant.util.ContactCaller.CallResult.NoMatch -> {
                                result.put("success", false)
                                result.put("message", "Could not find contact $contact.")
                            }
                            is com.jarvis.assistant.util.ContactCaller.CallResult.MultipleMatches -> {
                                result.put("success", false)
                                result.put("message", "Multiple contacts found for $contact.")
                            }
                            is com.jarvis.assistant.util.ContactCaller.CallResult.MissingContactsPermission -> {
                                result.put("success", false)
                                result.put("message", "Contacts permission is not granted, so JARVIS cannot look up that saved contact.")
                            }
                            is com.jarvis.assistant.util.ContactCaller.CallResult.MissingCallPermission -> {
                                result.put("success", false)
                                result.put("message", "Phone call permission is not granted.")
                            }
                            is com.jarvis.assistant.util.ContactCaller.CallResult.CallFailed -> {
                                result.put("success", false)
                                result.put("message", "Could not place phone call.")
                            }
                        }
                    }

                    "set_volume" -> {
                        val action = args.optString("action", "").trim()
                        val pct = if (args.has("percentage")) args.optInt("percentage", 50) else null
                        val msg = when {
                            action == "set" && pct != null -> app.phoneControlManager.setVolumePercent(pct)
                            action == "increase" -> app.phoneControlManager.adjustVolume(true)
                            action == "decrease" -> app.phoneControlManager.adjustVolume(false)
                            else -> "Volume unchanged."
                        }
                        result.put("success", true)
                        result.put("message", msg)
                    }

                    "set_brightness" -> {
                        val action = args.optString("action", "").trim()
                        val pct = if (args.has("percentage")) args.optInt("percentage", 50) else null
                        val msg = when {
                            action == "set" && pct != null -> app.phoneControlManager.setBrightnessPercent(pct)
                            action == "increase" -> app.phoneControlManager.adjustBrightness(true)
                            action == "decrease" -> app.phoneControlManager.adjustBrightness(false)
                            else -> "Brightness unchanged."
                        }
                        result.put("success", true)
                        result.put("message", msg)
                    }

                    "builtin_chrome_search" -> {
                        val query = args.optString("query", "").trim()
                        val searchExtracted = com.jarvis.assistant.util.BuiltInChromeEngine.searchAndExtract(query)
                        result.put("success", searchExtracted.isNotBlank())
                        result.put("result", searchExtracted)
                    }

                    "go_to_background" -> {
                        val userAskedForStandby = isExplicitStandbyCommand(currentTurnUserText.toString())
                        if (!userAskedForStandby) {
                            result.put("success", false)
                            result.put("message", "REFUSED: The user's current request did not explicitly ask Jarvis to go to standby. Keep the conversation active and continue the requested task.")
                        } else if (isAutonomousTaskRunning) {
                            // Going to standby mid-task stopped the conversation and left the task unfinished.
                            result.put("success", false)
                            result.put("message", "REFUSED: a task is still running. Do NOT go to background. Continue the task, or tell the user honestly what is blocking it.")
                        } else {
                            minimizeToHomeScreen()
                            enterStandby(announce = false)
                            result.put("success", true)
                            result.put("message", "Sent JARVIS to background standby mode.")
                        }
                    }

                    "set_flashlight" -> {
                        val enabled = args.optBoolean("enabled", true)
                        val msg = app.phoneControlManager.setFlashlight(enabled)
                        result.put("success", true)
                        result.put("message", msg)
                    }

                    "click_ui_element" -> {
                        val targetName = args.optString("target_name", "")
                        val elementId = if (args.has("element_id")) args.optInt("element_id") else null
                        val isToggle = args.optBoolean("is_toggle", false)
                        val desc = args.optString("description", "")
                        val x = if (args.has("x")) args.optInt("x") else null
                        val y = if (args.has("y")) args.optInt("y") else null
                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                        val activeRootForBatch = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance?.getActiveRootNode()
                        val hasExplicitInstagramUnlike = activeRootForBatch?.packageName?.toString()?.contains("instagram", true) == true &&
                            com.jarvis.assistant.vision.UiGroundingEngine().scanScreen(activeRootForBatch).any {
                                it.isClickable && it.primaryLabel.trim().startsWith("Unlike", true)
                            }
                        val actionRes = if (isInstagramBulkTask() && hasExplicitInstagramUnlike) {
                            orchestrator.runInstagramBulkUnlike(autonomousTaskGoal)
                        } else orchestrator.handleClick(
                                targetName, elementId, desc, isToggle, x, y,
                                allowChromeMenuForSettings = autonomousTaskGoal.contains("chrome", ignoreCase = true) &&
                                    autonomousTaskGoal.contains("settings", ignoreCase = true),
                                forbidAddingInstagramLike = isInstagramBulkTask(),
                                taskGoal = autonomousTaskGoal
                            )
                        result.put("success", actionRes.success)
                        result.put("message", actionRes.message)
                        actionRes.verified?.let { result.put("verified", it) }
                        if (actionRes.currentStateSummary.isNotBlank()) {
                            result.put("current_screen_elements", actionRes.currentStateSummary)
                        }
                        val instagramBatchEnd = isInstagramBulkTask() && actionRes.verified == true && actionRes.message.contains("stable end", true)
                        if (instagramBatchEnd) {
                            bulkEndOfListVerified = true
                            com.jarvis.assistant.automation.agent.AgentTaskEngine.current()?.let { task ->
                                bulkEndOfListVerified = task.completionEvidence == com.jarvis.assistant.automation.agent.AgentTaskEngine.CompletionEvidence.END_REACHED &&
                                    task.processedItemSignatures.isNotEmpty()
                            }
                        }
                        if (!instagramBatchEnd && isAutonomousTaskRunning && com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(autonomousTaskGoal).isBulkAction && actionRes.success) {
                            // Any successful item mutation invalidates a previous end-of-list observation.
                            bulkEndOfListVerified = false
                            bulkNoProgressScrollCount = 0
                            lastBulkScreenSignature = null
                        }
                        val clickTarget = targetName.lowercase(Locale.ROOT)
                        val isGenerateAction = clickTarget.contains("generate") || clickTarget.contains("start generation") ||
                            clickTarget.contains("submit") || clickTarget.contains("create arrow")
                        if (isGoogleFlowCreationTask && flowPromptVerified && isGenerateAction && actionRes.success) {
                            val summaryText = actionRes.currentStateSummary.lowercase(Locale.ROOT)
                            val promptMarker = lastVerifiedFlowPrompt.trim().lowercase(Locale.ROOT).take(20)
                            val promptVisibleInProject = promptMarker.length >= 8 && summaryText.contains(promptMarker)
                            val generatedProjectVisible = listOf("created oct", "generating", "generation in progress", "video generated")
                                .any(summaryText::contains)
                            flowGenerationVerified = promptVisibleInProject && generatedProjectVisible
                            Log.i("JarvisService", "Flow submit verification: prompt=$flowPromptVerified, generation=$flowGenerationVerified, tapVerified=${actionRes.verified}")
                        }
                        if (actionRes.requiresUserConfirmation) {
                            result.put("requires_confirmation", true)
                            autonomousWaitingForUser = true
                            com.jarvis.assistant.automation.agent.AgentTaskEngine.setState(
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.BLOCKED
                            )
                        }
                    }

                    "type_text" -> {
                        val text = args.optString("text", "")
                        val targetInput = args.optString("target_input_name", "")
                        val submit = args.optBoolean("submit", false)
                        val x = if (args.has("x")) args.optInt("x") else null
                        val y = if (args.has("y")) args.optInt("y") else null
                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                        val actionRes = orchestrator.handleType(text, targetInput.takeIf { it.isNotBlank() }, submit, x, y)
                        result.put("success", actionRes.success)
                        result.put("message", actionRes.message)
                        actionRes.verified?.let { result.put("verified", it) }
                        if (actionRes.currentStateSummary.isNotBlank()) {
                            result.put("current_screen_elements", actionRes.currentStateSummary)
                        }
                        val isPromptTarget = targetInput.contains("prompt", ignoreCase = true) ||
                            targetInput.contains("create", ignoreCase = true) || targetInput.contains("describe", ignoreCase = true)
                        if (isGoogleFlowCreationTask && isPromptTarget && actionRes.success && actionRes.verified == true) {
                            flowPromptVerified = true
                            lastVerifiedFlowPrompt = text
                            flowGenerationVerified = false
                            Log.i("JarvisService", "Google Flow prompt readback verified (${text.length} chars)")
                        }
                    }

                    "scroll_screen" -> {
                        val dir = args.optString("direction", "down")
                        val dist = args.optString("distance", "small")
                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                        val activeRootForBatch = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance?.getActiveRootNode()
                        val hasExplicitInstagramUnlike = activeRootForBatch?.packageName?.toString()?.contains("instagram", true) == true &&
                            com.jarvis.assistant.vision.UiGroundingEngine().scanScreen(activeRootForBatch).any {
                                it.isClickable && it.primaryLabel.trim().startsWith("Unlike", true)
                            }
                        val actionRes = if (isInstagramBulkTask() && hasExplicitInstagramUnlike) {
                            orchestrator.runInstagramBulkUnlike(autonomousTaskGoal)
                        } else orchestrator.handleScroll(dir, dist)
                        result.put("success", actionRes.success)
                        result.put("message", actionRes.message)
                        actionRes.verified?.let { result.put("verified", it) }
                        if (actionRes.currentStateSummary.isNotBlank()) {
                            result.put("current_screen_elements", actionRes.currentStateSummary)
                        }
                        val instagramBatchEnd = isInstagramBulkTask() && actionRes.verified == true && actionRes.message.contains("stable end", true)
                        if (instagramBatchEnd) {
                            val task = com.jarvis.assistant.automation.agent.AgentTaskEngine.current()
                            bulkEndOfListVerified = task?.completionEvidence == com.jarvis.assistant.automation.agent.AgentTaskEngine.CompletionEvidence.END_REACHED &&
                                (task.processedItemSignatures.isNotEmpty())
                        }
                        if (!instagramBatchEnd && isAutonomousTaskRunning && com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(autonomousTaskGoal).isBulkAction) {
                            val activeRoot = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance?.getActiveRootNode()
                            val signature = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(activeRoot)
                            val hasUsableObservation = signature.packageName.isNotBlank() && signature.elementCount > 0
                            if (hasUsableObservation && lastBulkScreenSignature?.isEquivalentTo(signature) == true) {
                                bulkNoProgressScrollCount++
                            } else {
                                bulkNoProgressScrollCount = 0
                            }
                            lastBulkScreenSignature = signature.takeIf { hasUsableObservation }
                            bulkEndOfListVerified = bulkNoProgressScrollCount >= 2
                            if (bulkEndOfListVerified) {
                                result.put("success", true)
                                result.put("bulk_end_of_list_verified", true)
                                result.put("message", "Two consecutive fresh screen observations after scrolling showed no further list content. Recheck that the visible batch is processed, then the requested list is at its end.")
                            }
                        }
                    }

                    "press_key" -> {
                        val key = args.optString("key", "back")
                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                        val actionRes = orchestrator.handleKey(key)
                        result.put("success", actionRes.success)
                        result.put("message", actionRes.message)
                    }

                    "task_completed" -> {
                        val summary = args.optString("summary", "Task completed.")
                        if (!isAutonomousTaskRunning &&
                            (!lastCompletedTaskSummary.isNullOrBlank() || System.currentTimeMillis() - lastVerifiedCompletionAt < 15_000L)
                        ) {
                            Log.i("JarvisService", "Ignored a duplicate task_completed call after verified task completion")
                            result.put("success", true)
                            result.put("already_completed", true)
                            result.put("message", "This task was already completed and verified. Do not repeat the result.")
                            result.put("system_directive", "DUPLICATE COMPLETION: You already gave the final result. Remain silent and wait for the user.")
                        } else {
                        val goalObj = com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(autonomousTaskGoal)
                        val localCompletionCheck = verifyTaskCompletionLocally(autonomousTaskGoal)
                        if (isGoogleFlowCreationTask && (!flowPromptVerified || !flowGenerationVerified)) {
                            Log.w("JarvisService", "Rejected Google Flow completion without verified prompt and submission (prompt=$flowPromptVerified, generation=$flowGenerationVerified)")
                            result.put("success", false)
                            result.put("message", "Google Flow task is not verified complete. Confirm the prompt text is visible and a generated or processing video item is visible before reporting completion.")
                            result.put("system_directive", "COMPLETION REJECTED: Do not tell the user the video was submitted. Inspect the current Flow screen. If the prompt is missing, enter it and verify it; if Generate has not started, click the visible Generate button and wait for a processing or generated item. Report a blocker honestly if the screen cannot confirm it.")
                        } else if (isAutonomousTaskRunning && goalObj.isBulkAction && !bulkEndOfListVerified) {
                            Log.w("JarvisService", "Premature task_completed rejected for bulk action goal: '$autonomousTaskGoal'")
                            result.put("success", false)
                            result.put("message", "Bulk task completion rejected: no end-of-list evidence yet. Continue processing matching visible items, then scroll and re-observe until two consecutive screen signatures show no further list content.")
                            result.put("system_directive", "COMPLETION REJECTED: The request explicitly applies to ALL matching items. Do not claim completion. Inspect the fresh screen, process each visible matching target once, then scroll and observe again. Repeat until the app shows no further content; do not scroll past a visible target before processing it.")
                        } else if (!isAutonomousTaskRunning || !localCompletionCheck.first) {
                            com.jarvis.assistant.automation.agent.AgentTaskEngine.recordCompletionEvidence(
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.CompletionEvidence.UNKNOWN,
                                if (isAutonomousTaskRunning) localCompletionCheck.second else "There is no active autonomous task to complete"
                            )
                            result.put("success", false)
                            result.put("message", "Completion is unverified: ${if (isAutonomousTaskRunning) localCompletionCheck.second else "There is no active autonomous task to complete"}")
                            result.put("system_directive", "COMPLETION REJECTED: Do not claim the task is done. The app has not verified the requested final state. Re-observe, continue only with a clear safe action, or report the blocker honestly.")
                        } else {
                            com.jarvis.assistant.automation.agent.AgentTaskEngine.recordCompletionEvidence(
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.CompletionEvidence.VERIFIED_GOAL,
                                localCompletionCheck.second
                            )
                            Log.i("JarvisService", "task_completed received: Autonomous task marked COMPLETE.")
                            lastVerifiedCompletionAt = System.currentTimeMillis()
                            isAutonomousTaskRunning = false
                            val engineCompleted = com.jarvis.assistant.automation.agent.AgentTaskEngine.finish(
                                com.jarvis.assistant.automation.agent.AgentTaskEngine.State.COMPLETED
                            )
                            autonomousStepCount = 0
                            bulkEndOfListVerified = false
                            bulkNoProgressScrollCount = 0
                            lastBulkScreenSignature = null
                            isGoogleFlowCreationTask = false
                            flowPromptVerified = false
                            flowGenerationVerified = false
                            lastVerifiedFlowPrompt = ""
                            autonomousLoopJob?.cancel()
                            autonomousLoopJob = null
                            result.put("success", engineCompleted)
                            result.put("message", if (engineCompleted) "Task completed and locally verified: $summary" else "Task completion was rejected because local verification did not pass.")
                            lastCompletedTaskSummary = summary
                            if (preferences.isSilentExecutionMode) {
                                result.put("system_directive", "SILENT EXECUTION: Do NOT speak or play audio. Task completed silently.")
                            } else {
                                val alreadySpoke = synchronized(currentTurnAssistantText) {
                                    currentTurnAssistantText.isNotBlank()
                                }
                                if (alreadySpoke) {
                                    result.put("system_directive", "TASK RECORDED: You already confirmed completion aloud to the user. Do NOT repeat yourself. Remain completely silent.")
                                } else {
                                    result.put("system_directive", "TASK COMPLETED: Speak ONE short confirmation sentence to the user in your natural voice: '$summary'. Do NOT repeat if already spoken.")
                                }
                                if (!alreadySpoke && liveWebSocket?.isSessionReady() != true) {
                                    // No Android TTS fallback: expose completion through app events instead.
                                    lastCompletedTaskSummary = null
                                    serviceScope.launch { _events.emit("Task completed: $summary") }
                                }
                            }
                        }
                        }
                    }

                    "inspect_screen" -> {
                        val orchestrator = com.jarvis.assistant.vision.VisualTaskOrchestrator.getInstance()
                        val summary = orchestrator.inspectScreen(waitForContent = true)
                        result.put("success", true)
                        result.put("screen_summary", summary)
                        result.put("message", "Screen inspected successfully.")
                    }

                    "save_memory" -> {
                        val fact = args.optString("fact", "").trim()
                        val category = args.optString("category", com.jarvis.assistant.data.memory.MemoryEntity.CATEGORY_NOTES)
                        val key = args.optString("key", "").trim()
                        val value = args.optString("value", "").trim()
                        val wishRequested = args.optBoolean("wish_requested", false)

                        if (fact.isBlank() && value.isBlank()) {
                            result.put("success", false)
                            result.put("message", "Fact cannot be empty.")
                        } else {
                            val finalFact = fact.ifBlank { value }
                            val finalVal = value.ifBlank { fact }
                            val isBirthday = key == "user_birthday" ||
                                category.equals(com.jarvis.assistant.data.memory.MemoryEntity.CATEGORY_PERSONAL, ignoreCase = true) &&
                                (finalFact.contains("birthday", ignoreCase = true) || finalFact.contains("janamdin", ignoreCase = true) ||
                                 finalVal.contains("birthday", ignoreCase = true) || finalVal.contains("janamdin", ignoreCase = true))

                            if (isBirthday) {
                                val parseRes = com.jarvis.assistant.data.memory.MemoryIntentParser.parse(finalFact)
                                val dateStr = if (parseRes is com.jarvis.assistant.data.memory.MemoryIntent.SaveBirthday) {
                                    parseRes.normalizedDate
                                } else finalVal

                                val saveRes = app.memoryManager.saveBirthday(
                                    birthday = dateStr,
                                    wishRequested = wishRequested,
                                    rawText = finalFact
                                )
                                when (saveRes) {
                                    is com.jarvis.assistant.data.memory.SaveMemoryResult.Success -> {
                                        result.put("success", true)
                                        result.put("message", saveRes.confirmation)
                                        result.put("reminder_supported", false)
                                        result.put("reminder_note", "Proactive reminder notifications are not yet supported in this version.")
                                    }
                                    is com.jarvis.assistant.data.memory.SaveMemoryResult.Failure -> {
                                        result.put("success", false)
                                        result.put("message", saveRes.errorMessage)
                                    }
                                }
                            } else {
                                val saveRes = app.memoryManager.saveGenericMemory(
                                    rawText = finalFact,
                                    category = category,
                                    key = key.ifBlank { null },
                                    value = finalVal.ifBlank { null }
                                )
                                when (saveRes) {
                                    is com.jarvis.assistant.data.memory.SaveMemoryResult.Success -> {
                                        result.put("success", true)
                                        result.put("message", saveRes.confirmation)
                                    }
                                    is com.jarvis.assistant.data.memory.SaveMemoryResult.Failure -> {
                                        result.put("success", false)
                                        result.put("message", saveRes.errorMessage)
                                    }
                                }
                            }
                        }
                    }

                    "recall_memory" -> {
                        val query = args.optString("query", "").trim()
                        val lowerQ = query.lowercase(Locale.ROOT)
                        val reply = when {
                            lowerQ.contains("birthday") || lowerQ.contains("janamdin") || lowerQ.contains("bday") -> {
                                app.memoryManager.getBirthdayResponse()
                            }
                            lowerQ.contains("song") || lowerQ.contains("music") || lowerQ.contains("gana") -> {
                                app.memoryManager.getFavoriteSongResponse()
                            }
                            lowerQ.contains("routine") -> {
                                app.memoryManager.getRoutineResponse()
                            }
                            lowerQ.contains("all") || lowerQ.contains("about me") || lowerQ.contains("remember") -> {
                                app.memoryManager.getWhatDoYouRemember()
                            }
                            else -> {
                                val matches = app.memoryRepository.searchMemories(query)
                                if (matches.isNotEmpty()) {
                                    val list = matches.take(3).joinToString("; ") { it.rawText }
                                    "Sir, I found the following in your Memory Core: $list."
                                } else {
                                    "I couldn't find any memory matching '$query', Sir."
                                }
                            }
                        }
                        result.put("success", true)
                        result.put("message", reply)
                    }

                    "forget_memory" -> {
                        val query = args.optString("query", "").trim()
                        val reply = app.memoryManager.forgetFact(query)
                        result.put("success", true)
                        result.put("message", reply)
                    }

                    else -> {
                        Log.w("JarvisService", "Unknown toolCall name: $name")
                        result.put("success", false)
                        result.put("message", "Unknown tool: $name")
                    }
                }

                if (isAutomationTool && name != "task_completed") {
                    val toolSuccess = result.optBoolean("success", true)
                    com.jarvis.assistant.automation.agent.AgentTaskEngine.recordVerifiedStep(
                        name,
                        result.optString("message", "Tool action completed"),
                        result.optBoolean("verified", false)
                    )
                    if (!toolSuccess) {
                        com.jarvis.assistant.automation.agent.AgentTaskEngine.failCurrentStep(
                            result.optString("message", "Tool action failed"),
                            blocked = result.optBoolean("requires_confirmation", false)
                        )
                    }
                    val recentOtp = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.getRecentOtp()
                    val otpNotice = if (recentOtp != null) " [SYSTEM OTP DETECTED: '$recentOtp']" else ""
                    val goalObj = com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(autonomousTaskGoal)
                    val bulkNotice = if (goalObj.isBulkAction) " [PERSISTENT BULK ACTION MODE: Goal involves '${goalObj.bulkKeywords.joinToString()}'. Process ALL matching items by scrolling down iteratively! Do NOT declare completion after only one screen.]" else ""
                    result.put("step_number", autonomousStepCount)
                    result.put("autonomous_goal", autonomousTaskGoal)
                    if (toolSuccess) consecutiveToolFailures = 0 else consecutiveToolFailures++
                    val toolSig = name + ":" + normalizedArgsSignature(args)
                    if (name != "scroll_screen" && toolSig == lastToolSignature) sameToolRepeatCount++
                    else { lastToolSignature = toolSig; sameToolRepeatCount = 1 }
                    if (consecutiveToolFailures >= MAX_CONSECUTIVE_FAILURES ||
                        (sameToolRepeatCount >= 3 && !isInstagramBulkTask()) ||
                        autonomousStepCount >= autonomousTaskStepLimit()) {
                        autonomousHardStop = true
                    }
                    if (autonomousHardStop) {
                        com.jarvis.assistant.automation.agent.AgentTaskEngine.finish(
                            com.jarvis.assistant.automation.agent.AgentTaskEngine.State.FAILED,
                            "Autonomous safety limit reached after repeated failures or actions."
                        )
                        result.put("autonomous_status", "STOPPED")
                        result.put("system_directive", "HARD STOP: you are repeating actions or failing repeatedly. Do NOT call any more tools. Tell the user honestly, in one or two short sentences, what you actually completed and what went wrong (do NOT claim success), then wait for their next instruction.")
                    } else if (toolSuccess) {
                        result.put("autonomous_status", "EXECUTED")
                        result.put("system_directive", "AUTONOMOUS STEP $autonomousStepCount EXECUTED. OVERARCHING GOAL: '$autonomousTaskGoal'$otpNotice$bulkNotice. MANDATE: Inspect the screen. If the OVERARCHING GOAL is not yet 100% finished on screen, you MUST IMMEDIATELY take the next concrete action (click, type, scroll, wait for install/open). NEVER ABANDON THE TASK MID-WAY! NEVER call task_completed until the ultimate goal is fully achieved on screen!")
                    } else {
                        result.put("autonomous_status", "FAILED")
                        val failMsg = result.optString("message", "Action failed")
                        result.put("system_directive", "CRITICAL WARNING: STEP $autonomousStepCount FAILED ($failMsg)$otpNotice. You MUST NOT claim the action succeeded, do NOT say you completed it, and do NOT call task_completed! Either retry the action, tap the field to focus it first, or truthfully inform the user.")
                    }
                }

                            liveWebSocket?.sendToolResponse(callId, name, result)
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.e("JarvisService", "Tool call '$name' (callId=$callId) timed out after 25 seconds.")
                        val timeoutResult = JSONObject().apply {
                            put("success", false)
                            put("message", "Tool execution timed out after 25 seconds.")
                        }
                        liveWebSocket?.sendToolResponse(callId, name, timeoutResult)
                    } catch (e: CancellationException) {
                        Log.i("JarvisService", "Tool call '$name' (callId=$callId) was cancelled.")
                        throw e
                    } catch (e: Exception) {
                        Log.e("JarvisService", "Tool call '$name' execution failed: ${e.message}", e)
                        val errResult = JSONObject().apply {
                            put("success", false)
                            put("message", "Error: ${e.message}")
                        }
                        liveWebSocket?.sendToolResponse(callId, name, errResult)
                    } finally {
                        isExecutingTool = false
                        if (_conversationState.value == ConversationState.EXECUTING) {
                            setConversationState(if (audioPlayer?.isPlaying() == true) ConversationState.SPEAKING else ConversationState.THINKING)
                        }
                    }
                }
            } finally {
                if (thisJob != null) activeToolJobs.remove(thisJob)
            }
        }
        activeToolJob = job
    }



    private suspend fun verifyTaskCompletionLocally(goal: String): Pair<Boolean, String> {
        if (isGoogleFlowCreationTask) {
            return (flowPromptVerified && flowGenerationVerified) to
                if (flowPromptVerified && flowGenerationVerified) "Flow prompt and generated/processing item were verified" else "Flow output evidence is incomplete"
        }

        val service = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
            ?: return false to "Accessibility service is unavailable"
        val root = service.getActiveRootNode()
            ?: return false to "The active application screen is unavailable"
        val packageName = root.packageName?.toString().orEmpty().lowercase(Locale.ROOT)
        val elements = com.jarvis.assistant.vision.UiGroundingEngine().scanScreen(root)
        val signature = com.jarvis.assistant.automation.agent.ScreenSignatureCalculator.computeSignature(root)
        val summary = com.jarvis.assistant.vision.UiGroundingEngine().formatElementsSummary(elements, root)
        com.jarvis.assistant.automation.agent.AgentTaskEngine.observe(signature, packageName, summary)

        if (com.jarvis.assistant.automation.agent.AgentTaskEngine.isCancelled()) {
            return false to "The task was cancelled"
        }
        val normalizedGoal = com.jarvis.assistant.automation.agent.AgentTaskEngine.normalize(goal)
        val goalModel = com.jarvis.assistant.automation.agent.TaskGoal.fromUserUtterance(goal)
        if (goalModel.isBulkAction) {
            val task = com.jarvis.assistant.automation.agent.AgentTaskEngine.current()
            val endObserved = task?.completionEvidence == com.jarvis.assistant.automation.agent.AgentTaskEngine.CompletionEvidence.END_REACHED
            val processed = task?.processedItemSignatures?.size ?: 0
            return (endObserved && processed > 0) to
                if (endObserved && processed > 0) "Reached list end after verifying $processed unique items" else "Bulk completion lacks verified per-item outcomes and end-of-list evidence (tracked items: $processed)"
        }

        val expectedPackage = expectedPackageForTask(goal)?.lowercase(Locale.ROOT)
        if (expectedPackage != null && !packageName.contains(expectedPackage)) {
            return false to "Foreground app is '$packageName'; expected an app matching '$expectedPackage'"
        }

        val openIntent = listOf("open", "launch", "start", "kholo", "khol do", "app kholo")
            .any(normalizedGoal::contains)
        val expectedAppWords = when {
            expectedPackage == "spotify" -> setOf("spotify")
            expectedPackage == "instagram" -> setOf("instagram", "insta")
            expectedPackage == "chrome" -> setOf("chrome", "browser", "google")
            expectedPackage == "com.google.android.gm" -> setOf("gmail", "email")
            else -> setOf(expectedPackage.orEmpty().substringAfterLast('.'))
        }
        val openTargetTerms = normalizedGoal.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .filterNot { it in expectedAppWords || it in setOf("jarvis", "please", "open", "launch", "start", "app", "the", "khol", "kholo", "do") }
        if (expectedPackage != null && openIntent) {
            if (openTargetTerms.isEmpty()) return true to "Expected application is foreground: $packageName"
            val destinationMatched = elements.any { item ->
                !item.isEditable && item.hasTextOrDesc &&
                    openTargetTerms.all { item.primaryLabel.lowercase(Locale.ROOT).contains(it) }
            }
            if (destinationMatched) return true to "Expected app is foreground and requested destination is visible"
        }

        val targetTerms = normalizedGoal.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .filterNot { it in setOf("jarvis", "please", "open", "launch", "start", "spotify", "youtube", "instagram", "playlist", "meri", "mera", "par", "mein", "find", "search", "look", "song", "track", "play", "karo", "kar", "do", "dhundo", "dhoondh", "liked", "reels", "posts", "unlike", "all", "every", "the", "for", "and", "with", "aur", "ko", "pe", "se", "ke", "ki", "ka", "bajao", "wale", "wali") }
        val matchingVisibleItems = elements.filter { item ->
            !item.isEditable && item.hasTextOrDesc && targetTerms.isNotEmpty() &&
                targetTerms.all { term -> item.primaryLabel.lowercase(Locale.ROOT).contains(term) }
        }

        val playbackRequested = listOf("play", "play karo", "play it", "bajao").any(normalizedGoal::contains)
        if (playbackRequested) {
            val spotifyOrYoutube = packageName.contains("spotify") || packageName.contains("youtube")
            if (!spotifyOrYoutube || matchingVisibleItems.size != 1) {
                return false to "Could not uniquely match the requested track in the active player"
            }
            val playbackEvidence = elements.any { item ->
                val label = item.primaryLabel.lowercase(Locale.ROOT)
                label.contains("pause") || label.contains("now playing") || label.contains("currently playing")
            }
            return playbackEvidence to if (playbackEvidence) "Unique requested track is visible and player exposes a pause/playing state" else "Requested track is visible, but active playback is not confirmed"
        }

        val searchRequested = listOf("search", "find", "look for", "dhundo", "dhoondh", "talaash").any(normalizedGoal::contains)
        if (searchRequested && matchingVisibleItems.size == 1) {
            return true to "One visible, non-editable result matches the requested terms"
        }

        if (openIntent && expectedPackage != null) {
            return false to "The app is foreground, but the requested destination is not verified"
        }
        return false to "No local verifier is available for this goal type"
    }

    private suspend fun executeSerializedTaskMutation(name: String, operation: suspend () -> Boolean): Boolean =
        com.jarvis.assistant.automation.agent.AgentTaskEngine.executeUiMutation(name, operation)

    private fun isFlowCreationIntent(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val mentionsCreation = listOf("create", "make", "generate", "video", "prompt", "submit", "film").any(lower::contains)
        return mentionsCreation
    }

    private suspend fun waitForWebDestination(
        orchestrator: com.jarvis.assistant.vision.VisualTaskOrchestrator,
        expectedUrl: String
    ): Pair<Boolean, String> {
        val expectedHost = Uri.parse(expectedUrl).host?.lowercase(Locale.ROOT).orEmpty()
        val isFlow = expectedHost.contains("flow.google") || expectedUrl.contains("flow.google", ignoreCase = true)
        var latestSummary = ""
        repeat(12) {
            delay(300)
            val service = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance
            val root = service?.getActiveRootNode()
            val packageName = root?.packageName?.toString().orEmpty()
            if (packageName.contains("chrome", ignoreCase = true) || packageName.contains("browser", ignoreCase = true)) {
                latestSummary = orchestrator.inspectScreen(waitForContent = true)
                val summary = latestSummary.lowercase(Locale.ROOT)
                val pageVisible = if (isFlow) {
                    listOf("what do you want to create", "start creating or drop media", "long live the process", "google flow can make mistakes", "new project")
                        .any(summary::contains)
                } else {
                    expectedHost.isBlank() || summary.contains(expectedHost) || summary.contains("${expectedHost.substringBefore('.')}")
                }
                if (pageVisible) return true to latestSummary
            }
        }
        return false to latestSummary
    }

    private fun isStopCommand(text: String): Boolean {
        val clean = text.lowercase(Locale.ROOT).trim()
        val patterns = listOf(
            "stop", "ruk jao", "ruko", "cancel", "abort", "pause",
            "bas karo", "thahar", "hold on", "ruk ja", "band karo"
        )
        val matched = patterns.firstOrNull { containsPhrase(clean, it) } ?: return false
        val matchIndex = clean.indexOf(matched)
        val precedingWords = clean.take(matchIndex).trim().split(Regex("\\s+")).takeLast(3)
        if (precedingWords.any { it in setOf("not", "dont", "don't", "never", "without", "mat", "nahi") }) {
            return false
        }
        return true
    }

    /**
     * Processes all queued tool calls after speaker verification completes.
     * If [verified] is true, each queued tool call is executed via handleToolCallExecution.
     * If [verified] is false, each queued tool call gets an "access denied" error response.
     */
    private fun processPendingToolCalls(verified: Boolean) {
        var pending = pendingToolCalls.poll()
        while (pending != null) {
            val tc = pending
            if (verified) {
                Log.i("JarvisService", "EXECUTING queued tool call '${tc.name}' (callId=${tc.callId}): Owner verified.")
                handleToolCallExecution(tc.callId, tc.name, tc.args)
            } else {
                Log.w("JarvisService", "REJECTED queued tool call '${tc.name}' (callId=${tc.callId}): Speaker is NOT verified owner.")
                liveWebSocket?.sendToolResponse(tc.callId, tc.name, JSONObject().put("error", "Access denied: Voice does not match owner."))
            }
            pending = pendingToolCalls.poll()
        }
    }

    private fun checkAndExecuteCommand(userSpeech: String) {
        val cleanSpeech = userSpeech.trim()
        if (cleanSpeech.isBlank()) return

        if (cleanSpeech.lowercase(Locale.ROOT).matches(Regex(".*\\b(stop|cancel task|cancel automation|bas karo|ruk jao)\\b.*"))) {
            com.jarvis.assistant.automation.agent.AgentTaskEngine.requestCancellation()
            autonomousLoopJob?.cancel()
            activeToolJobs.forEach { it.cancel() }
            activeToolJob?.cancel()
            isAutonomousTaskRunning = false
            lastHandledCommandText = cleanSpeech
            announce("Okay, I stopped the current task.")
            return
        }
        if (hasExecutedCommandInCurrentTurn || cleanSpeech == lastHandledCommandText) return

        if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile() && !isCurrentTurnOwnerVoiceVerified) {
            Log.w("JarvisService", "BLOCKED local command execution: Speaker is not verified owner! ('$cleanSpeech')")
            return
        }

        val creatorResponse = PromptGenerator.resolveCreatorResponse(cleanSpeech, preferences.language)
        if (creatorResponse != null) {
            lastHandledCommandText = cleanSpeech
            hasExecutedCommandInCurrentTurn = true
            announce(creatorResponse)
            val app = application as? JarvisApp
            val activeTurn = currentTurnId
            currentTurnId = null
            if (activeTurn != null) {
                app?.chatRepository?.updateAssistantResponse(activeTurn, creatorResponse, isComplete = true)
            } else {
                app?.chatRepository?.addTurn(cleanSpeech, creatorResponse)
            }
            return
        }

        Log.d("JarvisService", "checkAndExecuteCommand: '$cleanSpeech'")

        val app = JarvisApp.instance
        val isAwaitingConfirm = app.whatsAppAssistant.hasPendingDraft() ||
                pendingGenericCommand != null ||
                com.jarvis.assistant.security.PendingSensitiveActionManager.hasPendingAction() ||
                com.jarvis.assistant.data.memory.PendingMemoryDeletionManager.hasPending()
        val isAwaitingDisambig = app.whatsAppAssistant.isAwaitingDisambiguation()

        val parsed = com.jarvis.assistant.automation.command.CommandParser.parse(
            input = cleanSpeech,
            isAwaitingConfirmation = isAwaitingConfirm,
            isAwaitingDisambiguation = isAwaitingDisambig
        )

        val cmd = parsed.command

        if (cmd is com.jarvis.assistant.automation.command.JarvisCommand.ConfirmAction) {
            // 1. Memory Deletion confirmation priority
            if (com.jarvis.assistant.data.memory.PendingMemoryDeletionManager.hasPending()) {
                lastHandledCommandText = cleanSpeech
                serviceScope.launch {
                    val reply = if (cmd.confirmed) {
                        app.memoryManager.confirmPendingDeletion()
                    } else {
                        app.memoryManager.cancelPendingDeletion()
                    }
                    announce(reply)
                }
                return
            }

            // 2. Sensitive Action (WhatsApp send/call, Phone call) confirmation priority
            val pendingSensitive = com.jarvis.assistant.security.PendingSensitiveActionManager.getActiveAction()
            if (pendingSensitive != null) {
                lastHandledCommandText = cleanSpeech
                if (cmd.confirmed) {
                    com.jarvis.assistant.security.PendingSensitiveActionManager.approveActiveAction()
                    when (pendingSensitive.type) {
                        com.jarvis.assistant.security.SensitiveActionType.WHATSAPP_SEND -> {
                            serviceScope.launch {
                                val waResult = com.jarvis.assistant.util.WhatsAppMessenger.sendMessage(
                                    this@JarvisConversationService,
                                    pendingSensitive.recipientName,
                                    pendingSensitive.payloadText,
                                    null,
                                    confirmed = true
                                )
                                com.jarvis.assistant.security.PendingSensitiveActionManager.consumeApprovedAction(
                                    com.jarvis.assistant.security.SensitiveActionType.WHATSAPP_SEND,
                                    pendingSensitive.recipientName,
                                    pendingSensitive.payloadText
                                )
                                val msg = if (waResult is com.jarvis.assistant.util.WhatsAppMessenger.SendResult.Success) {
                                    "Sending WhatsApp message to ${waResult.contactName}."
                                } else "Could not send WhatsApp message."
                                announce(msg)
                            }
                        }
                        com.jarvis.assistant.security.SensitiveActionType.WHATSAPP_CALL -> {
                            serviceScope.launch {
                                val callResult = com.jarvis.assistant.util.WhatsAppMessenger.placeCall(
                                    this@JarvisConversationService,
                                    pendingSensitive.recipientName,
                                    pendingSensitive.payloadText,
                                    null,
                                    confirmed = true
                                )
                                com.jarvis.assistant.security.PendingSensitiveActionManager.consumeApprovedAction(
                                    com.jarvis.assistant.security.SensitiveActionType.WHATSAPP_CALL,
                                    pendingSensitive.recipientName,
                                    pendingSensitive.payloadText
                                )
                                val msg = if (callResult is com.jarvis.assistant.util.WhatsAppMessenger.CallResult.Success) {
                                    "Calling ${callResult.contactName} on WhatsApp."
                                } else "Could not place WhatsApp call."
                                announce(msg)
                            }
                        }
                        com.jarvis.assistant.security.SensitiveActionType.PHONE_CALL -> {
                            serviceScope.launch {
                                val callResult = com.jarvis.assistant.util.ContactCaller.callContact(
                                    this@JarvisConversationService,
                                    pendingSensitive.recipientName,
                                    confirmed = true
                                )
                                com.jarvis.assistant.security.PendingSensitiveActionManager.consumeApprovedAction(
                                    com.jarvis.assistant.security.SensitiveActionType.PHONE_CALL,
                                    pendingSensitive.recipientName,
                                    pendingSensitive.payloadText
                                )
                                val msg = if (callResult is com.jarvis.assistant.util.ContactCaller.CallResult.Success) {
                                    "Calling ${callResult.contact.name}."
                                } else "Could not place phone call."
                                announce(msg)
                            }
                        }
                    }
                } else {
                    com.jarvis.assistant.security.PendingSensitiveActionManager.cancelActiveAction()
                    announce("Understood, Sir. I cancelled that action.")
                }
                return
            }

            // 3. Generic command confirmation
            if (pendingGenericCommand != null) {
                lastHandledCommandText = cleanSpeech
                val genericCmd = pendingGenericCommand
                pendingGenericCommand = null
                if (cmd.confirmed && genericCmd != null) {
                    Log.i("JarvisService", "User confirmed pending command: $genericCmd")
                    executeDetectedCommand(genericCmd, cleanSpeech)
                } else {
                    Log.i("JarvisService", "User cancelled pending command: $genericCmd")
                    announce("Okay, I won't do that.")
                }
                return
            }
        }

        if (cmd != null && parsed.confidence >= 0.90f) {
            // If the requested video/song is too vague or could easily be a mis-hearing
            // (e.g. Jarvis only caught one short/filler word), confirm with the user
            // instead of guessing and playing the wrong thing.
            if (cmd is com.jarvis.assistant.automation.command.JarvisCommand.PlayYouTube &&
                com.jarvis.assistant.automation.command.CommandParser.isAmbiguousPlayQuery(cmd.query)
            ) {
                lastHandledCommandText = cleanSpeech
                pendingGenericCommand = cmd
                Log.i("JarvisService", "Ambiguous YouTube query '${cmd.query}', asking for confirmation before playing")
                announce("Did you want me to play \"${cmd.query}\" on YouTube? Say yes to confirm, or tell me the name again.")
                return
            }

            lastHandledCommandText = cleanSpeech
            Log.i("JarvisService", "USER TEXT → Detected command [${parsed.matchedPattern}] confidence=${parsed.confidence}: $cmd")
            executeDetectedCommand(cmd, cleanSpeech)
        } else {
            Log.d("JarvisService", "No command match from user text (confidence=${parsed.confidence})")
        }
    }

    /**
     * Speaks a short message locally via TTS and also surfaces it in the chat/event UI,
     * used for quick confirmations/cancellations that don't need a full Gemini round-trip.
     */
    private fun announce(message: String) {
        serviceScope.launch { _events.emit(message) }
    }

    /**
     * Dual-Path Execution Engine:
     * When user speech parsing doesn't trigger a command due to background noise or slight transcription variances,
     * Gemini Live's natural language comprehension reliably recognizes the user's intent and speaks the standardized
     * action phrase (e.g., "Opening vercel.com in browser", "Searching for techno gamers on google", "Playing Tadipaar on YouTube",
     * "Sending WhatsApp message to Rahul: hello", "Calling Mom").
     *
     * This handler parses those standardized phrases and executes them immediately.
     * Guarded by [hasExecutedCommandInCurrentTurn] to prevent duplicate execution in the same turn.
     */
    private fun checkAssistantTextForCommands(assistantText: String) {
        if (hasExecutedCommandInCurrentTurn || (System.currentTimeMillis() - lastCommandExecutionTime < 3500L)) return
        val rawClean = assistantText.trim()
        val clean = rawClean.lowercase(java.util.Locale.ROOT).trimEnd('.', '!', '?', ',', ' ')
        if (clean.isBlank() || clean == lastHandledAssistantCommand) return

        var command: com.jarvis.assistant.automation.command.JarvisCommand? = null
        var matchSource = ""

        // --- 1. Flashlight ---
        val app = application as? JarvisApp
        val isHardwareTorchOn = app?.phoneControlManager?.isTorchOn ?: false
        if ((clean.contains("turning on") || clean.contains("turned on") || clean.contains("torch on") || clean.contains("flash on") || ((clean.contains("already on") || clean.contains("already turned on")) && !isHardwareTorchOn)) && (clean.contains("flash") || clean.contains("torch") || clean.contains("it"))) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.Flash(true)
            matchSource = "ASSISTANT_FLASH_ON"
        } else if ((clean.contains("turning off") || clean.contains("turned off") || clean.contains("torch off") || clean.contains("flash off") || ((clean.contains("already off") || clean.contains("already turned off")) && isHardwareTorchOn)) && (clean.contains("flash") || clean.contains("torch") || clean.contains("it"))) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.Flash(false)
            matchSource = "ASSISTANT_FLASH_OFF"
        }

        // --- 2. Brightness ---
        else if (clean.startsWith("setting brightness") || clean.startsWith("adjusting brightness") || clean.startsWith("increasing brightness") || clean.startsWith("decreasing brightness")) {
            val numMatch = Regex("(\\d{1,3})\\s*(?:%|percent)?\\b").find(clean)
            if (numMatch != null) {
                val level = numMatch.groupValues[1].toIntOrNull() ?: 50
                command = com.jarvis.assistant.automation.command.JarvisCommand.Brightness(level)
                matchSource = "ASSISTANT_BRIGHTNESS_SET"
            } else if (clean.contains("increas")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Brightness(0, isRelative = true, delta = 20)
                matchSource = "ASSISTANT_BRIGHTNESS_UP"
            } else if (clean.contains("decreas")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Brightness(0, isRelative = true, delta = -20)
                matchSource = "ASSISTANT_BRIGHTNESS_DOWN"
            }
        }

        // --- 3. Volume ---
        else if (clean.startsWith("setting volume") || clean.startsWith("adjusting volume") || clean.startsWith("increasing volume") || clean.startsWith("decreasing volume") || clean.startsWith("muting")) {
            val numMatch = Regex("(\\d{1,3})\\s*(?:%|percent)?\\b").find(clean)
            if (numMatch != null) {
                val level = numMatch.groupValues[1].toIntOrNull() ?: 50
                command = com.jarvis.assistant.automation.command.JarvisCommand.Volume(level)
                matchSource = "ASSISTANT_VOLUME_SET"
            } else if (clean.contains("increas")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Volume(0, isRelative = true, delta = 1)
                matchSource = "ASSISTANT_VOLUME_UP"
            } else if (clean.contains("decreas")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Volume(0, isRelative = true, delta = -1)
                matchSource = "ASSISTANT_VOLUME_DOWN"
            } else if (clean.contains("mut")) {
                command = com.jarvis.assistant.automation.command.JarvisCommand.Volume(0)
                matchSource = "ASSISTANT_VOLUME_MUTE"
            }
        }

        // --- 4. Web Search: "Searching for [topic] on Google", "Searching google for [topic]" ---
        else if (clean.startsWith("searching for ") || clean.startsWith("searching google for ")) {
            val searchMatch = Regex("^(?:searching\\s+for\\s+|searching\\s+google\\s+for\\s+)(.+?)(?:\\s+(?:on\\s+google|in\\s+browser))?$").find(clean)
            if (searchMatch != null) {
                val query = searchMatch.groupValues[1].trim()
                if (query.isNotBlank() && query.length > 1) {
                    command = com.jarvis.assistant.automation.command.JarvisCommand.SearchWeb(query)
                    matchSource = "ASSISTANT_WEB_SEARCH"
                }
            }
        }

        // --- 5. YouTube Play: "Playing [song] on YouTube", "Playing your favorite song, [song], on YouTube" ---
        else if (clean.startsWith("playing ")) {
            val ytMatch = Regex("^(?:playing\\s+(?:your\\s+favou?rite\\s+song,\\s+)?)(.+?)(?:\\s*,?\\s*on\\s+youtube)?$").find(clean)
            if (ytMatch != null) {
                val song = ytMatch.groupValues[1].trim().trimEnd(',', '!')
                if (song.isNotBlank()) {
                    val finalSong = if (song.equals("music", ignoreCase = true) || song.equals("video", ignoreCase = true) ||
                        song.equals("a video", ignoreCase = true) || song.equals("a song", ignoreCase = true)) {
                        (application as? JarvisApp)?.memoryManager?.resolveSongQuery("my favorite song") ?: "my favorite song"
                    } else {
                        song
                    }
                    command = com.jarvis.assistant.automation.command.JarvisCommand.PlayYouTube(finalSong)
                    matchSource = "ASSISTANT_YOUTUBE_PLAY"
                }
            }
        }

        // --- 5b. YouTube Subscribe: "Subscribing to this channel!" ---
        else if (clean.startsWith("subscribing")) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.SubscribeChannel
            matchSource = "ASSISTANT_YOUTUBE_SUBSCRIBE"
        }

        // --- 5c. YouTube Fullscreen: "Switching to full screen!" ---
        else if (clean.contains("full screen") || clean.contains("fullscreen")) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.ToggleFullscreen
            matchSource = "ASSISTANT_YOUTUBE_FULLSCREEN"
        }

        // --- 5d. WhatsApp Delete: "Deleting the last message for everyone!" / "Deleting the last message!" ---
        else if (clean.startsWith("deleting")) {
            val forEveryone = clean.contains("everyone")
            val contactMatch = Regex("(?:to|for|from)\\s+([a-zA-Z0-9\\s]+?)(?:\\s+(?:for|from)\\s+everyone)?$").find(clean)
            val contact = contactMatch?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotBlank() && it != "everyone" && it != "me" }
            command = com.jarvis.assistant.automation.command.JarvisCommand.WhatsAppDeleteMessage(contact, forEveryone)
            matchSource = "ASSISTANT_WHATSAPP_DELETE"
        }

        // --- 6. WhatsApp Message: "Sending WhatsApp message to [Contact]: [Message]" ---
        else if (clean.startsWith("sending whatsapp message to ") || clean.startsWith("sending message to ")) {
            val waMatch = Regex("^(?:sending\\s+whatsapp\\s+message\\s+to\\s+|sending\\s+message\\s+to\\s+)([a-zA-Z0-9\\s]+?):\\s*(.+)$", RegexOption.IGNORE_CASE).find(rawClean)
            if (waMatch != null) {
                val contact = waMatch.groupValues[1].trim()
                val message = waMatch.groupValues[2].trim()
                if (contact.isNotBlank() && message.isNotBlank()) {
                    // Security: Do NOT auto-send from assistant text. Stage draft and require human confirmation.
                    app?.whatsAppAssistant?.createDraft(contact, message)
                    com.jarvis.assistant.security.PendingSensitiveActionManager.stageAction(
                        com.jarvis.assistant.security.SensitiveActionType.WHATSAPP_SEND,
                        contact,
                        null,
                        message
                    )
                    announce("Sir, I have prepared that WhatsApp message for $contact. Please confirm if I should send it.")
                    return
                }
            }
        }

        // --- 7. Website Navigation: "Opening [url] in browser", "Opening website [url]" ---
        else if (clean.startsWith("opening ") && (clean.contains("in browser") || clean.contains("in chrome") || clean.contains("website") || clean.contains(".com") || clean.contains(".org") || clean.contains(".dev") || clean.contains(".in") || clean.contains(".net") || clean.contains(".io"))) {
            val urlMatch = Regex("^(?:opening\\s+website\\s+|opening\\s+)(.+?)(?:\\s+in\\s+browser|\\s+in\\s+chrome|\\s+website)?$").find(clean)
            if (urlMatch != null) {
                val target = urlMatch.groupValues[1].trim()
                val normalizedUrl = com.jarvis.assistant.automation.phone.DomainNormalizer.normalizeToUrl(target)
                command = com.jarvis.assistant.automation.command.JarvisCommand.OpenWebUrls(listOf(normalizedUrl))
                matchSource = "ASSISTANT_OPEN_WEBSITE"
            }
        }

        // --- 8. Phone / WhatsApp Call: "Calling [Contact]", "Calling [Contact] on WhatsApp" ---
        else if (clean.startsWith("calling ")) {
            val callMatch = Regex("^calling\\s+([a-zA-Z0-9\\s+]+?)(?:\\s+on\\s+whatsapp)?$").find(clean)
            if (callMatch != null) {
                val target = callMatch.groupValues[1].trim()
                if (target.isNotBlank() && target.length >= 2 && !listOf("it", "that", "you", "someone").contains(target)) {
                    val isWa = clean.contains("whatsapp")
                    com.jarvis.assistant.security.PendingSensitiveActionManager.stageAction(
                        if (isWa) com.jarvis.assistant.security.SensitiveActionType.WHATSAPP_CALL else com.jarvis.assistant.security.SensitiveActionType.PHONE_CALL,
                        target,
                        null,
                        if (isWa) "voice" else "phone_call"
                    )
                    announce("Sir, would you like me to call $target${if (isWa) " on WhatsApp" else ""}? Say yes to place the call.")
                    return
                }
            }
        }

        // --- 9. App Launch: "Opening [App Name]" (fallback if not a website) ---
        else if (clean.startsWith("opening ")) {
            val appMatch = Regex("^opening\\s+([a-zA-Z0-9\\s]+?)(?:\\s+app)?$").find(clean)
            if (appMatch != null) {
                val app = appMatch.groupValues[1].trim()
                if (app.isNotBlank() && !listOf("website", "tab", "browser", "chrome").contains(app)) {
                    command = com.jarvis.assistant.automation.command.JarvisCommand.OpenApp(app)
                    matchSource = "ASSISTANT_OPEN_APP"
                }
            }
        }

        // --- 10. Background Transition: "Going to the background", "I am going to the background" ---
        else if (clean.contains("going to the background") || clean.contains("going in the background") || clean.contains("going to background")) {
            command = com.jarvis.assistant.automation.command.JarvisCommand.GoToBackground
            matchSource = "ASSISTANT_BACKGROUND"
        }

        // --- 11. Birthday / Memory Save Fallback ---
        else if (clean.contains("recorded your birthday") || clean.contains("saved your birthday") || clean.contains("birthday recorded") || clean.contains("birthday saved")) {
            val userText = currentTurnUserText.toString().trim()
            val intent = com.jarvis.assistant.data.memory.MemoryIntentParser.parse(userText)
            if (intent is com.jarvis.assistant.data.memory.MemoryIntent.SaveBirthday) {
                app?.let {
                    serviceScope.launch {
                        it.memoryManager.saveBirthday(intent.normalizedDate, intent.wishRequested, userText)
                    }
                }
            }
        }

        if (command != null) {
            lastHandledAssistantCommand = clean
            Log.i("JarvisService", "ASSISTANT TEXT → Detected hardware command [$matchSource]: $command")
            executeDetectedCommand(command, "assistant: $clean")
        }
    }

    private fun executeDetectedCommand(command: com.jarvis.assistant.automation.command.JarvisCommand, source: String) {
        if (command is com.jarvis.assistant.automation.command.JarvisCommand.GoToBackground) {
            minimizeToHomeScreen()
            enterStandby(announce = false)
            return
        }
        hasExecutedCommandInCurrentTurn = true
        lastCommandExecutionTime = System.currentTimeMillis()
        suppressAudioUntilTurnComplete = false
        if (command is com.jarvis.assistant.automation.command.JarvisCommand.PlayYouTube) {
            audioPlayer?.flush()
        }
        val app = application as? JarvisApp ?: return

        serviceScope.launch {
            try {
                val result = app.commandExecutor.execute(command)
                Log.i("JarvisService", "Command executed successfully: $result (source: $source)")
                app.memoryManager.addShortTermTurn(source, result)
                if (command is com.jarvis.assistant.automation.command.JarvisCommand.Remember ||
                    command is com.jarvis.assistant.automation.command.JarvisCommand.SaveBirthday ||
                    command is com.jarvis.assistant.automation.command.JarvisCommand.AmbiguousMemoryDate ||
                    command is com.jarvis.assistant.automation.command.JarvisCommand.Forget ||
                    command is com.jarvis.assistant.automation.command.JarvisCommand.WhatDoYouRemember ||
                    command is com.jarvis.assistant.automation.command.JarvisCommand.RecallBirthday ||
                    command is com.jarvis.assistant.automation.command.JarvisCommand.RecallFavoriteSong ||
                    command is com.jarvis.assistant.automation.command.JarvisCommand.RecallRoutine) {
                    announce(result)
                }
            } catch (e: Exception) {
                Log.e("JarvisService", "Command execution FAILED: ${e.message}", e)
                announce("I encountered an error executing that command, Sir: ${e.message}")
            }
        }
    }

    private fun finalizeTurn() {
        val rawReply = currentTurnAssistantText.toString().trim()
        var reply = rawReply.replace(Regex("\\s+"), " ")
        val rawQuery = currentTurnUserText.toString().trim()
        val query = rawQuery.replace(Regex("\\s+"), " ")
        val app = application as? JarvisApp

        if (reply.contains("Rehaan", ignoreCase = true) || reply.contains("Rehan", ignoreCase = true)) {
            reply = reply.replace(Regex("Reha?an\\s+Sir", RegexOption.IGNORE_CASE), "Pradip Sir")
        }

        if (PromptGenerator.isDirectCreatorQuestion(query)) {
            val official = PromptGenerator.resolveCreatorResponse(query, preferences.language)
            if (official != null) {
                reply = official
            }
        } else if (PromptGenerator.isPradipSirInquiry(query)) {
            val official = PromptGenerator.resolveCreatorResponse(query, preferences.language)
            if (official != null) {
                reply = official
            }
        }

        if (query.isNotEmpty()) {
            app?.memoryManager?.learnPreferencesFromConversation(query)
        }

        if (reply.isNotEmpty()) {
            lastFinalizedAssistantText = reply
        }

        val turnToComplete = currentTurnId
        currentTurnId = null

        if (reply.isNotEmpty() && app != null) {
            val isOwnerCheckPassed = !preferences.isOwnerVoiceEnabled || !preferences.hasOwnerVoiceProfile() || isCurrentTurnOwnerVoiceVerified
            if (isOwnerCheckPassed) {
                if (turnToComplete != null) {
                    app.chatRepository.updateAssistantResponse(turnToComplete, reply, isComplete = true)
                    app.memoryManager.addShortTermTurn(
                        userText = query.ifEmpty { "User query" },
                        jarvisText = reply
                    )
                } else if (!hasExecutedCommandInCurrentTurn && (query.isNotEmpty() || reply.isNotEmpty())) {
                    app.chatRepository.addTurn(
                        userText = query,
                        jarvisText = reply
                    )
                    app.memoryManager.addShortTermTurn(
                        userText = query.ifEmpty { "User query" },
                        jarvisText = reply
                    )
                }
            }
        } else if (turnToComplete != null) {
            app?.chatRepository?.updateAssistantResponse(turnToComplete, "", isComplete = true)
        }
        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        _liveUserTranscript.value = ""
        _liveAssistantResponse.value = ""
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false
        consecutiveVoicedFrames = 0
        totalVoicedFramesInCurrentUtterance = 0
        hasExecutedCommandInCurrentTurn = false
        lastHandledCommandText = ""
        lastHandledAssistantCommand = ""
        val prefs = (application as? JarvisApp)?.preferences
        val isLeaseActive = (System.currentTimeMillis() - lastOwnerVerifiedTimestamp) < 30_000L
        isCurrentTurnOwnerVoiceVerified = prefs == null || !prefs.isOwnerVoiceEnabled || !prefs.hasOwnerVoiceProfile() || isLeaseActive
        pendingToolCalls.clear()
        synchronized(currentUtteranceAudioStream) { currentUtteranceAudioStream.reset() }
    }

    private fun stopActiveConversation() {
        idleController.stopTimer()
        inactivityJob?.cancel()
        inactivityJob = null
        cancelAllActiveTools()
        com.jarvis.assistant.automation.agent.AgentTaskEngine.resetForSession()
        _isSessionOn.value = false
        finalizeTurn()


        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        preConnectAudioQueue.clear()
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false

        audioRecorder?.stop()
        audioRecorder = null

        audioPlayer?.flush()
        audioPlayer?.release()
        audioPlayer = null

        liveWebSocket?.disconnect()
        liveWebSocket = null

        setConversationState(ConversationState.IDLE)
        _connectionStatus.value = "READY"
        _audioLevel.value = 0f
        headsetAudioRouter?.stop()
    }

    // ---------------------------------------------------------------------
    // Standby / wake-word listening (Pure Vosk - Continuous Streaming)
    // ---------------------------------------------------------------------

    private fun enterStandby(announce: Boolean = false) {
        if (preferences.isManualOff || _isStandby.value) return
        abandonAudioDuckFocus()
        isPendingBackgroundTransition = false
        idleController.stopTimer()
        stopActiveConversation()
        currentTurnAssistantText.clear()
        currentTurnUserText.clear()
        hasSpeechInCurrentUtterance = false
        hasSignalledUtteranceEnd = false
        _isStandby.value = true
        _conversationState.value = ConversationState.STANDBY
        standbyActivatedTime = System.currentTimeMillis()
        updateNotification()

        val standbyToken = lifecycleToken
        serviceScope.launch(Dispatchers.Main) {
            delay(500)
            if (standbyToken == lifecycleToken && !preferences.isManualOff &&
                _isStandby.value && voskSpeechService == null) {
                startWakeListening()
            }
        }
    }

    private fun exitStandbyListening() {
        _isStandby.value = false
        try {
            voskSpeechService?.stop()
        } catch (e: Exception) {
            Log.e("JarvisService", "Error stopping Vosk SpeechService: ${e.message}")
        }
        voskSpeechService = null
    }

    private fun startWakeListening() {
        if (preferences.isManualOff || !_isStandby.value) return
        if (voskSpeechService != null) return

        val model = VoskModelManager.model
        if (model == null) {
            Log.i("JarvisService", "Vosk model still loading; triggering init and will start once ready")
            VoskModelManager.init(applicationContext, serviceScope)
            serviceScope.launch {
                VoskModelManager.isModelReady.collect { ready ->
                    if (ready && !preferences.isManualOff && _isStandby.value && voskSpeechService == null) {
                        startWakeListening()
                    }
                }
            }
            return
        }

        try {
            standbyActivatedTime = System.currentTimeMillis()
            // Build grammar from the user-configured assistant name and off phrases.
            val grammar = buildVoskGrammar(preferences.assistantName)
            val rec = Recognizer(model, 16000.0f, grammar)
            rec.setWords(true)
            val spkModel = VoskModelManager.speakerModel
            if (spkModel != null) {
                rec.setSpeakerModel(spkModel)
                Log.i("JarvisService", "Attached SpeakerModel to Vosk wake recognizer for owner voice gating")
            }
            val service = SpeechService(rec, 16000.0f)
            service.startListening(voskListener)
            voskSpeechService = service
            Log.i("JarvisService", "Vosk wake-word listening active (strict multi-syllable phrase grammar)")
        } catch (e: Exception) {
            Log.e("JarvisService", "Failed to start Vosk SpeechService: ${e.message}", e)
            try {
                voskSpeechService?.stop()
            } catch (_: Exception) {}
            voskSpeechService = null
            serviceScope.launch { _events.emit("Mic error: ${e.message}") }
        }
    }

    private val voskListener = object : VoskRecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (hypothesis.isNullOrBlank() || !_isStandby.value || _isManualOff.value) return
            try {
                val json = JSONObject(hypothesis)
                if (!json.has("partial")) return
                val text = json.getString("partial").trim()
                if (text.isEmpty()) return
                val currentName = preferences.assistantName
                if (isManualOffCommand(text, currentName)) {
                    Log.i("JarvisService", "HARD OFF detected in Vosk PARTIAL hypothesis: '$text'")
                    triggerManualOff()
                }
            } catch (_: Exception) {}
        }

        override fun onResult(hypothesis: String?) {
            checkVoskHypothesis(hypothesis)
        }

        override fun onFinalResult(hypothesis: String?) {
            checkVoskHypothesis(hypothesis)
        }

        override fun onError(exception: Exception?) {
            Log.e("JarvisService", "Vosk error: ${exception?.message}")
            if (_isStandby.value) {
                serviceScope.launch(Dispatchers.Main) {
                    delay(1500)
                    if (_isStandby.value && voskSpeechService == null) {
                        startWakeListening()
                    }
                }
            }
        }

        override fun onTimeout() {
            if (_isStandby.value && voskSpeechService == null) {
                startWakeListening()
            }
        }
    }

    private fun validWakePhrases(): Set<String> {
        val rawName = preferences.assistantName.trim()
        val aliases = getWakeAliasesForName(rawName)
        val set = mutableSetOf<String>()
        for (alias in aliases) {
            val a = alias.lowercase(Locale.ROOT)
            set.add(a)
            set.add("hey $a")
            set.add("hi $a")
            set.add("hello $a")
            set.add("ok $a")
            set.add("okay $a")
            set.add("wake up $a")
            set.add("suno $a")
            set.add("$a $a")
        }
        return set
    }

    private fun checkVoskHypothesis(hypothesisJson: String?) {
        if (hypothesisJson.isNullOrBlank() || !_isStandby.value) return
        if (isPhoneCallActive()) return

        // 1. Standby cooldown: ignore microphone input for 800ms after entering standby
        // to prevent ambient chimes or trailing speech from falsely re-triggering wake.
        if (System.currentTimeMillis() - standbyActivatedTime < 800L) {
            Log.d("JarvisService", "Ignored hypothesis during standby cooldown")
            return
        }

        try {
            val json = JSONObject(hypothesisJson)
            val text = json.optString("text", "").trim().lowercase(Locale.ROOT)
            if (text.isEmpty() || text == "[unk]") return

            val currentName = preferences.assistantName
            // CRITICAL STEP 1: Check HARD OFF FIRST before wake words!
            if (isManualOffCommand(text, currentName)) {
                Log.i("JarvisService", "HARD OFF detected in Vosk hypothesis: '$text'")
                triggerManualOff()
                return
            }

            // Word-level array must be present: never wake blindly without confidence verification.
            val resultArr = json.optJSONArray("result")
            if (resultArr == null || resultArr.length() == 0) {
                Log.d("JarvisService", "WAKE REJECTED: No word-level result array for '$text'. Ignored.")
                return
            }

            var totalConf = 0.0
            val wordList = mutableListOf<String>()
            val wordConfMap = mutableMapOf<String, Double>()
            for (i in 0 until resultArr.length()) {
                val wObj = resultArr.getJSONObject(i)
                val conf = wObj.optDouble("conf", 0.0)
                val w = wObj.optString("word", "").trim().lowercase(Locale.ROOT)
                if (w.isNotEmpty() && w != "[unk]") {
                    totalConf += conf
                    wordList.add(w)
                    wordConfMap[w] = maxOf(wordConfMap[w] ?: 0.0, conf)
                }
            }

            if (wordList.isEmpty()) return

            val cleanPhrase = wordList.joinToString(" ")
            if (isManualOffCommand(cleanPhrase, currentName)) {
                Log.i("JarvisService", "HARD OFF detected in Vosk clean phrase: '$cleanPhrase'")
                triggerManualOff()
                return
            }

            val wakeName = preferences.assistantName.trim().lowercase(Locale.ROOT)
            val validWakePhrases = validWakePhrases()
            val isMatch = cleanPhrase in validWakePhrases ||
                    validWakePhrases.any { cleanPhrase.contains(it) } ||
                    wordList.contains(wakeName)

            if (!isMatch) {
                Log.d("JarvisService", "WAKE REJECTED: '$cleanPhrase' is not an exact valid wake phrase.")
                return
            }

            val avgConf = totalConf / wordList.size
            val firstStart = resultArr.getJSONObject(0).optDouble("start", 0.0)
            val lastEnd = resultArr.getJSONObject(resultArr.length() - 1).optDouble("end", 0.0)
            val duration = lastEnd - firstStart

            Log.i("JarvisService", "Vosk candidate hypothesis: '$cleanPhrase' (words=$wordList, avgConf=$avgConf, duration=${duration}s)")

            // 4. Acoustic Verification:
            // 4. Acoustic Verification:
            // The configured assistant name must be specifically recognized with confidence.
            val wakeNameConf = wordConfMap[wakeName] ?: wordConfMap.values.maxOrNull() ?: 0.0
            if (wakeNameConf < 0.50) {
                Log.w("JarvisService", "WAKE REJECTED: '$wakeName' confidence too low ($wakeNameConf < 0.50). Ignored.")
                return
            }

            if (wordList.size == 1 && wordList[0] == wakeName) {
                // Standalone configured wake name
                if (avgConf < 0.55) {
                    Log.w("JarvisService", "WAKE REJECTED: Standalone '$wakeName' failed checks (conf=$avgConf). Ignored.")
                    return
                }
            } else {
                // Multi-word phrases like "hey jarvis", "hello jarvis", "ok jarvis"
                if (avgConf < 0.50) {
                    Log.w("JarvisService", "WAKE REJECTED: Wake phrase failed checks (conf=$avgConf). Ignored.")
                    return
                }
            }

            // 5. Owner Voice Verification (if enabled)
            if (preferences.isOwnerVoiceEnabled && preferences.hasOwnerVoiceProfile()) {
                val spkArray = json.optJSONArray("spk")
                if (spkArray != null && spkArray.length() > 0) {
                    val currentVector = FloatArray(spkArray.length()) { i -> spkArray.getDouble(i).toFloat() }
                    val match = com.jarvis.assistant.audio.OwnerVoiceManager.verifySpeakerVector(currentVector, preferences)
                    if (!match.isMatch) {
                        Log.w("JarvisService", "WAKE REJECTED: Heard '$cleanPhrase', but speaker distance (${match.distance}) > threshold (${match.threshold}). Non-owner ignored!")
                        return
                    } else {
                        lastOwnerVerifiedTimestamp = System.currentTimeMillis()
                        isCurrentTurnOwnerVoiceVerified = true
                        Log.i("JarvisService", "WAKE VERIFIED: Owner voice confirmed for '$cleanPhrase'! (Distance: ${match.distance}, Similarity: ${match.similarityPercent}%)")
                    }
                } else {
                    Log.w("JarvisService", "WAKE REJECTED: No speaker vector found in hypothesis for '$cleanPhrase' while owner voice is enabled. Non-owner ignored!")
                    return
                }
            }

            Log.i("JarvisService", "Vosk confirmed wake word: '$cleanPhrase' (conf=$avgConf, duration=${duration}s)")
            onWakeWordDetected()
        } catch (e: Exception) {
            Log.e("JarvisService", "Error in checkVoskHypothesis: ${e.message}")
        }
    }

    private var currentFocusRequest: Any? = null

    private fun requestAudioDuckFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(JarvisAudioPolicy.PLAYBACK_USAGE)
                            .setContentType(JarvisAudioPolicy.PLAYBACK_CONTENT_TYPE)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                Log.i("JarvisService", "AudioFocus: permanent loss")
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                Log.i("JarvisService", "AudioFocus: transient loss/duck (preserving speech playback)")
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                Log.i("JarvisService", "AudioFocus: gained")
                            }
                        }
                    }
                    .build()
                currentFocusRequest = focusReq
                audioManager.requestAudioFocus(focusReq)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    { /* transient ducking listener */ },
                    JarvisAudioPolicy.TARGET_VOLUME_STREAM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.w("JarvisService", "Failed to request audio duck focus: ${e.message}")
        }
    }

    private fun abandonAudioDuckFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (currentFocusRequest as? AudioFocusRequest)?.let {
                    audioManager.abandonAudioFocusRequest(it)
                }
                currentFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    private fun onWakeWordDetected() {
        if (preferences.isManualOff || !_isStandby.value) return
        wakeUp(WakeReason.VOICE_WAKE)
    }

    private fun vibrateBriefly() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(40)
            }
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------------
    // Lifecycle plumbing
    // ---------------------------------------------------------------------

    private fun fullStop() {
        // Invalidate delayed standby starts and callbacks before releasing resources.
        lifecycleToken++
        idleController.stopTimer()
        inactivityJob?.cancel()
        inactivityJob = null
        stopActiveConversation()
        exitStandbyListening()
        _isSessionOn.value = false
        _isStandby.value = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun triggerManualOff() {
        if (_isManualOff.value && _conversationState.value == ConversationState.OFF) {
            Log.d("JarvisService", "Already in manual OFF, ignoring duplicate trigger")
            return
        }
        Log.i("JarvisService", "triggerManualOff() -> entering hard OFF")
        preferences.isManualOff = true
        _isManualOff.value = true
        _isSessionOn.value = false
        _isStandby.value = false

        cancelAllActiveTools()
        fullStop()
        _conversationState.value = ConversationState.OFF
        _connectionStatus.value = "OFF"
        _audioLevel.value = 0f
    }

    fun manualOn(startConversation: Boolean = false) {
        Log.i("JarvisService", "manualOn(startConversation=$startConversation)")
        preferences.isManualOff = false
        _isManualOff.value = false
        ensureMicrophoneForegroundService()
        acquireWakeLock()
        lifecycleToken++
        if (startConversation) {
            beginActiveConversation()
        } else {
            enterStandby(announce = false)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:JarvisConversation")
                .also { it.acquire(6 * 60 * 60 * 1000L) }
        }
        if (wifiLock?.isHeld != true) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            wifiLock = wifiManager?.createWifiLock(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                },
                "$packageName:JarvisWifi"
            )?.also { it.acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    override fun onDestroy() {
        lifecycleToken++
        taskProgressOverlayJob?.cancel()
        taskProgressOverlayJob = null
        taskProgressOverlay?.remove()
        taskProgressOverlay = null
        try {
            unregisterReceiver(volumeChangeReceiver)
        } catch (_: Exception) {}
        stopActiveConversation()
        exitStandbyListening()
        try {
            voskSpeechService?.stop()
        } catch (_: Exception) {}
        voskSpeechService = null
        releaseWakeLock()
        serviceScope.cancel()
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Command and Call Helpers
    // ---------------------------------------------------------------------

    private fun isExplicitStandbyCommand(text: String): Boolean {
        val clean = text.lowercase(Locale.ROOT).trim()
        val patterns = listOf(
            "go to background", "go background", "go in background", "go in the background",
            "background me jao", "background mein jao", "send to background", "move to background",
            "minimize", "go home", "go to home", "go to home screen",
            "go to sleep", "sleep jarvis", "enter standby", "standby mode", "go to standby",
            "take a break", "so jao"
        )
        return patterns.any { containsPhrase(clean, it) }
    }

    /** Order-independent argument signature (identical calls arrive with JSON keys in different order). */
    private fun normalizedArgsSignature(args: JSONObject): String {
        val keys = mutableListOf<String>()
        val iter = args.keys()
        while (iter.hasNext()) keys.add(iter.next())
        keys.sort()
        return keys.joinToString(",") { k -> "$k=${args.opt(k)}" }
    }

    /** Whole-word/phrase match (so "stop" does not match "stopped"). */
    private fun containsPhrase(text: String, phrase: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(phrase) + "(?![\\p{L}\\p{N}])").containsMatchIn(text)

    private fun minimizeToHomeScreen() {
        val app = application as? JarvisApp
        val movedBack = app?.moveAppToBack() == true
        if (movedBack) {
            Log.i("JarvisService", "JARVIS foreground activity moved to back safely")
        } else {
            Log.i("JarvisService", "JARVIS going to background standby; leaving active foreground app intact")
        }
    }

    private fun isPhoneCallActive(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (audioManager.mode == AudioManager.MODE_IN_CALL) return true
        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            if (telephonyManager != null && telephonyManager.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (preferences.isManualOff) {
            Log.i("JarvisService", "onTaskRemoved while manually OFF; not restarting microphone or standby")
            fullStop()
            return
        }
        Log.i("JarvisService", "onTaskRemoved: App removed from recents. Keeping service alive in foreground.")
        acquireWakeLock()
        ensureMicrophoneForegroundService()
        updateNotification()
        if (!_isSessionOn.value && !_isStandby.value) enterStandby(announce = false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val currentName = preferences.assistantName.ifBlank { "Jarvis" }
        val (title, text) = when {
            _isManualOff.value || _conversationState.value == ConversationState.OFF ->
                "$currentName is OFF" to "Tap to open and activate $currentName"
            _conversationState.value == ConversationState.EXECUTING ->
                "$currentName is executing" to "Performing requested action"
            _isStandby.value ->
                "$currentName is in the background" to "Say \"Hey $currentName\" to wake me up"
            _isSessionOn.value && !_connectionStatus.value.equals("LIVE", ignoreCase = true) ->
                "$currentName is reconnecting" to "Connection: ${_connectionStatus.value}. Requests may not be heard yet."
            _isSessionOn.value && lastResponseTimedOut ->
                "$currentName did not reply" to "Connection is active. Repeat your request."
            _isSessionOn.value && _conversationState.value == ConversationState.SPEAKING ->
                "$currentName is replying" to "Audio response is playing"
            _isSessionOn.value && _conversationState.value == ConversationState.THINKING ->
                "$currentName is thinking" to "Processing your request"
            _isSessionOn.value && _conversationState.value == ConversationState.LISTENING ->
                "$currentName is listening" to "Speak your request now"
            _isSessionOn.value ->
                "$currentName is ready" to "Connected. Say your request or \"Hey $currentName\"."
            else -> currentName to "Starting up\u2026"
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isOff = _isManualOff.value || _conversationState.value == ConversationState.OFF
        val actionText = if (isOff) "Start" else "Stop"
        val actionIntent = PendingIntent.getService(
            this, 0,
            Intent(this, JarvisConversationService::class.java).setAction(if (isOff) ACTION_MANUAL_ON else ACTION_MANUAL_OFF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(!isOff)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, actionText, actionIntent)
            .build()
    }
}
