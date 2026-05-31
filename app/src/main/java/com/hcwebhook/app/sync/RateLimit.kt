package com.hcwebhook.app.sync

/**
 * Decides whether a Health Connect read/aggregate failure was a rate-limit, so the
 * caller can back off and retry rather than surfacing a fatal error.
 */
object RateLimit {

    /** Fallback classifier: matches the platform's rate-limit / quota wording. */
    fun messageIndicatesRateLimit(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return "rate limit" in m || "quota" in m
    }

    /**
     * Prefer the typed signal (API 34+); fall back to message matching for older
     * Health Connect providers / wrapped exceptions.
     */
    fun isRateLimited(e: Throwable): Boolean {
        // The typed platform exception only exists on API 34+; guard so older devices
        // (and JVM unit tests, where SDK_INT == 0) fall through to message matching.
        if (android.os.Build.VERSION.SDK_INT >= 34 && e is android.health.connect.HealthConnectException) {
            return e.errorCode == android.health.connect.HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED
        }
        return messageIndicatesRateLimit(e.message)
    }
}
