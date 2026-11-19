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
    fun testSingleArrowNormalizedToDoubleArrow() {
        val raw = "graph TD\nA[Start] -> B[End]"
        val sanitized = MermaidSanitizer.sanitize(raw)
        assertEquals("graph TD\nA[Start] --> B[End]", sanitized)
        assertTrue(MermaidSanitizer.isValid(sanitized))
    }

    @Test
    fun testNumericNodeIdPrefixing() {
        val raw = "graph TD\n1[Start] --> 2[Step 2] --> 3[End]"
        val sanitized = MermaidSanitizer.sanitize(raw)
        assertEquals("graph TD\nN1[Start] --> N2[Step 2] --> N3[End]", sanitized)
        assertTrue(MermaidSanitizer.isValid(sanitized))
    }

    @Test
    fun testPreambleWithoutDiscardingDiagram() {
        val raw = "Here is the diagram:\nA[Start] --> B[End]"
        val sanitized = MermaidSanitizer.sanitize(raw)
        assertEquals("graph TD\nA[Start] --> B[End]", sanitized)
        assertTrue(MermaidSanitizer.isValid(sanitized))
    }

    @Test
    fun testThinkTagStripping() {
        val raw = "<think>Let me analyze the prompt...</think>\ngraph TD\nA[Start] --> B[End]"
        val sanitized = MermaidSanitizer.sanitize(raw)
        assertEquals("graph TD\nA[Start] --> B[End]", sanitized)
        assertTrue(MermaidSanitizer.isValid(sanitized))
    }

    @Test
    fun testUnclosedThinkTagHandled() {
        val raw = "<think>Generating diagram...\ngraph TD\nA[Start] --> B[End]"
        val sanitized = MermaidSanitizer.sanitize(raw)
        assertEquals("graph TD\nA[Start] --> B[End]", sanitized)
        assertTrue(MermaidSanitizer.isValid(sanitized))
    }

    @Test
    fun testDoubleQuotesInsideBracketsConverted() {
        val raw = "graph TD\nA[\"User enters\"] --> B[\"Done\"]"
        val sanitized = MermaidSanitizer.sanitize(raw)
        assertEquals("graph TD\nA['User enters'] --> B['Done']", sanitized)
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
    fun testEmptyInputFallsBackToValidDiagram() {
        val sanitized = MermaidSanitizer.sanitize("", "login to dashboard")
        assertTrue(sanitized.startsWith("graph TD"))
        assertTrue(MermaidSanitizer.isValid(sanitized))
    }

    @Test
    fun testInvalidEmptyRejected() {
        assertFalse(MermaidSanitizer.isValid(""))
        assertFalse(MermaidSanitizer.isValid("Just some text without mermaid"))
    }
}
