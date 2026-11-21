package com.cothink.keyboard.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(prompt.contains("STRICTLY CONCISE (UNDER 300 WORDS)"))
        assertTrue(prompt.contains("NO TABLES"))
        assertTrue(prompt.contains("NO PREAMBLE OR CLOSING REMARKS"))
        assertTrue(prompt.contains("NO QUESTIONS OR CLARIFICATIONS"))
        assertTrue(prompt.contains("DOMAIN EXPERT TONE"))
    }

    @Test
    fun testSanitizeStripsLeadingTitleAndSeparator() {
        val input = "Acquisition\n---\n1. High maintenance costs.\n2. Strong brand equity."
        val cleaned = GroqSmeBrainstormer.sanitizeResponse(input)
        assertEquals("1. High maintenance costs.\n2. Strong brand equity.", cleaned)
    }

    @Test
    fun testSanitizeStripsBottomLineAndClosings() {
        val input = "1. First insight.\n2. Second insight.\n\n**Bottom line:** This is a concluding remark."
        val cleaned = GroqSmeBrainstormer.sanitizeResponse(input)
        assertEquals("1. First insight.\n2. Second insight.", cleaned)
    }

    @Test
    fun testSanitizeConvertsMarkdownTablesToBullets() {
        val input = """
1. Points:
| Counterargument | Blind Spot |
|-----------------|------------|
| Status symbol | Long term value |
        """.trimIndent()
        val cleaned = GroqSmeBrainstormer.sanitizeResponse(input)
        assertFalse(cleaned.contains("|"))
        assertTrue(cleaned.contains("• Counterargument: Blind Spot"))
        assertTrue(cleaned.contains("• Status symbol: Long term value"))
    }
}
