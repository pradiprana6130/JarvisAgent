package com.jarvis.assistant.network

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiRestClient {

    private const val TAG = "GeminiRestClient"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generateText(
        apiKey: String,
        model: String? = null,
        prompt: String
    ): Result<String> {
        val resolvedModel = GeminiModelManager.resolveTextModel(model)
        return executeGenerateContent(
            apiKey = apiKey,
            model = resolvedModel,
            prompt = prompt,
            imageBytes = null,
            isVisionRequest = false
        )
    }

    suspend fun generateVision(
        apiKey: String,
        model: String? = null,
        prompt: String,
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg"
    ): Result<String> {
        if (imageBytes.isEmpty()) {
            return Result.failure(IllegalArgumentException("Gemini vision analysis failed: Image payload is empty."))
        }
        val resolvedModel = GeminiModelManager.resolveVisionModel(model)
        return executeGenerateContent(
            apiKey = apiKey,
            model = resolvedModel,
            prompt = prompt,
            imageBytes = imageBytes,
            mimeType = mimeType,
            isVisionRequest = true
        )
    }

    suspend fun generateContent(
        apiKey: String,
        model: String? = null,
        prompt: String,
        imageBytes: ByteArray? = null,
        mimeType: String = "image/jpeg"
    ): Result<String> {
        return if (imageBytes != null && imageBytes.isNotEmpty()) {
            generateVision(apiKey, model, prompt, imageBytes, mimeType)
        } else {
            generateText(apiKey, model, prompt)
        }
    }

    private suspend fun executeGenerateContent(
        apiKey: String,
        model: String,
        prompt: String,
        imageBytes: ByteArray?,
        mimeType: String = "image/jpeg",
        isVisionRequest: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        val errorPrefix = if (isVisionRequest) "Gemini vision analysis failed" else "Gemini text generation failed"

        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("$errorPrefix: Gemini API key is not configured in Settings."))
        }

        val restPath = GeminiModelManager.toRestPath(model)
        val url = "https://generativelanguage.googleapis.com/v1beta/$restPath:generateContent?key=$apiKey"

        val partsArray = JSONArray()
        if (prompt.isNotBlank()) {
            partsArray.put(JSONObject().apply { put("text", prompt) })
        }

        if (imageBytes != null && imageBytes.isNotEmpty()) {
            val base64Data = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            partsArray.put(JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", mimeType)
                    put("data", base64Data)
                })
            })
        }

        if (partsArray.length() == 0) {
            return@withContext Result.failure(IllegalArgumentException("$errorPrefix: Request payload is empty."))
        }

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", partsArray)
            }))
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = requestJson.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errMsg = try {
                        val json = JSONObject(bodyString)
                        val err = json.optJSONObject("error")
                        err?.optString("message", "") ?: "HTTP ${response.code}"
                    } catch (_: Exception) {
                        "HTTP ${response.code}: ${response.message}"
                    }
                    val safeErrMsg = GeminiModelManager.sanitizeForLogging(errMsg)
                    Log.e(TAG, "Gemini REST error: $safeErrMsg")
                    return@withContext Result.failure(Exception("$errorPrefix: $safeErrMsg"))
                }

                val json = JSONObject(bodyString)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val finishReason = firstCandidate.optString("finishReason", "")
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val sb = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            sb.append(part.optString("text", ""))
                        }
                        val resultText = sb.toString().trim()
                        if (resultText.isNotEmpty()) {
                            return@withContext Result.success(resultText)
                        }
                    }
                    if (finishReason.isNotBlank() && finishReason != "STOP") {
                        return@withContext Result.failure(Exception("$errorPrefix: Model finished with reason $finishReason."))
                    }
                }
                Result.failure(Exception("$errorPrefix: Model returned an empty response."))
            }
        } catch (e: Exception) {
            val safeMsg = GeminiModelManager.sanitizeForLogging(e.message ?: "Network call failed")
            Log.e(TAG, "Network call failed: $safeMsg", e)
            Result.failure(Exception("$errorPrefix: $safeMsg", e))
        }
    }
}
