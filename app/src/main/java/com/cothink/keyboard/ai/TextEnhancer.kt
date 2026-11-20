package com.cothink.keyboard.ai

interface TextEnhancer {
    suspend fun enhanceText(rawInput: String): Result<String>
}
