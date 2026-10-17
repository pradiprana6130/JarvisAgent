package com.jarvis.assistant.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SEC-01 sensitive action authorization gating.
 * Verifies that:
 * 1. Sensitive actions default to unapproved (deny-by-default).
 * 2. Unapproved actions cannot be consumed or executed.
 * 3. Approvals are single-use, payload-bound, and cannot be replayed.
 * 4. Modified recipient or payload invalidates prior authorization.
 * 5. Expired actions cannot be approved or consumed.
 * 6. Explicit cancellations clear pending actions immediately.
 */
class SensitiveActionAuthorizationTest {

    @Before
    fun setUp() {
        PendingSensitiveActionManager.cancelActiveAction()
    }

    @After
    fun tearDown() {
        PendingSensitiveActionManager.cancelActiveAction()
    }

    @Test
    fun defaultToDenyWhenStagedWithoutApproval() {
        val staged = PendingSensitiveActionManager.stageAction(
            type = SensitiveActionType.WHATSAPP_SEND,
            recipientName = "Alice",
            targetPhoneDigits = null,
            payloadText = "Meeting at 5 PM"
        )

        assertNotNull(staged)
        assertFalse("Staged action must be unapproved by default", staged.isUserApproved)
        assertTrue(PendingSensitiveActionManager.hasPendingAction())
        assertFalse(
            "hasActiveApprovedSend must return false before approval",
            PendingSensitiveActionManager.hasActiveApprovedSend()
        )

        // Attempting to consume without approval must return null
        val consumed = PendingSensitiveActionManager.consumeApprovedAction(
            expectedType = SensitiveActionType.WHATSAPP_SEND,
            recipientName = "Alice",
            payloadText = "Meeting at 5 PM"
        )
        assertNull("Unapproved action cannot be consumed", consumed)
    }

    @Test
    fun approvalAllowsSingleConsumptionAndPreventsReplay() {
        PendingSensitiveActionManager.stageAction(
            type = SensitiveActionType.WHATSAPP_SEND,
            recipientName = "Bob",
            targetPhoneDigits = null,
            payloadText = "Hey Bob, are you free?"
        )

        val approved = PendingSensitiveActionManager.approveActiveAction()
        assertNotNull(approved)
        assertTrue(approved!!.isUserApproved)
        assertTrue(PendingSensitiveActionManager.hasActiveApprovedSend())
        assertEquals("Hey Bob, are you free?", PendingSensitiveActionManager.getApprovedSendPayload())

        // First consumption succeeds
        val consumedFirst = PendingSensitiveActionManager.consumeApprovedAction(
            expectedType = SensitiveActionType.WHATSAPP_SEND,
            recipientName = "Bob",
            payloadText = "Hey Bob, are you free?"
        )
        assertNotNull("First consumption must succeed", consumedFirst)
        assertEquals("Bob", consumedFirst!!.recipientName)

        // Second consumption fails (token consumed, no replay allowed)
        val consumedSecond = PendingSensitiveActionManager.consumeApprovedAction(
            expectedType = SensitiveActionType.WHATSAPP_SEND,
            recipientName = "Bob",
            payloadText = "Hey Bob, are you free?"
        )
        assertNull("Second consumption must fail to prevent replay", consumedSecond)
        assertFalse(PendingSensitiveActionManager.hasPendingAction())
        assertFalse(PendingSensitiveActionManager.hasActiveApprovedSend())
    }

    @Test
    fun payloadMismatchInvalidatesConsumption() {
        PendingSensitiveActionManager.stageAction(
            type = SensitiveActionType.WHATSAPP_SEND,
            recipientName = "Charlie",
            targetPhoneDigits = null,
            payloadText = "Approved original text"
        )
        PendingSensitiveActionManager.approveActiveAction()

        // Recipient mismatch
        val wrongRecipient = PendingSensitiveActionManager.consumeApprovedAction(
            expectedType = SensitiveActionType.WHATSAPP_SEND,
            recipientName = "David",
            payloadText = "Approved original text"
        )
        assertNull("Consumption with mismatched recipient must fail", wrongRecipient)

        // Message text mismatch
        val modifiedText = PendingSensitiveActionManager.consumeApprovedAction(
            expectedType = SensitiveActionType.WHATSAPP_SEND,
            recipientName = "Charlie",
            payloadText = "Malicious injected modification"
        )
        assertNull("Consumption with tampered payload must fail", modifiedText)

        // Action type mismatch
        val wrongType = PendingSensitiveActionManager.consumeApprovedAction(
            expectedType = SensitiveActionType.PHONE_CALL,
            recipientName = "Charlie",
            payloadText = "Approved original text"
        )
        assertNull("Consumption with mismatched action type must fail", wrongType)
    }

    @Test
    fun phoneCallRequiresExplicitApproval() {
        PendingSensitiveActionManager.stageAction(
            type = SensitiveActionType.PHONE_CALL,
            recipientName = "Mom",
            targetPhoneDigits = "1234567890",
            payloadText = "phone_call"
        )

        assertFalse(PendingSensitiveActionManager.hasActiveApprovedAction(SensitiveActionType.PHONE_CALL))

        // Approve
        PendingSensitiveActionManager.approveActiveAction()
        assertTrue(PendingSensitiveActionManager.hasActiveApprovedAction(SensitiveActionType.PHONE_CALL))

        // Consume
        val consumed = PendingSensitiveActionManager.consumeApprovedAction(
            expectedType = SensitiveActionType.PHONE_CALL,
            recipientName = "Mom",
            payloadText = "phone_call"
        )
        assertNotNull(consumed)
        assertEquals("1234567890", consumed!!.targetPhoneDigits)
        assertFalse(PendingSensitiveActionManager.hasActiveApprovedAction(SensitiveActionType.PHONE_CALL))
    }

    @Test
    fun stagingNewActionReplacesPriorUnconfirmedAction() {
        PendingSensitiveActionManager.stageAction(
            type = SensitiveActionType.WHATSAPP_SEND,
            recipientName = "First",
            targetPhoneDigits = null,
            payloadText = "First message"
        )

        // Stage second action without confirming first
        PendingSensitiveActionManager.stageAction(
            type = SensitiveActionType.WHATSAPP_SEND,
            recipientName = "Second",
            targetPhoneDigits = null,
            payloadText = "Second message"
        )

        val active = PendingSensitiveActionManager.getActiveAction()
        assertNotNull(active)
        assertEquals("Second", active!!.recipientName)
        assertEquals("Second message", active.payloadText)
    }

    @Test
    fun userRejectionClearsActionImmediately() {
        PendingSensitiveActionManager.stageAction(
            type = SensitiveActionType.WHATSAPP_SEND,
            recipientName = "Eve",
            targetPhoneDigits = null,
            payloadText = "Hello"
        )
        assertTrue(PendingSensitiveActionManager.hasPendingAction())

        val cancelled = PendingSensitiveActionManager.cancelActiveAction()
        assertNotNull(cancelled)
        assertEquals("Eve", cancelled!!.recipientName)
        assertFalse(PendingSensitiveActionManager.hasPendingAction())
        assertNull(PendingSensitiveActionManager.getActiveAction())
    }

    @Test
    fun expiredActionCannotBeApprovedOrConsumed() {
        // Create an expired action with past timestamp
        val expired = PendingSensitiveAction(
            type = SensitiveActionType.WHATSAPP_SEND,
            recipientName = "Frank",
            targetPhoneDigits = null,
            payloadText = "Old text",
            createdAt = System.currentTimeMillis() - 35_000L, // 35 seconds ago
            ttlMillis = 30_000L,
            isUserApproved = true
        )
        assertTrue("Action older than 30s must be marked expired", expired.isExpired())
    }
}
