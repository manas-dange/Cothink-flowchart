package com.cothink.keyboard.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
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
    fun testSystemPromptContainsClearFormattingRules() {
        val prompt = GroqTextEnhancer.SYSTEM_PROMPT
        assertTrue(prompt.contains("RULES"))
        assertTrue(prompt.contains("INSTRUCTION FOLLOWING"))
        assertTrue(prompt.contains("GRAMMAR & POLISH"))
        assertTrue(prompt.contains("OUTPUT ONLY THE ENHANCED TEXT"))
    }
}
