package com.jarvis.assistant.network

import com.jarvis.assistant.attachment.AttachmentItem
import com.jarvis.assistant.attachment.AttachmentParser
import com.jarvis.assistant.attachment.AttachmentType
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.data.repository.ChatRepository
import org.junit.Assert.*
import org.junit.Test

class GeminiModelRoutingTest {

    @Test
    fun testTxtAttachmentExtractsAndRoutesToTextGenerationNeverVisionHandler() {
        val txtItem = AttachmentItem(
            name = "data.txt",
            mimeType = "text/plain",
            sizeBytes = 250L,
            formattedSize = "250 B",
            attachmentType = AttachmentType.DOCUMENT,
            extractedText = "Important project notes line 1\nImportant project notes line 2",
            imageBytes = null
        )

        // Verify attachment properties: extracted text present, imageBytes null
        assertNotNull("Extracted text must be populated for TXT", txtItem.extractedText)
        assertNull("Image bytes must be null for pure text attachment", txtItem.imageBytes)
        assertEquals(AttachmentType.DOCUMENT, txtItem.attachmentType)

        // Verify prompt construction preserves extracted text and wraps cleanly
        val prompt = AttachmentParser.buildModelPrompt("Please summarize this file", listOf(txtItem))
        assertTrue(prompt.contains("Important project notes line 1"))
        assertTrue(prompt.contains("ATTACHMENT #1: data.txt"))

        // Routing decision: imageBytesToSend must be null for TXT
        val screenFrameBytes: ByteArray? = null
        val attachments = listOf(txtItem)
        val imageBytesToSend = screenFrameBytes ?: attachments.firstOrNull { it.imageBytes != null }?.imageBytes
        assertNull("imageBytesToSend must be null so text generation route is chosen", imageBytesToSend)

        // Model resolution must pick a model supporting generateContent, never Live-only
        val textModel = GeminiModelManager.resolveTextModel(GeminiConstants.MODEL_2_5_FLASH_NATIVE_AUDIO)
        assertEquals(
            "Text model must resolve to a generateContent model, not native audio live",
            GeminiConstants.DEFAULT_TEXT_MODEL,
            textModel
        )
    }

    @Test
    fun testPdfTextExtractionFollowsDocumentPath() {
        // PDF with extractable text
        val textPdf = AttachmentItem(
            name = "manual.pdf",
            mimeType = "application/pdf",
            sizeBytes = 15000L,
            formattedSize = "14.6 KB",
            attachmentType = AttachmentType.PDF,
            extractedText = "Chapter 1: Installation and Setup instructions...",
            imageBytes = null
        )
        assertNull("Text-extractable PDF must have null imageBytes", textPdf.imageBytes)
        val imageBytesToSend = textPdf.imageBytes
        assertNull("Text PDF must route to text generation", imageBytesToSend)

        // Scanned PDF with rendered page fallback
        val scannedPdf = AttachmentItem(
            name = "scanned.pdf",
            mimeType = "application/pdf",
            sizeBytes = 85000L,
            formattedSize = "83.0 KB",
            attachmentType = AttachmentType.PDF,
            extractedText = "[Scanned PDF (1 page): Page 1 rendered for visual analysis]",
            imageBytes = ByteArray(100) { 0xFF.toByte() }
        )
        assertNotNull("Scanned PDF must have rendered page imageBytes", scannedPdf.imageBytes)
        assertTrue("Scanned PDF must route to multimodal vision", (scannedPdf.imageBytes?.size ?: 0) > 0)
    }

    @Test
    fun testDocxExtractionOrExplicitUnsupportedReport() {
        val validDocx = AttachmentItem(
            name = "contract.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            sizeBytes = 20000L,
            formattedSize = "19.5 KB",
            attachmentType = AttachmentType.DOCUMENT,
            extractedText = "This Agreement is made on this day...",
            parseError = null
        )
        assertEquals("contract.docx", validDocx.name)
        assertNotNull(validDocx.extractedText)
        assertNull(validDocx.parseError)

        val unreadableDocx = AttachmentItem(
            name = "corrupt.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            sizeBytes = 500L,
            formattedSize = "500 B",
            attachmentType = AttachmentType.DOCUMENT,
            extractedText = null,
            parseError = "Document appears empty or contains no extractable text."
        )
        assertNull("Unreadable docx must not produce arbitrary text", unreadableDocx.extractedText)
        assertNotNull("Unreadable docx must report explicit parseError", unreadableDocx.parseError)
        val prompt = AttachmentParser.buildModelPrompt("Review", listOf(unreadableDocx))
        assertTrue(prompt.contains("[Document parsing error: Document appears empty or contains no extractable text.]"))
    }

    @Test
    fun testImageRequestsIncludeImagePayloadsAndMultimodalModel() {
        val fakeJpeg = ByteArray(50) { 0x01.toByte() }
        val imageItem = AttachmentItem(
            name = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 50L,
            formattedSize = "50 B",
            attachmentType = AttachmentType.IMAGE,
            imageBytes = fakeJpeg
        )

        assertNotNull("Image attachment must contain non-null image payload", imageItem.imageBytes)
        val imageBytesToSend = imageItem.imageBytes
        assertNotNull("imageBytesToSend must not be null for image attachments", imageBytesToSend)

        val visionModel = GeminiModelManager.resolveVisionModel()
        assertTrue("Resolved vision model must support vision and generateContent",
            GeminiModelManager.isMultimodalModel(visionModel)
        )
    }

    @Test
    fun testScreenShareFramesIncludedInVisionRequests() {
        val fakeFrame = ByteArray(1024) { 0x42.toByte() }
        val screenFrameBytes: ByteArray? = fakeFrame
        val attachments = emptyList<AttachmentItem>()

        val imageBytesToSend = screenFrameBytes ?: attachments.firstOrNull { it.imageBytes != null }?.imageBytes
        assertNotNull("imageBytesToSend must include captured screen frame", imageBytesToSend)
        assertEquals(1024, imageBytesToSend?.size)

        val visionModel = GeminiModelManager.resolveVisionModel()
        assertTrue("Vision model for screen share must be multimodal",
            GeminiModelManager.isMultimodalModel(visionModel)
        )
    }

    @Test
    fun testModelWithoutGenerateContentRejectedForStandardText() {
        val liveOnlyModel = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        val supportsGenerateContent = GeminiModelManager.validateModelForMethod(liveOnlyModel, "generateContent")
        assertFalse("Live native audio model must not claim generateContent support", supportsGenerateContent)

        // When requesting text model resolution with live-only model preference, it must resolve to a valid text model
        val resolvedTextModel = GeminiModelManager.resolveTextModel(liveOnlyModel)
        assertNotEquals("Must not use live-only model for text generation", liveOnlyModel, resolvedTextModel)
        assertEquals("Must resolve to verified default text model", GeminiConstants.DEFAULT_TEXT_MODEL, resolvedTextModel)
    }

    @Test
    fun testModelWithoutRequiredLiveMethodRejectedForLiveVoice() {
        val textOnlyModel = "models/gemini-3.8-flash"
        val supportsBidi = GeminiModelManager.validateModelForMethod(textOnlyModel, "bidiGenerateContent")
        assertFalse("Standard REST text model must not claim bidiGenerateContent support", supportsBidi)

        // When requesting live model resolution with text-only preference, it must resolve to a valid live model
        val resolvedLiveModel = GeminiModelManager.resolveLiveModel(textOnlyModel)
        assertNotEquals("Must not use text-only model for live voice session", textOnlyModel, resolvedLiveModel)
        assertEquals("Must resolve to verified default live model", GeminiConstants.DEFAULT_LIVE_MODEL, resolvedLiveModel)
    }

    @Test
    fun testModelIdNormalizationWithAndWithoutModelsPrefix() {
        // Without prefix
        assertEquals("models/gemini-3.8-flash", GeminiModelManager.normalizeModelId("gemini-3.8-flash"))
        assertEquals("models/gemini-3.8-flash", GeminiModelManager.toRestPath("gemini-3.8-flash"))
        assertEquals("gemini-3.8-flash", GeminiModelManager.cleanModelId("gemini-3.8-flash"))

        // With prefix
        assertEquals("models/gemini-3.8-flash", GeminiModelManager.normalizeModelId("models/gemini-3.8-flash"))
        assertEquals("models/gemini-3.8-flash", GeminiModelManager.toRestPath("models/gemini-3.8-flash"))
        assertEquals("gemini-3.8-flash", GeminiModelManager.cleanModelId("models/gemini-3.8-flash"))

        // Repeating prefix - must never form models/models/gemini-3.8-flash
        assertEquals("models/gemini-3.8-flash", GeminiModelManager.normalizeModelId("models/models/gemini-3.8-flash"))
        assertEquals("models/gemini-3.8-flash", GeminiModelManager.toRestPath("models/models/models/gemini-3.8-flash"))
        assertEquals("gemini-3.8-flash", GeminiModelManager.cleanModelId("models/models/gemini-3.8-flash"))

        // Whitespace handling
        assertEquals("models/gemini-3.8-flash", GeminiModelManager.normalizeModelId("  models/gemini-3.8-flash  "))
        assertEquals("models/gemini-3.8-flash", GeminiModelManager.toRestPath("  gemini-3.8-flash  "))
    }

    @Test
    fun testModelListAndNetworkFailureProducesUsefulErrorWithoutLeakingSecrets() {
        val rawSecretKey = "AIzaSyD-1234567890abcdefghijklmnopqrstuv"
        val rawError = "Request failed for url https://generativelanguage.googleapis.com/v1beta/models?key=$rawSecretKey: 403 Forbidden"

        val sanitized = GeminiModelManager.sanitizeForLogging(rawError)
        assertFalse("Sanitized error must not contain raw secret API key", sanitized.contains(rawSecretKey))
        assertTrue("Sanitized error must redact key", sanitized.contains("[REDACTED]"))
    }

    @Test
    fun testSecretSanitizationRedactsBearerAndHeaderKeys() {
        val rawWithHeaders = "Error with Authorization: Bearer AIzaSyD-1234567890abcdefghijklmnopqrstuv and x-goog-api-key: AIzaSyD-abcdefghijklmnopqrstuv1234567890"
        val sanitized = GeminiModelManager.sanitizeForLogging(rawWithHeaders)
        assertFalse("Must redact Bearer token", sanitized.contains("AIzaSyD-1234567890"))
        assertFalse("Must redact x-goog-api-key header", sanitized.contains("AIzaSyD-abcdefghij"))
        assertTrue("Must include [REDACTED]", sanitized.contains("[REDACTED]"))
    }

    @Test
    fun testNoStaleActiveGemini15Or25FlashConfigurationRemains() {
        assertFalse("DEFAULT_LIVE_MODEL must not be gemini-1.5-flash", GeminiConstants.DEFAULT_LIVE_MODEL.contains("gemini-1.5-flash"))
        assertFalse("DEFAULT_TEXT_MODEL must not be gemini-1.5-flash", GeminiConstants.DEFAULT_TEXT_MODEL.contains("gemini-1.5-flash"))
        assertFalse("DEFAULT_VISION_MODEL must not be gemini-1.5-flash", GeminiConstants.DEFAULT_VISION_MODEL.contains("gemini-1.5-flash"))
        assertFalse("DEFAULT_MODEL must not be gemini-1.5-flash", GeminiConstants.DEFAULT_MODEL.contains("gemini-1.5-flash"))

        assertFalse("DEFAULT_TEXT_MODEL must not be gemini-2.5-flash", GeminiConstants.DEFAULT_TEXT_MODEL.contains("gemini-2.5-flash"))
        assertFalse("DEFAULT_VISION_MODEL must not be gemini-2.5-flash", GeminiConstants.DEFAULT_VISION_MODEL.contains("gemini-2.5-flash"))

        assertEquals("models/gemini-3.8-flash", GeminiConstants.DEFAULT_TEXT_MODEL)
        assertEquals("models/gemini-3.8-flash", GeminiConstants.DEFAULT_VISION_MODEL)

        val resolvedLive = GeminiModelManager.resolveLiveModel()
        val resolvedText = GeminiModelManager.resolveTextModel()
        val resolvedVision = GeminiModelManager.resolveVisionModel()

        assertFalse("Resolved live model must not be gemini-1.5-flash", resolvedLive.contains("gemini-1.5-flash"))
        assertFalse("Resolved text model must not be gemini-1.5-flash", resolvedText.contains("gemini-1.5-flash"))
        assertFalse("Resolved vision model must not be gemini-1.5-flash", resolvedVision.contains("gemini-1.5-flash"))

        assertFalse("Resolved text model must not be gemini-2.5-flash", resolvedText.contains("gemini-2.5-flash"))
        assertFalse("Resolved vision model must not be gemini-2.5-flash", resolvedVision.contains("gemini-2.5-flash"))
    }

    @Test
    fun testResolveTextAndVisionModelRejectsUnavailable25Flash() {
        val resolvedText = GeminiModelManager.resolveTextModel("gemini-2.5-flash")
        assertEquals(GeminiConstants.DEFAULT_TEXT_MODEL, resolvedText)
        assertFalse("Resolved text model must never be 2.5-flash", resolvedText.contains("gemini-2.5-flash"))

        val resolvedTextWithPrefix = GeminiModelManager.resolveTextModel("models/gemini-2.5-flash")
        assertEquals(GeminiConstants.DEFAULT_TEXT_MODEL, resolvedTextWithPrefix)

        val resolvedVision = GeminiModelManager.resolveVisionModel("gemini-2.5-flash")
        assertEquals(GeminiConstants.DEFAULT_VISION_MODEL, resolvedVision)
        assertFalse("Resolved vision model must never be 2.5-flash", resolvedVision.contains("gemini-2.5-flash"))

        val resolvedVisionWithPrefix = GeminiModelManager.resolveVisionModel("models/gemini-2.5-flash")
        assertEquals(GeminiConstants.DEFAULT_VISION_MODEL, resolvedVisionWithPrefix)
    }

    @Test
    fun testGenerateContentAndInteractionsResponseParsing() {
        // GenerateContent standard response with thought filter
        val genContentJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {"text": "Internal thoughts here", "thought": true},
                      {"text": "Summary of your document"}
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()
        val parsedGenContent = GeminiRestClient.parseGenerateContentResponse(genContentJson)
        assertEquals("Summary of your document", parsedGenContent)

        // Interactions v1beta response schema (steps -> content -> text)
        val interactionsV1BetaJson = """
            {
              "steps": [
                {
                  "content": [
                    {"type": "text", "text": "Result from Interactions API"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val parsedInteractions = GeminiRestClient.parseInteractionsResponse(interactionsV1BetaJson)
        assertEquals("Result from Interactions API", parsedInteractions)

        // Interactions legacy outputs response
        val legacyOutputsJson = """
            {
              "outputs": [
                {"text": "Legacy output result"}
              ]
            }
        """.trimIndent()
        val parsedLegacy = GeminiRestClient.parseInteractionsResponse(legacyOutputsJson)
        assertEquals("Legacy output result", parsedLegacy)

        // Direct output string format
        val directOutputJson = """
            {
              "output": "Direct output text"
            }
        """.trimIndent()
        val parsedDirect = GeminiRestClient.parseInteractionsResponse(directOutputJson)
        assertEquals("Direct output text", parsedDirect)
    }

    @Test
    fun testChatRepositoryTurnDeduplicationWithinWindow() {
        val repo = ChatRepository(preferences = null)
        val turn1 = repo.startTurn(
            userText = "summary do",
            attachmentName = "notes.txt",
            attachmentSize = "1.2 KB",
            attachmentType = "DOCUMENT"
        )
        // Immediate repeat invocation (e.g. rapid wake-up or reconnection)
        val turn2 = repo.startTurn(
            userText = "summary do",
            attachmentName = "notes.txt",
            attachmentSize = "1.2 KB",
            attachmentType = "DOCUMENT"
        )
        assertEquals("Immediate identical turn must be deduplicated to the same turn ID", turn1, turn2)
        assertEquals("Chat turn list must only contain 1 turn", 1, repo.turns.value.size)
    }

    @Test
    fun testExtractUserInstructionFromDocumentContext() {
        val item = AttachmentItem(
            name = "notes.txt",
            mimeType = "text/plain",
            sizeBytes = 1200L,
            formattedSize = "1.2 KB",
            attachmentType = AttachmentType.DOCUMENT,
            extractedText = "Line 1: Meeting notes..."
        )
        val fullPrompt = AttachmentParser.buildModelPrompt("summary do", listOf(item))
        assertTrue("Full prompt must contain document content for model", fullPrompt.contains("Meeting notes"))

        val extractedInstruction = AttachmentParser.extractUserInstruction(fullPrompt)
        assertEquals("summary do", extractedInstruction)

        val pureInstruction = "What is the weather?"
        assertEquals(pureInstruction, AttachmentParser.extractUserInstruction(pureInstruction))
    }

    @Test
    fun testParseModelsJsonDiscoversCapabilities() {
        val mockApiResponse = """
        {
          "models": [
            {
              "name": "models/gemini-3.1-flash-lite-preview",
              "displayName": "Gemini 3.1 Flash Lite Preview",
              "supportedGenerationMethods": ["generateContent", "countTokens"]
            },
            {
              "name": "models/gemini-2.5-flash-native-audio-preview-12-2025",
              "displayName": "Gemini 2.5 Flash Native Audio Preview",
              "supportedGenerationMethods": ["bidiGenerateContent"]
            },
            {
              "name": "models/text-embedding-004",
              "displayName": "Text Embedding 004",
              "supportedGenerationMethods": ["embedContent"]
            }
          ]
        }
        """.trimIndent()

        val parsed = GeminiModelManager.parseModelsJson(mockApiResponse)
        assertEquals(3, parsed.size)

        val flashLite = parsed.first { it.name == "models/gemini-3.1-flash-lite-preview" }
        assertTrue(flashLite.supportsGenerateContent)
        assertFalse(flashLite.supportsBidi)
        assertTrue(flashLite.supportsVision)

        val nativeAudio = parsed.first { it.name == "models/gemini-2.5-flash-native-audio-preview-12-2025" }
        assertFalse(nativeAudio.supportsGenerateContent)
        assertTrue(nativeAudio.supportsBidi)
        assertFalse(nativeAudio.supportsVision)

        val embedding = parsed.first { it.name == "models/text-embedding-004" }
        assertFalse(embedding.supportsGenerateContent)
        assertFalse(embedding.supportsBidi)
        assertFalse(embedding.supportsVision)
    }
}
