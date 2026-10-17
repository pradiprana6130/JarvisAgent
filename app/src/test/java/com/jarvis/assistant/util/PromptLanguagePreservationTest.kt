package com.jarvis.assistant.util

import com.jarvis.assistant.data.model.GeminiConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptLanguagePreservationTest {

    @Test
    fun testAutoModeIncludesSpeechRecognitionDirective() {
        val prompt = PromptGenerator.generateSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "Pradip",
            assistantName = "Jarvis",
            language = "Auto"
        )

        assertTrue(
            "Prompt must contain speech recognition context section",
            prompt.contains("[SPEECH RECOGNITION & PHONETIC CONTEXT]:")
        )
        assertTrue(
            "Prompt must explicitly warn against misinterpreting Jarvis as service",
            prompt.contains("service")
        )
        assertTrue(
            "Prompt must reference the assistant name in the ASR directive",
            prompt.contains("Never transcribe or misinterpret the user saying \"Jarvis\" as \"service\"")
        )
    }

    @Test
    fun testAutoModeIncludesSpokenLanguagePreservation() {
        val prompt = PromptGenerator.generateSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "Boss",
            assistantName = "Jarvis",
            language = "Auto"
        )

        assertTrue(
            "Prompt must include language preservation mandate in auto mode",
            prompt.contains("[SPOKEN LANGUAGE PRESERVATION & MULTILINGUAL MANDATE (AUTO-DETECT)]:")
        )
        assertTrue(
            "Prompt must instruct replying in natural Hindi when Hindi is spoken",
            prompt.contains("Tum kaise ho?") && prompt.contains("reply in natural Hindi")
        )
        assertTrue(
            "Prompt must instruct replying in natural Hinglish when Hinglish is spoken",
            prompt.contains("WhatsApp message bhejna hai") && prompt.contains("reply in smooth, natural Hinglish")
        )
        assertTrue(
            "Prompt must instruct replying in English when English is spoken",
            prompt.contains("Hey Jarvis") && prompt.contains("reply in English")
        )
        assertTrue(
            "Prompt must forbid unsolicited language translation",
            prompt.contains("DO NOT translate the user's spoken language")
        )
    }

    @Test
    fun testConfiguredLanguageEnforcesStrictLanguageMandate() {
        val hindiPrompt = PromptGenerator.generateSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "Sir",
            assistantName = "Jarvis",
            language = "Hindi"
        )

        assertTrue(
            "Configured Hindi should enforce strict Hindi mandate",
            hindiPrompt.contains("[STRICT SPOKEN LANGUAGE MANDATE - Hindi ONLY]:")
        )
        assertFalse(
            "Configured Hindi should not contain the auto-detect mandate",
            hindiPrompt.contains("[SPOKEN LANGUAGE PRESERVATION & MULTILINGUAL MANDATE (AUTO-DETECT)]:")
        )
    }
}
