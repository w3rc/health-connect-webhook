package com.hcwebhook.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class SyncBackoffTest {

    @Test
    fun ladder_mapsFailureCountToEscalatingDelays() {
        assertEquals(Duration.ofMinutes(1), SyncBackoff.backoff(1))
        assertEquals(Duration.ofMinutes(10), SyncBackoff.backoff(2))
        assertEquals(Duration.ofMinutes(30), SyncBackoff.backoff(3))
        assertEquals(Duration.ofHours(1), SyncBackoff.backoff(4))
        assertEquals(Duration.ofHours(2), SyncBackoff.backoff(5))
    }

    @Test
    fun backoff_clampsAboveLadderToMax() {
        assertEquals(Duration.ofHours(2), SyncBackoff.backoff(6))
        assertEquals(Duration.ofHours(2), SyncBackoff.backoff(99))
    }

    @Test
    fun backoff_clampsBelowOneToFirstStep() {
        assertEquals(Duration.ofMinutes(1), SyncBackoff.backoff(0))
        assertEquals(Duration.ofMinutes(1), SyncBackoff.backoff(-3))
    }
}
