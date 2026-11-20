package com.cothink.keyboard.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqTextEnhancerTest {

    @Test
    fun testEmptyInputFailsFast() = runBlocking {
        val enhancer = GroqTextEnhancer(
            apiKeyProvider = { "dummy_key" }
        )
        val result = enhancer.enhanceText("   ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun testSystemPromptProhibitsQuestionsAndConversations() {
        val prompt = GroqTextEnhancer.SYSTEM_PROMPT
        assertTrue(prompt.contains("NEVER ASK QUESTIONS OR FOLLOW-UPS"))
        assertTrue(prompt.contains("DO NOT CONVERSE OR ACT AS A CHATBOT"))
        assertTrue(prompt.contains("STRICT OUTPUT FORMAT"))
    }

    @Test
    fun testSanitizeEnhancedTextStripsFollowUpTrailers() {
        val input = "Here is the polished message to send to the team.\n\nWould you like me to make it more concise?"
        val cleaned = GroqTextEnhancer.sanitizeEnhancedText(input)
        assertEquals("Here is the polished message to send to the team.", cleaned)
    }

    @Test
    fun testSanitizeEnhancedTextStripsQuotes() {
        val input = "\"Please find the attached document for your review.\""
        val cleaned = GroqTextEnhancer.sanitizeEnhancedText(input)
        assertEquals("Please find the attached document for your review.", cleaned)
    }
}
