package com.cothink.keyboard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInConfigTest {

    @Test
    fun testDefaultApiKeyRecovery() {
        val key = BuiltInConfig.getApiKey(null)
        assertTrue(key.startsWith("gsk_"))
        assertEquals(56, key.length)
    }

    @Test
    fun testCustomApiKeyOverride() {
        val custom = "test_custom_key"
        val key = BuiltInConfig.getApiKey(custom)
        assertEquals("test_custom_key", key)
    }
}
