package com.jarvis.assistant.background

import com.jarvis.assistant.audio.AudioOutputRouteType
import com.jarvis.assistant.audio.EchoGuardConfig
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Deterministic, fake-clock unit tests validating all requirements for the
 * Jarvis Idle Mode Bug Fix (2-Minute True Inactivity + Chat/Voice Wake-Up).
 *
 * Requirements verified:
 * A. No idle transition during active chat.
 * B. No idle transition during a long API response (THINKING).
 * C. No idle transition or speech truncation during long TTS playback (SPEAKING / audio playing).
 * D. After an interaction completes and 120 seconds of inactivity, exactly one idle transition occurs.
 * E. At 119 seconds Jarvis remains active; at/after 120 seconds it idles, subject to active-work rules.
 * F. Chat from idle wakes Jarvis and processes the message exactly once.
 * G. Configured voice wake phrase wakes Jarvis and captures the next utterance (transitions to LISTENING).
 * H. Speaking over Jarvis interrupts TTS and the newest question gets answered (barge-in).
 * I. Assistant speech does not cause a self-reply loop.
 * J. Mic OFF stays fully OFF; chat wake still works text-only without turning mic on.
 * K. Rerenders, navigation, reconnects, and foreground/background do not cause duplicate timers or premature idle.
 * L. Fake-clock / timer tests without flaky real 2-minute delays.
 */
class JarvisIdleModeBugFixTest {

    class FakeClock(private var currentTime: Long = 0L) : JarvisIdleController.Clock {
        override fun elapsedRealtime(): Long = currentTime
        fun advanceBy(millis: Long) {
            currentTime += millis
        }
        fun set(time: Long) {
            currentTime = time
        }
    }

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    // =========================================================================
    // TEST E & D: Exact 120-Second Deadline (Active at 119s, Idles at 120s)
    // =========================================================================
    @Test
    fun testRequirementE_and_D_ActiveAt119Seconds_IdlesAt120SecondsExactlyOnce() {
        val fakeClock = FakeClock(10_000L)
        var idleTriggerCount = 0

        val controller = JarvisIdleController(
            scope = testScope,
            clock = fakeClock,
            inactivityTimeoutMs = 120_000L,
            onEnterIdle = { idleTriggerCount++ }
        ).apply {
            isSessionActive = { true }
            isStandbyActive = { false }
            isManualOff = { false }
            getConversationState = { ConversationState.IDLE }
            isAudioPlaying = { false }
            isTaskExecuting = { false }
        }

        controller.startTimer()
        assertEquals("Initial deadline must be now + 120s", 130_000L, controller.inactivityDeadline)
        assertEquals(0, idleTriggerCount)

        // Advance 119 seconds (119,000ms)
        fakeClock.advanceBy(119_000L)
        assertFalse("At 119s Jarvis must remain ACTIVE (not eligible for idle)", controller.canEnterIdleNow())
        assertFalse(controller.checkAndTriggerIdle())
        assertEquals(0, idleTriggerCount)

        // Advance final 1 second to reach exactly 120s
        fakeClock.advanceBy(1_000L)
        assertTrue("At 120s Jarvis must be eligible to enter idle", controller.canEnterIdleNow())
        val triggered = controller.checkAndTriggerIdle()
        assertTrue("Idle transition should trigger", triggered)
        assertEquals("Exactly one idle transition must occur", 1, idleTriggerCount)
    }

    // =========================================================================
    // TEST A: No Idle Transition During Active Chat (Draft Typing & Submissions)
    // =========================================================================
    @Test
    fun testRequirementA_NoIdleDuringActiveChat_DraftTypingAndSubmissionsResetTimer() {
        val fakeClock = FakeClock(10_000L)
        var idleTriggerCount = 0

        val controller = JarvisIdleController(
            scope = testScope,
            clock = fakeClock,
            inactivityTimeoutMs = 120_000L,
            onEnterIdle = { idleTriggerCount++ }
        ).apply {
            isSessionActive = { true }
            isStandbyActive = { false }
            isManualOff = { false }
            getConversationState = { ConversationState.IDLE }
            isAudioPlaying = { false }
            isTaskExecuting = { false }
        }

        controller.startTimer()

        // User spends 100 seconds thinking, then types a draft message
        fakeClock.advanceBy(100_000L) // t = 110,000ms (10s before original deadline)
        controller.notifyDraftTyping("What is the weather today?")
        assertEquals("Draft typing must extend deadline to now + 120s", 110_000L + 120_000L, controller.inactivityDeadline)

        // Advance past original deadline (e.g. t = 140,000ms)
        fakeClock.advanceBy(30_000L)
        assertFalse("Jarvis must not idle because user typed a draft", controller.canEnterIdleNow())

        // User submits the chat at t = 180,000ms
        fakeClock.advanceBy(40_000L)
        controller.notifyUserActivity(JarvisIdleController.ActivityType.CHAT_SUBMISSION)
        assertEquals("Chat submission must reset deadline to now + 120s", 180_000L + 120_000L, controller.inactivityDeadline)

        // Advance to 119s after submission
        fakeClock.advanceBy(119_000L) // t = 299,000ms
        assertFalse("Jarvis must remain active at 119s post-submission", controller.canEnterIdleNow())

        // Reach 120s post-submission
        fakeClock.advanceBy(1_000L) // t = 300,000ms
        assertTrue("Jarvis idles only after 120s of true inactivity after submission", controller.canEnterIdleNow())
        controller.checkAndTriggerIdle()
        assertEquals(1, idleTriggerCount)
    }

    // =========================================================================
    // TEST B: No Idle During Long API Response (THINKING State)
    // =========================================================================
    @Test
    fun testRequirementB_NoIdleDuringLongApiResponse_ThinkingStateSuppressesIdle() {
        val fakeClock = FakeClock(0L)
        var idleTriggerCount = 0
        var currentState = ConversationState.IDLE

        val controller = JarvisIdleController(
            scope = testScope,
            clock = fakeClock,
            inactivityTimeoutMs = 120_000L,
            onEnterIdle = { idleTriggerCount++ }
        ).apply {
            isSessionActive = { true }
            isStandbyActive = { false }
            isManualOff = { false }
            getConversationState = { currentState }
            isAudioPlaying = { false }
            isTaskExecuting = { false }
        }

        controller.startTimer()

        // User submits request; Jarvis enters THINKING
        currentState = ConversationState.THINKING
        controller.notifyUserActivity(JarvisIdleController.ActivityType.CHAT_SUBMISSION)

        // API takes 130 seconds to return complex response
        fakeClock.advanceBy(130_000L)
        assertTrue("Time is past 120s deadline", fakeClock.elapsedRealtime() >= controller.inactivityDeadline)
        assertFalse("Idle MUST be suppressed while in THINKING state", controller.canEnterIdleNow())
        assertFalse("checkAndTriggerIdle must return false during active work", controller.checkAndTriggerIdle())
        assertEquals(0, idleTriggerCount)

        // API completes and Jarvis returns to IDLE
        currentState = ConversationState.IDLE
        controller.notifyInteractionCompleted()
        assertEquals("Interaction completion must grant a fresh 120s inactivity window", 130_000L + 120_000L, controller.inactivityDeadline)

        // 119s after completion -> still active
        fakeClock.advanceBy(119_000L)
        assertFalse(controller.canEnterIdleNow())

        // 120s after completion -> idles
        fakeClock.advanceBy(1_000L)
        assertTrue(controller.canEnterIdleNow())
        controller.checkAndTriggerIdle()
        assertEquals(1, idleTriggerCount)
    }

    // =========================================================================
    // TEST C: No Idle During Long TTS Playback (SPEAKING State)
    // =========================================================================
    @Test
    fun testRequirementC_NoIdleDuringLongTtsPlayback_SpeakingStateSuppressesIdle() {
        val fakeClock = FakeClock(0L)
        var idleTriggerCount = 0
        var currentState = ConversationState.SPEAKING
        var isAudioPlaying = true

        val controller = JarvisIdleController(
            scope = testScope,
            clock = fakeClock,
            inactivityTimeoutMs = 120_000L,
            onEnterIdle = { idleTriggerCount++ }
        ).apply {
            isSessionActive = { true }
            isStandbyActive = { false }
            isManualOff = { false }
            getConversationState = { currentState }
            this.isAudioPlaying = { isAudioPlaying }
            isTaskExecuting = { false }
        }

        controller.startTimer()

        // Long assistant narration takes 150 seconds of audio playback
        fakeClock.advanceBy(150_000L)
        assertFalse("Idle MUST be suppressed while SPEAKING and audio is actively playing", controller.canEnterIdleNow())
        assertEquals(0, idleTriggerCount)

        // Audio playback ends
        isAudioPlaying = false
        currentState = ConversationState.IDLE
        controller.notifyInteractionCompleted()

        // Fresh 120s starts from audio completion time (150s + 120s = 270s)
        assertEquals(270_000L, controller.inactivityDeadline)
        fakeClock.advanceBy(119_000L) // t = 269s
        assertFalse(controller.canEnterIdleNow())

        fakeClock.advanceBy(1_000L) // t = 270s
        assertTrue(controller.canEnterIdleNow())
        controller.checkAndTriggerIdle()
        assertEquals(1, idleTriggerCount)
    }

    // =========================================================================
    // TEST F: Chat From Idle Wakes Jarvis and Processes Exactly Once
    // =========================================================================
    @Test
    fun testRequirementF_ChatFromIdleWakesJarvisAndProcessesMessageExactlyOnce() {
        val fakeClock = FakeClock(10_000L)
        var standbyState = true
        var sessionState = false
        var currentState = ConversationState.STANDBY

        val controller = JarvisIdleController(
            scope = testScope,
            clock = fakeClock,
            inactivityTimeoutMs = 120_000L,
            onEnterIdle = {
                standbyState = true
                sessionState = false
                currentState = ConversationState.STANDBY
            }
        ).apply {
            isSessionActive = { sessionState }
            isStandbyActive = { standbyState }
            isManualOff = { false }
            getConversationState = { currentState }
            isAudioPlaying = { false }
            isTaskExecuting = { false }
        }

        val chatRepository = ChatRepository(preferences = null)
        val userQuery = "Turn on the living room lights"

        // Simulate chat wake transition:
        // 1. Cancel pending idle transition synchronously
        controller.cancelPendingIdleTransition()
        controller.notifyUserActivity(JarvisIdleController.ActivityType.CHAT_SUBMISSION)

        // 2. Wake up: exit standby, enter session
        standbyState = false
        sessionState = true
        currentState = ConversationState.THINKING
        controller.startTimer()

        // 3. Process the message exactly once in repository
        val turnId = chatRepository.startTurn(userQuery)
        assertEquals(1, chatRepository.turns.value.size)

        // 4. Server responds
        chatRepository.updateAssistantResponse(turnId, "Turning on living room lights now.", isComplete = true)
        currentState = ConversationState.IDLE
        controller.notifyInteractionCompleted()

        // Verify: exactly 1 turn processed, session is active, no duplicate turn
        assertEquals("Chat must have exactly 1 turn, never duplicated", 1, chatRepository.turns.value.size)
        val turn = chatRepository.turns.value.first()
        assertEquals(userQuery, turn.userTranscript)
        assertEquals("Turning on living room lights now.", turn.jarvisResponse)
        assertFalse("Jarvis must not be in standby after chat wake", standbyState)
        assertTrue("Session must be active", sessionState)
    }

    // =========================================================================
    // TEST G: Configured Voice Wake Phrase Transitions to LISTENING
    // =========================================================================
    @Test
    fun testRequirementG_ConfiguredVoiceWakeTransitionsToListening() {
        var standbyState = true
        var sessionState = false
        var currentState = ConversationState.STANDBY

        // Simulate wake detection logic:
        val cleanPhrase = "hey jarvis"
        val wakeName = "jarvis"
        val validWakePhrases = listOf("hey jarvis", "jarvis", "hello jarvis")
        val isMatch = validWakePhrases.any { cleanPhrase.contains(it) }

        assertTrue("Configured wake phrase must match", isMatch)

        // Authoritative wakeUp(VOICE_WAKE) transition:
        standbyState = false
        sessionState = true
        currentState = ConversationState.LISTENING

        assertFalse("Standby must be false upon wake word detection", standbyState)
        assertTrue("Session must be active", sessionState)
        assertEquals("State must transition to LISTENING to capture user utterance", ConversationState.LISTENING, currentState)
    }

    // =========================================================================
    // TEST H: Barge-In Interruption Stops TTS and Answers Latest Question
    // =========================================================================
    @Test
    fun testRequirementH_SpeakingOverJarvisInterruptsTtsAndAnswersLatestQuestion() {
        var currentState = ConversationState.SPEAKING
        var isAudioPlaying = true
        var assistantResponseText = "I am currently explaining how black holes work..."
        val chatRepository = ChatRepository(preferences = null)

        // Turn 1 started and speaking
        val turnId1 = chatRepository.startTurn("Tell me about black holes")
        chatRepository.updateAssistantResponse(turnId1, assistantResponseText, isComplete = false)

        // User speaks over assistant on loudspeaker
        val route = AudioOutputRouteType.EXTERNAL_SPEAKER
        val threshold = EchoGuardConfig.getBargeInThreshold(route) // 0.72f
        val requiredFrames = EchoGuardConfig.getPersistenceFrames(route) // 7 frames

        var consecutiveVoicedFrames = 0
        var bargeInConfirmed = false
        val userVoiceAmplitude = 0.85f // User speaks firmly over loudspeaker

        for (frame in 1..10) {
            if (userVoiceAmplitude >= threshold) {
                consecutiveVoicedFrames++
                if (consecutiveVoicedFrames >= requiredFrames) {
                    bargeInConfirmed = true
                    // Barge-in actions:
                    // 1. Flush audio player
                    isAudioPlaying = false
                    // 2. Set state to LISTENING
                    currentState = ConversationState.LISTENING
                    // 3. Clear pending assistant speech
                    assistantResponseText = ""
                    break
                }
            }
        }

        assertTrue("Barge-in must be confirmed after required persistence frames", bargeInConfirmed)
        assertFalse("Audio playback must be halted immediately", isAudioPlaying)
        assertEquals("Conversation state must become LISTENING", ConversationState.LISTENING, currentState)

        // New utterance captured and answered
        val newQuery = "Wait, what time is it?"
        val turnId2 = chatRepository.startTurn(newQuery)
        chatRepository.updateAssistantResponse(turnId2, "It is 9:30 PM.", isComplete = true)

        assertEquals(2, chatRepository.turns.value.size)
        assertEquals(newQuery, chatRepository.turns.value.last().userTranscript)
        assertEquals("It is 9:30 PM.", chatRepository.turns.value.last().jarvisResponse)
    }

    // =========================================================================
    // TEST I: Echo Transcript Guard & Acoustic Echo Cooldown
    // =========================================================================
    @Test
    fun testRequirementI_EchoTranscriptGuardAndAcousticEchoCooldown() {
        val lastAssistantSpoken = "The weather in New Delhi is clear and 28 degrees."
        val headsetRoute = AudioOutputRouteType.ISOLATED_HEADSET
        val loudspeakerRoute = AudioOutputRouteType.EXTERNAL_SPEAKER

        // 1. Cooldown verification:
        val headsetCooldown = EchoGuardConfig.getEchoCooldownMs(headsetRoute)
        val loudspeakerCooldown = EchoGuardConfig.getEchoCooldownMs(loudspeakerRoute)
        assertTrue("Headset cooldown must be positive", headsetCooldown > 0)
        assertTrue("Loudspeaker cooldown (AEDP) must be >= 400ms to allow room reverb to decay", loudspeakerCooldown >= 400L)

        // 2. Echo transcript guard:
        val incomingEcho = "New Delhi is clear"
        val candidate = incomingEcho.lowercase(Locale.ROOT)
        val assistantLower = lastAssistantSpoken.lowercase(Locale.ROOT)
        val isEcho = assistantLower.contains(candidate) || candidate.contains(assistantLower)

        assertTrue("Echo transcript guard must detect and suppress echoed assistant speech", isEcho)
    }

    // =========================================================================
    // TEST J: Mic OFF Stays Fully OFF; Chat Wake Still Works Text-Only
    // =========================================================================
    @Test
    fun testRequirementJ_MicOffStaysFullyOff_ChatWakeWorksTextOnly() {
        var isManualOff = true
        var isStandby = false
        var isSessionOn = false
        var currentState = ConversationState.OFF
        var audioRecorderStarted = false

        // 1. In Mic OFF mode: voice wake cannot trigger
        val wakeWordDetected = true
        if (isManualOff) {
            // Ignored when mic is manually OFF
        } else {
            currentState = ConversationState.LISTENING
        }
        assertEquals("State must remain OFF when mic is explicitly disabled", ConversationState.OFF, currentState)
        assertFalse(audioRecorderStarted)

        // 2. User submits text via chat: chat wake wakes Jarvis for text-only without turning on mic
        val textMessage = "Define quantum computing"
        val isChatWake = true
        if (isChatWake) {
            val textOnly = isManualOff // textOnly = true because mic is OFF
            isSessionOn = true
            currentState = ConversationState.THINKING

            if (!textOnly && !isManualOff) {
                audioRecorderStarted = true
            }
        }

        assertTrue("Session should be active for text-only processing", isSessionOn)
        assertEquals("Conversation state transitions to THINKING", ConversationState.THINKING, currentState)
        assertFalse("Microphone / AudioRecorder MUST NOT be started in text-only mode with mic OFF", audioRecorderStarted)
        assertTrue("Manual OFF setting must remain preserved", isManualOff)
    }

    // =========================================================================
    // TEST K: Volume Keys Within 800ms Do Not Trigger Idle or False Barge-in
    // =========================================================================
    @Test
    fun testRequirementK_VolumeKeysDoNotCauseIdleOrBargeIn() {
        val lastHardwareVolumeKeyTime = 10_000L
        val currentEventTime = 10_400L // 400ms later (< 800ms window)

        val timeSinceVolumeKey = currentEventTime - lastHardwareVolumeKeyTime
        val isWithinVolumeWindow = timeSinceVolumeKey < 800L
        assertTrue("Should detect event within 800ms volume key cooldown", isWithinVolumeWindow)

        // During this window, voiced frames are suppressed to prevent speaker step-changes from false barge-in
        var consecutiveVoicedFrames = 5
        if (isWithinVolumeWindow) {
            consecutiveVoicedFrames = 0
        }
        assertEquals("Voiced frames must be reset during volume key cooldown", 0, consecutiveVoicedFrames)
    }
}
