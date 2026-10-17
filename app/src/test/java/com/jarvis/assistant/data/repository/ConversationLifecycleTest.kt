package com.jarvis.assistant.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLifecycleTest {

    @Test
    fun testStartTurnCreatesConversationAndTurn() {
        val repository = ChatRepository(preferences = null)
        assertTrue("Initial turns should be empty", repository.turns.value.isEmpty())
        assertNull("Initial active conversation ID should be null", repository.activeConversationId.value)

        val turnId = repository.startTurn("What is the time?")
        assertNotNull(turnId)

        assertEquals("Turns list should have exactly 1 turn", 1, repository.turns.value.size)
        val turn = repository.turns.value.first()
        assertEquals(turnId, turn.id)
        assertEquals("What is the time?", turn.userTranscript)
        assertEquals("", turn.jarvisResponse)

        assertEquals("Conversations list should have 1 conversation", 1, repository.conversations.value.size)
        val conv = repository.conversations.value.first()
        assertEquals(repository.activeConversationId.value, conv.id)
        assertEquals("What is the time?", conv.title)
    }

    @Test
    fun testTurnUpdatesInPlaceWithoutDuplicates() {
        val repository = ChatRepository(preferences = null)
        val turnId = repository.startTurn("what is the weather")
        assertEquals(1, repository.turns.value.size)

        // Live voice speech update
        repository.updateUserTranscript(turnId, "what is the weather in Delhi")
        assertEquals(1, repository.turns.value.size)
        assertEquals("what is the weather in Delhi", repository.turns.value.first().userTranscript)

        // Streaming assistant chunks
        repository.updateAssistantResponse(turnId, "Checking Delhi weather...", isComplete = false)
        assertEquals(1, repository.turns.value.size)
        assertEquals("Checking Delhi weather...", repository.turns.value.first().jarvisResponse)

        repository.updateAssistantResponse(turnId, "It is 28 degrees in Delhi.", isComplete = true)
        assertEquals("Should still be exactly 1 turn, never duplicated", 1, repository.turns.value.size)
        val finalTurn = repository.turns.value.first()
        assertEquals("what is the weather in Delhi", finalTurn.userTranscript)
        assertEquals("It is 28 degrees in Delhi.", finalTurn.jarvisResponse)
    }

    @Test
    fun testMultiConversationSwitching() {
        val repository = ChatRepository(preferences = null)

        // First conversation
        repository.startTurn("First conversation query")
        repository.updateAssistantResponse(repository.turns.value.first().id, "First reply", isComplete = true)
        val firstConvId = repository.activeConversationId.value
        assertNotNull(firstConvId)

        // Start new conversation
        repository.startNewConversation()
        assertNull("Active conversation should be reset to null", repository.activeConversationId.value)
        assertTrue("Turns should be empty in fresh chat", repository.turns.value.isEmpty())
        assertEquals("First conversation must still exist in history", 1, repository.conversations.value.size)

        // Second conversation
        repository.startTurn("Second conversation query")
        repository.updateAssistantResponse(repository.turns.value.first().id, "Second reply", isComplete = true)
        val secondConvId = repository.activeConversationId.value
        assertNotNull(secondConvId)
        assertEquals(2, repository.conversations.value.size)

        // Switch back to first conversation
        repository.selectConversation(firstConvId!!)
        assertEquals(firstConvId, repository.activeConversationId.value)
        assertEquals(1, repository.turns.value.size)
        assertEquals("First conversation query", repository.turns.value.first().userTranscript)
        assertEquals("First reply", repository.turns.value.first().jarvisResponse)
    }

    @Test
    fun testDeleteConversation() {
        val repository = ChatRepository(preferences = null)

        repository.startTurn("Conv 1")
        val conv1Id = repository.activeConversationId.value!!

        repository.startNewConversation()
        repository.startTurn("Conv 2")
        val conv2Id = repository.activeConversationId.value!!

        assertEquals(2, repository.conversations.value.size)

        // Delete active conversation (Conv 2)
        repository.deleteConversation(conv2Id)
        assertEquals(1, repository.conversations.value.size)
        assertEquals(conv1Id, repository.conversations.value.first().id)
        assertNull("Active conversation should reset when deleted", repository.activeConversationId.value)
        assertTrue("Turns should be cleared when active conversation is deleted", repository.turns.value.isEmpty())
    }

    @Test
    fun testClearHistory() {
        val repository = ChatRepository(preferences = null)
        repository.startTurn("Conv 1")
        repository.startNewConversation()
        repository.startTurn("Conv 2")
        assertEquals(2, repository.conversations.value.size)

        repository.clearHistory()
        assertTrue("Conversations should be completely cleared", repository.conversations.value.isEmpty())
        assertTrue("Turns should be completely cleared", repository.turns.value.isEmpty())
        assertNull("Active conversation should be null", repository.activeConversationId.value)
    }
}
