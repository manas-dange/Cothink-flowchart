package com.cothink.keyboard.ai

object MermaidSanitizer {

    private val CODE_BLOCK_REGEX = Regex("```(?:mermaid)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
    private val HEADER_CLEANUP_REGEX = Regex("^(?:here is|below is|here's).*?\\n", RegexOption.IGNORE_CASE)

    /**
     * Sanitizes raw LLM output into clean, render-ready Mermaid syntax.
     * Strips Markdown wrappers, conversational preambles, and trailing notes.
     */
    fun sanitize(rawOutput: String): String {
        var text = rawOutput.trim()

        // 1. If wrapped in markdown code blocks, extract inner content
        val match = CODE_BLOCK_REGEX.find(text)
        if (match != null && match.groupValues.size > 1) {
            text = match.groupValues[1].trim()
        }

        // 2. Strip leading preamble lines (e.g. "Here is the flowchart:")
        text = text.replace(HEADER_CLEANUP_REGEX, "").trim()

        // 3. Locate where the actual graph declaration begins
        val lines = text.lines()
        val startIndex = lines.indexOfFirst { line ->
            val trimmed = line.trim()
            trimmed.startsWith("graph ", ignoreCase = true) ||
                    trimmed.startsWith("flowchart ", ignoreCase = true)
        }

        val cleanLines = if (startIndex != -1) {
            lines.subList(startIndex, lines.size)
        } else {
            // Default to graph TD if the model omitted the root declaration
            listOf("graph TD") + lines
        }

        // 4. Filter out any trailing conversational notes
        val validGraphLines = mutableListOf<String>()
        for (line in cleanLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // Stop if the model started an explanatory footer (e.g. "Note: This diagram shows...")
            if (trimmed.startsWith("Note:", ignoreCase = true) ||
                trimmed.startsWith("Explanation:", ignoreCase = true) ||
                trimmed.startsWith("This flowchart", ignoreCase = true)
            ) {
                break
            }
            validGraphLines.add(line)
        }

        return validGraphLines.joinToString("\n").trim()
    }

    /**
     * Checks if the sanitized syntax has minimal required structure.
     */
    fun isValid(syntax: String): Boolean {
        val trimmed = syntax.trim()
        return (trimmed.startsWith("graph ", ignoreCase = true) ||
                trimmed.startsWith("flowchart ", ignoreCase = true)) &&
                trimmed.contains("-->")
    }
}
