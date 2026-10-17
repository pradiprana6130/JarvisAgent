package com.jarvis.assistant.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiVoiceCatalogTest {

    private val expectedOfficialVoiceIds = listOf(
        "Zephyr",
        "Puck",
        "Charon",
        "Kore",
        "Fenrir",
        "Leda",
        "Orus",
        "Aoede",
        "Callirrhoe",
        "Autonoe",
        "Enceladus",
        "Iapetus",
        "Umbriel",
        "Algieba",
        "Despina",
        "Erinome",
        "Algenib",
        "Rasalgethi",
        "Laomedeia",
        "Achernar",
        "Alnilam",
        "Schedar",
        "Gacrux",
        "Pulcherrima",
        "Achird",
        "Zubenelgenubi",
        "Vindemiatrix",
        "Sadachbia",
        "Sadaltager",
        "Sulafat"
    )

    @Test
    fun testCatalogContainsExact30OfficialVoices() {
        assertEquals("Catalog must contain exactly 30 voices", 30, GeminiVoiceCatalog.ALL_VOICES.size)
        assertEquals("Catalog IDs must contain exactly 30 entries", 30, GeminiVoiceCatalog.ALL_VOICE_IDS.size)

        val uniqueIds = GeminiVoiceCatalog.ALL_VOICE_IDS.toSet()
        assertEquals("All 30 voice IDs must be unique", 30, uniqueIds.size)

        for (expectedId in expectedOfficialVoiceIds) {
            assertTrue("Expected voice ID '$expectedId' must be present in catalog", uniqueIds.contains(expectedId))
        }
    }

    @Test
    fun testGenderFilterPartitioning() {
        val maleVoices = GeminiVoiceCatalog.MALE_VOICES
        val femaleVoices = GeminiVoiceCatalog.FEMALE_VOICES

        assertEquals("Male voices count must be exactly 16", 16, maleVoices.size)
        assertEquals("Female voices count must be exactly 14", 14, femaleVoices.size)
        assertEquals("Total partitioned voices must sum to 30", 30, maleVoices.size + femaleVoices.size)

        // Ensure disjoint sets
        val maleSet = maleVoices.map { it.id }.toSet()
        val femaleSet = femaleVoices.map { it.id }.toSet()
        assertTrue("Male and female categories must be disjoint", maleSet.intersect(femaleSet).isEmpty())
    }

    @Test
    fun testVoiceDescriptorsAndMetadataAreNonEmpty() {
        for (voice in GeminiVoiceCatalog.ALL_VOICES) {
            assertTrue("Voice id '${voice.id}' must have a non-blank displayName", voice.displayName.isNotBlank())
            assertTrue("Voice id '${voice.id}' must have a non-blank officialDescriptor", voice.officialDescriptor.isNotBlank())
            assertTrue("Voice id '${voice.id}' must have a non-blank styleTags", voice.styleTags.isNotBlank())
            assertTrue("Voice id '${voice.id}' must have a non-blank description", voice.description.isNotBlank())
        }
    }

    @Test
    fun testFindVoiceById_ExactAndCaseInsensitive() {
        val fenrir = GeminiVoiceCatalog.findVoiceById("Fenrir")
        assertEquals("Fenrir", fenrir.id)
        assertEquals(VoiceGender.MALE, fenrir.gender)

        val aoedeLower = GeminiVoiceCatalog.findVoiceById("aoede")
        assertEquals("Aoede", aoedeLower.id)
        assertEquals(VoiceGender.FEMALE, aoedeLower.gender)

        val zephyrUpper = GeminiVoiceCatalog.findVoiceById("ZEPHYR")
        assertEquals("Zephyr", zephyrUpper.id)
        assertEquals(VoiceGender.FEMALE, zephyrUpper.gender)
    }

    @Test
    fun testFindVoiceById_UnknownFallsBackToDefaultVoice() {
        val fallback = GeminiVoiceCatalog.findVoiceById("NonExistentVoice123")
        assertEquals(GeminiConstants.DEFAULT_VOICE, fallback.id)

        val nullFallback = GeminiVoiceCatalog.findVoiceById(null)
        assertEquals(GeminiConstants.DEFAULT_VOICE, nullFallback.id)

        val blankFallback = GeminiVoiceCatalog.findVoiceById("   ")
        assertEquals(GeminiConstants.DEFAULT_VOICE, blankFallback.id)
    }

    @Test
    fun testGeminiConstantsSupportedVoicesMatchesCatalog() {
        assertEquals(30, GeminiConstants.SUPPORTED_VOICES.size)
        for (id in expectedOfficialVoiceIds) {
            assertTrue("GeminiConstants.SUPPORTED_VOICES must contain '$id'", GeminiConstants.SUPPORTED_VOICES.contains(id))
        }
    }
}
