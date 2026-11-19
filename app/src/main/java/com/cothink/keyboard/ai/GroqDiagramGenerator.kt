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

class GroqDiagramGenerator(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String? = { null }
) : DiagramGenerator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        const val DEFAULT_MODEL = "openai/gpt-oss-20b"
        const val FALLBACK_MODEL = "qwen/qwen3.8-27b"

        const val SYSTEM_PROMPT = """
You are an expert real-time Mermaid.js flowchart generator embedded in a keyboard.
Convert the user's input into a clean, concise flowchart.

STRICT CONSTRAINTS:
1. Output ONLY raw Mermaid syntax.
2. Start strictly with "graph TD".
3. NO markdown wrappers (do NOT use ```mermaid or ```).
4. NO greetings, NO introductory text, NO conversational explanations.
5. Keep node labels short (under 5 words).
6. Use decision diamonds {Condition} and arrow labels -->|Yes| or -->|No| where appropriate.

FEW-SHOT EXAMPLES:

User Input: "If the user clicks login check the database if yes go to dashboard if no show error"
Output:
graph TD
A[Click Login] --> B{Check DB}
B -->|Yes| C[Dashboard]
B -->|No| D[Show Error]

User Input: "Order food then restaurant accepts it kitchen prepares food delivery partner picks it up and delivers to customer"
Output:
graph TD
A[Order Food] --> B[Restaurant Accepts]
B --> C[Kitchen Prepares]
C --> D[Delivery Partner Picks Up]
D --> E[Delivered to Customer]
"""
    }

    override suspend fun generateDiagram(transcript: String): Result<String> = withContext(Dispatchers.IO) {
        val rawKey = apiKeyProvider()
        if (rawKey.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Groq API key not set."))
        }
        val apiKey = rawKey.trim()

        val selectedModel = modelProvider()?.trim()
        val candidateModels = if (!selectedModel.isNullOrBlank() && !selectedModel.contains("gemini", ignoreCase = true)) {
            listOf(selectedModel, DEFAULT_MODEL, FALLBACK_MODEL)
        } else {
            listOf(DEFAULT_MODEL, FALLBACK_MODEL)
        }.distinct()

        var lastError: Exception? = null

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", transcript)
            })
        }

        for (modelName in candidateModels) {
            try {
                val requestJson = JSONObject().apply {
                    put("model", modelName)
                    put("messages", messagesArray)
                    put("temperature", 0.1)
                    put("max_tokens", 1536)
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

                    if (rawContent.isBlank()) {
                        android.util.Log.w("GroqDiagramGenerator", "Model $modelName returned blank content, trying fallback model...")
                        lastError = IOException("Model $modelName returned blank content.")
                        return@use
                    }

                    val sanitized = MermaidSanitizer.sanitize(rawContent, transcript)
                    if (MermaidSanitizer.isValid(sanitized)) {
                        return@withContext Result.success(sanitized)
                    } else {
                        android.util.Log.w("GroqDiagramGenerator", "Model $modelName produced invalid syntax: $sanitized")
                        lastError = IllegalArgumentException("Generated syntax failed validation:\n$sanitized")
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (lastError is IllegalArgumentException) {
            return@withContext Result.success(MermaidSanitizer.fallbackDiagram(transcript))
        }

        Result.failure(lastError ?: IOException("All Groq models failed. Check your API key."))
    }
}
