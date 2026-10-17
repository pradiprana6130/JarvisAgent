package com.jarvis.assistant.network

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.GeminiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GeminiModelInfo(
    val name: String,
    val displayName: String = "",
    val description: String = "",
    val supportedMethods: List<String> = emptyList(),
    val inputTokenLimit: Int = 0,
    val outputTokenLimit: Int = 0
) {
    val cleanName: String get() = name.removePrefix("models/")
    val supportsGenerateContent: Boolean get() = supportedMethods.contains("generateContent")
    val supportsBidi: Boolean get() = supportedMethods.contains("bidiGenerateContent")
    val supportsVision: Boolean get() = supportsGenerateContent && GeminiModelManager.isMultimodalModel(cleanName)
}

object GeminiModelManager {
    private const val TAG = "GeminiModelManager"
    private const val PREFS_NAME = "gemini_model_registry"
    private const val KEY_CACHED_MODELS_JSON = "cached_models_json"
    private const val KEY_LAST_FETCH_TIME = "last_fetch_time"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedModels: List<GeminiModelInfo>? = null

    val VERIFIED_DEFAULT_LIVE_MODEL = GeminiConstants.DEFAULT_LIVE_MODEL
    val VERIFIED_DEFAULT_TEXT_MODEL = GeminiConstants.DEFAULT_TEXT_MODEL
    val VERIFIED_DEFAULT_VISION_MODEL = GeminiConstants.DEFAULT_VISION_MODEL

    fun normalizeModelId(id: String): String {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return VERIFIED_DEFAULT_LIVE_MODEL
        var clean = trimmed
        while (clean.startsWith("models/")) {
            clean = clean.removePrefix("models/").trim()
        }
        return "models/$clean"
    }

    fun cleanModelId(id: String): String {
        var clean = id.trim()
        while (clean.startsWith("models/")) {
            clean = clean.removePrefix("models/").trim()
        }
        return clean
    }

    /**
     * Prepares model ID for REST URL path parameter:
     * Normalizes to "models/{cleanId}" to prevent "models/models/{cleanId}".
     */
    fun toRestPath(id: String): String {
        val clean = cleanModelId(id)
        return "models/$clean"
    }

    fun isMultimodalModel(modelName: String): Boolean {
        val clean = cleanModelId(modelName).lowercase()
        // Embedding or text-only models do not support multimodal vision
        if (clean.contains("embedding") || clean.contains("aqa")) {
            return false
        }
        // Gemini 2.0, 2.5, 3.0, 3.1, 3.8 Flash and Pro models are multimodal
        return clean.contains("flash") || clean.contains("pro") || clean.contains("gemini-2") || clean.contains("gemini-3")
    }

    fun isModelSupported(modelId: String): Boolean {
        val norm = normalizeModelId(modelId)
        if (norm in GeminiConstants.SUPPORTED_MODELS) return true
        val list = getCachedModels()
        return list.any { it.name.equals(norm, ignoreCase = true) || it.cleanName.equals(cleanModelId(modelId), ignoreCase = true) }
    }

    fun getCachedModels(context: Context? = null): List<GeminiModelInfo> {
        cachedModels?.let { return it }

        val ctx = context ?: try { JarvisApp.instance } catch (_: Exception) { null }
        if (ctx != null) {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_CACHED_MODELS_JSON, null)
            if (!jsonStr.isNullOrBlank()) {
                try {
                    val parsed = parseModelsJson(jsonStr)
                    if (parsed.isNotEmpty()) {
                        cachedModels = parsed
                        return parsed
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse cached models JSON: ${e.message}")
                }
            }
        }

        // Default seed models if cache is not yet populated
        val defaults = listOf(
            GeminiModelInfo(
                name = GeminiConstants.MODEL_3_8_FLASH,
                displayName = "Gemini 3.8 Flash",
                supportedMethods = listOf("generateContent", "countTokens")
            ),
            GeminiModelInfo(
                name = GeminiConstants.MODEL_2_5_FLASH_NATIVE_AUDIO,
                displayName = "Gemini 2.5 Flash Native Audio Preview",
                supportedMethods = listOf("bidiGenerateContent")
            ),
            GeminiModelInfo(
                name = GeminiConstants.MODEL_2_5_FLASH_LIVE,
                displayName = "Gemini 2.5 Flash Live Preview",
                supportedMethods = listOf("bidiGenerateContent")
            ),
            GeminiModelInfo(
                name = GeminiConstants.MODEL_3_0_FLASH,
                displayName = "Gemini 3.0 Flash",
                supportedMethods = listOf("generateContent", "countTokens")
            ),
            GeminiModelInfo(
                name = GeminiConstants.MODEL_3_1_FLASH_PREVIEW,
                displayName = "Gemini 3.1 Flash Preview",
                supportedMethods = listOf("generateContent", "countTokens")
            ),
            GeminiModelInfo(
                name = GeminiConstants.MODEL_3_1_FLASH_LITE_PREVIEW,
                displayName = "Gemini 3.1 Flash Lite Preview",
                supportedMethods = listOf("generateContent", "countTokens")
            )
        )
        cachedModels = defaults
        return defaults
    }

    suspend fun fetchAvailableModels(
        apiKey: String,
        customEndpoint: String? = null,
        context: Context? = null
    ): Result<List<GeminiModelInfo>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API Key cannot be blank"))
        }

        val baseUrl = if (!customEndpoint.isNullOrBlank()) {
            customEndpoint.trimEnd('/')
        } else {
            "https://generativelanguage.googleapis.com"
        }

        val url = "$baseUrl/v1beta/models?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val safeError = parseApiError(body, response.code)
                    Log.e(TAG, "Failed to list models: $safeError")
                    return@withContext Result.failure(Exception(safeError))
                }

                val list = parseModelsJson(body)
                if (list.isEmpty()) {
                    return@withContext Result.failure(Exception("ModelService returned an empty list of models."))
                }

                cachedModels = list
                val ctx = context ?: try { JarvisApp.instance } catch (_: Exception) { null }
                if (ctx != null) {
                    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString(KEY_CACHED_MODELS_JSON, body)
                        .putLong(KEY_LAST_FETCH_TIME, System.currentTimeMillis())
                        .apply()
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            val safeErr = sanitizeForLogging(e.message ?: "Network error connecting to model service")
            Log.e(TAG, "fetchAvailableModels exception: $safeErr")
            Result.failure(Exception(safeErr))
        }
    }

    fun parseModelsJson(jsonString: String): List<GeminiModelInfo> {
        return try {
            val root = com.google.gson.JsonParser.parseString(jsonString).asJsonObject
            val arr = root.getAsJsonArray("models") ?: return emptyList()
            val result = mutableListOf<GeminiModelInfo>()
            for (elem in arr) {
                if (!elem.isJsonObject) continue
                val obj = elem.asJsonObject
                val name = obj.get("name")?.asString ?: ""
                if (name.isBlank()) continue
                val displayName = obj.get("displayName")?.asString ?: ""
                val desc = obj.get("description")?.asString ?: ""
                val inputTokens = obj.get("inputTokenLimit")?.asInt ?: 0
                val outputTokens = obj.get("outputTokenLimit")?.asInt ?: 0
                val methodsArr = obj.getAsJsonArray("supportedGenerationMethods")
                val methods = mutableListOf<String>()
                if (methodsArr != null) {
                    for (m in methodsArr) {
                        methods.add(m.asString)
                    }
                }
                result.add(
                    GeminiModelInfo(
                        name = normalizeModelId(name),
                        displayName = displayName,
                        description = desc,
                        supportedMethods = methods,
                        inputTokenLimit = inputTokens,
                        outputTokenLimit = outputTokens
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Resolves an appropriate Live Voice model (must support bidiGenerateContent).
     */
    fun resolveLiveModel(preferred: String? = null): String {
        val norm = if (!preferred.isNullOrBlank()) normalizeModelId(preferred) else null
        val models = getCachedModels()

        // 1. If preferred model supports bidiGenerateContent, use it
        if (norm != null) {
            val match = models.firstOrNull { it.name.equals(norm, ignoreCase = true) }
            if (match != null && match.supportsBidi) {
                return match.name
            }
            if (norm in GeminiConstants.SUPPORTED_LIVE_MODELS) {
                return norm
            }
        }

        // 2. Look for any cached model supporting bidiGenerateContent (prefer native audio)
        val candidate = models.firstOrNull { it.supportsBidi && it.name.contains("native-audio") }
            ?: models.firstOrNull { it.supportsBidi }
        if (candidate != null) {
            return candidate.name
        }

        return VERIFIED_DEFAULT_LIVE_MODEL
    }

    /**
     * Resolves an appropriate Text generation model (must support generateContent).
     */
    fun resolveTextModel(preferred: String? = null): String {
        val norm = if (!preferred.isNullOrBlank()) normalizeModelId(preferred) else null
        val models = getCachedModels()

        // Guard: Never resolve to unavailable gemini-2.5-flash for text generation
        val cleanPreferred = norm?.let { cleanModelId(it) }
        val effectivePreferred = if (cleanPreferred.equals("gemini-2.5-flash", ignoreCase = true)) {
            VERIFIED_DEFAULT_TEXT_MODEL
        } else {
            norm
        }

        // 1. If preferred model supports generateContent, use it (excluding unavailable gemini-2.5-flash)
        if (effectivePreferred != null) {
            val match = models.firstOrNull { it.name.equals(effectivePreferred, ignoreCase = true) }
            if (match != null && match.supportsGenerateContent && !match.cleanName.equals("gemini-2.5-flash", ignoreCase = true)) {
                return match.name
            }
            if (effectivePreferred in GeminiConstants.SUPPORTED_TEXT_MODELS && !effectivePreferred.contains("gemini-2.5-flash")) {
                return effectivePreferred
            }
        }

        // 2. Find any cached model that supports generateContent (prioritize 3.8-flash, then other flash, never 2.5-flash)
        val candidate = models.firstOrNull { it.supportsGenerateContent && it.cleanName.contains("3.8-flash") }
            ?: models.firstOrNull { it.supportsGenerateContent && it.name.contains("flash") && !it.cleanName.equals("gemini-2.5-flash", ignoreCase = true) }
            ?: models.firstOrNull { it.supportsGenerateContent && !it.cleanName.equals("gemini-2.5-flash", ignoreCase = true) }
        if (candidate != null) {
            return candidate.name
        }

        return VERIFIED_DEFAULT_TEXT_MODEL
    }

    /**
     * Resolves an appropriate Multimodal/Vision model (must support generateContent and vision input).
     */
    fun resolveVisionModel(preferred: String? = null): String {
        val norm = if (!preferred.isNullOrBlank()) normalizeModelId(preferred) else null
        val models = getCachedModels()

        // Guard: Never resolve to unavailable gemini-2.5-flash for vision requests
        val cleanPreferred = norm?.let { cleanModelId(it) }
        val effectivePreferred = if (cleanPreferred.equals("gemini-2.5-flash", ignoreCase = true)) {
            VERIFIED_DEFAULT_VISION_MODEL
        } else {
            norm
        }

        // 1. If preferred model supports vision, use it (excluding unavailable gemini-2.5-flash)
        if (effectivePreferred != null) {
            val match = models.firstOrNull { it.name.equals(effectivePreferred, ignoreCase = true) }
            if (match != null && match.supportsVision && !match.cleanName.equals("gemini-2.5-flash", ignoreCase = true)) {
                return match.name
            }
            if (effectivePreferred in GeminiConstants.SUPPORTED_TEXT_MODELS && isMultimodalModel(effectivePreferred) && !effectivePreferred.contains("gemini-2.5-flash")) {
                return effectivePreferred
            }
        }

        // 2. Find any cached model that supports vision (prioritize 3.8-flash, then other flash, never 2.5-flash)
        val candidate = models.firstOrNull { it.supportsVision && it.cleanName.contains("3.8-flash") }
            ?: models.firstOrNull { it.supportsVision && it.name.contains("flash") && !it.cleanName.equals("gemini-2.5-flash", ignoreCase = true) }
            ?: models.firstOrNull { it.supportsVision && !it.cleanName.equals("gemini-2.5-flash", ignoreCase = true) }
        if (candidate != null) {
            return candidate.name
        }

        return VERIFIED_DEFAULT_VISION_MODEL
    }

    fun getFallbackLiveModel(failedModel: String): String? {
        val norm = normalizeModelId(failedModel)
        val liveModels = getCachedModels().filter { it.supportsBidi }.map { it.name }
        val remaining = liveModels.filterNot { it.equals(norm, ignoreCase = true) }
        return remaining.firstOrNull() ?: if (!norm.equals(VERIFIED_DEFAULT_LIVE_MODEL, ignoreCase = true)) VERIFIED_DEFAULT_LIVE_MODEL else null
    }

    fun validateModelForMethod(modelName: String, method: String): Boolean {
        val norm = normalizeModelId(modelName)
        val model = getCachedModels().firstOrNull { it.name.equals(norm, ignoreCase = true) }
        return model?.supportedMethods?.contains(method) == true
    }

    private fun parseApiError(body: String, code: Int): String {
        return try {
            val root = com.google.gson.JsonParser.parseString(body).asJsonObject
            val err = root.getAsJsonObject("error")
            val msg = err?.get("message")?.asString ?: ""
            if (msg.isNotBlank()) {
                sanitizeForLogging("HTTP $code: $msg")
            } else {
                "HTTP $code error"
            }
        } catch (_: Exception) {
            "HTTP $code error"
        }
    }

    fun sanitizeForLogging(input: String): String {
        return input
            .replace(Regex("key=AIza[A-Za-z0-9_-]+"), "key=[REDACTED]")
            .replace(Regex("AIza[0-9A-Za-z-_]{35}"), "[REDACTED_API_KEY]")
            .replace(Regex("Bearer\\s+[A-Za-z0-9_.-]+", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
            .replace(Regex("x-goog-api-key:\\s*[^\\s,]+", RegexOption.IGNORE_CASE), "x-goog-api-key: [REDACTED]")
    }

    fun runDiagnostics(apiKey: String, customEndpoint: String? = null): Map<String, String> {
        val results = mutableMapOf<String, String>()
        val models = getCachedModels()
        results["cached_models_count"] = models.size.toString()
        results["live_models_count"] = models.count { it.supportsBidi }.toString()
        results["text_models_count"] = models.count { it.supportsGenerateContent }.toString()
        results["vision_models_count"] = models.count { it.supportsVision }.toString()
        results["resolved_live_model"] = resolveLiveModel()
        results["resolved_text_model"] = resolveTextModel()
        results["resolved_vision_model"] = resolveVisionModel()
        return results
    }

    fun clearCache(context: Context? = null) {
        cachedModels = null
        val ctx = context ?: try { JarvisApp.instance } catch (_: Exception) { null }
        ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
    }
}
