package com.jarvis.assistant.security

/**
 * Thread-safe, in-memory gatekeeper for high-consequence operations (WhatsApp messages, calls).
 * Enforces explicit human confirmation with a strict 30-second TTL.
 *
 * Rules:
 * 1. Model parameters (e.g. confirmed=true) NEVER bypass this gate.
 * 2. An action must be explicitly staged, previewed to the user, and confirmed by the user.
 * 3. Approvals are single-use, strictly bound to the exact payload, and invalidated upon modification.
 * 4. Stored in volatile memory only; cleared on app/service restart.
 */
enum class SensitiveActionType {
    WHATSAPP_SEND,
    WHATSAPP_CALL,
    PHONE_CALL
}

data class PendingSensitiveAction(
    val actionId: String = java.util.UUID.randomUUID().toString(),
    val type: SensitiveActionType,
    val recipientName: String,
    val targetPhoneDigits: String?,
    val payloadText: String, // Message content or Call Type ("voice" / "video")
    val createdAt: Long = System.currentTimeMillis(),
    val ttlMillis: Long = 30_000L,
    var isUserApproved: Boolean = false
) {
    fun isExpired(): Boolean = (System.currentTimeMillis() - createdAt) > ttlMillis

    fun matches(expectedType: SensitiveActionType, target: String, payload: String): Boolean {
        return type == expectedType &&
                recipientName.equals(target.trim(), ignoreCase = true) &&
                payloadText.trim() == payload.trim()
    }
}

object PendingSensitiveActionManager {
    @Volatile
    private var activeAction: PendingSensitiveAction? = null

    private val lock = Any()

    /**
     * Stages a sensitive action for human confirmation.
     * Overwrites any prior unconfirmed action.
     */
    fun stageAction(
        type: SensitiveActionType,
        recipientName: String,
        targetPhoneDigits: String?,
        payloadText: String
    ): PendingSensitiveAction = synchronized(lock) {
        val action = PendingSensitiveAction(
            type = type,
            recipientName = recipientName.trim(),
            targetPhoneDigits = targetPhoneDigits?.trim(),
            payloadText = payloadText.trim(),
            isUserApproved = false
        )
        activeAction = action
        action
    }

    /**
     * Returns the currently active pending action if it has not expired.
     */
    fun getActiveAction(): PendingSensitiveAction? = synchronized(lock) {
        val current = activeAction ?: return null
        if (current.isExpired()) {
            activeAction = null
            return null
        }
        current
    }

    fun hasPendingAction(): Boolean = getActiveAction() != null

    /**
     * Marks the currently active pending action as user-approved.
     * Returns the approved action, or null if expired/absent.
     */
    fun approveActiveAction(): PendingSensitiveAction? = synchronized(lock) {
        val current = getActiveAction() ?: return null
        current.isUserApproved = true
        current
    }

    /**
     * Validates and consumes an approved action token for immediate execution.
     * Once consumed, the token is cleared (single-use, non-replayable).
     */
    fun consumeApprovedAction(
        expectedType: SensitiveActionType,
        recipientName: String,
        payloadText: String
    ): PendingSensitiveAction? = synchronized(lock) {
        val current = getActiveAction() ?: return null
        if (!current.isUserApproved) return null
        if (!current.matches(expectedType, recipientName, payloadText)) return null
        activeAction = null
        current
    }

    /**
     * Validates whether an active approved action matches the given type.
     */
    fun hasActiveApprovedAction(expectedType: SensitiveActionType): Boolean = synchronized(lock) {
        val current = getActiveAction() ?: return@synchronized false
        current.isUserApproved && current.type == expectedType
    }

    /**
     * For accessibility auto-send verification: checks if an approved send action exists.
     */
    fun hasActiveApprovedSend(): Boolean = synchronized(lock) {
        val current = getActiveAction() ?: return@synchronized false
        current.isUserApproved && current.type == SensitiveActionType.WHATSAPP_SEND
    }

    fun getApprovedSendPayload(): String? = synchronized(lock) {
        val current = getActiveAction() ?: return null
        if (current.isUserApproved && current.type == SensitiveActionType.WHATSAPP_SEND) {
            current.payloadText
        } else null
    }

    /**
     * Cancels the active action on user rejection or timeout.
     */
    fun cancelActiveAction(): PendingSensitiveAction? = synchronized(lock) {
        val current = activeAction
        activeAction = null
        current
    }
}
