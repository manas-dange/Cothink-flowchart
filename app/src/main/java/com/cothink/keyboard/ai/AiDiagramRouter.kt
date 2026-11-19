package com.cothink.keyboard.ai

class AiDiagramRouter(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String? = { null }
) : DiagramGenerator {

    private val groqGenerator = GroqDiagramGenerator(apiKeyProvider, modelProvider)
    private val geminiGenerator = GeminiDiagramGenerator(apiKeyProvider, modelProvider)

    override suspend fun generateDiagram(transcript: String): Result<String> {
        val key = apiKeyProvider()?.trim().orEmpty()
        return if (key.startsWith("gsk_", ignoreCase = true)) {
            groqGenerator.generateDiagram(transcript)
        } else {
            geminiGenerator.generateDiagram(transcript)
        }
    }
}
