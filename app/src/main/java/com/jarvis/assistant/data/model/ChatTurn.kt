package com.jarvis.assistant.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ChatTurn(
    val id: String = UUID.randomUUID().toString(),
    val userTranscript: String = "",
    val jarvisResponse: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentName: String? = null,
    val attachmentSize: String? = null,
    val attachmentType: String? = null,
    val attachmentThumbnailBase64: String? = null
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}
