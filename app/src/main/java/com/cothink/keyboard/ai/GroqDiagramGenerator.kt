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
        const val DEFAULT_MODEL = "qwen/qwen3.8-27b"
        const val FALLBACK_MODEL = "openai/gpt-oss-20b"

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
                    put("max_tokens", 3072)
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
