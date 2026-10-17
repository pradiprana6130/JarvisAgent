package com.jarvis.assistant.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GeminiVoicePreviewManagerTest {

    @Test
    fun testPreviewHindiTextIsNaturalDevanagari() {
        val text = GeminiVoicePreviewManager.PREVIEW_HINDI_TEXT
        assertTrue("Preview text must contain Devanagari characters", text.contains("नमस्ते"))
        assertTrue("Preview text must contain 'जार्विस'", text.contains("जार्विस"))
        assertTrue("Preview text must ask how to assist", text.contains("सहायता"))
    }

    @Test
    fun testPcmToWavPackagingGeneratesValidWavHeader() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        // Dummy manager instance to access helper logic
        // We can test pcmToWav logic with 1000 bytes of dummy PCM
        val dummyPcm = ByteArray(4800) { (it % 128).toByte() }

        val sampleRate = 24000
        val channels = 1
        val bitsPerSample = 16

        val wavBytes = GeminiVoicePreviewManager.pcmToWav(
            pcmData = dummyPcm,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample
        )

        assertEquals("Total WAV size must be 44-byte header + PCM payload", 44 + dummyPcm.size, wavBytes.size)

        // RIFF header
        assertEquals('R'.code.toByte(), wavBytes[0])
        assertEquals('I'.code.toByte(), wavBytes[1])
        assertEquals('F'.code.toByte(), wavBytes[2])
        assertEquals('F'.code.toByte(), wavBytes[3])

        // WAVE marker
        assertEquals('W'.code.toByte(), wavBytes[8])
        assertEquals('A'.code.toByte(), wavBytes[9])
        assertEquals('V'.code.toByte(), wavBytes[10])
        assertEquals('E'.code.toByte(), wavBytes[11])

        // 'fmt ' chunk
        assertEquals('f'.code.toByte(), wavBytes[12])
        assertEquals('m'.code.toByte(), wavBytes[13])
        assertEquals('t'.code.toByte(), wavBytes[14])
        assertEquals(' '.code.toByte(), wavBytes[15])

        val buffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Subchunk1Size
        val subchunk1Size = buffer.getInt(16)
        assertEquals(16, subchunk1Size)

        // AudioFormat = 1 (PCM)
        val audioFormat = buffer.getShort(20)
        assertEquals(1.toShort(), audioFormat)

        // NumChannels = 1
        val numChannels = buffer.getShort(22)
        assertEquals(channels.toShort(), numChannels)

        // SampleRate = 24000
        val sampleRateRead = buffer.getInt(24)
        assertEquals(sampleRate, sampleRateRead)

        // ByteRate = SampleRate * NumChannels * BitsPerSample/8
        val expectedByteRate = sampleRate * channels * bitsPerSample / 8
        val byteRateRead = buffer.getInt(28)
        assertEquals(expectedByteRate, byteRateRead)

        // 'data' chunk marker
        assertEquals('d'.code.toByte(), wavBytes[36])
        assertEquals('a'.code.toByte(), wavBytes[37])
        assertEquals('t'.code.toByte(), wavBytes[38])
        assertEquals('a'.code.toByte(), wavBytes[39])

        // Data length
        val dataLenRead = buffer.getInt(40)
        assertEquals(dummyPcm.size, dataLenRead)

        // Payload matches original PCM
        for (i in dummyPcm.indices) {
            assertEquals("PCM byte at $i must match", dummyPcm[i], wavBytes[44 + i])
        }
    }
}
