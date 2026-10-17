package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayer(
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onPlaybackAmplitude: (amplitude: Float) -> Unit = {},
    val audioUsage: Int = JarvisAudioPolicy.PLAYBACK_USAGE,
    val audioContentType: Int = JarvisAudioPolicy.PLAYBACK_CONTENT_TYPE
) {
    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private val trackLock = Any()
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val isRunning = AtomicBoolean(false)
    private val isPlayingState = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    // Frame tracking for accurate hardware DAC drain detection
    @Volatile private var totalFramesWritten: Long = 0L
    @Volatile private var headPositionOffset: Long = 0L
    @Volatile private var isTurnStreamingCompleted: Boolean = false
    @Volatile private var currentVolume: Float = 0.8f
    @Volatile private var currentPlaybackSpeed: Float = 1.0f

    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(audioUsage)
        .setContentType(audioContentType)
        .build()

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // 96,000 bytes = 2 full seconds of 24kHz 16-bit mono PCM headroom.
        val bufferSize = maxOf(minBufferSize * 8, 96000)

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                track.addOnRoutingChangedListener({ router ->
                    val t = router as? AudioTrack
                    val routed = t?.routedDevice
                    Log.i(TAG, "AudioTrack route changed: device=${routed?.productName ?: "Default"} (type=${routed?.type})")
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.w(TAG, "Could not attach routing listener: ${e.message}")
            }
        }

        synchronized(trackLock) {
            totalFramesWritten = 0L
            headPositionOffset = try {
                (track.playbackHeadPosition.toLong()) and 0xFFFFFFFFL
            } catch (_: Exception) {
                0L
            }
            try {
                track.setVolume(currentVolume)
                if (currentPlaybackSpeed != 1.0f) {
                    val params = android.media.PlaybackParams().setSpeed(currentPlaybackSpeed)
                    track.playbackParams = params
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not apply volume/speed on init: ${e.message}")
            }
        }
    }

    fun setVolume(volumePercent: Int) {
        val gain = (volumePercent.coerceIn(0, 100) / 100f)
        currentVolume = gain
        synchronized(trackLock) {
            try {
                audioTrack?.setVolume(gain)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting volume on AudioTrack: ${e.message}")
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        currentPlaybackSpeed = clamped
        synchronized(trackLock) {
            try {
                audioTrack?.let { track ->
                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        val params = try {
                            track.playbackParams.setSpeed(clamped)
                        } catch (_: Exception) {
                            android.media.PlaybackParams().setSpeed(clamped)
                        }
                        track.playbackParams = params
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting playback speed on AudioTrack: ${e.message}")
            }
        }
    }

    fun notifyTurnComplete() {
        notifyTurnStreamingCompleted(true)
    }

    fun notifyTurnStreamingCompleted(completed: Boolean = true) {
        isTurnStreamingCompleted = completed
    }

    fun isHardwareDrained(): Boolean {
        if (!audioQueue.isEmpty()) return false
        val track = audioTrack ?: return true
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return true

        return synchronized(trackLock) {
            try {
                val rawHead = (track.playbackHeadPosition.toLong()) and 0xFFFFFFFFL
                AudioDrainCalculator.isDrained(totalFramesWritten, rawHead, headPositionOffset)
            } catch (e: Exception) {
                true // On HAL error, fail safe to drained
            }
        }
    }

    fun getRemainingBufferedFrames(): Long {
        val track = audioTrack ?: return 0L
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return 0L
        return synchronized(trackLock) {
            try {
                val rawHead = (track.playbackHeadPosition.toLong()) and 0xFFFFFFFFL
                val framesPlayed = AudioDrainCalculator.calculateFramesPlayed(rawHead, headPositionOffset)
                AudioDrainCalculator.calculateRemainingFrames(totalFramesWritten, framesPlayed)
            } catch (_: Exception) {
                0L
            }
        }
    }

    fun hasPendingAudio(): Boolean = !audioQueue.isEmpty() || isPlayingState.get() || !isHardwareDrained()

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        playbackThread = Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            var silenceCounter = 0
            while (isRunning.get()) {
                try {
                    if (!isPlayingState.get()) {
                        val firstChunk = audioQueue.poll(30, TimeUnit.MILLISECONDS)
                        if (firstChunk != null && firstChunk.isNotEmpty()) {
                            isTurnStreamingCompleted = false
                            isPlayingState.set(true)
                            silenceCounter = 0
                            onPlaybackStateChanged(true)

                            synchronized(trackLock) {
                                audioTrack?.let { track ->
                                    if (track.state == AudioTrack.STATE_INITIALIZED && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                        try {
                                            track.play()
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error calling play(): ${e.message}")
                                        }
                                    }
                                    // Reset baseline for the start of speech rendering
                                    headPositionOffset = try {
                                        (track.playbackHeadPosition.toLong()) and 0xFFFFFFFFL
                                    } catch (_: Exception) {
                                        0L
                                    }
                                    totalFramesWritten = 0L
                                }
                            }

                            writeToTrack(firstChunk)
                            val amplitude = AudioUtils.calculateRmsNormalized(firstChunk, firstChunk.size)
                            onPlaybackAmplitude(amplitude)
                        }
                        continue
                    }

                    // Currently playing: stream incoming chunks to the hardware AudioTrack
                    val chunk = audioQueue.poll(20, TimeUnit.MILLISECONDS)
                    if (chunk != null && chunk.isNotEmpty()) {
                        silenceCounter = 0
                        val amplitude = AudioUtils.calculateRmsNormalized(chunk, chunk.size)
                        onPlaybackAmplitude(amplitude)

                        writeToTrack(chunk)
                    } else {
                        silenceCounter++
                        val drained = isHardwareDrained()
                        // Completion logic:
                        // 1. If turn streaming completed: wait until hardware has genuinely drained.
                        // Calculate maximum required drain time from remaining buffered frames + HAL safety margin.
                        // 2. If turn streaming still in progress (network gap): wait up to network safety timeout.
                        val canComplete = if (isTurnStreamingCompleted) {
                            if (drained) {
                                true
                            } else {
                                val remainingFrames = getRemainingBufferedFrames()
                                val remainingMs = (remainingFrames * 1000L / SAMPLE_RATE)
                                val maxDrainTicks = (remainingMs / 20L).toInt() + EchoGuardConfig.HARDWARE_SAFETY_DRAIN_TICKS + 50
                                silenceCounter >= maxDrainTicks
                            }
                        } else {
                            silenceCounter >= EchoGuardConfig.NETWORK_SAFETY_DRAIN_TICKS
                        }

                        if (canComplete && isPlayingState.get()) {
                            isPlayingState.set(false)
                            isTurnStreamingCompleted = false
                            synchronized(trackLock) {
                                try {
                                    audioTrack?.let {
                                        if (it.state == AudioTrack.STATE_INITIALIZED && it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                            it.pause()
                                        }
                                        headPositionOffset = (it?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL
                                        totalFramesWritten = 0L
                                    }
                                } catch (_: Exception) {}
                            }
                            onPlaybackStateChanged(false)
                            onPlaybackAmplitude(0f)
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in playback worker: ${e.message}")
                }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun enqueueAudio(pcmData: ByteArray) {
        if (pcmData.size >= 16) {
            val validLen = pcmData.size and 1.inv()
            val aligned = if (validLen == pcmData.size) pcmData else pcmData.copyOf(validLen)
            audioQueue.offer(aligned)
        }
    }

    fun flush() {
        audioQueue.clear()
        isTurnStreamingCompleted = false
        synchronized(trackLock) {
            try {
                audioTrack?.let {
                    if (it.state == AudioTrack.STATE_INITIALIZED) {
                        try { it.pause() } catch (_: Exception) {}
                        try { it.flush() } catch (_: Exception) {}
                        try { it.stop() } catch (_: Exception) {}
                        headPositionOffset = (it.playbackHeadPosition.toLong()) and 0xFFFFFFFFL
                        totalFramesWritten = 0L
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error flushing AudioTrack: ${e.message}")
            }
        }
        if (isPlayingState.getAndSet(false)) {
            onPlaybackStateChanged(false)
            onPlaybackAmplitude(0f)
        }
    }

    fun isPlaying(): Boolean = isPlayingState.get() || !audioQueue.isEmpty() || !isHardwareDrained()

    fun release() {
        isRunning.set(false)
        isTurnStreamingCompleted = false
        playbackThread?.interrupt()
        playbackThread = null
        audioQueue.clear()

        synchronized(trackLock) {
            totalFramesWritten = 0L
            headPositionOffset = 0L
            try {
                audioTrack?.let {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.stop()
                    }
                    it.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioTrack: ${e.message}")
            } finally {
                audioTrack = null
            }
        }
    }

    fun recreateAudioTrack() {
        synchronized(trackLock) {
            val wasPlaying = try { audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING } catch (_: Exception) { false }
            try { audioTrack?.release() } catch (_: Exception) {}
            audioTrack = null
            totalFramesWritten = 0L
            headPositionOffset = 0L
            try {
                initAudioTrack()
                if (wasPlaying) audioTrack?.play()
                Log.i(TAG, "AudioTrack recreated successfully")
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack recreation failed: ${e.message}", e)
            }
        }
    }

    private fun writeToTrack(chunk: ByteArray) {
        synchronized(trackLock) {
            var track = audioTrack
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                recreateAudioTrack()
                track = audioTrack
            }
            var offset = 0
            var deadObjectRetries = 0
            while (track != null && offset < chunk.size && isRunning.get()) {
                val t = track
                try {
                    if (t.state != AudioTrack.STATE_INITIALIZED) {
                        throw IllegalStateException("AudioTrack is not initialized")
                    }
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                    val written = t.write(chunk, offset, chunk.size - offset)
                    when {
                        written > 0 -> {
                            offset += written
                            // 16-bit Mono PCM: 1 frame = 2 bytes
                            totalFramesWritten += (written / 2)
                        }
                        written == AudioTrack.ERROR_DEAD_OBJECT && deadObjectRetries < 2 -> {
                            deadObjectRetries++
                            Log.w(TAG, "AudioTrack returned ERROR_DEAD_OBJECT; recreating (attempt $deadObjectRetries)")
                            try { t.release() } catch (_: Exception) {}
                            audioTrack = null
                            totalFramesWritten = 0L
                            headPositionOffset = 0L
                            initAudioTrack()
                            track = audioTrack
                            track?.let { if (it.state == AudioTrack.STATE_INITIALIZED) it.play() }
                        }
                        written < 0 -> {
                            Log.w(TAG, "AudioTrack write failed ($written); dropping remaining ${chunk.size - offset} bytes")
                            break
                        }
                        else -> break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AudioTrack write/recovery failed: ${e.message}", e)
                    try { t.release() } catch (_: Exception) {}
                    audioTrack = null
                    totalFramesWritten = 0L
                    headPositionOffset = 0L
                    if (deadObjectRetries++ < 2) {
                        try {
                            initAudioTrack()
                            track = audioTrack
                        } catch (recoveryError: Exception) {
                            Log.e(TAG, "AudioTrack recreation failed: ${recoveryError.message}", recoveryError)
                            break
                        }
                    } else break
                }
            }
        }
    }
}
