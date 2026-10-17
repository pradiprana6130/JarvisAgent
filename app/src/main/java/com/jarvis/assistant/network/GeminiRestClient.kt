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
        prompt: String,
        systemInstruction: String? = null,
        customEndpoint: String? = null,
        onPartialResponse: ((String) -> Unit)? = null
    ): Result<String> {
        val resolvedModel = GeminiModelManager.resolveTextModel(model)
        val isInteractions = customEndpoint?.contains("/interactions", ignoreCase = true) == true
        return if (isInteractions) {
            executeInteractions(
                apiKey = apiKey,
                model = resolvedModel,
                prompt = prompt,
                systemInstruction = systemInstruction,
                customEndpoint = customEndpoint
            )
        } else {
            executeGenerateContent(
                apiKey = apiKey,
                model = resolvedModel,
                prompt = prompt,
                imageBytes = null,
                isVisionRequest = false,
                systemInstruction = systemInstruction,
                customEndpoint = customEndpoint,
                onPartialResponse = onPartialResponse
            )
        }
    }

    suspend fun generateInteractions(
        apiKey: String,
        model: String? = null,
        prompt: String,
        systemInstruction: String? = null,
        customEndpoint: String? = null
    ): Result<String> {
        val resolvedModel = GeminiModelManager.resolveTextModel(model)
        return executeInteractions(
            apiKey = apiKey,
            model = resolvedModel,
            prompt = prompt,
            systemInstruction = systemInstruction,
            customEndpoint = customEndpoint
        )
    }

    suspend fun generateVision(
        apiKey: String,
        model: String? = null,
        prompt: String,
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg",
        systemInstruction: String? = null,
        customEndpoint: String? = null,
        onPartialResponse: ((String) -> Unit)? = null
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
            isVisionRequest = true,
            systemInstruction = systemInstruction,
            customEndpoint = customEndpoint,
            onPartialResponse = onPartialResponse
        )
    }

    suspend fun generateContent(
        apiKey: String,
        model: String? = null,
        prompt: String,
        imageBytes: ByteArray? = null,
        mimeType: String = "image/jpeg",
        systemInstruction: String? = null,
        customEndpoint: String? = null,
        onPartialResponse: ((String) -> Unit)? = null
    ): Result<String> {
        return if (imageBytes != null && imageBytes.isNotEmpty()) {
            generateVision(apiKey, model, prompt, imageBytes, mimeType, systemInstruction, customEndpoint, onPartialResponse)
        } else {
            generateText(apiKey, model, prompt, systemInstruction, customEndpoint, onPartialResponse)
        }
    }

    private suspend fun executeGenerateContent(
        apiKey: String,
        model: String,
        prompt: String,
        imageBytes: ByteArray?,
        mimeType: String = "image/jpeg",
        isVisionRequest: Boolean,
        systemInstruction: String? = null,
        customEndpoint: String? = null,
        onPartialResponse: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val errorPrefix = if (isVisionRequest) "Gemini vision analysis failed" else "Gemini text generation failed"

        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("$errorPrefix: Gemini API key is not configured in Settings."))
        }

        val baseUrl = if (!customEndpoint.isNullOrBlank()) {
            customEndpoint.trimEnd('/')
        } else {
            "https://generativelanguage.googleapis.com"
        }

        val restPath = GeminiModelManager.toRestPath(model)

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
            if (!systemInstruction.isNullOrBlank()) {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", systemInstruction)
                    }))
                })
            }
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", partsArray)
            }))
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = requestJson.toString().toRequestBody(mediaType)

        // If streaming callback provided, try SSE streaming endpoint first
        if (onPartialResponse != null) {
            val streamUrl = "$baseUrl/v1beta/$restPath:streamGenerateContent?alt=sse&key=$apiKey"
            val streamRequest = Request.Builder()
                .url(streamUrl)
                .post(requestBody)
                .build()

            try {
                httpClient.newCall(streamRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body
                        if (body != null) {
                            val source = body.source()
                            val accumulatedSb = StringBuilder()
                            while (!source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                val trimmed = line.trim()
                                if (trimmed.startsWith("data:")) {
                                    val dataPayload = trimmed.substring(5).trim()
                                    if (dataPayload.isNotEmpty() && dataPayload != "[DONE]") {
                                        val chunkText = parseGenerateContentResponse(dataPayload)
                                        if (!chunkText.isNullOrEmpty()) {
                                            accumulatedSb.append(chunkText)
                                            onPartialResponse(accumulatedSb.toString())
                                        }
                                    }
                                }
                            }
                            val finalStreamed = accumulatedSb.toString().trim()
                            if (finalStreamed.isNotEmpty()) {
                                return@withContext Result.success(finalStreamed)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Streaming request failed, falling back to standard generateContent: ${e.message}")
            }
        }

        // Standard non-streaming generateContent call
        val url = "$baseUrl/v1beta/$restPath:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val safeErrMsg = parseErrorBody(bodyString, response.code, response.message)
                    Log.e(TAG, "Gemini REST error: $safeErrMsg")
                    return@withContext Result.failure(Exception("$errorPrefix: $safeErrMsg"))
                }

                val parsedText = parseGenerateContentResponse(bodyString)
                if (!parsedText.isNullOrBlank()) {
                    onPartialResponse?.invoke(parsedText)
                    return@withContext Result.success(parsedText)
                }

                val blockReason = extractBlockReason(bodyString)
                if (blockReason.isNotBlank()) {
                    return@withContext Result.failure(Exception("$errorPrefix: Content generation was blocked by safety policy (blockReason: $blockReason)."))
                }

                val finishReason = extractFinishReason(bodyString)
                if (finishReason.isNotBlank() && finishReason != "STOP") {
                    val finishDesc = when (finishReason) {
                        "SAFETY" -> "blocked by content safety filters"
                        "RECITATION" -> "stopped due to recitation checks"
                        "MAX_TOKENS" -> "reached maximum token length"
                        else -> "model finished with reason $finishReason"
                    }
                    return@withContext Result.failure(Exception("$errorPrefix: Response generation was $finishDesc."))
                }

                Result.failure(Exception("$errorPrefix: Model returned an empty response. Please try rephrasing your request."))
            }
        } catch (e: Exception) {
            val safeMsg = GeminiModelManager.sanitizeForLogging(e.message ?: "Network call failed")
            Log.e(TAG, "Network call failed: $safeMsg", e)
            Result.failure(Exception("$errorPrefix: $safeMsg", e))
        }
    }

    private suspend fun executeInteractions(
        apiKey: String,
        model: String,
        prompt: String,
        systemInstruction: String? = null,
        customEndpoint: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val errorPrefix = "Gemini Interactions API failed"

        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("$errorPrefix: Gemini API key is not configured in Settings."))
        }

        val baseUrl = if (!customEndpoint.isNullOrBlank()) {
            customEndpoint.trimEnd('/')
        } else {
            "https://generativelanguage.googleapis.com"
        }

        val cleanModel = GeminiModelManager.cleanModelId(model)
        val url = if (baseUrl.endsWith("/interactions")) {
            "$baseUrl?key=$apiKey"
        } else {
            "$baseUrl/v1beta/interactions?key=$apiKey"
        }

        // Schema for Interactions API: {"model": "...", "input": "..."}
        // NOTE: Do NOT send GenerateContent's contents/parts body to Interactions endpoint!
        val requestJson = JSONObject().apply {
            put("model", cleanModel)
            put("input", prompt)
            if (!systemInstruction.isNullOrBlank()) {
                put("instructions", systemInstruction)
            }
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
                    val safeErrMsg = parseErrorBody(bodyString, response.code, response.message)
                    Log.e(TAG, "Gemini Interactions error: $safeErrMsg")
                    return@withContext Result.failure(Exception("$errorPrefix: $safeErrMsg"))
                }

                val parsedText = parseInteractionsResponse(bodyString)
                if (!parsedText.isNullOrBlank()) {
                    return@withContext Result.success(parsedText)
                }

                Result.failure(Exception("$errorPrefix: Interactions API returned an empty response."))
            }
        } catch (e: Exception) {
            val safeMsg = GeminiModelManager.sanitizeForLogging(e.message ?: "Network call failed")
            Log.e(TAG, "Interactions network call failed: $safeMsg", e)
            Result.failure(Exception("$errorPrefix: $safeMsg", e))
        }
    }

    fun parseGenerateContentResponse(bodyString: String): String? {
        return try {
            val parsed = com.google.gson.JsonParser.parseString(bodyString)
            val candidatesArray = when {
                parsed.isJsonObject -> {
                    parsed.asJsonObject.getAsJsonArray("candidates")
                }
                parsed.isJsonArray -> {
                    val merged = com.google.gson.JsonArray()
                    for (elem in parsed.asJsonArray) {
                        if (elem.isJsonObject) {
                            elem.asJsonObject.getAsJsonArray("candidates")?.forEach { merged.add(it) }
                        }
                    }
                    merged
                }
                else -> null
            } ?: return null

            if (candidatesArray.size() == 0) return null

            val nonThoughtSb = StringBuilder()
            val allSb = StringBuilder()
            for (candElem in candidatesArray) {
                if (!candElem.isJsonObject) continue
                val candObj = candElem.asJsonObject
                val content = candObj.getAsJsonObject("content") ?: continue
                val parts = content.getAsJsonArray("parts") ?: continue

                for (elem in parts) {
                    if (!elem.isJsonObject) continue
                    val part = elem.asJsonObject
                    val text = part.get("text")?.asString ?: ""
                    val isThought = part.get("thought")?.asBoolean ?: false
                    allSb.append(text)
                    if (!isThought) {
                        nonThoughtSb.append(text)
                    }
                }
            }
            val nonThoughtResult = nonThoughtSb.toString().trim()
            if (nonThoughtResult.isNotEmpty()) {
                return nonThoughtResult
            }
            val allResult = allSb.toString().trim()
            if (allResult.isNotEmpty()) {
                return allResult
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun extractBlockReason(bodyString: String): String {
        return try {
            val root = com.google.gson.JsonParser.parseString(bodyString).asJsonObject
            root.getAsJsonObject("promptFeedback")?.get("blockReason")?.asString ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun parseInteractionsResponse(bodyString: String): String? {
        return try {
            val root = com.google.gson.JsonParser.parseString(bodyString).asJsonObject

            // Current v1beta format: steps -> content -> text
            val steps = root.getAsJsonArray("steps")
            if (steps != null && steps.size() > 0) {
                val sb = StringBuilder()
                for (sElem in steps) {
                    if (!sElem.isJsonObject) continue
                    val step = sElem.asJsonObject
                    val content = step.getAsJsonArray("content") ?: continue
                    for (cElem in content) {
                        if (!cElem.isJsonObject) continue
                        val item = cElem.asJsonObject
                        val text = item.get("text")?.asString ?: ""
                        if (text.isNotBlank()) sb.append(text)
                    }
                }
                val res = sb.toString().trim()
                if (res.isNotEmpty()) return res
            }

            // Legacy format: outputs -> text
            val outputs = root.getAsJsonArray("outputs")
            if (outputs != null && outputs.size() > 0) {
                val sb = StringBuilder()
                for (oElem in outputs) {
                    if (!oElem.isJsonObject) continue
                    val item = oElem.asJsonObject
                    val text = item.get("text")?.asString ?: ""
                    if (text.isNotBlank()) sb.append(text)
                }
                val res = sb.toString().trim()
                if (res.isNotEmpty()) return res
            }

            // Direct output string format
            val directOutput = root.get("output")?.asString ?: ""
            if (directOutput.isNotBlank()) return directOutput.trim()

            null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFinishReason(bodyString: String): String {
        return try {
            val root = com.google.gson.JsonParser.parseString(bodyString).asJsonObject
            root.getAsJsonArray("candidates")?.get(0)?.asJsonObject?.get("finishReason")?.asString ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseErrorBody(bodyString: String, code: Int, defaultMsg: String): String {
        val errMsg = try {
            val root = com.google.gson.JsonParser.parseString(bodyString).asJsonObject
            val err = root.getAsJsonObject("error")
            err?.get("message")?.asString ?: "HTTP $code"
        } catch (_: Exception) {
            "HTTP $code: $defaultMsg"
        }
        return GeminiModelManager.sanitizeForLogging(errMsg)
    }
}
