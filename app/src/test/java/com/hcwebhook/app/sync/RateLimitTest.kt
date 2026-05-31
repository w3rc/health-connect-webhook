package com.hcwebhook.app.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitTest {

    @Test
    fun messageWithRateLimitPhrase_isRateLimited() {
        assertTrue(RateLimit.messageIndicatesRateLimit("Rate limited request quota has been exceeded."))
        assertTrue(RateLimit.messageIndicatesRateLimit("RATE LIMIT hit"))
    }

    @Test
    fun messageWithQuota_isRateLimited() {
        assertTrue(RateLimit.messageIndicatesRateLimit("API quota exceeded, please wait"))
    }

    @Test
    fun unrelatedMessage_isNotRateLimited() {
        assertFalse(RateLimit.messageIndicatesRateLimit("Permission denied"))
        assertFalse(RateLimit.messageIndicatesRateLimit(null))
        assertFalse(RateLimit.messageIndicatesRateLimit(""))
    }

    @Test
    fun isRateLimited_fallsBackToMessageForPlainException() {
        assertTrue(RateLimit.isRateLimited(RuntimeException("Rate limited request quota exceeded")))
        assertFalse(RateLimit.isRateLimited(RuntimeException("network down")))
    }
}
