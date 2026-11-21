package com.cothink.keyboard.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GroqSmeBrainstormer(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String? = { null }
) : SmeBrainstormer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        const val DEFAULT_MODEL = BuiltInConfig.DEFAULT_MODEL
        const val FALLBACK_MODEL = BuiltInConfig.FALLBACK_MODEL

        const val SYSTEM_PROMPT = """You are an elite, on-demand Subject Matter Expert (SME) and strategic thinking partner.
You analyze user topics, questions, or draft thoughts and provide sharp, senior-level insights.

RULES:
1. AUTO-DETECT DOMAIN: Instantly determine the domain of the query (e.g., Software Engineering, System Architecture, Business Strategy, Product Design, Marketing, Finance, Law, Science).
2. SENIOR PRACTITIONER TONE: Speak as a seasoned specialist in that field. Provide actionable frameworks, high-signal points, critical trade-offs, or non-obvious angles.
3. CONCISE & STRUCTURED: Keep your response punchy and well-structured with clear bullet points or short paragraphs. Avoid walls of text.
4. ZERO FLUFF: Do NOT include conversational greetings ("Sure, I can help with that!", "Hello!"), pleasantries, or closing filler ("Let me know if you need more info"). Output ONLY the substantive expert insights ready to be pasted or reviewed.
"""

        fun sanitizeResponse(raw: String): String {
            var text = raw.trim()
            if (text.startsWith("\"") && text.endsWith("\"") && text.length > 2) {
                text = text.substring(1, text.length - 1).trim()
            }
            return text
        }
    }

    override suspend fun brainstorm(topicOrQuery: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanInput = topicOrQuery.trim()
        if (cleanInput.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Topic or query cannot be empty."))
        }

        val rawKey = apiKeyProvider()
        val apiKey = BuiltInConfig.getApiKey(rawKey)
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("API key not configured."))
        }

        val customModel = modelProvider()
        val primaryModel = BuiltInConfig.getModel(customModel)
        val candidateModels = listOf(primaryModel, DEFAULT_MODEL, FALLBACK_MODEL).distinct()

        var lastError: Exception? = null

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", cleanInput)
            })
        }

        for (modelName in candidateModels) {
            try {
                val requestJson = JSONObject().apply {
                    put("model", modelName)
                    put("messages", messagesArray)
                    put("temperature", 0.3)
                    put("max_tokens", 1024)
                }

                val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val respBody = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        lastError = IOException("Groq error ${response.code} ($modelName): $respBody")
                        return@use
                    }

                    val json = JSONObject(respBody)
                    val choices = json.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) {
                        lastError = IOException("Groq returned empty choices.")
                        return@use
                    }

                    val messageObj = choices.getJSONObject(0).optJSONObject("message")
                    val rawContent = messageObj?.optString("content").orEmpty()
                    val cleaned = sanitizeResponse(rawContent)

                    if (cleaned.isNotBlank()) {
                        return@withContext Result.success(cleaned)
                    } else {
                        lastError = IOException("Model $modelName returned empty brainstorm.")
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: IOException("Brainstorm service unavailable. Please check connection."))
    }
}
