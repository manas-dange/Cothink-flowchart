package com.cothink.keyboard.ai

object MermaidSanitizer {

    private val THINK_BLOCK_REGEX = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val CODE_BLOCK_REGEX = Regex("```(?:mermaid)?\\s*([\\s\\S]*?)(?:```|$)", RegexOption.IGNORE_CASE)
    private val SINGLE_ARROW_REGEX = Regex("(?<![-\\.=>])->(?!>)")
    private val INNER_PAREN_REGEX = Regex("\\[([^\\]]*)\\(([^\\)]*)\\)([^\\]]*)\\]")
    private val NUMERIC_NODE_START_REGEX = Regex("(?<=\\b)(\\d+)(?=\\s*[\\[\\(\\{])")
    private val NUMERIC_NODE_BEFORE_ARROW_REGEX = Regex("(?<=\\b)(\\d+)(?=\\s*-->)")
    private val NUMERIC_NODE_AFTER_ARROW_REGEX = Regex("(-->\\s*)(\\d+)(?=\\b)")
    private val ARROW_LABEL_REGEX = Regex("-->\\|([^\\|]+)\\|")
    private val UNQUOTED_SQUARE_REGEX = Regex("([A-Za-z0-9_]+)\\s*\\[(?!\\\")(.*?)(?<!\\\")\\]")
    private val UNQUOTED_CURLY_REGEX = Regex("([A-Za-z0-9_]+)\\s*\\{(?!\\\")(.*?)(?<!\\\")\\}")

    /**
     * Sanitizes raw LLM output into clean, crash-proof, render-ready Mermaid syntax.
     */
    fun sanitize(rawOutput: String, transcript: String = ""): String {
        var text = rawOutput.trim()

        // 1. Strip reasoning blocks (<think>...</think>), even if cut off before closing
        if (text.contains("<think>", ignoreCase = true)) {
            if (text.contains("</think>", ignoreCase = true)) {
                text = text.replace(THINK_BLOCK_REGEX, "").trim()
            } else {
                val graphIdx = text.indexOf("graph ", ignoreCase = true)
                val flowIdx = text.indexOf("flowchart ", ignoreCase = true)
                val pos = when {
                    graphIdx != -1 && flowIdx != -1 -> minOf(graphIdx, flowIdx)
                    graphIdx != -1 -> graphIdx
                    else -> flowIdx
                }
                text = if (pos != -1) text.substring(pos).trim() else ""
            }
        }

        // 2. Extract inner content if wrapped in markdown code blocks
        val match = CODE_BLOCK_REGEX.find(text)
        if (match != null && match.groupValues.size > 1 && match.groupValues[1].isNotBlank()) {
            text = match.groupValues[1].trim()
        }

        // 3. Normalize single arrows (->) to Mermaid standard (-->)
        text = text.replace(SINGLE_ARROW_REGEX, "-->")

        // 4. Prefix numeric node IDs so Mermaid parser doesn't crash on numbers (e.g. 1[Start] -> N1[Start])
        text = text.replace(NUMERIC_NODE_START_REGEX, "N$1")
        text = text.replace(NUMERIC_NODE_BEFORE_ARROW_REGEX, "N$1")
        text = text.replace(NUMERIC_NODE_AFTER_ARROW_REGEX, "$1N$2")

        // 5. Clean up arrow labels (replace & with 'and', < with 'less than', > with 'greater than')
        text = text.replace(ARROW_LABEL_REGEX) { mr ->
            val inner = mr.groupValues[1]
                .replace("&", "and")
                .replace("<", "less than")
                .replace(">", "greater than")
                .replace("\"", "'")
            "-->|$inner|"
        }

        // 6. Automatically wrap unquoted bracket node labels in double quotes ["..."]
        // This is Mermaid's official syntax for allowing special chars (&, <, >, ?, parens) safely.
        text = text.replace(UNQUOTED_SQUARE_REGEX) { mr ->
            val nodeId = mr.groupValues[1].trim()
            val inner = mr.groupValues[2].trim().replace("\"", "'")
            "$nodeId[\"$inner\"]"
        }

        // 7. Automatically wrap unquoted curly decision labels in double quotes {"..."}
        text = text.replace(UNQUOTED_CURLY_REGEX) { mr ->
            val nodeId = mr.groupValues[1].trim()
            val inner = mr.groupValues[2].trim().replace("\"", "'")
            "$nodeId{\"$inner\"}"
        }

        // 8. Sanitize nested parentheses inside brackets if any remain
        while (INNER_PAREN_REGEX.containsMatchIn(text)) {
            text = text.replace(INNER_PAREN_REGEX) { mr ->
                "[${mr.groupValues[1]} ${mr.groupValues[2]} ${mr.groupValues[3]}".trim() + "]"
            }
        }

        // 9. Extract valid Mermaid lines, skipping conversational preamble and postscript
        val lines = text.lines()
        val graphLines = mutableListOf<String>()
        var hasHeader = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val lower = trimmed.lowercase()

            if (lower.startsWith("graph ") || lower.startsWith("flowchart ")) {
                graphLines.add(trimmed)
                hasHeader = true
                continue
            }

            // Skip conversational preamble / footer lines
            if (lower.startsWith("here is") ||
                lower.startsWith("note:") ||
                lower.startsWith("explanation:") ||
                lower.startsWith("this flowchart") ||
                lower.startsWith("hope this") ||
                lower.startsWith("sure") ||
                lower.startsWith("certainly") ||
                lower.startsWith("i have created")
            ) {
                continue
            }

            // Check if line looks like valid Mermaid syntax
            val isMermaid = trimmed.contains("-->") ||
                            trimmed.contains("---") ||
                            trimmed.contains("-.->") ||
                            trimmed.contains("==>") ||
                            trimmed.contains("[") ||
                            trimmed.contains("(") ||
                            trimmed.contains("{") ||
                            lower.startsWith("subgraph") ||
                            lower == "end" ||
                            lower.startsWith("class") ||
                            lower.startsWith("style") ||
                            lower.startsWith("%%")

            if (isMermaid) {
                graphLines.add(trimmed)
            }
        }

        if (!hasHeader) {
            graphLines.add(0, "graph TD")
        }

        // If only header exists or no valid nodes/connections, fallback to a clean diagram
        if (graphLines.size <= 1) {
            return fallbackDiagram(transcript)
        }

        return graphLines.joinToString("\n").trim()
    }

    /**
     * Creates a guaranteed-valid fallback Mermaid diagram from the transcript or defaults.
     */
    fun fallbackDiagram(transcript: String): String {
        val cleanPrompt = transcript.trim()
        if (cleanPrompt.isBlank()) {
            return "graph TD\nA[\"Start\"] --> B[\"Process\"] --> C[\"End\"]"
        }

        // Split transcript into logical sentence / clause chunks
        val sentences = cleanPrompt.split(Regex("[.!?\\n]+|\\bthen\\b|\\bnext\\b|\\bafter that\\b", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 2 }

        val nodes = if (sentences.size >= 2) {
            sentences.take(6)
        } else {
            val words = cleanPrompt.split("\\s+".toRegex()).filter { it.isNotBlank() }
            words.chunked(4).map { it.joinToString(" ") }.take(4)
        }

        val lines = mutableListOf("graph TD")
        val ids = ('A'..'Z').map { it.toString() }

        for (i in nodes.indices) {
            val label = nodes[i].replace("\"", "'").take(40)
            if (i < nodes.size - 1) {
                lines.add("${ids[i]}[\"$label\"] --> ${ids[i + 1]}")
            } else {
                lines.add("${ids[i]}[\"$label\"]")
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * Checks if the sanitized syntax has minimal required structure.
     */
    fun isValid(syntax: String): Boolean {
        val trimmed = syntax.trim()
        val hasGraphHeader = trimmed.startsWith("graph ", ignoreCase = true) ||
                             trimmed.startsWith("flowchart ", ignoreCase = true)

        val hasStructure = trimmed.contains("-->") ||
                           trimmed.contains("->") ||
                           trimmed.contains("---") ||
                           trimmed.contains("-.->") ||
                           trimmed.contains("==>") ||
                           trimmed.contains("[") ||
                           trimmed.contains("(") ||
                           trimmed.contains("{")

        return hasGraphHeader && hasStructure
    }
}
