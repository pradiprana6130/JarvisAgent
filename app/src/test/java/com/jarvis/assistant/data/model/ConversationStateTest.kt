package com.jarvis.assistant.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStateTest {

    @Test
    fun testAllCanonicalStatesExist() {
        val states = ConversationState.values().map { it.name }.toSet()
        val expected = setOf(
            "OFF",
            "STANDBY",
            "IDLE",
            "WAKE_UP",
            "LISTENING",
            "THINKING",
            "EXECUTING",
            "SPEAKING",
            "ERROR"
        )
        assertEquals(expected, states)
        assertEquals(9, ConversationState.values().size)
    }

    @Test
    fun testDisplayNamesAndOrbKeys() {
        assertEquals("OFF", ConversationState.OFF.displayName)
        assertEquals("offline", ConversationState.OFF.orbKey)

        assertEquals("STANDBY", ConversationState.STANDBY.displayName)
        assertEquals("sleep", ConversationState.STANDBY.orbKey)

        assertEquals("IDLE", ConversationState.IDLE.displayName)
        assertEquals("idle", ConversationState.IDLE.orbKey)

        assertEquals("WAKING UP", ConversationState.WAKE_UP.displayName)
        assertEquals("wake_up", ConversationState.WAKE_UP.orbKey)

        assertEquals("LISTENING", ConversationState.LISTENING.displayName)
        assertEquals("listening", ConversationState.LISTENING.orbKey)

        assertEquals("THINKING", ConversationState.THINKING.displayName)
        assertEquals("thinking", ConversationState.THINKING.orbKey)

        assertEquals("EXECUTING", ConversationState.EXECUTING.displayName)
        assertEquals("executing", ConversationState.EXECUTING.orbKey)

        assertEquals("SPEAKING", ConversationState.SPEAKING.displayName)
        assertEquals("speaking", ConversationState.SPEAKING.orbKey)

        assertEquals("ERROR", ConversationState.ERROR.displayName)
        assertEquals("error", ConversationState.ERROR.orbKey)
    }

    @Suppress("DEPRECATION")
    @Test
    fun testDeprecatedAliasesResolveToCanonicalSingletons() {
        // OFFLINE must alias directly to OFF
        assertTrue(ConversationState.OFFLINE === ConversationState.OFF)
        assertEquals(ConversationState.OFF, ConversationState.OFFLINE)

        // SLEEP must alias directly to STANDBY
        assertTrue(ConversationState.SLEEP === ConversationState.STANDBY)
        assertEquals(ConversationState.STANDBY, ConversationState.SLEEP)
    }

    @Test
    fun testExhaustiveWhenWithoutElseBranch() {
        for (state in ConversationState.values()) {
            val label = when (state) {
                ConversationState.OFF -> "off"
                ConversationState.STANDBY -> "standby"
                ConversationState.IDLE -> "idle"
                ConversationState.WAKE_UP -> "wake_up"
                ConversationState.LISTENING -> "listening"
                ConversationState.THINKING -> "thinking"
                ConversationState.EXECUTING -> "executing"
                ConversationState.SPEAKING -> "speaking"
                ConversationState.ERROR -> "error"
            }
            assertTrue(label.isNotBlank())
        }
    }
}
