package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGuardPolicyTest {

    @Test
    fun testLoudspeakerVsHeadsetConfiguration() {
        val speakerThreshold = EchoGuardConfig.getBargeInThreshold(AudioOutputRouteType.EXTERNAL_SPEAKER)
        val headsetThreshold = EchoGuardConfig.getBargeInThreshold(AudioOutputRouteType.ISOLATED_HEADSET)

        assertEquals(0.72f, speakerThreshold, 0.001f)
        assertEquals(0.25f, headsetThreshold, 0.001f)

        val speakerFrames = EchoGuardConfig.getPersistenceFrames(AudioOutputRouteType.EXTERNAL_SPEAKER)
        val headsetFrames = EchoGuardConfig.getPersistenceFrames(AudioOutputRouteType.ISOLATED_HEADSET)

        assertEquals(7, speakerFrames)
        assertEquals(4, headsetFrames)

        val speakerCooldown = EchoGuardConfig.getEchoCooldownMs(AudioOutputRouteType.EXTERNAL_SPEAKER)
        val headsetCooldown = EchoGuardConfig.getEchoCooldownMs(AudioOutputRouteType.ISOLATED_HEADSET)

        assertEquals(400L, speakerCooldown)
        assertEquals(100L, headsetCooldown)
    }

    @Test
    fun testLoudspeakerRejectsIsolatedEnergySpikes() {
        // Over loudspeaker, threshold is 0.72 and required frames is 7 (140ms)
        val threshold = EchoGuardConfig.getBargeInThreshold(AudioOutputRouteType.EXTERNAL_SPEAKER)
        val requiredFrames = EchoGuardConfig.getPersistenceFrames(AudioOutputRouteType.EXTERNAL_SPEAKER)

        // Simulate 3 loud frames (e.g., door slam or speaker acoustic burst of 60ms)
        var consecutiveFrames = 0
        val burstAmplitudes = listOf(0.85f, 0.90f, 0.80f, 0.10f, 0.05f)

        var bargeInConfirmed = false
        for (amp in burstAmplitudes) {
            if (amp >= threshold) {
                consecutiveFrames++
                if (consecutiveFrames >= requiredFrames) {
                    bargeInConfirmed = true
                    break
                }
            } else {
                consecutiveFrames = 0
            }
        }

        // Must reject short burst because 3 < 7 frames
        assertFalse(bargeInConfirmed)
        assertEquals(0, consecutiveFrames)
    }

    @Test
    fun testLoudspeakerConfirmsSustainedIntentionalSpeech() {
        val threshold = EchoGuardConfig.getBargeInThreshold(AudioOutputRouteType.EXTERNAL_SPEAKER)
        val requiredFrames = EchoGuardConfig.getPersistenceFrames(AudioOutputRouteType.EXTERNAL_SPEAKER)

        // Simulate 8 consecutive loud frames (160ms of continuous human voice)
        var consecutiveFrames = 0
        var bargeInConfirmed = false
        val speechAmplitudes = List(8) { 0.78f }

        for (amp in speechAmplitudes) {
            if (amp >= threshold) {
                consecutiveFrames++
                if (consecutiveFrames >= requiredFrames) {
                    bargeInConfirmed = true
                    break
                }
            } else {
                consecutiveFrames = 0
            }
        }

        assertTrue(bargeInConfirmed)
        assertEquals(7, consecutiveFrames)
    }

    @Test
    fun testHeadsetConfirmsLowerThresholdInShorterWindow() {
        val threshold = EchoGuardConfig.getBargeInThreshold(AudioOutputRouteType.ISOLATED_HEADSET)
        val requiredFrames = EchoGuardConfig.getPersistenceFrames(AudioOutputRouteType.ISOLATED_HEADSET)

        // 4 frames at 0.30f (well below loudspeaker 0.72, but above headset 0.25)
        var consecutiveFrames = 0
        var bargeInConfirmed = false
        val quietSpeech = List(4) { 0.32f }

        for (amp in quietSpeech) {
            if (amp >= threshold) {
                consecutiveFrames++
                if (consecutiveFrames >= requiredFrames) {
                    bargeInConfirmed = true
                    break
                }
            } else {
                consecutiveFrames = 0
            }
        }

        assertTrue(bargeInConfirmed)
        assertEquals(4, consecutiveFrames)
    }

    @Test
    fun testCooldownSuppressionWindow() {
        val speakerCooldown = EchoGuardConfig.getEchoCooldownMs(AudioOutputRouteType.EXTERNAL_SPEAKER)
        val headsetCooldown = EchoGuardConfig.getEchoCooldownMs(AudioOutputRouteType.ISOLATED_HEADSET)

        val lastPlaybackEnd = 1_000_000L

        // During speaker cooldown (300ms after playback end < 400ms)
        var currentTime = lastPlaybackEnd + 300L
        assertTrue(currentTime - lastPlaybackEnd < speakerCooldown)

        // After speaker cooldown (450ms after playback end > 400ms)
        currentTime = lastPlaybackEnd + 450L
        assertFalse(currentTime - lastPlaybackEnd < speakerCooldown)

        // For headset route (150ms after playback end > 100ms headset cooldown)
        currentTime = lastPlaybackEnd + 150L
        assertFalse(currentTime - lastPlaybackEnd < headsetCooldown)
    }
}
