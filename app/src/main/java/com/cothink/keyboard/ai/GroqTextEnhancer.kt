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

        const val SYSTEM_PROMPT = """You are an intelligent real-time keyboard text enhancer and speech-to-text polisher.
Your job is to transform raw input (typed text or spoken transcript) into clear, polished, well-structured text.

RULES:
1. INSTRUCTION FOLLOWING: If the input contains explicit formatting or style instructions (e.g. "make it bulleted", "make it formal", "make it concise", "write an apology", "translate to Spanish"), follow those instructions faithfully.
2. GRAMMAR & POLISH: Fix spelling mistakes, punctuation, capitalization, awkward phrasing, and run-on sentences while preserving the original meaning and natural voice.
3. OUTPUT ONLY THE ENHANCED TEXT: Do NOT include preamble, greetings ("Here is your text:"), conversational commentary, or quotes around the whole response. Output ONLY the final polished text ready to be pasted into the chat bar.
"""
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
                    put("temperature", 0.2)
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
                    var content = messageObj?.optString("content").orEmpty().trim()

                    // Strip any accidental markdown quotes around the whole block
                    if (content.startsWith("\"") && content.endsWith("\"") && content.length > 2) {
                        content = content.substring(1, content.length - 1).trim()
                    }

                    if (content.isNotBlank()) {
                        return@withContext Result.success(content)
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
