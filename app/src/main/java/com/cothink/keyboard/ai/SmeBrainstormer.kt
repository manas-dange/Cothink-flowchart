package com.cothink.keyboard.ai

data class ChatTurn(
    val role: String, // "user" or "assistant"
    val content: String
)

interface SmeBrainstormer {
    suspend fun brainstorm(topicOrQuery: String): Result<String>
    suspend fun quickMessage(topicOrQuery: String): Result<String>
    suspend fun converseTurn(history: List<ChatTurn>, userMessage: String): Result<String>
    suspend fun concludeSession(history: List<ChatTurn>): Result<String>
}
