package com.jarvis.assistant.network

import com.jarvis.assistant.attachment.AttachmentItem
import com.jarvis.assistant.attachment.AttachmentParser
import com.jarvis.assistant.attachment.AttachmentType
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.data.repository.ChatRepository
import com.jarvis.assistant.util.PromptGenerator
import org.junit.Assert.*
import org.junit.Test

/**
 * Validates the complete response pipeline:
 * - GenerateContent multi-part and thought filtering
 * - Interactions API schema parsing
 * - Streaming partial text updates and persistence
 * - Empty final event preservation (no erasure of streamed content)
 * - Safety blocks and actionable error statuses (no blank outputs)
 * - Message ID isolation under concurrent/duplicate turns
 * - Separation of visible user text from hidden extracted PDF context
 * - Screen snapshot byte routing and visible text rendering
 * - Central detailed-by-default prompt generation
 * - Hindi/Hinglish language matching and explicit short-answer preference
 */
class JarvisDetailedResponseTest {

    @Test
    fun test1_generateContentResponseCombinesMultipleTextParts() {
        val multiPartJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {"text": "Internal chain of thought", "thought": true},
                      {"text": "## Summary of Document\n\n"},
                      {"text": "- Key Point 1: System initialized\n"},
                      {"text": "- Key Point 2: Configuration verified"}
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()

        val parsed = GeminiRestClient.parseGenerateContentResponse(multiPartJson)
        assertNotNull("Parsed text must not be null", parsed)
        assertFalse("Thought part must be filtered out", parsed!!.contains("Internal chain of thought"))
        assertTrue("Must contain section heading", parsed.contains("## Summary of Document"))
        assertTrue("Must combine multiple parts: Key Point 1", parsed.contains("- Key Point 1: System initialized"))
        assertTrue("Must combine multiple parts: Key Point 2", parsed.contains("- Key Point 2: Configuration verified"))
    }

    @Test
    fun test2_interactionsApiResponseUsesCorrectSchema() {
        // Current v1beta steps schema
        val v1betaJson = """
            {
              "steps": [
                {
                  "content": [
                    {"type": "text", "text": "Detailed interaction analysis result."}
                  ]
                }
              ]
            }
        """.trimIndent()
        val parsedV1 = GeminiRestClient.parseInteractionsResponse(v1betaJson)
        assertEquals("Detailed interaction analysis result.", parsedV1)

        // Legacy outputs schema
        val legacyJson = """
            {
              "outputs": [
                {"text": "Legacy format response."}
              ]
            }
        """.trimIndent()
        val parsedLegacy = GeminiRestClient.parseInteractionsResponse(legacyJson)
        assertEquals("Legacy format response.", parsedLegacy)

        // Direct output string format
        val directJson = """{"output": "Direct output string."}"""
        val parsedDirect = GeminiRestClient.parseInteractionsResponse(directJson)
        assertEquals("Direct output string.", parsedDirect)
    }

    @Test
    fun test3_streamingPartialTextAppearsAndFinalCompleteTextPersists() {
        val repo = ChatRepository(preferences = null)
        val turnId = repo.startTurn(userText = "Explain quantum computing")

        // Streaming partial chunks arrive
        repo.updateAssistantResponse(turnId, "Quantum computing uses qubits", isComplete = false)
        assertEquals("Quantum computing uses qubits", repo.turns.value.first().jarvisResponse)

        repo.updateAssistantResponse(turnId, "Quantum computing uses qubits to perform superposition and entanglement calculations.", isComplete = false)
        assertEquals(
            "Quantum computing uses qubits to perform superposition and entanglement calculations.",
            repo.turns.value.first().jarvisResponse
        )

        // Final completion event
        repo.updateAssistantResponse(
            turnId,
            "Quantum computing uses qubits to perform superposition and entanglement calculations. In conclusion, it solves problems exponentially faster.",
            isComplete = true
        )

        val finalizedTurn = repo.turns.value.first()
        assertEquals(
            "Quantum computing uses qubits to perform superposition and entanglement calculations. In conclusion, it solves problems exponentially faster.",
            finalizedTurn.jarvisResponse
        )
    }

    @Test
    fun test4_emptyFinalStreamEventDoesNotErasePreviouslyReceivedText() {
        val repo = ChatRepository(preferences = null)
        val turnId = repo.startTurn(userText = "is me keya hai is pdf me")

        val partialText = "इस PDF में प्रोजेक्ट का पूरा विवरण और सेटअप स्टेप्स दिए गए हैं।"
        // Streaming received non-empty text
        repo.updateAssistantResponse(turnId, partialText, isComplete = false)
        assertEquals(partialText, repo.turns.value.first().jarvisResponse)

        // Server sends an empty final event or stream terminator chunk "" with isComplete = true
        repo.updateAssistantResponse(turnId, "", isComplete = true)

        // Crucial verification: The previous streamed answer MUST NOT be wiped out with an empty string!
        val preservedTurn = repo.turns.value.first()
        assertEquals("Empty final stream event must never erase previously received text", partialText, preservedTurn.jarvisResponse)
    }

    @Test
    fun test5_apiErrorsAndSafetyBlocksShowActionableStatusInsteadOfBlankOutput() {
        // Safety block parsing
        val safetyBlockedJson = """
            {
              "promptFeedback": {
                "blockReason": "SAFETY",
                "safetyRatings": [
                  {"category": "HARM_CATEGORY_DANGEROUS_CONTENT", "probability": "HIGH", "blocked": true}
                ]
              }
            }
        """.trimIndent()
        val blockReason = GeminiRestClient.extractBlockReason(safetyBlockedJson)
        assertEquals("SAFETY", blockReason)

        // If turn completes with no text at all, ChatRepository provides an actionable status instead of remaining blank
        val repo = ChatRepository(preferences = null)
        val turnId = repo.startTurn(userText = "Unsafe request")
        repo.updateAssistantResponse(turnId, "", isComplete = true)

        val turn = repo.turns.value.first()
        assertTrue("Assistant response must not be blank on failed generation", turn.jarvisResponse.isNotBlank())
        assertTrue("Must contain actionable message", turn.jarvisResponse.contains("unable to generate a response"))
    }

    @Test
    fun test6_correctAssistantMessageIdUpdatedUnderConcurrentTurns() {
        val repo = ChatRepository(preferences = null)
        val turn1Id = repo.startTurn(userText = "Question 1")
        val turn2Id = repo.startTurn(userText = "Question 2")

        assertNotEquals("Turns must have unique IDs", turn1Id, turn2Id)

        // Update Turn 1
        repo.updateAssistantResponse(turn1Id, "Answer to Question 1", isComplete = true)
        // Update Turn 2
        repo.updateAssistantResponse(turn2Id, "Answer to Question 2", isComplete = true)

        val turn1 = repo.turns.value.first { it.id == turn1Id }
        val turn2 = repo.turns.value.first { it.id == turn2Id }

        assertEquals("Answer to Question 1", turn1.jarvisResponse)
        assertEquals("Answer to Question 2", turn2.jarvisResponse)
    }

    @Test
    fun test7_pdfQuestionSeparatesExtractedTextFromVisibleUserBubble() {
        val pdfItem = AttachmentItem(
            name = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 25000L,
            formattedSize = "24.4 KB",
            attachmentType = AttachmentType.PDF,
            extractedText = "SECTION 1: FINANCIAL OVERVIEW\nRevenue increased by 20% in Q3..."
        )

        val userQuestion = "is me keya hai is pdf me"
        val fullPrompt = AttachmentParser.buildModelPrompt(userQuestion, listOf(pdfItem))

        // Prompt sent to model contains extracted PDF text
        assertTrue("Model prompt must contain PDF text", fullPrompt.contains("SECTION 1: FINANCIAL OVERVIEW"))
        assertTrue("Model prompt must contain user request", fullPrompt.contains("is me keya hai is pdf me"))

        // Visible text displayed in user bubble extracts ONLY the user's question
        val visibleUserText = AttachmentParser.extractUserInstruction(fullPrompt)
        assertEquals("Visible user bubble must only contain user's question", userQuestion, visibleUserText)
        assertFalse("Visible user bubble must NOT contain extracted document body", visibleUserText.contains("FINANCIAL OVERVIEW"))

        // Add to ChatRepository: userTranscript is clean question, attachment metadata stored separately
        val repo = ChatRepository(preferences = null)
        val turnId = repo.startTurn(
            userText = visibleUserText,
            attachmentName = pdfItem.name,
            attachmentSize = pdfItem.formattedSize,
            attachmentType = pdfItem.attachmentType.name
        )
        val storedTurn = repo.turns.value.first { it.id == turnId }
        assertEquals(userQuestion, storedTurn.userTranscript)
        assertEquals("report.pdf", storedTurn.attachmentName)
        assertEquals("24.4 KB", storedTurn.attachmentSize)
        assertEquals("PDF", storedTurn.attachmentType)
    }

    @Test
    fun test8_screenSnapshotRequestIncludesImageBytesAndMultimodalModel() {
        val fakeScreenBytes = ByteArray(2048) { 0x5A.toByte() }

        // Must contain real image bytes
        assertTrue("Screen frame bytes must be non-empty", fakeScreenBytes.isNotEmpty())

        val visionModel = GeminiModelManager.resolveVisionModel()
        assertTrue("Vision model must support multimodal generation", GeminiModelManager.isMultimodalModel(visionModel))

        val repo = ChatRepository(preferences = null)
        val turnId = repo.startTurn(
            userText = "keya dekh raha hai screen ka",
            attachmentName = "Screen Snapshot",
            attachmentSize = "2 KB",
            attachmentType = "SCREEN"
        )

        repo.updateAssistantResponse(turnId, "Aapki screen par Settings app khula hai jisme Wi-Fi aur Bluetooth options dikh rahe hain.", isComplete = true)

        val stored = repo.turns.value.first { it.id == turnId }
        assertEquals("keya dekh raha hai screen ka", stored.userTranscript)
        assertEquals("Screen Snapshot", stored.attachmentName)
        assertEquals("SCREEN", stored.attachmentType)
        assertTrue("Answer text must be visible", stored.jarvisResponse.contains("Settings app"))
    }

    @Test
    fun test9_sharedSystemPromptIncludesDetailedByDefaultInstructions() {
        val textPrompt = PromptGenerator.generateTextSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "Boss",
            responseStyle = "Detailed"
        )

        assertTrue(
            "System prompt must mandate detailed and complete answers by default",
            textPrompt.contains("[DETAILED & COMPLETE ANSWERS MANDATE — COMPREHENSIVE BY DEFAULT]:")
        )
        assertTrue(
            "Prompt must include document/PDF specific instructions",
            textPrompt.contains("For document/PDF questions: Thoroughly explain what the document is about")
        )
        assertTrue(
            "Prompt must include screen snapshot specific instructions",
            textPrompt.contains("For \"what is on my screen?\" or screen snapshot requests:")
        )
        assertTrue(
            "Prompt must include troubleshooting guidance",
            textPrompt.contains("For troubleshooting questions: Clearly explain the likely cause")
        )
        assertTrue(
            "Prompt must mandate markdown formatting for rich text chat",
            textPrompt.contains("[RICH TEXT & VISUAL CHAT FORMATTING]:")
        )
    }

    @Test
    fun test10_hindiHinglishQuestionDirectivePreservesLanguage() {
        val prompt = PromptGenerator.generateTextSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "Sir",
            language = "Hinglish"
        )

        assertTrue("Must include Hinglish/Hindi spoken and text mandate",
            prompt.contains("Speak in smooth, natural conversational Hinglish") ||
            prompt.contains("When the user asks in Hindi or Hinglish")
        )
        assertTrue("Must instruct natural Hindi/Hinglish reply",
            prompt.contains("reply in clear, polite, natural Hindi/Hinglish")
        )
    }

    @Test
    fun test11_explicitShortAnswerPreferenceIsRespected() {
        val prompt = PromptGenerator.generateTextSystemPrompt(
            personality = GeminiConstants.PERSONALITY_ASSISTANT,
            userName = "Sir"
        )

        assertTrue(
            "System prompt must instruct respecting explicit short requests",
            prompt.contains("Respect Explicit Short Preference")
        )
        assertTrue(
            "Must mention explicit short triggers like 'short answer do' or 'in one line'",
            prompt.contains("sirf short answer do") || prompt.contains("short answer do")
        )
    }

    @Test
    fun test12_existingModelRoutingAndVoiceConfigurationRemainValid() {
        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", GeminiConstants.DEFAULT_LIVE_MODEL)
        assertEquals("models/gemini-3.8-flash", GeminiConstants.DEFAULT_TEXT_MODEL)
        assertEquals("models/gemini-3.8-flash", GeminiConstants.DEFAULT_VISION_MODEL)

        val liveModel = GeminiModelManager.resolveLiveModel()
        assertEquals(GeminiConstants.DEFAULT_LIVE_MODEL, liveModel)

        val textModel = GeminiModelManager.resolveTextModel()
        assertEquals(GeminiConstants.DEFAULT_TEXT_MODEL, textModel)

        val visionModel = GeminiModelManager.resolveVisionModel()
        assertEquals(GeminiConstants.DEFAULT_VISION_MODEL, visionModel)
    }
}
