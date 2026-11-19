package com.cothink.keyboard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidSanitizerTest {

    @Test
    fun testMarkdownCodeBlockStripping() {
        val raw = """
            Here is your flowchart:
            ```mermaid
            graph TD
            A[Start] --> B[End]
            ```
            Hope this helps!
        """.trimIndent()

        val sanitized = MermaidSanitizer.sanitize(raw)
        assertEquals("graph TD\nA[Start] --> B[End]", sanitized)
        assertTrue(MermaidSanitizer.isValid(sanitized))
    }

    @Test
    fun testMissingGraphDeclarationPrependsGraphTD() {
        val raw = "A[Start] --> B{Check} -->|Yes| C[Done]"
        val sanitized = MermaidSanitizer.sanitize(raw)
        assertTrue(sanitized.startsWith("graph TD"))
        assertTrue(MermaidSanitizer.isValid(sanitized))
    }

    @Test
    fun testInvalidSyntaxRejected() {
        val raw = "I am an AI assistant and cannot generate diagrams right now."
        val sanitized = MermaidSanitizer.sanitize(raw)
        assertFalse(MermaidSanitizer.isValid(sanitized))
    }
}
