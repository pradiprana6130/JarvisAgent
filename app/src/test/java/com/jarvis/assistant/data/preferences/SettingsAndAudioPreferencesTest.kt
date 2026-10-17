package com.jarvis.assistant.data.preferences

import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.util.PromptGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAndAudioPreferencesTest {

    @Test
    fun testResponseStyleDirectivesInSystemPrompt() {
        // Balanced
        val balancedPrompt = PromptGenerator.generateSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "Boss",
            responseStyle = "Balanced"
        )
        assertTrue(
            "Balanced prompt must contain balanced response style directive",
            balancedPrompt.contains("[RESPONSE STYLE: BALANCED (HELPFUL + NATURAL)]:")
        )

        // Concise
        val concisePrompt = PromptGenerator.generateSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "Boss",
            responseStyle = "Concise"
        )
        assertTrue(
            "Concise prompt must contain concise directive",
            concisePrompt.contains("[RESPONSE STYLE: CONCISE]:")
        )
        assertTrue(
            "Concise prompt must instruct direct answers",
            concisePrompt.contains("Give extremely concise, direct, to-the-point spoken answers")
        )

        // Detailed
        val detailedPrompt = PromptGenerator.generateSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "Boss",
            responseStyle = "Detailed"
        )
        assertTrue(
            "Detailed prompt must contain detailed directive",
            detailedPrompt.contains("[RESPONSE STYLE: DETAILED]:")
        )
        assertTrue(
            "Detailed prompt must instruct technical depth",
            detailedPrompt.contains("Provide thorough, insightful, and comprehensive answers")
        )
    }

    @Test
    fun testSilentExecutionModeDirective() {
        val silentPrompt = PromptGenerator.generateSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "Boss",
            isSilentExecutionMode = true
        )
        assertTrue(
            "Silent prompt must declare silent mode active",
            silentPrompt.contains("[EXECUTION MODE: SILENT EXECUTION MODE IS ACTIVE]:")
        )
        assertTrue(
            "Silent prompt must mandate silent tool execution",
            silentPrompt.contains("You MUST execute ALL commands, tool calls, and tasks COMPLETELY SILENTLY")
        )

        val vocalPrompt = PromptGenerator.generateSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "Boss",
            isSilentExecutionMode = false
        )
        assertFalse(
            "Vocal prompt must not contain silent mode block",
            vocalPrompt.contains("[EXECUTION MODE: SILENT EXECUTION MODE IS ACTIVE]:")
        )
        assertTrue(
            "Vocal prompt must contain vocal confirmations policy",
            vocalPrompt.contains("[AUTONOMOUS TASK EXECUTION POLICY — VOCAL CONFIRMATIONS ACTIVE]:")
        )
    }

    @Test
    fun testHinglishLanguagePreservation() {
        val hinglishPrompt = PromptGenerator.generateSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "Boss",
            language = "Hinglish"
        )
        assertTrue(
            "Hinglish prompt must contain strict spoken mandate",
            hinglishPrompt.contains("[STRICT SPOKEN LANGUAGE MANDATE - Hinglish ONLY]:")
        )
        assertTrue(
            "Hinglish prompt must specify smooth conversational Latin script mix",
            hinglishPrompt.contains("Speak in smooth, natural conversational Hinglish")
        )
    }

    @Test
    fun testOwnerNameDefaultsAndFormatting() {
        val bossPrompt = PromptGenerator.generateSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "Boss"
        )
        assertTrue(
            "Prompt must address Boss as user's preferred name",
            bossPrompt.contains("The user's preferred name is Boss. Address them accordingly.")
        )
    }

    @Test
    fun testWakeNameValidationRegex() {
        val regex = Regex("[A-Za-z][A-Za-z0-9_-]{1,19}")

        // Valid single-word wake names for Vosk
        assertTrue("Jarvis should be valid", "Jarvis".matches(regex))
        assertTrue("Friday should be valid", "Friday".matches(regex))
        assertTrue("Jarvis_Pro should be valid", "Jarvis_Pro".matches(regex))
        assertTrue("Optimus-1 should be valid", "Optimus-1".matches(regex))

        // Invalid names (spaces, special chars, numbers leading, too short)
        assertFalse("Hey Jarvis with spaces should be invalid", "Hey Jarvis".matches(regex))
        assertFalse("Single letter should be invalid", "J".matches(regex))
        assertFalse("Leading number should be invalid", "1Jarvis".matches(regex))
        assertFalse("Symbols should be invalid", "Jarvis!".matches(regex))
    }

    @Test
    fun testSpeedAndVolumeClampingLogic() {
        // Clamping speed within 0.5f..2.0f
        assertEquals(0.8f, 0.8f.coerceIn(0.5f, 2.0f), 0.001f)
        assertEquals(1.0f, 1.0f.coerceIn(0.5f, 2.0f), 0.001f)
        assertEquals(1.25f, 1.25f.coerceIn(0.5f, 2.0f), 0.001f)
        assertEquals(1.5f, 1.5f.coerceIn(0.5f, 2.0f), 0.001f)
        assertEquals(0.5f, 0.2f.coerceIn(0.5f, 2.0f), 0.001f)
        assertEquals(2.0f, 3.5f.coerceIn(0.5f, 2.0f), 0.001f)

        // Clamping volume within 0..100
        assertEquals(0, (-10).coerceIn(0, 100))
        assertEquals(80, 80.coerceIn(0, 100))
        assertEquals(100, 150.coerceIn(0, 100))
    }
}
