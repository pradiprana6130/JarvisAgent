package com.jarvis.assistant.data.memory

/**
 * Thread-safe, in-memory state holder for safe two-phase voice memory deletion.
 *
 * Rules:
 * 1. Voice commands search candidates without deleting anything.
 * 2. A single match is staged here and previewed to the user.
 * 3. Deletion occurs ONLY when the user explicitly confirms, and strictly by primary key ID.
 * 4. Staged candidates expire after 30 seconds (fail-closed).
 */
data class PendingMemoryDeletion(
    val memoryId: Long,
    val category: String,
    val key: String,
    val rawText: String,
    val snippet: String,
    val createdAt: Long = System.currentTimeMillis(),
    val ttlMillis: Long = 30_000L
) {
    fun isExpired(): Boolean = (System.currentTimeMillis() - createdAt) > ttlMillis
}

object PendingMemoryDeletionManager {
    @Volatile
    private var pending: PendingMemoryDeletion? = null

    private val lock = Any()

    fun stage(candidate: MemoryEntity): PendingMemoryDeletion = synchronized(lock) {
        val snippet = if (candidate.rawText.length > 70) {
            candidate.rawText.take(67) + "..."
        } else {
            candidate.rawText
        }
        val record = PendingMemoryDeletion(
            memoryId = candidate.id,
            category = candidate.category,
            key = candidate.key,
            rawText = candidate.rawText,
            snippet = snippet
        )
        pending = record
        record
    }

    fun getActive(): PendingMemoryDeletion? = synchronized(lock) {
        val current = pending ?: return null
        if (current.isExpired()) {
            pending = null
            return null
        }
        current
    }

    fun consume(): PendingMemoryDeletion? = synchronized(lock) {
        val current = getActive()
        pending = null
        current
    }

    fun cancel(): PendingMemoryDeletion? = synchronized(lock) {
        val current = pending
        pending = null
        current
    }

    fun hasPending(): Boolean = getActive() != null
}
