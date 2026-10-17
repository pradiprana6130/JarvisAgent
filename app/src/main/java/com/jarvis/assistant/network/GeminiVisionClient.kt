package com.jarvis.assistant.network

/**
 * Backward-compatible delegating client for Gemini REST generation.
 * New code should prefer [GeminiRestClient] which distinguishes text generation and vision analysis.
 */
object GeminiVisionClient {

    suspend fun generateContent(
        apiKey: String,
        model: String,
        prompt: String,
        imageBytes: ByteArray? = null,
        mimeType: String = "image/jpeg"
    ): Result<String> {
        return GeminiRestClient.generateContent(
            apiKey = apiKey,
            model = model,
            prompt = prompt,
            imageBytes = imageBytes,
            mimeType = mimeType
        )
    }
}
