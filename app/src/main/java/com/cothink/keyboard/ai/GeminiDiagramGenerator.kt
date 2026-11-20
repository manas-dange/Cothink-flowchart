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
You are an expert real-time Mermaid.js flowchart generator embedded in a voice keyboard.
Your goal is to turn the user's spoken words into a clean, accurate, and completely valid Mermaid flowchart.

CORE INSTRUCTIONS:
1. WORKFLOW DESCRIPTIONS: If the user is describing a process, pipeline, or system (e.g. "how an AI agent works", "order processing flow", etc.), FAITHFULLY extract each step, decision, and loop that the user describes. Do NOT invent a random or unrelated diagram.
2. OPEN-ENDED QUESTIONS: If the user asks a question or decision ("should I buy X?", "how to do Y?"), structure a logical decision flow with relevant criteria, branches, and outcomes.
3. SYNTAX RULES (CRITICAL):
   - Start strictly with "graph TD".
   - Use clean alphanumeric node IDs (A, B, C, D, E... or N1, N2...). NEVER put spaces in node IDs.
   - ALWAYS wrap node text in double quotes inside brackets: A["Step Description"], B{"Is Condition Met?"}.
   - Double quotes around text prevent Mermaid syntax errors from special characters like &, <, >, ?, or parentheses.
   - Decision diamonds must use braces with quotes: B{"Condition?"} -->|Yes| C["Action"] and B -->|No| D["Action"].
   - Output ONLY raw Mermaid syntax. NO markdown code fences (no ```), no introductory text, no explanations.
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
                put("maxOutputTokens", 3072)
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

                    val sanitized = MermaidSanitizer.sanitize(text, transcript)
                    if (MermaidSanitizer.isValid(sanitized)) {
                        return@withContext Result.success(sanitized)
                    } else {
                        android.util.Log.w("GeminiDiagramGenerator", "Model $modelName produced invalid syntax: $sanitized")
                        lastError = IllegalArgumentException("Generated syntax failed validation: $sanitized")
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (lastError is IllegalArgumentException) {
            return@withContext Result.success(MermaidSanitizer.fallbackDiagram(transcript))
        }

        Result.failure(lastError ?: IOException("All candidate Gemini models failed. Please verify your API key."))
    }
}
