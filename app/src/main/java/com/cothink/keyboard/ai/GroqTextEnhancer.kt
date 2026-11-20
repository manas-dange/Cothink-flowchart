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

class GroqTextEnhancer(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String? = { null }
) : TextEnhancer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        const val DEFAULT_MODEL = BuiltInConfig.DEFAULT_MODEL
        const val FALLBACK_MODEL = BuiltInConfig.FALLBACK_MODEL

        const val SYSTEM_PROMPT = """You are a silent, direct keyboard text-enhancement engine.
You receive raw user input from a mobile keyboard and rewrite it into polished, grammatically correct, well-structured text.

CRITICAL INSTRUCTIONS:
1. NEVER ASK QUESTIONS OR FOLLOW-UPS: Under NO circumstances should you ask questions, ask for clarification, or offer multiple choices. NEVER append "Would you like...", "Do you mean...", "Let me know if...", or similar prompts.
2. DO NOT CONVERSE OR ACT AS A CHATBOT: Never answer questions contained in the user's text. If the user's input is a question (e.g. "what time is the meeting" or "should I learn Python"), polish the phrasing and grammar of their question so they can send it to someone else. DO NOT answer it.
3. INSTRUCTION FOLLOWING: If the input contains formatting commands (e.g. "make it bulleted", "make it formal", "make it concise", "fix grammar", "translate to French"), apply that transformation directly.
4. GRAMMAR & REWRITING: Fix misspellings, punctuation, sentence fragments, and awkward phrasing while preserving the user's intended meaning.
5. STRICT OUTPUT FORMAT: Output ONLY the final polished text. Do not include preambles, greetings, notes, explanations, or enclosing quotation marks. Output must be immediately ready to paste into a chat input.
"""

        fun sanitizeEnhancedText(rawText: String): String {
            var text = rawText.trim()
            // Strip any accidental markdown quotes around the whole block
            if (text.startsWith("\"") && text.endsWith("\"") && text.length > 2) {
                text = text.substring(1, text.length - 1).trim()
            }

            // Remove conversational trailers or follow-up questions
            val lines = text.lines().toMutableList()
            while (lines.isNotEmpty()) {
                val lastLine = lines.last().trim()
                val lower = lastLine.lowercase()
                if (lower.startsWith("would you like") ||
                    lower.startsWith("do you want me to") ||
                    lower.startsWith("let me know if") ||
                    lower.startsWith("feel free to") ||
                    lower.startsWith("hope this helps") ||
                    lower.startsWith("is there anything else") ||
                    lower.startsWith("if you need more") ||
                    lower.startsWith("please let me know") ||
                    lower.startsWith("could you clarify") ||
                    lower.startsWith("did you mean")
                ) {
                    lines.removeAt(lines.size - 1)
                } else {
                    break
                }
            }
            val cleaned = lines.joinToString("\n").trim()
            return if (cleaned.isNotBlank()) cleaned else text
        }
    }

    override suspend fun enhanceText(rawInput: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanInput = rawInput.trim()
        if (cleanInput.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Input text is empty."))
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
                    put("temperature", 0.1)
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
                    val cleanedContent = sanitizeEnhancedText(rawContent)

                    if (cleanedContent.isNotBlank()) {
                        return@withContext Result.success(cleanedContent)
                    } else {
                        lastError = IOException("Model $modelName returned blank text.")
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: IOException("Text enhancement failed. Check connection."))
    }
}
