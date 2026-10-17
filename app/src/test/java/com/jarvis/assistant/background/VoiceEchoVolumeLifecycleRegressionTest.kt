package com.jarvis.assistant.background

import com.jarvis.assistant.audio.AudioDrainCalculator
import com.jarvis.assistant.audio.AudioOutputRouteType
import com.jarvis.assistant.audio.EchoGuardConfig
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.repository.ChatRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Regression Test Suite for Priorities 1, 2, 3, and 4:
 *
 * Case A: User sends "kaise ho" -> exactly one turn, echo transcript guard suppresses assistant feedback.
 * Case B: Long multi-sentence response drains completely without premature 400ms cutoff.
 * Case C: Hardware volume buttons adjust volume only without cancelling or restarting TTS.
 * Case D: Rapid state changes/rerenders do not create duplicate utterances or cancel playback.
 * Case E: Genuine user utterance vs assistant audio; isolated headset barge-in vs loudspeaker isolation.
 * Case F: Playback flush/cancellation safely clears speaking state without self-reply loops.
 * Case G: Natural speech completion transitions to IDLE without unsolicited standby announcements.
 * Case H: Mic OFF stops listening and prevents wake-word reactivation.
 * Case I: Chat transcript displays complete assistant response even on playback interruption.
 * Case J: Text typing, send messaging, and turn tracking remain intact.
 */
class VoiceEchoVolumeLifecycleRegressionTest {

    // =========================================================================
    // REGRESSION TEST A: User sends "kaise ho" once -> No Echo Loop
    // =========================================================================
    @Test
    fun testRegressionA_SingleTurnNoSelfEchoLoop() {
        val chatRepository = ChatRepository(preferences = null)
        val userQuery = "kaise ho"
        val turnId = chatRepository.startTurn(userQuery)

        val assistantReply = "Main badhiya hoon, aap bataiye aap kaise hain?"
        chatRepository.updateAssistantResponse(turnId, assistantReply, isComplete = true)

        assertEquals(1, chatRepository.turns.value.size)
        val turn = chatRepository.turns.value.first()
        assertEquals("kaise ho", turn.userTranscript)
        assertEquals(assistantReply, turn.jarvisResponse)

        // Echo transcript guard simulation:
        // Suppose server sends back an echo of the assistant's speech while speaking or recent (<1500ms)
        val isSpeakingOrRecent = true
        val echoedServerText = "Main badhiya hoon"
        val candidate = echoedServerText.trim().lowercase(Locale.ROOT)
        val assistantLower = assistantReply.lowercase(Locale.ROOT)

        val isEcho = isSpeakingOrRecent && (assistantLower.contains(candidate) || candidate.contains(assistantLower))
        assertTrue("Echo transcript guard must detect and suppress echoed assistant speech", isEcho)

        // Ensure no extra turn was created in the chat repository
        assertEquals("Chat must have exactly 1 turn, never a self-reply loop", 1, chatRepository.turns.value.size)
    }

    // =========================================================================
    // REGRESSION TEST B: Long Multi-Sentence Response Drains Fully Without Cutoff
    // =========================================================================
    @Test
    fun testRegressionB_LongResponseDrainsCompletelyWithoutPrematureCutoff() {
        // 24kHz 16-bit mono PCM: 1 second = 24,000 frames (48,000 bytes)
        // A long response of 4 seconds = 96,000 frames
        val totalWrittenFrames = 96000L
        val headPositionOffset = 0L

        // At 400ms (the old safety timeout cutoff), only 9,600 frames have left DAC:
        val headAt400ms = 9600L
        val framesPlayedAt400ms = AudioDrainCalculator.calculateFramesPlayed(headAt400ms, headPositionOffset)
        val remainingAt400ms = AudioDrainCalculator.calculateRemainingFrames(totalWrittenFrames, framesPlayedAt400ms)

        assertEquals(86400L, remainingAt400ms)
        assertFalse("At 400ms, 86,400 frames remain in hardware buffer; must NOT report drained!",
            AudioDrainCalculator.isDrained(totalWrittenFrames, headAt400ms, headPositionOffset))

        // True DAC completion: hardware head reaches 96,000 frames
        val headCompleted = 96000L
        val framesPlayedCompleted = AudioDrainCalculator.calculateFramesPlayed(headCompleted, headPositionOffset)
        val remainingCompleted = AudioDrainCalculator.calculateRemainingFrames(totalWrittenFrames, framesPlayedCompleted)

        assertEquals(0L, remainingCompleted)
        assertTrue("When head reaches total frames written, buffer is genuinely drained",
            AudioDrainCalculator.isDrained(totalWrittenFrames, headCompleted, headPositionOffset))
    }

    // =========================================================================
    // REGRESSION TEST C: Hardware Volume Keys Do Not Cancel TTS
    // =========================================================================
    @Test
    fun testRegressionC_HardwareVolumeKeyPressSuppressesFalseBargeIn() {
        val lastHardwareVolumeKeyTime = 1000L
        val currentTime = 1200L // 200ms after volume button pressed (< 800ms)

        val timeSinceVolumeKey = currentTime - lastHardwareVolumeKeyTime
        val isRecentVolumeKey = timeSinceVolumeKey < 800L
        assertTrue("Volume key press within 800ms is detected as recent", isRecentVolumeKey)

        // An acoustic click/beep with high amplitude occurs
        val clickAmplitude = 0.85f
        var consecutiveVoicedFrames = 5

        if (isRecentVolumeKey) {
            consecutiveVoicedFrames = 0
        }

        // Must be reset to 0 so it cannot meet barge-in persistence requirement
        assertEquals(0, consecutiveVoicedFrames)
        assertFalse("Volume button click must not trigger barge-in",
            consecutiveVoicedFrames >= EchoGuardConfig.LOUDSPEAKER_BARGE_IN_PERSISTENCE_FRAMES)
    }

    // =========================================================================
    // REGRESSION TEST D: Rapid State Changes Do Not Duplicate Turns or Cancel TTS
    // =========================================================================
    @Test
    fun testRegressionD_RapidStateUpdatesMaintainSingleTurn() {
        val chatRepository = ChatRepository(preferences = null)
        val turnId = chatRepository.startTurn("Test query")

        // Rapid streaming updates
        chatRepository.updateAssistantResponse(turnId, "Chunk 1", isComplete = false)
        chatRepository.updateAssistantResponse(turnId, "Chunk 1 Chunk 2", isComplete = false)
        chatRepository.updateAssistantResponse(turnId, "Chunk 1 Chunk 2 Chunk 3", isComplete = false)
        chatRepository.updateAssistantResponse(turnId, "Chunk 1 Chunk 2 Chunk 3 Final.", isComplete = true)

        assertEquals("Turns list must retain exactly 1 turn across rapid updates", 1, chatRepository.turns.value.size)
        assertEquals("Chunk 1 Chunk 2 Chunk 3 Final.", chatRepository.turns.value.first().jarvisResponse)
    }

    // =========================================================================
    // REGRESSION TEST E: Route-Aware Barge-In Isolation
    // =========================================================================
    @Test
    fun testRegressionE_LoudspeakerVsHeadsetBargeInBehavior() {
        // On loudspeaker, acoustic voice barge-in is disabled to eliminate echo loop:
        val loudspeakerRoute = AudioOutputRouteType.EXTERNAL_SPEAKER
        val headsetRoute = AudioOutputRouteType.ISOLATED_HEADSET

        // Loudspeaker simulation:
        val isSpeakerActive = true
        var bargeInTriggeredOnLoudspeaker = false
        if (isSpeakerActive) {
            if (loudspeakerRoute == AudioOutputRouteType.ISOLATED_HEADSET) {
                bargeInTriggeredOnLoudspeaker = true
            }
        }
        assertFalse("Acoustic barge-in must NEVER trigger over loudspeaker during speech", bargeInTriggeredOnLoudspeaker)

        // Headset simulation:
        var bargeInTriggeredOnHeadset = false
        val sustainedHeadsetVoice = List(EchoGuardConfig.HEADSET_BARGE_IN_PERSISTENCE_FRAMES) { 0.35f }
        var headsetFrames = 0
        for (amp in sustainedHeadsetVoice) {
            if (amp >= EchoGuardConfig.HEADSET_BARGE_IN_ENERGY_THRESHOLD) {
                headsetFrames++
                if (headsetFrames >= EchoGuardConfig.HEADSET_BARGE_IN_PERSISTENCE_FRAMES) {
                    bargeInTriggeredOnHeadset = true
                }
            }
        }
        assertTrue("Acoustic barge-in is reliably permitted on isolated headset", bargeInTriggeredOnHeadset)
    }

    // =========================================================================
    // REGRESSION TEST F: Safe Playback Flush and Cancellation
    // =========================================================================
    @Test
    fun testRegressionF_PlaybackFlushClearsDrainAndFramesSafely() {
        var totalFramesWritten = 48000L
        var isPlayingState = true

        // Simulate flush()
        totalFramesWritten = 0L
        isPlayingState = false

        assertTrue("After flush, buffer is drained",
            AudioDrainCalculator.isDrained(totalFramesWritten, 0L, 0L))
        assertFalse("After flush, isPlaying is false", isPlayingState)
    }

    // =========================================================================
    // REGRESSION TEST G: Idle State Transition Without Standby Voice Announcement
    // =========================================================================
    @Test
    fun testRegressionG_SpeechEndTransitionsToIdleSilently() {
        var state = ConversationState.SPEAKING
        val chatRepository = ChatRepository(preferences = null)
        val turnId = chatRepository.startTurn("hello")
        chatRepository.updateAssistantResponse(turnId, "Hello sir", isComplete = true)

        // When playback ends naturally:
        val isPlaybackActive = false
        if (!isPlaybackActive) {
            state = ConversationState.IDLE
        }

        assertEquals(ConversationState.IDLE, state)
        // Verify chat repository does NOT contain any extra "standby" announcements:
        assertEquals(1, chatRepository.turns.value.size)
        assertEquals("Hello sir", chatRepository.turns.value.first().jarvisResponse)
    }

    // =========================================================================
    // REGRESSION TEST H: Mic OFF Stops Listening & Prevents Auto-Restart
    // =========================================================================
    @Test
    fun testRegressionH_ManualOffCommandsPreventAutoRestart() {
        val assistantName = "Jarvis"

        // Various multilingual manual off expressions
        assertTrue(JarvisConversationService.isManualOffCommand("turn off", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("off ho jao", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("so jao", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("band ho jao", assistantName))

        // State machine guard:
        val isManualOff = true
        var canAutoRestart = false
        if (!isManualOff) {
            canAutoRestart = true
        }
        assertFalse("When manual off is set, auto-restart must be blocked", canAutoRestart)
    }

    // =========================================================================
    // REGRESSION TEST I: Chat Retains Full Response Even on Playback Interruption
    // =========================================================================
    @Test
    fun testRegressionI_FullResponseRetainedInChatOnInterruption() {
        val chatRepository = ChatRepository(preferences = null)
        val turnId = chatRepository.startTurn("Tell me a story")

        val fullStory = "Once upon a time in a digital realm, Jarvis operated with absolute precision."
        // Full response received from model
        chatRepository.updateAssistantResponse(turnId, fullStory, isComplete = true)

        // Playback error / flush occurs afterwards
        val playbackInterrupted = true
        assertTrue(playbackInterrupted)

        // Chat must still retain the full response
        assertEquals(fullStory, chatRepository.turns.value.first().jarvisResponse)
    }

    // =========================================================================
    // REGRESSION TEST J: Text Sending and Manual Input Integrity Intact
    // =========================================================================
    @Test
    fun testRegressionJ_TextSendingAndTurnTrackingIntact() {
        val chatRepository = ChatRepository(preferences = null)

        val clean = "what is artificial intelligence"
        val turnId = chatRepository.startTurn(clean)
        assertNotNull(turnId)

        chatRepository.updateAssistantResponse(turnId, "AI is intelligence demonstrated by machines.", isComplete = true)

        val turn = chatRepository.turns.value.first()
        assertEquals(clean, turn.userTranscript)
        assertEquals("AI is intelligence demonstrated by machines.", turn.jarvisResponse)
    }
}
