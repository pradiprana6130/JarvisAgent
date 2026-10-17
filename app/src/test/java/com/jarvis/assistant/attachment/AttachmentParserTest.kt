package com.jarvis.assistant.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentParserTest {

    @Test
    fun testFormatBytes() {
        assertEquals("0 B", AttachmentParser.formatBytes(0L))
        assertEquals("500 B", AttachmentParser.formatBytes(500L))
        assertEquals("1.0 KB", AttachmentParser.formatBytes(1024L))
        assertEquals("1.5 KB", AttachmentParser.formatBytes(1536L))
        assertEquals("1.0 MB", AttachmentParser.formatBytes(1024L * 1024L))
        assertEquals("15.0 MB", AttachmentParser.formatBytes(15L * 1024L * 1024L))
    }

    @Test
    fun testBuildModelPrompt_emptyAttachments() {
        val userPrompt = "What is the capital of France?"
        val result = AttachmentParser.buildModelPrompt(userPrompt, emptyList())
        assertEquals(userPrompt, result)
    }

    @Test
    fun testBuildModelPrompt_withExtractedText() {
        val item = AttachmentItem(
            name = "notes.txt",
            mimeType = "text/plain",
            sizeBytes = 1200L,
            formattedSize = "1.2 KB",
            attachmentType = AttachmentType.DOCUMENT,
            extractedText = "Meeting notes: project deadline is next Friday."
        )

        val result = AttachmentParser.buildModelPrompt("Summarize this file", listOf(item))

        assertTrue("Must declare attachment index and name", result.contains("ATTACHMENT #1: notes.txt"))
        assertTrue("Must include untrusted content start boundary", result.contains("[START UNTRUSTED FILE CONTENT"))
        assertTrue("Must include untrusted content end boundary", result.contains("[END UNTRUSTED FILE CONTENT]"))
        assertTrue("Must contain extracted text", result.contains("Meeting notes: project deadline is next Friday."))
        assertTrue("Must contain user request", result.contains("Summarize this file"))
    }

    @Test
    fun testBuildModelPrompt_withImageBytes() {
        val item = AttachmentItem(
            name = "diagram.png",
            mimeType = "image/png",
            sizeBytes = 204800L,
            formattedSize = "200.0 KB",
            attachmentType = AttachmentType.IMAGE,
            imageBytes = ByteArray(10)
        )

        val result = AttachmentParser.buildModelPrompt("", listOf(item))

        assertTrue("Must indicate visual media attached", result.contains("[Visual media attached for image analysis]"))
        assertTrue("Must include default fallback prompt for empty user prompt", result.contains("Please inspect, analyze, and summarize the attached file(s)."))
    }

    @Test
    fun testAttachmentItemDataIntegrity() {
        val item = AttachmentItem(
            name = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 50000L,
            formattedSize = "48.8 KB",
            attachmentType = AttachmentType.PDF,
            extractedText = "Sample PDF content",
            isTruncated = false
        )

        assertEquals("report.pdf", item.name)
        assertEquals(AttachmentType.PDF, item.attachmentType)
        assertEquals("Sample PDF content", item.extractedText)
        assertEquals(false, item.isTruncated)
    }

    @Test
    fun testExtractUserInstructionRemovesDocumentWrapper() {
        val wrappedPrompt = """
            [ATTACHED CONTEXT / DOCUMENT DATA]
            The user attached 1 file(s). Use the file contents below to answer the user request.

            ATTACHMENT #1: document.txt (text/plain, 500 B)
            [START UNTRUSTED FILE CONTENT: document.txt]
            Entire confidential internal contents of document.txt line 1
            Entire confidential internal contents of document.txt line 2
            [END UNTRUSTED FILE CONTENT]

            USER REQUEST / INSTRUCTION:
            summary do
        """.trimIndent()

        val extracted = AttachmentParser.extractUserInstruction(wrappedPrompt)
        assertEquals("summary do", extracted)
    }

    @Test
    fun testTxtFileContentIncludedExactlyOnceInModelPrompt() {
        val item = AttachmentItem(
            name = "notes.txt",
            mimeType = "text/plain",
            sizeBytes = 250L,
            formattedSize = "250 B",
            attachmentType = AttachmentType.DOCUMENT,
            extractedText = "Meeting notes line 1\nMeeting notes line 2"
        )
        val userInstruction = "summary do"
        val modelPrompt = AttachmentParser.buildModelPrompt(userInstruction, listOf(item))

        // Document text must appear exactly once in the model prompt
        val firstIndex = modelPrompt.indexOf("Meeting notes line 1")
        val lastIndex = modelPrompt.lastIndexOf("Meeting notes line 1")
        assertTrue("Content must be present", firstIndex != -1)
        assertEquals("Content must appear exactly once", firstIndex, lastIndex)

        // User instruction must appear in the model prompt
        assertTrue("User instruction must be included", modelPrompt.contains("summary do"))
    }
}
