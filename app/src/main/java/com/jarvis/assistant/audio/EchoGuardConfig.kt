package com.jarvis.assistant.audio

/**
 * Classification of current audio output route for acoustic isolation determination.
 */
enum class AudioOutputRouteType {
    ISOLATED_HEADSET,
    EXTERNAL_SPEAKER
}

/**
 * Internal states of the speaking-time echo gate and post-playback cooldown.
 */
enum class EchoGuardState {
    NORMAL_LISTENING,
    ASSISTANT_SPEAKING,
    BARGE_IN_CONFIRMED,
    ECHO_COOLDOWN
}

/**
 * Calibrated parameters for acoustic echo protection and multi-frame barge-in detection.
 * All thresholds and timing parameters are configurable constants to allow tuning without
 * modifying audio pipeline architecture.
 */
object EchoGuardConfig {
    // Loudspeaker (Acoustically Coupled) Defaults:
    // Physical loudspeaker requires sustained acoustic energy to distinguish intentional
    // speech barge-in from speaker audio leakage reflected into the microphone.
    const val LOUDSPEAKER_BARGE_IN_ENERGY_THRESHOLD = 0.72f       // Normalized RMS threshold for barge-in candidates
    const val LOUDSPEAKER_BARGE_IN_PERSISTENCE_FRAMES = 7          // 7 frames * 20ms = 140ms sustained energy required
    const val LOUDSPEAKER_ECHO_COOLDOWN_MS = 400L                  // 400ms cooldown post-playback before opening mic

    // Headset / Bluetooth (Acoustically Isolated) Defaults:
    // When output is physically isolated in earbuds/headphones, acoustic leakage into
    // the microphone is near-zero; lower threshold and shorter persistence deliver rapid barge-in.
    const val HEADSET_BARGE_IN_ENERGY_THRESHOLD = 0.25f            // Normalized RMS threshold for headset barge-in
    const val HEADSET_BARGE_IN_PERSISTENCE_FRAMES = 4              // 4 frames * 20ms = 80ms sustained energy required
    const val HEADSET_ECHO_COOLDOWN_MS = 100L                      // 100ms minimal cooldown for headset route

    // Buffer flush and speech detection tuning
    const val TAIL_DISCARD_FRAMES = 4                              // 4 frames * 20ms = 80ms discarded post-flush
    const val USER_SPEECH_THRESHOLD = 0.15f                        // Speech start threshold during normal listening
    const val VAD_SILENCE_STREAM_END_MS = 1500L                    // 1500ms silence to signal stream end
    const val VAD_SILENCE_THINKING_MS = 2000L                      // 2000ms silence to transition to THINKING

    // Hardware DAC drain safety timeouts
    const val HARDWARE_SAFETY_DRAIN_TICKS = 20                     // 20 ticks * 20ms = 400ms HAL safety net
    const val NETWORK_SAFETY_DRAIN_TICKS = 125                     // 125 ticks * 20ms = 2500ms network packet jitter ceiling

    // Standard aliases for backwards compatibility with tests and callers
    const val BARGE_IN_ENERGY_THRESHOLD = LOUDSPEAKER_BARGE_IN_ENERGY_THRESHOLD
    const val BARGE_IN_PERSISTENCE_FRAMES = LOUDSPEAKER_BARGE_IN_PERSISTENCE_FRAMES
    const val ECHO_COOLDOWN_MS = LOUDSPEAKER_ECHO_COOLDOWN_MS

    /**
     * Resolves the barge-in energy threshold based on the active audio output route.
     */
    fun getBargeInThreshold(routeType: AudioOutputRouteType): Float {
        return when (routeType) {
            AudioOutputRouteType.ISOLATED_HEADSET -> HEADSET_BARGE_IN_ENERGY_THRESHOLD
            AudioOutputRouteType.EXTERNAL_SPEAKER -> LOUDSPEAKER_BARGE_IN_ENERGY_THRESHOLD
        }
    }

    /**
     * Resolves the required consecutive confirmation frames based on the active audio output route.
     */
    fun getPersistenceFrames(routeType: AudioOutputRouteType): Int {
        return when (routeType) {
            AudioOutputRouteType.ISOLATED_HEADSET -> HEADSET_BARGE_IN_PERSISTENCE_FRAMES
            AudioOutputRouteType.EXTERNAL_SPEAKER -> LOUDSPEAKER_BARGE_IN_PERSISTENCE_FRAMES
        }
    }

    /**
     * Resolves the post-playback echo cooldown duration (in ms) based on the active audio output route.
     */
    fun getEchoCooldownMs(routeType: AudioOutputRouteType): Long {
        return when (routeType) {
            AudioOutputRouteType.ISOLATED_HEADSET -> HEADSET_ECHO_COOLDOWN_MS
            AudioOutputRouteType.EXTERNAL_SPEAKER -> LOUDSPEAKER_ECHO_COOLDOWN_MS
        }
    }
}
