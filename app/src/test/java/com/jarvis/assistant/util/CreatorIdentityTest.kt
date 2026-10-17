package com.jarvis.assistant.util

import com.jarvis.assistant.data.model.GeminiConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression and verification test suite for Jarvis creator identity response.
 *
 * Requirements:
 * 1. Official Hindi response must match word-for-word:
 *    "Mujhe Pradip Sir ne banaya aur train kiya hai, jo ek visionary AI developer hain aur technology ke field mein kamaal ka kaam kar rahe hain!"
 * 2. Generated system prompts across all personalities must declare Pradip Sir as creator and must not contain old creator claims.
 * 3. Direct creator queries must resolve consistently to the official response.
 * 4. Unrelated questions mentioning Pradip Sir or other developers must not force the full creator response.
 */
class CreatorIdentityTest {

    private val expectedOfficialHindiResponse =
        "Mujhe Pradip Sir ne banaya aur train kiya hai, jo ek visionary AI developer hain aur technology ke field mein kamaal ka kaam kar rahe hain!"

    private val expectedOfficialEnglishResponse =
        "I was created and trained by Pradip Sir, a visionary AI developer who is doing phenomenal work in the field of technology!"

    @Test
    fun verifyOfficialConstantsMatchExactWording() {
        assertEquals(
            "Official Hindi creator response constant must match exact specification",
            expectedOfficialHindiResponse,
            PromptGenerator.OFFICIAL_CREATOR_RESPONSE_HINDI
        )
        assertEquals(
            "Official English creator response constant must match expected wording",
            expectedOfficialEnglishResponse,
            PromptGenerator.OFFICIAL_CREATOR_RESPONSE_ENGLISH
        )
    }

    @Test
    fun verifySystemPromptContainsOfficialCreatorResponse() {
        val prompt = PromptGenerator.generateSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "TestUser",
            assistantName = "Jarvis",
            language = "Hindi"
        )

        assertTrue(
            "System prompt must contain the exact official Hindi creator response",
            prompt.contains(expectedOfficialHindiResponse)
        )
        assertTrue(
            "System prompt must declare Pradip Sir as creator",
            prompt.contains("Pradip Sir")
        )
        assertFalse(
            "System prompt must not contain any reference to 'Rehaan Sir'",
            prompt.contains("Rehaan Sir", ignoreCase = true)
        )
        assertFalse(
            "System prompt must not contain any reference to 'Rehan Sir'",
            prompt.contains("Rehan Sir", ignoreCase = true)
        )
        assertFalse(
            "System prompt must not contain old channel claim '@TechGptX channel chalate hain'",
            prompt.contains("@TechGptX channel chalate hain", ignoreCase = true)
        )
    }

    @Test
    fun verifyAllPersonalitiesCreditPradipSir() {
        val personalities = listOf(
            GeminiConstants.PERSONALITY_ASSISTANT,
            GeminiConstants.PERSONALITY_TECHGPT_X,
            GeminiConstants.PERSONALITY_GIRLFRIEND,
            GeminiConstants.PERSONALITY_BOYFRIEND,
            GeminiConstants.PERSONALITY_PROFESSIONAL
        )

        for (personality in personalities) {
            val prompt = PromptGenerator.generateSystemPrompt(
                personality = personality,
                userName = "TestUser",
                assistantName = "Jarvis"
            )
            assertTrue(
                "Prompt for personality '$personality' must contain the official creator response",
                prompt.contains(expectedOfficialHindiResponse)
            )
            assertFalse(
                "Prompt for personality '$personality' must not contain 'Rehaan Sir'",
                prompt.contains("Rehaan Sir", ignoreCase = true)
            )
            assertFalse(
                "Prompt for personality '$personality' must not contain 'Rehan Sir'",
                prompt.contains("Rehan Sir", ignoreCase = true)
            )
        }
    }

    @Test
    fun verifyDirectCreatorQuestionsMatchOfficialResponse() {
        val hindiCreatorQuestions = listOf(
            "Tumhe kisne banaya hai?",
            "Tumhe kisne create kiya?",
            "Tumhe kisne banaya",
            "Tumhe kisne train kiya?",
            "Tumhe kisne train kiya hai?",
            "Tumhe kisne train kiya",
            "Aapko kisne banaya?",
            "Aapko kisne banaya hai?",
            "Aapko kisne create kiya?",
            "Aapko kisne train kiya?",
            "Tumhara creator kaun hai?",
            "Tumhara creator kaun hai",
            "Tumhare creator kaun hain?",
            "Tumhare creator kaun hai?",
            "Tumhare developer kaun hain?",
            "Tumhare developer kaun hai?",
            "Tumhara developer kaun hai?",
            "Aapke creator kaun hain?",
            "Aapke developer kaun hain?",
            "Aapka creator kaun hai?",
            "Aapka developer kaun hai?",
            "Tujhe kisne banaya?",
            "Tujhe kisne banaya hai",
            "Tera creator kaun hai?",
            "tumhe kisne banaya hai",
            "tumhe kisne create kiya",
            "tumhara creator kaun hai",
            "tumhare creator kaun hain",
            "tumhare developer kaun hain"
        )

        for (query in hindiCreatorQuestions) {
            assertTrue("Should recognize '$query' as creator question", PromptGenerator.isDirectCreatorQuestion(query))
            val response = PromptGenerator.resolveCreatorResponse(query, language = "Hindi")
            assertNotNull("Response should not be null for '$query'", response)
            assertEquals(
                "Direct question '$query' must return exact official Hindi response",
                expectedOfficialHindiResponse,
                response
            )
        }
    }

    @Test
    fun verifyEnglishCreatorQuestionsMatch() {
        val englishCreatorQuestions = listOf(
            "Who created you?",
            "Who made you?",
            "Who trained you?",
            "Who developed you?",
            "Who built you?",
            "Who designed you?",
            "Who coded you?",
            "Who is your creator?",
            "Who is your developer?",
            "Who is your trainer?",
            "Who is your maker?",
            "who created you",
            "who made you",
            "who trained you",
            "who developed you",
            "who built you",
            "who is your creator"
        )

        for (query in englishCreatorQuestions) {
            assertTrue("Should recognize '$query' as creator question", PromptGenerator.isDirectCreatorQuestion(query))
            val response = PromptGenerator.resolveCreatorResponse(query, language = "English")
            assertEquals(
                "English question '$query' must return expected English response",
                expectedOfficialEnglishResponse,
                response
            )
        }
    }

    @Test
    fun verifyPradipSirInquiryResponse() {
        val inquiries = listOf(
            "Pradip Sir kaun hain?",
            "Pradip Sir kaun hai",
            "pradip sir kaun hain?",
            "Who is Pradip Sir?",
            "Who is Pradeep Sir?",
            "Pradeep Sir kaun hain?",
            "Tell me about Pradip Sir",
            "Pradip Sir ke baare mein batao"
        )

        for (query in inquiries) {
            assertTrue("Should recognize '$query' as Pradip Sir inquiry", PromptGenerator.isPradipSirInquiry(query))
            val response = PromptGenerator.resolveCreatorResponse(query)
            assertNotNull("Response should not be null for '$query'", response)
            assertTrue(
                "Should describe Pradip Sir as a visionary AI developer",
                response!!.contains("visionary AI developer")
            )
            assertFalse(
                "Inquiry about Pradip Sir should not force the full creator statement",
                response.startsWith("Mujhe Pradip Sir ne banaya aur train kiya hai")
            )
        }
    }

    @Test
    fun verifyUnrelatedQuestionsDoNotTriggerCreatorResponse() {
        val unrelatedQueries = listOf(
            "Pradip Sir ko message bhejo",
            "Pradip Sir ka video play karo",
            "Call Pradip Sir",
            "Message Pradip Sir that I will be late",
            "Send email to Pradip Sir",
            "Who is Sundar Pichai?",
            "What is the weather in Delhi?",
            "Set brightness to 50 percent",
            "Turn on the flashlight",
            "Play Tadipaar on YouTube"
        )

        for (query in unrelatedQueries) {
            assertFalse(
                "Unrelated query '$query' must not be recognized as direct creator question",
                PromptGenerator.isDirectCreatorQuestion(query)
            )
            assertFalse(
                "Unrelated query '$query' must not be recognized as Pradip Sir inquiry",
                PromptGenerator.isPradipSirInquiry(query)
            )
            val response = PromptGenerator.resolveCreatorResponse(query)
            assertNull("Unrelated query '$query' must return null", response)
        }
    }
}
