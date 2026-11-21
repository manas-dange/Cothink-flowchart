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

CRITICAL FORMATTING & CONTENT RULES:
1. STRICTLY CONCISE (UNDER 300 WORDS): Output must be dense, punchy, and strictly under 300 words (maximum 400 words). Focus only on the top 3 to 5 highest-signal insights or action steps. Never write an essay.
2. NO TABLES: NEVER use markdown tables (| col | col |). Tables break mobile chat screens. Use clean bullet points (-) or numbered steps (1.) only.
3. NO PREAMBLE OR CLOSING REMARKS: Do NOT include intro titles, topic restatements, or closing summaries (never write "Bottom line:", "In conclusion:", "Summary:"). Start immediately with your first substantive point and end with your final point.
4. NO QUESTIONS OR CLARIFICATIONS: The user will paste this text directly into a chat. Never ask questions back, never ask for clarification, and never offer follow-up choices. Make the best executive assumption and deliver the insight directly.
5. DOMAIN EXPERT TONE: Automatically adopt the persona of a seasoned senior specialist in the relevant domain (Tech, Strategy, Finance, Legal, etc.). Provide critical trade-offs and non-obvious angles.
"""

        const val CONVERSATIONAL_SYSTEM_PROMPT = """You are an elite, on-demand Subject Matter Expert (SME) in an active spoken discussion.
The user is speaking to you turn-by-turn to brainstorm, pressure-test ideas, or evaluate choices.

CRITICAL CONVERSATIONAL RULES:
1. ULTRA-BRIEF (UNDER 60 WORDS): Keep every response under 2-3 sentences (strictly maximum 60 words). Never lecture.
2. THINKING PARTNER DYNAMICS: Directly address what the user said. Point out the primary trade-off, challenge an unexamined assumption, or offer a sharp perspective to narrow the decision.
3. NO FLUFF OR GREETINGS: Zero greetings ("Hello", "Certainly"), zero pleasantries ("Great question", "Thanks"), and zero emojis. Start immediately with the core counter-point or insight.
4. NO TABLES: Plain text only, or at most 2 brief bullet points.
"""

        const val CONCLUDE_SYSTEM_PROMPT = """You are an executive synthesizer. You have observed a brainstorming session between a user and a Subject Matter Expert.
Produce a definitive, executive-ready decision summary of what was decided and agreed upon.

CRITICAL FORMATTING & CONTENT RULES:
1. STRICTLY CONCISE (UNDER 250 WORDS): Dense, punchy, actionable summary ready to paste into team chat.
2. STRUCTURE (USE EXACTLY THESE BULLETS IF APPLICABLE):
• Key Decision / Core Takeaway
• Primary Rationale & Trade-offs
• Concrete Next Steps
3. NO TABLES: NEVER use markdown tables.
4. NO PREAMBLE OR CLOSING REMARKS: Never include titles, intros ("Here is the summary"), or closings ("In conclusion", "Hope this helps"). Start immediately with the first bullet.
5. NO QUESTIONS: Deliver conclusive synthesized statements.
"""

        fun sanitizeResponse(raw: String): String {
            var text = raw.trim()
            if (text.startsWith("\"") && text.endsWith("\"") && text.length > 2) {
                text = text.substring(1, text.length - 1).trim()
            }

            val lines = text.lines().toMutableList()

            // 1. Remove leading single-word headers or dashes (e.g. "Acquisition\n---")
            while (lines.isNotEmpty()) {
                val first = lines.first().trim()
                if (first.startsWith("---") || first.startsWith("===") || first.isEmpty()) {
                    lines.removeAt(0)
                } else if (lines.size > 1 && (lines[1].trim().startsWith("---") || lines[1].trim().startsWith("==="))) {
                    lines.removeAt(0) // remove title
                    lines.removeAt(0) // remove separator
                } else {
                    break
                }
            }

            // 2. Remove closing summary / bottom-line lines
            while (lines.isNotEmpty()) {
                val last = lines.last().trim()
                val lower = last.lowercase()
                if (lower.isEmpty() ||
                    lower.startsWith("---") ||
                    lower.startsWith("**bottom line") ||
                    lower.startsWith("bottom line") ||
                    lower.startsWith("**in summary") ||
                    lower.startsWith("in summary") ||
                    lower.startsWith("**conclusion") ||
                    lower.startsWith("conclusion:") ||
                    lower.startsWith("let me know") ||
                    lower.startsWith("would you like") ||
                    lower.startsWith("feel free") ||
                    lower.startsWith("hope this helps") ||
                    lower.startsWith("if you have questions")
                ) {
                    lines.removeAt(lines.size - 1)
                } else {
                    break
                }
            }

            // 3. Convert any accidental markdown table lines into clean bullet points
            val cleanedLines = mutableListOf<String>()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                    if (trimmed.contains("---")) continue // separator row
                    val cells = trimmed.split("|")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (cells.isNotEmpty()) {
                        if (cells.size == 1) {
                            cleanedLines.add("• ${cells[0]}")
                        } else {
                            cleanedLines.add("• ${cells[0]}: ${cells.drop(1).joinToString(" - ")}")
                        }
                    }
                } else {
                    cleanedLines.add(line)
                }
            }

            return cleanedLines.joinToString("\n").trim()
        }

        fun sanitizeConversationalResponse(raw: String): String {
            var text = raw.trim()
            if (text.startsWith("\"") && text.endsWith("\"") && text.length > 2) {
                text = text.substring(1, text.length - 1).trim()
            }
            // Strip leading pleasantries
            val pleasantries = listOf("certainly!", "sure!", "hello!", "great question.", "understood.", "got it.")
            for (p in pleasantries) {
                if (text.lowercase().startsWith(p)) {
                    text = text.substring(p.length).trim()
                }
            }
            return text
        }
    }

    private suspend fun executeChatRequest(
        messagesArray: JSONArray,
        maxTokens: Int,
        temperature: Double
    ): Result<String> = withContext(Dispatchers.IO) {
        val rawKey = apiKeyProvider()
        val apiKey = BuiltInConfig.getApiKey(rawKey)
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("API key not configured."))
        }

        val customModel = modelProvider()
        val primaryModel = BuiltInConfig.getModel(customModel)
        val candidateModels = listOf(primaryModel, DEFAULT_MODEL, FALLBACK_MODEL).distinct()

        var lastError: Exception? = null

        for (modelName in candidateModels) {
            try {
                val requestJson = JSONObject().apply {
                    put("model", modelName)
                    put("messages", messagesArray)
                    put("temperature", temperature)
                    put("max_tokens", maxTokens)
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
                    if (rawContent.isNotBlank()) {
                        return@withContext Result.success(rawContent)
                    } else {
                        lastError = IOException("Model $modelName returned empty content.")
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: IOException("Brainstorm service unavailable. Please check connection."))
    }

    override suspend fun brainstorm(topicOrQuery: String): Result<String> {
        val cleanInput = topicOrQuery.trim()
        if (cleanInput.isBlank()) {
            return Result.failure(IllegalArgumentException("Topic or query cannot be empty."))
        }

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

        val rawResult = executeChatRequest(messagesArray, maxTokens = 500, temperature = 0.2)
        return rawResult.map { sanitizeResponse(it) }
    }

    override suspend fun converseTurn(history: List<ChatTurn>, userMessage: String): Result<String> {
        val cleanInput = userMessage.trim()
        if (cleanInput.isBlank()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty."))
        }

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", CONVERSATIONAL_SYSTEM_PROMPT)
            })
            for (turn in history) {
                put(JSONObject().apply {
                    put("role", turn.role)
                    put("content", turn.content)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", cleanInput)
            })
        }

        val rawResult = executeChatRequest(messagesArray, maxTokens = 150, temperature = 0.3)
        return rawResult.map { sanitizeConversationalResponse(it) }
    }

    override suspend fun concludeSession(history: List<ChatTurn>): Result<String> {
        if (history.isEmpty()) {
            return Result.failure(IllegalArgumentException("No conversation turns to conclude."))
        }

        val transcript = history.joinToString("\n\n") {
            "${if (it.role == "user") "USER" else "SME"}: ${it.content}"
        }

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", CONCLUDE_SYSTEM_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "Synthesize and conclude this brainstorm session into an executive decision summary:\n\n$transcript")
            })
        }

        val rawResult = executeChatRequest(messagesArray, maxTokens = 500, temperature = 0.2)
        return rawResult.map { sanitizeResponse(it) }
    }
}
