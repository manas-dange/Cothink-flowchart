package com.cothink.keyboard.ai

interface SmeBrainstormer {
    suspend fun brainstorm(topicOrQuery: String): Result<String>
}
