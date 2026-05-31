package com.hcwebhook.app.sync

import java.time.Duration

/**
 * Escalating retry delays for a rate-limited data type. The burst quota clears in
 * seconds, but Health Connect's daily quota needs hours — hence the long tail.
 */
object SyncBackoff {

    /** Delay indexed by consecutive-failure count (1-based via [backoff]). */
    val LADDER: List<Duration> = listOf(
        Duration.ofMinutes(1),
        Duration.ofMinutes(10),
        Duration.ofMinutes(30),
        Duration.ofHours(1),
        Duration.ofHours(2),
    )

    /** Delay before retrying after [failureCount] consecutive failures. Clamped to the ladder ends. */
    fun backoff(failureCount: Int): Duration =
        LADDER[(failureCount - 1).coerceIn(0, LADDER.size - 1)]
}
