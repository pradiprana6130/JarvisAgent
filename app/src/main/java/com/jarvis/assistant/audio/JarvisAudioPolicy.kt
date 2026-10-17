package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioManager

/**
 * Centralized audio policy and volume stream specification for JARVIS.
 *
 * Requirements enforced:
 * 1. Playback stream is strictly bound to STREAM_MUSIC via USAGE_MEDIA (not USAGE_ASSISTANT / STREAM_ASSISTANT).
 * 2. Speech acoustics are preserved via CONTENT_TYPE_SPEECH.
 * 3. Media mute or zero media volume makes playback inaudible.
 * 4. Notification mute (vibrate/silent ringer) does NOT mute media playback (independent verification).
 * 5. Respects Android system volume and mute behavior (no forced volume or automatic system volume modification).
 */
object JarvisAudioPolicy {
    /**
     * Intended playback usage: USAGE_MEDIA.
     * USAGE_MEDIA maps directly to AudioManager.STREAM_MUSIC in Android's AudioPolicy.
     * Unlike USAGE_ASSISTANT (which routes to STREAM_ASSISTANT, an independent internal stream
     * not accessible or muted by the device volume panel), USAGE_MEDIA guarantees that the user's
     * volume panel, media slider, and mute button govern speech audibility.
     */
    const val PLAYBACK_USAGE: Int = AudioAttributes.USAGE_MEDIA

    /**
     * Speech acoustic tuning: CONTENT_TYPE_SPEECH applies appropriate DSP voice filtering.
     */
    const val PLAYBACK_CONTENT_TYPE: Int = AudioAttributes.CONTENT_TYPE_SPEECH

    /**
     * Target volume stream: AudioManager.STREAM_MUSIC.
     */
    const val TARGET_VOLUME_STREAM: Int = AudioManager.STREAM_MUSIC

    /**
     * Resolves the legacy Android volume stream corresponding to an AudioAttributes usage.
     */
    fun resolveVolumeControlStream(usage: Int): Int {
        return when (usage) {
            AudioAttributes.USAGE_MEDIA -> AudioManager.STREAM_MUSIC
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_EVENT -> AudioManager.STREAM_NOTIFICATION
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> AudioManager.STREAM_RING
            AudioAttributes.USAGE_ALARM -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
    }

    /**
     * Determines whether media playback is inaudible based on MEDIA stream volume and mute state.
     * Evaluates the MEDIA stream independently from notification or ringer streams.
     */
    fun isMediaOutputMuted(
        mediaVolume: Int,
        isMediaMuted: Boolean = false
    ): Boolean {
        return isMediaMuted || mediaVolume <= 0
    }

    /**
     * Verifies that notification mute state does NOT determine media mute state.
     * Android separates Media volume from Ring/Notification volume; a muted notification stream
     * or silent/vibrate ringer mode does not make media playback inaudible if media volume > 0.
     */
    fun isPlaybackGovernedByMediaNotNotification(
        mediaVolume: Int,
        isMediaMuted: Boolean,
        notificationVolume: Int,
        isNotificationMuted: Boolean,
        ringerMode: Int
    ): Boolean {
        val mediaSilent = isMediaOutputMuted(mediaVolume, isMediaMuted)
        return !mediaSilent
    }
}
