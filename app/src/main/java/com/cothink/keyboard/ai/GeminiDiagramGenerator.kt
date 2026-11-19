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

class GeminiDiagramGenerator(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String? = { null }
) : DiagramGenerator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        const val SYSTEM_PROMPT = """
You are an expert real-time Mermaid.js diagram generator embedded in a voice keyboard.
Convert the user's spoken stream-of-consciousness explanation into a clear, concise flowchart.

STRICT CONSTRAINTS:
1. Output ONLY valid Mermaid syntax.
2. Start strictly with "graph TD".
3. NO markdown wrappers (do NOT use ```mermaid or ```).
4. NO greetings, NO commentary, NO explanations, NO conversational filler.
5. Keep node labels short (under 5 words).
6. Escape or omit special characters like quotes or parentheses inside node labels.
7. Use decision diamonds {Condition} and arrow labels -->|Yes| or -->|No| where appropriate.

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

User Input: "First upload photo. Is it below 5MB? If no, compress it. If yes, run face detection. If face found, save to cloud, otherwise notify user to retry."
Output:
graph TD
A[Upload Photo] --> B{Size <= 5MB?}
B -->|No| C[Compress Photo]
C --> B
B -->|Yes| D{Run Face Detection}
D -->|Face Found| E[Save to Cloud]
D -->|No Face| F[Notify User to Retry]
"""
    }

    override suspend fun generateDiagram(transcript: String): Result<String> = withContext(Dispatchers.IO) {
        val rawApiKey = apiKeyProvider()
        if (rawApiKey.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("API key is not configured. Please set it in CoThinker settings."))
        }
        val apiKey = rawApiKey.trim()

        val customModel = modelProvider()?.trim()
        val candidateModels = if (!customModel.isNullOrBlank()) {
            listOf(customModel, "gemini-2.0-flash", "gemini-2.5-flash", "gemini-1.5-flash", "gemini-1.5-flash-latest")
        } else {
            listOf("gemini-2.0-flash", "gemini-2.5-flash", "gemini-1.5-flash", "gemini-1.5-flash-latest")
        }.distinct()

        var lastError: Exception? = null

        val contentsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", "$SYSTEM_PROMPT\n\nUser Input: $transcript\nOutput:"))
                })
            })
        }

        val requestBodyJson = JSONObject().apply {
            put("contents", contentsArray)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 500)
            })
        }

        val requestBody = requestBodyJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        for (modelName in candidateModels) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val errorBody = response.body?.string().orEmpty()
                    if (response.code == 404) {
                        // Model not found on this endpoint, try next candidate model
                        lastError = IOException("Model $modelName not found (404): $errorBody")
                        return@use
                    }

                    if (!response.isSuccessful) {
                        return@withContext Result.failure(IOException("Gemini error ${response.code} ($modelName): $errorBody"))
                    }

                    val jsonResponse = JSONObject(errorBody)
                    val candidates = jsonResponse.optJSONArray("candidates")
                    if (candidates == null || candidates.length() == 0) {
                        return@withContext Result.failure(IOException("No candidates returned from model $modelName."))
                    }

                    val content = candidates.getJSONObject(0).optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    val text = parts?.getJSONObject(0)?.optString("text").orEmpty()

                    val sanitized = MermaidSanitizer.sanitize(text)
                    if (MermaidSanitizer.isValid(sanitized)) {
                        return@withContext Result.success(sanitized)
                    } else {
                        return@withContext Result.failure(IllegalArgumentException("Generated syntax failed validation: $sanitized"))
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: IOException("All candidate Gemini models failed. Please verify your API key."))
    }
}
