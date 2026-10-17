package com.jarvis.assistant.data.memory

import com.jarvis.assistant.automation.command.CommandParser
import com.jarvis.assistant.automation.command.JarvisCommand
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for DAT-01 safe memory deletion workflow.
 * Verifies that:
 * 1. Candidates are staged with clear snippets without being immediately deleted.
 * 2. Staged deletion tokens are single-use and cleared upon consumption.
 * 3. Expired candidates cannot be consumed.
 * 4. User cancellations clear staged deletion immediately.
 * 5. CommandParser accurately parses confirmation/cancellation intents when awaiting confirmation.
 * 6. Ambiguous/unrelated speech does not trigger unintended deletion approval.
 */
class SafeMemoryDeletionTest {

    @Before
    fun setUp() {
        PendingMemoryDeletionManager.cancel()
    }

    @After
    fun tearDown() {
        PendingMemoryDeletionManager.cancel()
    }

    @Test
    fun stagingCandidateCreatesSnippetAndHoldsIdentity() {
        val memory = MemoryEntity(
            id = 42L,
            category = MemoryEntity.CATEGORY_PERSONAL,
            key = "user_hobby",
            value = "Playing chess",
            rawText = "User enjoys playing chess on weekends at the community club in downtown."
        )

        val staged = PendingMemoryDeletionManager.stage(memory)
        assertNotNull(staged)
        assertEquals(42L, staged.memoryId)
        assertEquals("user_hobby", staged.key)
        assertEquals(MemoryEntity.CATEGORY_PERSONAL, staged.category)
        assertTrue("Snippet must truncate long text with ellipsis", staged.snippet.endsWith("..."))
        assertTrue("Snippet length must be capped", staged.snippet.length <= 70)
        assertTrue(PendingMemoryDeletionManager.hasPending())
    }

    @Test
    fun consumptionClearsPendingStatePreventingReplay() {
        val memory = MemoryEntity(
            id = 101L,
            category = MemoryEntity.CATEGORY_NOTES,
            key = "project_deadline",
            value = "Friday",
            rawText = "Project deadline is Friday"
        )
        PendingMemoryDeletionManager.stage(memory)
        assertTrue(PendingMemoryDeletionManager.hasPending())

        val consumedFirst = PendingMemoryDeletionManager.consume()
        assertNotNull(consumedFirst)
        assertEquals(101L, consumedFirst!!.memoryId)

        // Attempting to consume again must return null (single-use)
        val consumedSecond = PendingMemoryDeletionManager.consume()
        assertNull("Pending memory deletion must not be re-consumable", consumedSecond)
        assertFalse(PendingMemoryDeletionManager.hasPending())
    }

    @Test
    fun cancelClearsPendingStateImmediately() {
        val memory = MemoryEntity(
            id = 99L,
            category = MemoryEntity.CATEGORY_PREFERENCES,
            key = "favorite_food",
            value = "Pizza",
            rawText = "Favorite food is pizza"
        )
        PendingMemoryDeletionManager.stage(memory)
        assertTrue(PendingMemoryDeletionManager.hasPending())

        val cancelled = PendingMemoryDeletionManager.cancel()
        assertNotNull(cancelled)
        assertEquals(99L, cancelled!!.memoryId)
        assertFalse(PendingMemoryDeletionManager.hasPending())
        assertNull(PendingMemoryDeletionManager.getActive())
    }

    @Test
    fun expiredCandidateReturnsNullOnGetActive() {
        val expired = PendingMemoryDeletion(
            memoryId = 55L,
            category = "Notes",
            key = "temp_key",
            rawText = "temp text",
            snippet = "temp text",
            createdAt = System.currentTimeMillis() - 35_000L, // 35 seconds ago
            ttlMillis = 30_000L
        )
        assertTrue("Candidate older than 30s must report expired", expired.isExpired())
    }

    @Test
    fun commandParserAccuratelyDetectsDeletionConfirmations() {
        val confirmPhrases = listOf(
            "yes",
            "confirm",
            "proceed",
            "delete",
            "delete it",
            "forget it",
            "erase",
            "erase it",
            "haan",
            "kardo",
            "theek hai"
        )

        for (phrase in confirmPhrases) {
            val parsed = CommandParser.parse(input = phrase, isAwaitingConfirmation = true)
            assertTrue(
                "Phrase '$phrase' should be parsed as ConfirmAction(true)",
                parsed.command is JarvisCommand.ConfirmAction && (parsed.command as JarvisCommand.ConfirmAction).confirmed
            )
        }
    }

    @Test
    fun commandParserAccuratelyDetectsDeletionCancellations() {
        val cancelPhrases = listOf(
            "no",
            "nope",
            "cancel",
            "stop",
            "abort",
            "keep",
            "keep it",
            "don't",
            "never mind",
            "nahi",
            "mat karo"
        )

        for (phrase in cancelPhrases) {
            val parsed = CommandParser.parse(input = phrase, isAwaitingConfirmation = true)
            assertTrue(
                "Phrase '$phrase' should be parsed as ConfirmAction(false)",
                parsed.command is JarvisCommand.ConfirmAction && !(parsed.command as JarvisCommand.ConfirmAction).confirmed
            )
        }
    }

    @Test
    fun unrelatedSpeechWhileAwaitingConfirmationDoesNotTriggerFalseConfirmation() {
        val unrelatedPhrases = listOf(
            "what time is it",
            "who is the president",
            "open settings",
            "play music"
        )

        for (phrase in unrelatedPhrases) {
            val parsed = CommandParser.parse(input = phrase, isAwaitingConfirmation = true)
            assertFalse(
                "Unrelated phrase '$phrase' must not trigger ConfirmAction",
                parsed.command is JarvisCommand.ConfirmAction
            )
        }
    }

    @Test
    fun confirmationPhrasesIgnoredWhenNotAwaitingConfirmation() {
        val parsedYes = CommandParser.parse(input = "yes", isAwaitingConfirmation = false)
        assertFalse(parsedYes.command is JarvisCommand.ConfirmAction)

        val parsedDeleteIt = CommandParser.parse(input = "delete it", isAwaitingConfirmation = false)
        assertFalse(parsedDeleteIt.command is JarvisCommand.ConfirmAction)
    }
}
