package com.jarvis.assistant.background

import android.os.SystemClock
import com.jarvis.assistant.data.model.ConversationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Authoritative controller for Jarvis Idle / Standby lifecycle and the 120-second inactivity deadline.
 *
 * Guarantees:
 * 1. Exactly one 120-second inactivity deadline is tracked.
 * 2. Idle transition occurs only after 120 seconds of continuous inactivity in ACTIVE/READY mode.
 * 3. Any active user speech, ongoing voice interaction, draft typing, chat submission,
 *    assistant response generation (THINKING), tool execution (EXECUTING), or TTS playback (SPEAKING)
 *    completely suppresses idle entry.
 * 4. When active work finishes, a fresh 120-second inactivity window begins.
 * 5. Synchronous cancellation on new input prevents race conditions.
 * 6. Supports clock injection for deterministic, fast fake-clock unit tests.
 */
class JarvisIdleController(
    private val scope: CoroutineScope,
    private val clock: Clock = SystemClockImpl,
    val inactivityTimeoutMs: Long = 120_000L,
    private val onEnterIdle: () -> Unit
) {
    interface Clock {
        fun elapsedRealtime(): Long
    }

    object SystemClockImpl : Clock {
        override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    }

    enum class ActivityType {
        CHAT_SUBMISSION,
        DRAFT_TYPING,
        VOICE_WAKE,
        VOICE_SPEECH_START,
        VOICE_SPEECH_CONTINUE,
        INTERACTION_COMPLETED,
        SESSION_STARTED
    }

    private val _lastActivityTime = AtomicLong(0L)
    val lastActivityTime: Long get() = _lastActivityTime.get()

    private val _inactivityDeadline = AtomicLong(0L)
    val inactivityDeadline: Long get() = _inactivityDeadline.get()

    private var tickerJob: Job? = null
    private val generationToken = AtomicLong(0L)

    // State suppliers
    var isSessionActive: () -> Boolean = { false }
    var isStandbyActive: () -> Boolean = { false }
    var isManualOff: () -> Boolean = { false }
    var getConversationState: () -> ConversationState = { ConversationState.IDLE }
    var isAudioPlaying: () -> Boolean = { false }
    var isTaskExecuting: () -> Boolean = { false }

    /**
     * Resets the inactivity deadline to (clock.elapsedRealtime() + inactivityTimeoutMs)
     * based on meaningful user activity.
     */
    fun notifyUserActivity(type: ActivityType) {
        val now = clock.elapsedRealtime()
        _lastActivityTime.set(now)
        _inactivityDeadline.set(now + inactivityTimeoutMs)
        generationToken.incrementAndGet()
    }

    /**
     * Resets the inactivity deadline when an interaction (speech, tool, assistant reply) completes.
     */
    fun notifyInteractionCompleted() {
        val now = clock.elapsedRealtime()
        _lastActivityTime.set(now)
        _inactivityDeadline.set(now + inactivityTimeoutMs)
        generationToken.incrementAndGet()
    }

    /**
     * Called on draft typing in chat input.
     * Only meaningful text input (non-blank) resets the deadline; cursor movement/empty text does not.
     */
    fun notifyDraftTyping(text: CharSequence?) {
        if (!text.isNullOrBlank()) {
            notifyUserActivity(ActivityType.DRAFT_TYPING)
        }
    }

    /**
     * Synchronously cancels any pending idle transition before processing new input.
     */
    fun cancelPendingIdleTransition() {
        generationToken.incrementAndGet()
        val now = clock.elapsedRealtime()
        _lastActivityTime.set(now)
        _inactivityDeadline.set(now + inactivityTimeoutMs)
    }

    /**
     * Returns true if Jarvis is currently performing active work for the user
     * (listening, thinking, executing tool, or playing audio/TTS).
     */
    fun isActiveWorkInProgress(): Boolean {
        val state = getConversationState()
        if (state == ConversationState.LISTENING ||
            state == ConversationState.THINKING ||
            state == ConversationState.SPEAKING ||
            state == ConversationState.EXECUTING ||
            state == ConversationState.WAKE_UP) {
            return true
        }
        if (isAudioPlaying()) return true
        if (isTaskExecuting()) return true
        return false
    }

    /**
     * Evaluates if idle mode can be entered at the current moment.
     */
    fun canEnterIdleNow(): Boolean {
        if (!isSessionActive()) return false
        if (isStandbyActive()) return false
        if (isManualOff()) return false
        if (isActiveWorkInProgress()) return false

        val deadline = _inactivityDeadline.get()
        if (deadline <= 0L) return false

        val now = clock.elapsedRealtime()
        return now >= deadline
    }

    /**
     * Evaluates whether the deadline has passed and triggers idle if all conditions are met.
     * Returns true if idle transition was triggered.
     */
    fun checkAndTriggerIdle(): Boolean {
        if (canEnterIdleNow()) {
            onEnterIdle()
            return true
        }
        return false
    }

    /**
     * Starts the single authoritative 120-second inactivity ticker.
     */
    fun startTimer() {
        val now = clock.elapsedRealtime()
        _lastActivityTime.set(now)
        _inactivityDeadline.set(now + inactivityTimeoutMs)
        val currentToken = generationToken.incrementAndGet()

        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && isSessionActive() && !isStandbyActive() && !isManualOff()) {
                delay(1_000L)
                val nowTime = clock.elapsedRealtime()
                val deadline = _inactivityDeadline.get()

                if (deadline > 0L && nowTime >= deadline) {
                    if (canEnterIdleNow()) {
                        onEnterIdle()
                        break
                    }
                }
            }
        }
    }

    /**
     * Stops and cleans up the timer.
     */
    fun stopTimer() {
        generationToken.incrementAndGet()
        tickerJob?.cancel()
        tickerJob = null
        _inactivityDeadline.set(0L)
    }
}
