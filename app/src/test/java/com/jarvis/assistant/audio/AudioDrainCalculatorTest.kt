package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDrainCalculatorTest {

    @Test
    fun testNormalDrainProgress() {
        val head = 48000L
        val offset = 0L
        val totalWritten = 48000L

        val played = AudioDrainCalculator.calculateFramesPlayed(head, offset)
        assertEquals(48000L, played)

        val remaining = AudioDrainCalculator.calculateRemainingFrames(totalWritten, played)
        assertEquals(0L, remaining)

        assertTrue(AudioDrainCalculator.isDrained(totalWritten, head, offset))
    }

    @Test
    fun testUndrainedBuffer() {
        val head = 24000L
        val offset = 0L
        val totalWritten = 48000L

        val played = AudioDrainCalculator.calculateFramesPlayed(head, offset)
        assertEquals(24000L, played)

        val remaining = AudioDrainCalculator.calculateRemainingFrames(totalWritten, played)
        assertEquals(24000L, remaining)

        assertFalse(AudioDrainCalculator.isDrained(totalWritten, head, offset))
    }

    @Test
    fun testSigned32BitWraparoundSafety() {
        // AudioTrack head wraps around after 2^31 - 1 frames
        // Masking to 0xFFFFFFFFL ensures head moves from 0xFFFFFFFFL to 0x00000000L
        val offset = 0xFFFFFF00L // Near wrap boundary
        val headWrapped = 0x00000100L // 256 frames after wrap

        val played = AudioDrainCalculator.calculateFramesPlayed(headWrapped, offset)
        // Expected distance: (0x100 - 0xFFFFFF00) and 0xFFFFFFFFL = 512 frames
        assertEquals(512L, played)

        val totalWritten = 512L
        assertTrue(AudioDrainCalculator.isDrained(totalWritten, headWrapped, offset))
    }

    @Test
    fun testOffsetResetOnTrackRecreation() {
        // When track is re-created, offset and totalWritten reset to 0
        var totalWritten = 0L
        var offset = 0L

        // Initial state before playback
        assertTrue(AudioDrainCalculator.isDrained(totalWritten, 0L, offset))

        // First chunk written (480 frames = 20ms at 24kHz)
        totalWritten += 480L
        assertFalse(AudioDrainCalculator.isDrained(totalWritten, 0L, offset))

        // Hardware plays 480 frames
        val head = 480L
        assertTrue(AudioDrainCalculator.isDrained(totalWritten, head, offset))
    }

    @Test
    fun testHalHeadPositionResetTreatedSafely() {
        // If HAL or AudioTrack resets head position to 0 (head < offset),
        // and offset was small (not near 2^32), it must be treated as head reset to 0,
        // NOT a 4-billion-frame 32-bit forward wrap.
        val offset = 1000L
        val headReset = 0L
        val totalWritten = 24000L

        val played = AudioDrainCalculator.calculateFramesPlayed(headReset, offset)
        assertEquals(0L, played)

        val remaining = AudioDrainCalculator.calculateRemainingFrames(totalWritten, played)
        assertEquals(24000L, remaining)

        // Must NOT falsely report drained!
        assertFalse(AudioDrainCalculator.isDrained(totalWritten, headReset, offset))
    }

    @Test
    fun testConfigurableCeilingsExist() {
        // Verify 400ms HAL safety ceiling and 2500ms network timeout
        assertEquals(20, EchoGuardConfig.HARDWARE_SAFETY_DRAIN_TICKS)
        assertEquals(400L, EchoGuardConfig.HARDWARE_SAFETY_DRAIN_TICKS * 20L)

        assertEquals(125, EchoGuardConfig.NETWORK_SAFETY_DRAIN_TICKS)
        assertEquals(2500L, EchoGuardConfig.NETWORK_SAFETY_DRAIN_TICKS * 20L)
    }
}
