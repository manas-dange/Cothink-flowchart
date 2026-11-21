package com.cothink.keyboard.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqSmeBrainstormerTest {

    @Test
    fun testEmptyInputFailsFast() = runBlocking {
        val brainstormer = GroqSmeBrainstormer(
            apiKeyProvider = { "dummy_key" }
        )
        val result = brainstormer.brainstorm("   ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun testSystemPromptContainsSmeRules() {
        val prompt = GroqSmeBrainstormer.SYSTEM_PROMPT
        assertTrue(prompt.contains("AUTO-DETECT DOMAIN"))
        assertTrue(prompt.contains("SENIOR PRACTITIONER TONE"))
        assertTrue(prompt.contains("CONCISE & STRUCTURED"))
        assertTrue(prompt.contains("ZERO FLUFF"))
    }

    @Test
    fun testSanitizeResponseStripsQuotes() {
        val input = "\"1. Microservices add network latency.\n2. Start with a modular monolith.\""
        val cleaned = GroqSmeBrainstormer.sanitizeResponse(input)
        assertEquals("1. Microservices add network latency.\n2. Start with a modular monolith.", cleaned)
    }
}
