package com.jarvis.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.GeminiConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages official Gemini voice previews in natural Hindi.
 *
 * Implements:
 * 1. Dual-path Gemini audio generation:
 *    - Path A: Gemini REST generateContent audio modality (fast, single HTTP request)
 *    - Path B: Gemini Live WebSocket BidiGenerateContent fallback (matches conversational pipeline)
 * 2. Standard 24kHz 16-bit Mono PCM-to-WAV packaging.
 * 3. Echo-guard isolation: mutes live mic during playback to prevent acoustic self-triggers.
 * 4. Safe single-instance playback with clean cancellation and error reporting.
 */
class GeminiVoicePreviewManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VoicePreviewManager"
        const val PREVIEW_HINDI_TEXT = "नमस्ते! मैं जार्विस हूँ। बताइए, मैं आपकी क्या सहायता कर सकता हूँ?"

        private val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /**
         * Converts raw 16-bit Mono PCM bytes into a standard 44-byte WAV container.
         */
        fun pcmToWav(
            pcmData: ByteArray,
            sampleRate: Int = 24000,
            channels: Int = 1,
            bitsPerSample: Int = 16
        ): ByteArray {
            val totalAudioLen = pcmData.size
            val totalDataLen = totalAudioLen + 36
            val byteRate = sampleRate * channels * bitsPerSample / 8

            val header = ByteArray(44)
            // RIFF/WAVE header
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            // 'fmt ' subchunk
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16 // Subchunk1Size (16 for PCM)
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1 // AudioFormat (1 for PCM)
            header[21] = 0
            header[22] = channels.toByte()
            header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = (channels * bitsPerSample / 8).toByte() // BlockAlign
            header[33] = 0
            header[34] = bitsPerSample.toByte()
            header[35] = 0
            // 'data' subchunk
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xff).toByte()
            header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
            header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
            header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

            val output = ByteArray(44 + pcmData.size)
            System.arraycopy(header, 0, output, 0, 44)
            System.arraycopy(pcmData, 0, output, 44, pcmData.size)
            return output
        }
    }

    sealed class PreviewState {
        object Idle : PreviewState()
        data class Loading(val voiceId: String) : PreviewState()
        data class Playing(val voiceId: String) : PreviewState()
        data class Error(val voiceId: String, val message: String) : PreviewState()
    }

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var activeJob: Job? = null
    private var activeWebSocket: WebSocket? = null
    private val preferences by lazy { JarvisApp.instance.preferences }

    fun togglePreview(voiceId: String) {
        val current = _state.value
        when (current) {
            is PreviewState.Loading -> {
                if (current.voiceId.equals(voiceId, ignoreCase = true)) {
                    stopPreview()
                } else {
                    startPreview(voiceId)
                }
            }
            is PreviewState.Playing -> {
                if (current.voiceId.equals(voiceId, ignoreCase = true)) {
                    stopPreview()
                } else {
                    startPreview(voiceId)
                }
            }
            else -> {
                startPreview(voiceId)
            }
        }
    }

    fun updateVolumeAndSpeed(volumePercent: Int, speed: Float) {
        try {
            val vol = (volumePercent.coerceIn(0, 100) / 100f)
            mediaPlayer?.setVolume(vol, vol)
            val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.playbackParams = player.playbackParams.setSpeed(clampedSpeed)
                }
            }
        } catch (_: Exception) {}
    }

    fun startPreview(voiceId: String) {
        stopPlaybackOnly()

        val apiKey = preferences.apiKey.trim()
        if (apiKey.isBlank()) {
            _state.value = PreviewState.Error(
                voiceId = voiceId,
                message = "Gemini API key is required to preview voices. Configure it in Settings -> API Connection."
            )
            return
        }

        _state.value = PreviewState.Loading(voiceId)

        // Echo protection: isolate microphone while assistant voice is auditioning
        try {
            JarvisConversationService.setMicMuted(true)
        } catch (e: Exception) {
            Log.w(TAG, "Could not mute conversation mic: ${e.message}")
        }

        activeJob = scope.launch(Dispatchers.IO) {
            try {
                // Try REST generateContent first
                var wavFile = requestRestAudioPreview(voiceId, apiKey)

                // If REST failed or returned empty, try Gemini Live WebSocket fallback
                if (wavFile == null || !wavFile.exists() || wavFile.length() < 100) {
                    Log.i(TAG, "REST audio preview not available; trying Gemini Live WebSocket for $voiceId...")
                    wavFile = requestWebSocketAudioPreview(voiceId, apiKey)
                }

                if (!isActive) {
                    restoreMicMuteState()
                    return@launch
                }

                if (wavFile != null && wavFile.exists() && wavFile.length() > 100) {
                    playAudioFile(voiceId, wavFile)
                } else {
                    restoreMicMuteState()
                    withContext(Dispatchers.Main) {
                        _state.value = PreviewState.Error(
                            voiceId = voiceId,
                            message = "Failed to generate audio preview from Gemini. Please check API key and network."
                        )
                    }
                }
            } catch (ce: CancellationException) {
                restoreMicMuteState()
                throw ce
            } catch (e: Exception) {
                restoreMicMuteState()
                Log.e(TAG, "Voice preview exception for $voiceId: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _state.value = PreviewState.Error(
                        voiceId = voiceId,
                        message = e.localizedMessage ?: "Audio generation failed"
                    )
                }
            }
        }
    }

    private suspend fun requestRestAudioPreview(voiceId: String, apiKey: String): File? = withContext(Dispatchers.IO) {
        // Candidate models supporting audio modality
        val candidateModels = listOf(
            com.jarvis.assistant.network.GeminiModelManager.cleanModelId(preferences.textModel),
            "gemini-2.5-flash",
            "gemini-2.5-flash-preview-tts",
            com.jarvis.assistant.network.GeminiModelManager.cleanModelId(preferences.aiModel)
        ).distinct()

        for (modelName in candidateModels) {
            if (!isActive) return@withContext null
            try {
                val restPath = com.jarvis.assistant.network.GeminiModelManager.toRestPath(modelName)
                val url = "https://generativelanguage.googleapis.com/v1beta/$restPath:generateContent?key=$apiKey"

                val payload = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", "Please speak strictly the following text in natural Hindi without introducing yourself or adding extra words: $PREVIEW_HINDI_TEXT")
                        }))
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", voiceId)
                                })
                            })
                        })
                    })
                }

                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string().orEmpty()
                        Log.w(TAG, "REST preview model $modelName returned HTTP ${resp.code}: $errBody")
                        return@use
                    }
                    val jsonStr = resp.body?.string() ?: return@use
                    val root = JSONObject(jsonStr)
                    val candidates = root.optJSONArray("candidates") ?: return@use
                    if (candidates.length() == 0) return@use
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content") ?: return@use
                    val parts = content.optJSONArray("parts") ?: return@use
                    for (p in 0 until parts.length()) {
                        val part = parts.getJSONObject(p)
                        val inlineData = part.optJSONObject("inlineData") ?: continue
                        val b64 = inlineData.optString("data", "")
                        if (b64.isNotBlank()) {
                            val rawBytes = Base64.decode(b64, Base64.DEFAULT)
                            if (rawBytes.isNotEmpty()) {
                                return@withContext packageAudioAsWav(voiceId, rawBytes)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "REST preview failed for $modelName: ${e.message}")
            }
        }
        null
    }

    private suspend fun requestWebSocketAudioPreview(voiceId: String, apiKey: String): File? = withContext(Dispatchers.IO) {
        val pcmAccumulator = ByteArrayOutputStream()
        val completionSignal = CompletableDeferred<Boolean>()
        val url = "${GeminiConstants.WS_BASE_URL}?key=$apiKey"
        val request = Request.Builder().url(url).build()

        val activeModel = com.jarvis.assistant.network.GeminiModelManager.resolveLiveModel(preferences.aiModel)

        var wsInstance: WebSocket? = null
        val isSetupDone = AtomicBoolean(false)

        try {
            wsInstance = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val setup = JSONObject().apply {
                        put("setup", JSONObject().apply {
                            put("model", activeModel)
                            put("generation_config", JSONObject().apply {
                                put("response_modalities", JSONArray().put("AUDIO"))
                                put("speech_config", JSONObject().apply {
                                    put("voice_config", JSONObject().apply {
                                        put("prebuilt_voice_config", JSONObject().apply {
                                            put("voice_name", voiceId)
                                        })
                                    })
                                })
                            })
                        })
                    }
                    webSocket.send(setup.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleWsMessage(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleWsMessage(webSocket, bytes.utf8())
                }

                private fun handleWsMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        if (json.has("setupComplete")) {
                            isSetupDone.set(true)
                            // Send single turn request for preview
                            val prompt = JSONObject().apply {
                                put("client_content", JSONObject().apply {
                                    put("turns", JSONArray().put(JSONObject().apply {
                                        put("role", "user")
                                        put("parts", JSONArray().put(JSONObject().apply {
                                            put("text", "Speak in natural Hindi: $PREVIEW_HINDI_TEXT")
                                        }))
                                    }))
                                    put("turn_complete", true)
                                })
                            }
                            webSocket.send(prompt.toString())
                            return
                        }

                        val serverContent = json.optJSONObject("serverContent")
                        if (serverContent != null) {
                            val modelTurn = serverContent.optJSONObject("modelTurn")
                            if (modelTurn != null) {
                                val parts = modelTurn.optJSONArray("parts")
                                if (parts != null) {
                                    for (i in 0 until parts.length()) {
                                        val part = parts.getJSONObject(i)
                                        val inlineData = part.optJSONObject("inlineData")
                                        if (inlineData != null) {
                                            val b64 = inlineData.optString("data", "")
                                            if (b64.length > 8) {
                                                val pcm = Base64.decode(b64, Base64.DEFAULT)
                                                synchronized(pcmAccumulator) {
                                                    pcmAccumulator.write(pcm)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            val isTurnDone = serverContent.optBoolean("turnComplete", false) ||
                                    serverContent.optBoolean("generationComplete", false)
                            if (isTurnDone) {
                                completionSignal.complete(true)
                            }
                        }

                        if (json.has("error")) {
                            val errMsg = json.optJSONObject("error")?.optString("message") ?: "WebSocket Error"
                            Log.w(TAG, "WebSocket preview server error: $errMsg")
                            completionSignal.complete(false)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing WS preview message: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "WebSocket preview failure: ${t.message}")
                    completionSignal.complete(false)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    completionSignal.complete(true)
                }
            })

            activeWebSocket = wsInstance

            // Wait up to 10 seconds for preview audio streaming to finish
            withTimeoutOrNull(10000L) {
                completionSignal.await()
            }

            wsInstance.close(1000, "Preview turn complete")
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket preview handshake error: ${e.message}")
        } finally {
            activeWebSocket = null
        }

        val accumulated = synchronized(pcmAccumulator) { pcmAccumulator.toByteArray() }
        if (accumulated.size > 200) {
            packageAudioAsWav(voiceId, accumulated)
        } else {
            null
        }
    }

    private fun packageAudioAsWav(voiceId: String, audioBytes: ByteArray): File {
        val file = File(context.cacheDir, "preview_voice_${voiceId.lowercase()}.wav")
        // Check if rawBytes is already a WAV container (starts with 'RIFF')
        val finalBytes = if (audioBytes.size >= 4 &&
            audioBytes[0] == 'R'.code.toByte() &&
            audioBytes[1] == 'I'.code.toByte() &&
            audioBytes[2] == 'F'.code.toByte() &&
            audioBytes[3] == 'F'.code.toByte()
        ) {
            audioBytes
        } else {
            pcmToWav(audioBytes, sampleRate = 24000, channels = 1, bitsPerSample = 16)
        }

        FileOutputStream(file).use { it.write(finalBytes) }
        return file
    }

    private suspend fun playAudioFile(voiceId: String, audioFile: File) = withContext(Dispatchers.Main) {
        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(audioFile.absolutePath)
                setOnCompletionListener {
                    _state.value = PreviewState.Idle
                    restoreMicMuteState()
                    cleanupPlayer()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    _state.value = PreviewState.Error(voiceId, "Playback hardware error ($what)")
                    restoreMicMuteState()
                    cleanupPlayer()
                    true
                }
                val vol = (preferences.voiceVolume.coerceIn(0, 100) / 100f)
                setVolume(vol, vol)
                prepare()
                val speed = preferences.voiceSpeed.coerceIn(0.5f, 2.0f)
                if (speed != 1.0f) {
                    playbackParams = playbackParams.setSpeed(speed)
                }
                start()
            }
            mediaPlayer = player
            _state.value = PreviewState.Playing(voiceId)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio file: ${e.message}", e)
            restoreMicMuteState()
            _state.value = PreviewState.Error(voiceId, "Unable to play audio: ${e.message}")
        }
    }

    private fun restoreMicMuteState() {
        try {
            JarvisConversationService.setMicMuted(preferences.isMicMuted)
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore mic mute state: ${e.message}")
        }
    }

    fun stopPreview() {
        stopPlaybackOnly()
        restoreMicMuteState()
        _state.value = PreviewState.Idle
    }

    private fun stopPlaybackOnly() {
        activeJob?.cancel()
        activeJob = null

        activeWebSocket?.let {
            try { it.close(1000, "Stopped by user") } catch (_: Exception) {}
        }
        activeWebSocket = null

        cleanupPlayer()
    }

    private fun cleanupPlayer() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaPlayer: ${e.message}")
            }
        }
        mediaPlayer = null
    }
}
