package com.jarvis.assistant.attachment

import android.graphics.Bitmap
import android.net.Uri

enum class AttachmentType {
    PDF,
    DOCUMENT, // DOCX, TXT, MD
    IMAGE,    // JPEG, PNG, WEBP
    DATA,     // CSV, JSON
    CODE      // Programming source files
}

data class AttachmentItem(
    val uri: Uri? = null,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val formattedSize: String,
    val attachmentType: AttachmentType,
    val extractedText: String? = null,
    val imageBytes: ByteArray? = null,
    val thumbnail: Bitmap? = null,
    val thumbnailBase64: String? = null,
    val isTruncated: Boolean = false,
    val parseError: String? = null
)
