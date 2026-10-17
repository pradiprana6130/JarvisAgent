package com.jarvis.assistant.background

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenManualOffTest {

    private val assistantName = "Jarvis"

    @Test
    fun testEnglishManualOffCommands() {
        // Standalone
        assertTrue(JarvisConversationService.isManualOffCommand("turn off", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("power off", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("shut down", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("go to sleep", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("please turn off", assistantName))

        // Name First
        assertTrue(JarvisConversationService.isManualOffCommand("Jarvis turn off", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("hey jarvis power off", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("ok jarvis shut down", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("jarvis go to sleep now", assistantName))

        // Action First
        assertTrue(JarvisConversationService.isManualOffCommand("turn off jarvis", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("power off jarvis", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("shut down please jarvis", assistantName))
    }

    @Test
    fun testHinglishAndHindiManualOffCommands() {
        // Standalone
        assertTrue(JarvisConversationService.isManualOffCommand("off ho jao", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("band ho jao", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("sojao", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("so jao", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("band ho", assistantName))

        // Name First
        assertTrue(JarvisConversationService.isManualOffCommand("jarvis off ho jao", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("hey jarvis band ho jao", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("jarvis sojao", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("jarvis so jao ab", assistantName))

        // Action First
        assertTrue(JarvisConversationService.isManualOffCommand("off ho jao jarvis", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("band ho jao jarvis", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("so jao jarvis", assistantName))

        // Devanagari
        assertTrue(JarvisConversationService.isManualOffCommand("बंद हो जाओ", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("ऑफ हो जाओ", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("सोजाओ", assistantName))
        assertTrue(JarvisConversationService.isManualOffCommand("जार्विस बंद हो जाओ", assistantName))
    }

    @Test
    fun testNegativeCommandsDoNotFalseTrigger() {
        assertFalse(JarvisConversationService.isManualOffCommand("turn off the lights", assistantName))
        assertFalse(JarvisConversationService.isManualOffCommand("band karo music", assistantName))
        assertFalse(JarvisConversationService.isManualOffCommand("hello jarvis", assistantName))
        assertFalse(JarvisConversationService.isManualOffCommand("what is the time", assistantName))
        assertFalse(JarvisConversationService.isManualOffCommand("play song on spotify", assistantName))
        assertFalse(JarvisConversationService.isManualOffCommand("continue", assistantName))
        assertFalse(JarvisConversationService.isManualOffCommand("don't turn off", assistantName))
        assertFalse(JarvisConversationService.isManualOffCommand("", assistantName))
    }

    @Test
    fun testWakeWordDetectionAndAliases() {
        assertTrue(JarvisConversationService.containsWakeWord("hey jarvis", assistantName))
        assertTrue(JarvisConversationService.containsWakeWord("hello jarvis", assistantName))
        assertTrue(JarvisConversationService.containsWakeWord("ok jarvis", assistantName))
        assertTrue(JarvisConversationService.containsWakeWord("jarvis", assistantName))

        assertFalse(JarvisConversationService.containsWakeWord("what is the weather", assistantName))
        assertFalse(JarvisConversationService.containsWakeWord("open instagram", assistantName))

        val aliases = JarvisConversationService.getWakeAliasesForName("jarvis")
        assertTrue(aliases.contains("jarvis"))
        assertTrue(aliases.contains("जार्विस"))
    }

    @Test
    fun testVoskGrammarConstruction() {
        val grammar = JarvisConversationService.buildVoskGrammar("jarvis")
        assertTrue(grammar.startsWith("["))
        assertTrue(grammar.endsWith("]"))
        assertTrue(grammar.contains("\"jarvis\""))
        assertTrue(grammar.contains("\"hey jarvis\""))
        assertTrue(grammar.contains("\"turn off\""))
        assertTrue(grammar.contains("\"off ho jao\""))
        assertTrue(grammar.contains("\"[unk]\""))
    }
}
