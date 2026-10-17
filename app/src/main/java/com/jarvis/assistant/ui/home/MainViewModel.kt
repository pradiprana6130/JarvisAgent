package com.jarvis.assistant.ui.home

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.model.Conversation
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * This ViewModel is intentionally "thin": the microphone, the Gemini Live
 * socket, and the standby/wake-word listener all live inside
 * [JarvisConversationService], not here. This class only mirrors that
 * service's state for the UI and forwards user taps as control intents.
 *
 * That split matters: when the app is removed from Recents, Android destroys
 * the Activity and this ViewModel - if the live conversation lived here, it
 * would die with it. Because it lives in the foreground service instead, the
 * conversation (or standby listening) keeps running untouched, and whenever
 * the UI comes back it just resumes observing whatever the service is doing.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences: AppPreferences = (application as JarvisApp).preferences

    val isSessionOn: StateFlow<Boolean> = JarvisConversationService.isSessionOn
    val isManualOff: StateFlow<Boolean> = JarvisConversationService.isManualOff
    val conversationState: StateFlow<ConversationState> = JarvisConversationService.conversationState
    val audioLevel: StateFlow<Float> = JarvisConversationService.audioLevel
    val audioFrequency: StateFlow<com.jarvis.assistant.audio.AudioFrequencyData> = JarvisConversationService.audioFrequency
    val liveUserTranscript: StateFlow<String> = JarvisConversationService.liveUserTranscript
    val liveAssistantResponse: StateFlow<String> = JarvisConversationService.liveAssistantResponse
    val liveError: StateFlow<com.jarvis.assistant.background.JarvisLiveError?> = JarvisConversationService.liveError
    val chatTurns: StateFlow<List<ChatTurn>> = (application as JarvisApp).chatRepository.turns
    val conversations: StateFlow<List<Conversation>> = (application as JarvisApp).chatRepository.conversations
    val activeConversationId: StateFlow<String?> = (application as JarvisApp).chatRepository.activeConversationId

    fun selectConversation(id: String) {
        (getApplication() as JarvisApp).chatRepository.selectConversation(id)
    }

    fun startNewConversation() {
        (getApplication() as JarvisApp).chatRepository.startNewConversation()
    }

    fun deleteConversation(id: String) {
        (getApplication() as JarvisApp).chatRepository.deleteConversation(id)
    }

    fun clearAllChatHistory() {
        (getApplication() as JarvisApp).chatRepository.clearHistory()
    }

    fun clearLiveError() {
        JarvisConversationService.clearLiveError()
    }

    private val _isMicMuted = MutableStateFlow(preferences.isMicMuted)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    // Surfaces "STANDBY" whenever the service is listening only for the wake
    // word, otherwise mirrors the service's live connection status.
    private val _connectionStatus = MutableStateFlow("READY")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _liveTime = MutableStateFlow("")
    val liveTime: StateFlow<String> = _liveTime.asStateFlow()

    private val _personalityName = MutableStateFlow(preferences.personality)
    val personalityName: StateFlow<String> = _personalityName.asStateFlow()

    private val _eventFlow = MutableSharedFlow<String>()
    val eventFlow: SharedFlow<String> = _eventFlow.asSharedFlow()

    private var timeClockJob: Job? = null

    init {
        startTimeClock()
        observeServiceStatus()
        observeServiceEvents()
    }

    private fun startTimeClock() {
        timeClockJob = viewModelScope.launch(Dispatchers.Default) {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            while (isActive) {
                _liveTime.value = sdf.format(Date())
                delay(1000)
            }
        }
    }

    private fun observeServiceStatus() {
        viewModelScope.launch {
            combine(
                JarvisConversationService.isStandby,
                JarvisConversationService.connectionStatus
            ) { standby, liveStatus -> if (standby) "IDLE" else liveStatus }
                .collect { _connectionStatus.value = it }
        }
    }

    private fun observeServiceEvents() {
        viewModelScope.launch {
            JarvisConversationService.events.collect { message -> _eventFlow.emit(message) }
        }
    }

    fun refreshSettings() {
        _personalityName.value = preferences.personality
        _isMicMuted.value = preferences.isMicMuted
        JarvisConversationService.setMicMuted(preferences.isMicMuted)
    }

    fun turnOffSession() {
        sendServiceAction(JarvisConversationService.ACTION_MANUAL_OFF)
    }

    fun toggleSession() {
        if (JarvisConversationService.isSessionOn.value && !JarvisConversationService.isManualOff.value) {
            turnOffSession()
        } else {
            val context = getApplication<Application>()
            if (!PermissionHelper.hasAllRequiredPermissions(context)) {
                viewModelScope.launch {
                    _eventFlow.emit("Please allow all permissions in Settings to activate Jarvis.")
                }
                return
            }
            val apiKey = preferences.apiKey.trim()
            if (apiKey.isBlank()) {
                viewModelScope.launch {
                    _eventFlow.emit("Please configure your Gemini API Key in Settings first.")
                }
                return
            }
            sendServiceAction(JarvisConversationService.ACTION_MANUAL_ON)
        }
    }

    fun toggleMicMute() {
        val newMuted = !_isMicMuted.value
        _isMicMuted.value = newMuted
        preferences.isMicMuted = newMuted
        JarvisConversationService.setMicMuted(newMuted)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(getApplication<Application>(), JarvisConversationService::class.java)
            .setAction(action)
        ContextCompat.startForegroundService(getApplication<Application>(), intent)
    }

    override fun onCleared() {
        super.onCleared()
        timeClockJob?.cancel()
        // Deliberately NOT stopping JarvisConversationService here. The
        // Activity/ViewModel being torn down (e.g. the app swiped away from
        // Recents) must not stop the mic or the live conversation - that's
        // the whole point of owning them in the service instead of here.
    }
}
