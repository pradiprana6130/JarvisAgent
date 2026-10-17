package com.jarvis.assistant.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ConversationTest {

    @Test
    fun testGenerateTitleFromVariousInputs() {
        assertEquals("New Chat", Conversation.generateTitle(""))
        assertEquals("New Chat", Conversation.generateTitle("   "))
        assertEquals("New Chat", Conversation.generateTitle("Hey Jarvis"))
        assertEquals("New Chat", Conversation.generateTitle("Hello Jarvis, "))
        assertEquals("Weather in Mumbai", Conversation.generateTitle("Hey Jarvis, Weather in Mumbai"))
        assertEquals("What is the time?", Conversation.generateTitle("What is the time?"))

        val longInput = "Please help me write a very detailed full breakdown of the quantum physics equations for propulsion"
        val title = Conversation.generateTitle(longInput)
        assertTrue("Title should be truncated to 35 chars or less with ellipsis", title.length <= 35)
        assertTrue("Title should end with ...", title.endsWith("..."))
    }

    @Test
    fun testDateGroupHelper() {
        val now = System.currentTimeMillis()
        assertEquals(DateGroup.TODAY, DateGroupHelper.resolveGroup(now))

        val calYesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        assertEquals(DateGroup.YESTERDAY, DateGroupHelper.resolveGroup(calYesterday.timeInMillis))

        val cal3DaysAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -3) }
        assertEquals(DateGroup.THIS_WEEK, DateGroupHelper.resolveGroup(cal3DaysAgo.timeInMillis))

        val cal15DaysAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -15) }
        assertEquals(DateGroup.THIS_MONTH, DateGroupHelper.resolveGroup(cal15DaysAgo.timeInMillis))

        val cal60DaysAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -60) }
        assertEquals(DateGroup.OLDER, DateGroupHelper.resolveGroup(cal60DaysAgo.timeInMillis))
    }

    @Test
    fun testSnippetGeneration() {
        val emptyConv = Conversation()
        assertEquals("No messages yet", emptyConv.snippet)

        val convWithTurn = Conversation(
            turns = listOf(
                ChatTurn(userTranscript = "Hello", jarvisResponse = "Online and ready.")
            )
        )
        assertEquals("Online and ready.", convWithTurn.snippet)

        val convUserOnly = Conversation(
            turns = listOf(
                ChatTurn(userTranscript = "Show me the weather", jarvisResponse = "")
            )
        )
        assertEquals("Show me the weather", convUserOnly.snippet)
    }

    @Test
    fun testFormattedTimeAndDate() {
        val conv = Conversation(updatedAt = 1760000000000L)
        assertFalse(conv.formattedTime.isBlank())
        assertFalse(conv.formattedDate.isBlank())
    }
}
