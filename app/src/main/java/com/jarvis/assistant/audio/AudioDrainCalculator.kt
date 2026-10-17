package com.jarvis.assistant.audio

/**
 * Pure Kotlin mathematical helper for AudioTrack DAC drain calculation and wraparound safety.
 *
 * Handles:
 * 1. 32-bit unsigned playback-head wraparound (0 to 4,294,967,295 frames ~ 49.7 hours).
 * 2. Offset baseline synchronization across turns, flushes, and track re-creation.
 * 3. Exact frame accounting for 16-bit mono PCM (2 bytes per frame).
 */
object AudioDrainCalculator {

    /**
     * Calculates frames played since offset baseline, handling 32-bit unsigned wraparound.
     */
    fun calculateFramesPlayed(rawHeadPosition: Long, headPositionOffset: Long): Long {
        val headMasked = rawHeadPosition and 0xFFFFFFFFL
        val offsetMasked = headPositionOffset and 0xFFFFFFFFL
        return if (headMasked >= offsetMasked) {
            headMasked - offsetMasked
        } else {
            // True 32-bit unsigned wraparound only occurs if baseline was near 2^32 (> 0xF0000000L)
            if (offsetMasked > 0xF0000000L) {
                (headMasked - offsetMasked) and 0xFFFFFFFFL
            } else {
                // If head < offset and offset was not near the wrap boundary,
                // the HAL / AudioTrack reset playback head position back to 0.
                headMasked
            }
        }
    }

    /**
     * Calculates remaining unplayed buffered frames in the hardware track.
     */
    fun calculateRemainingFrames(totalFramesWritten: Long, framesPlayed: Long): Long {
        return maxOf(0L, totalFramesWritten - framesPlayed)
    }

    /**
     * Determines whether all written audio frames have physically exited the DAC buffer.
     */
    fun isDrained(totalFramesWritten: Long, rawHeadPosition: Long, headPositionOffset: Long): Boolean {
        if (totalFramesWritten <= 0L) return true
        val framesPlayed = calculateFramesPlayed(rawHeadPosition, headPositionOffset)
        return (totalFramesWritten - framesPlayed) <= 0L
    }
}
