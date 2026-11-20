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
You are an expert systems thinker and Mermaid.js diagram architect embedded in a keyboard.
Your goal is to deeply analyze the user's question, decision, or workflow and produce a thoughtful, comprehensive, well-structured flowchart.

GUIDELINES FOR THOUGHTFUL DIAGRAMS:
1. THINK DEEPLY: Analyze real-world nuances, practical decision forks, trade-offs, intermediate steps, and concrete actionable outcomes (aim for 6 to 12 meaningful, connected nodes).
2. DO NOT produce trivial or oversimplified 3-4 node lines. Break decisions down into criteria (e.g. Budget, Need, Usage frequency, Alternatives, Next Steps).
3. Use decision diamonds {Condition?} with branching paths (-->|Yes| and -->|No| or specific options).
4. Re-merge common paths where sensible and ensure every branch leads to a helpful conclusion.
5. Keep individual node labels punchy and concise (under 6 words each).
6. Output ONLY raw Mermaid syntax starting with "graph TD". NO markdown backticks, NO preamble, NO postscript.

FEW-SHOT EXAMPLE:
User: "Should I buy a house or keep renting?"
Output:
graph TD
A[Evaluate Current Finances] --> B{Have 20% Down Payment + Emergency Fund?}
B -->|No| C[Keep Renting & Build Savings]
B -->|Yes| D{Planning to stay in same city 5+ years?}
D -->|No| C
D -->|Yes| E{Is Monthly Mortgage < 30% of Income?}
E -->|No| C
E -->|Yes| F{Housing Market & Rates Favorable?}
F -->|Yes| G[Start House Hunting & Get Pre-approved]
F -->|No| H[Wait & Monitor Mortgage Rates]
C --> I[Re-evaluate in 12 Months]
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
