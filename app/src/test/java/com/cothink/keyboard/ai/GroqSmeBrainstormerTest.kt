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
    fun testQuickMessageEmptyInputFailsFast() = runBlocking {
        val brainstormer = GroqSmeBrainstormer(
            apiKeyProvider = { "dummy_key" }
        )
        val result = brainstormer.quickMessage("   ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun testConverseTurnEmptyInputFailsFast() = runBlocking {
        val brainstormer = GroqSmeBrainstormer(
            apiKeyProvider = { "dummy_key" }
        )
        val result = brainstormer.converseTurn(emptyList(), "   ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun testConcludeSessionEmptyHistoryFailsFast() = runBlocking {
        val brainstormer = GroqSmeBrainstormer(
            apiKeyProvider = { "dummy_key" }
        )
        val result = brainstormer.concludeSession(emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun testSystemPromptContainsSmeRules() {
        val prompt = GroqSmeBrainstormer.SYSTEM_PROMPT
        assertTrue(prompt.contains("STRICTLY CONCISE (UNDER 300 WORDS)"))
        assertTrue(prompt.contains("NO MARKDOWN ASTERISKS OR BOLDING"))
        assertTrue(prompt.contains("NO TABLES"))
        assertTrue(prompt.contains("NO PREAMBLE OR CLOSING REMARKS"))
        assertTrue(prompt.contains("NO QUESTIONS OR CLARIFICATIONS"))
        assertTrue(prompt.contains("DOMAIN EXPERT TONE"))
    }

    @Test
    fun testQuickMessagePromptContainsRules() {
        val prompt = GroqSmeBrainstormer.QUICK_MESSAGE_SYSTEM_PROMPT
        assertTrue(prompt.contains("STRICTLY UNDER 50 WORDS"))
        assertTrue(prompt.contains("NO MARKDOWN ASTERISKS OR BOLDING"))
        assertTrue(prompt.contains("NO BULLET POINTS"))
    }

    @Test
    fun testConversationalSystemPromptContainsRules() {
        val prompt = GroqSmeBrainstormer.CONVERSATIONAL_SYSTEM_PROMPT
        assertTrue(prompt.contains("ULTRA-BRIEF (UNDER 60 WORDS)"))
        assertTrue(prompt.contains("NO MARKDOWN ASTERISKS OR BOLDING"))
        assertTrue(prompt.contains("NO FLUFF OR GREETINGS"))
        assertTrue(prompt.contains("NO TABLES"))
    }

    @Test
    fun testConcludeSystemPromptContainsRules() {
        val prompt = GroqSmeBrainstormer.CONCLUDE_SYSTEM_PROMPT
        assertTrue(prompt.contains("STRICTLY CONCISE (UNDER 250 WORDS)"))
        assertTrue(prompt.contains("NO MARKDOWN ASTERISKS OR BOLDING"))
        assertTrue(prompt.contains("NO TABLES"))
        assertTrue(prompt.contains("NO PREAMBLE OR CLOSING REMARKS"))
        assertTrue(prompt.contains("NO QUESTIONS"))
    }

    @Test
    fun testSanitizeConversationalResponseStripsPleasantriesAndAsterisks() {
        val raw = "\"Certainly! The main risk is **high customer acquisition cost**.\""
        val cleaned = GroqSmeBrainstormer.sanitizeConversationalResponse(raw)
        assertEquals("The main risk is high customer acquisition cost.", cleaned)
        assertFalse(cleaned.contains("*"))
    }

    @Test
    fun testSanitizeStripsAllMarkdownAsterisksFromPointers() {
        val input = "- **Assuming data equals insight**: Heavy reliance on analytics.\n- **Neglecting cultural alignment**: Strategic actions."
        val cleaned = GroqSmeBrainstormer.sanitizeResponse(input)
        assertFalse(cleaned.contains("*"))
        assertEquals("- Assuming data equals insight: Heavy reliance on analytics.\n- Neglecting cultural alignment: Strategic actions.", cleaned)
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
