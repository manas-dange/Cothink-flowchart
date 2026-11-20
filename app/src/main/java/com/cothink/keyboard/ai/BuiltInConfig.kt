package com.cothink.keyboard.ai

object BuiltInConfig {
    const val DEFAULT_MODEL = "qwen/qwen3.8-27b"
    const val FALLBACK_MODEL = "openai/gpt-oss-20b"

    private const val MASK = 0x5A
    private val OBFUSCATED_KEY = byteArrayOf(
        61, 41, 49, 5, 98, 63, 50, 48, 32, 45, 27, 27, 29, 99, 51, 40,
        54, 9, 46, 9, 63, 0, 56, 17, 13, 29, 62, 35, 56, 105, 28, 3,
        48, 57, 48, 23, 31, 109, 47, 50, 14, 61, 24, 51, 19, 12, 12, 99,
        29, 56, 107, 99, 43, 99, 8, 19
    )

    fun getApiKey(customKey: String?): String {
        if (!customKey.isNullOrBlank()) {
            return customKey.trim()
        }
        return try {
            val bytes = OBFUSCATED_KEY.map { (it.toInt() xor MASK).toByte() }.toByteArray()
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun getModel(customModel: String?): String {
        if (!customModel.isNullOrBlank()) {
            return customModel.trim()
        }
        return DEFAULT_MODEL
    }
}
