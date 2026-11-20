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
                put("maxOutputTokens", 1536)
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
