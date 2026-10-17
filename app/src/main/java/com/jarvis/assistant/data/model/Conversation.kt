package com.jarvis.assistant.data.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class DateGroup(val displayTitle: String) {
    TODAY("TODAY"),
    YESTERDAY("YESTERDAY"),
    THIS_WEEK("THIS WEEK"),
    THIS_MONTH("THIS MONTH"),
    OLDER("OLDER")
}

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val turns: List<ChatTurn> = emptyList(),
    val isStarred: Boolean = false
) {
    val dateGroup: DateGroup
        get() = DateGroupHelper.resolveGroup(updatedAt)

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("EEE, d MMM yyyy • h:mm a", Locale.getDefault())
            return sdf.format(Date(updatedAt))
        }

    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            return sdf.format(Date(updatedAt))
        }

    val snippet: String
        get() {
            val lastTurn = turns.lastOrNull() ?: return "No messages yet"
            val resp = lastTurn.jarvisResponse.trim()
            if (resp.isNotEmpty() && !resp.startsWith("(")) return resp
            val query = lastTurn.userTranscript.trim()
            if (query.isNotEmpty() && !query.startsWith("(")) return query
            return "Session active"
        }

    companion object {
        fun generateTitle(rawText: String): String {
            val clean = rawText.trim()
                .replace(Regex("^(?:hey jarvis|hi jarvis|hello jarvis|ok jarvis|jarvis)[,\\s]*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("[\\n\\r\\t]+"), " ")
                .trim()
            if (clean.isBlank()) return "New Chat"
            return if (clean.length > 32) {
                clean.take(32).trimEnd() + "..."
            } else {
                clean
            }
        }
    }
}

object DateGroupHelper {
    fun resolveGroup(timestamp: Long): DateGroup {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }

        val isSameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        if (isSameDay) return DateGroup.TODAY

        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        if (isYesterday) return DateGroup.YESTERDAY

        val daysDiff = ((now.timeInMillis - target.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
        return when {
            daysDiff in 0..7 -> DateGroup.THIS_WEEK
            daysDiff in 8..30 -> DateGroup.THIS_MONTH
            else -> DateGroup.OLDER
        }
    }
}
