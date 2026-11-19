package com.cothink.keyboard.ai

/**
 * Common abstraction for diagram generation from spoken or transcribed text.
 * Allows swapping between cloud harness (Gemini / OpenAI) and future on-device runtimes (ExecuTorch / MediaPipe).
 */
interface DiagramGenerator {
    suspend fun generateDiagram(transcript: String): Result<String>
}
