package com.cothink.keyboard.ai

class AiDiagramRouter(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String? = { null }
) : DiagramGenerator {

    private val effectiveApiKeyProvider = {
        BuiltInConfig.getApiKey(apiKeyProvider())
    }

    private val effectiveModelProvider = {
        BuiltInConfig.getModel(modelProvider())
    }

    private val groqGenerator = GroqDiagramGenerator(effectiveApiKeyProvider, effectiveModelProvider)
    private val geminiGenerator = GeminiDiagramGenerator(effectiveApiKeyProvider, effectiveModelProvider)

    override suspend fun generateDiagram(transcript: String): Result<String> {
        val key = effectiveApiKeyProvider().trim()
        return if (key.startsWith("gsk_", ignoreCase = true)) {
            groqGenerator.generateDiagram(transcript)
        } else {
            geminiGenerator.generateDiagram(transcript)
        }
    }
}
