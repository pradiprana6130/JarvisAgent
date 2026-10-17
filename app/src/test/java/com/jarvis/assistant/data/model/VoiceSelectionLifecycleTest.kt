package com.jarvis.assistant.data.model

import com.google.gson.Gson
import com.jarvis.assistant.audio.GeminiVoicePreviewManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSelectionLifecycleTest {

    @Test
    fun testSetupPayloadSerializesVoiceConfigCorrectly() {
        val gson = Gson()
        for (voice in GeminiVoiceCatalog.ALL_VOICES) {
            val setupPayload = SetupPayload(
                model = GeminiConstants.DEFAULT_MODEL,
                generationConfig = GenerationConfigPayload(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = SpeechConfigPayload(
                        voiceConfig = VoiceConfigPayload(
                            prebuiltVoiceConfig = PrebuiltVoiceConfigPayload(
                                voiceName = voice.id
                            )
                        )
                    )
                )
            )

            val jsonString = gson.toJson(GeminiBidiSetup(setupPayload))
            assertNotNull(jsonString)

            val rootJson = com.google.gson.JsonParser.parseString(jsonString).asJsonObject
            val setupObj = rootJson.getAsJsonObject("setup")
            val genConfigObj = setupObj.getAsJsonObject("generationConfig")
            val speechConfigObj = genConfigObj.getAsJsonObject("speechConfig")
            val voiceConfigObj = speechConfigObj.getAsJsonObject("voiceConfig")
            val prebuiltVoiceConfigObj = voiceConfigObj.getAsJsonObject("prebuiltVoiceConfig")

            assertEquals(
                "Serialized voiceName must exactly match official catalog id '${voice.id}'",
                voice.id,
                prebuiltVoiceConfigObj.get("voiceName").asString
            )
        }
    }

    @Test
    fun testTabFilteringMaintainsCatalogIntegrity() {
        val allVoices = GeminiVoiceCatalog.ALL_VOICES
        val maleVoices = GeminiVoiceCatalog.MALE_VOICES
        val femaleVoices = GeminiVoiceCatalog.FEMALE_VOICES

        assertEquals(30, allVoices.size)
        assertEquals(16, maleVoices.size)
        assertEquals(14, femaleVoices.size)

        // Union of male and female should exactly match all 30 voices
        val combinedIds = (maleVoices + femaleVoices).map { it.id }.toSet()
        assertEquals(30, combinedIds.size)
        assertEquals(allVoices.map { it.id }.toSet(), combinedIds)
    }

    @Test
    fun testEachVoiceProducesUniqueValidWav() {
        val testPcm = ByteArray(960) { (it % 256).toByte() }
        for (voice in GeminiVoiceCatalog.ALL_VOICES) {
            val wav = GeminiVoicePreviewManager.pcmToWav(
                pcmData = testPcm,
                sampleRate = 24000,
                channels = 1,
                bitsPerSample = 16
            )
            assertEquals(44 + testPcm.size, wav.size)
            assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        }
    }
}
