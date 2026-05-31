package com.hcwebhook.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SyncQueuePolicyTest {

    private val now = Instant.parse("2026-05-31T08:00:00Z")
    private val nextCycle = Instant.parse("2026-05-31T21:00:00Z")

    @Test
    fun onSuccess_advancesLastSuccessAndClearsRetryState() {
        val s = SyncTypeState("steps", retryCount = 3, nextEligibleAt = now)
        val r = SyncQueuePolicy.onSuccess(s, now)
        assertEquals(now, r.lastSuccessAt)
        assertEquals(0, r.retryCount)
        assertNull(r.nextEligibleAt)
    }

    @Test
    fun onRateLimited_firstFailureBacksOffOneMinute() {
        val r = SyncQueuePolicy.onRateLimited(SyncTypeState("hr"), now, nextCycle)
        assertEquals(1, r.retryCount)
        assertEquals(now.plus(Duration.ofMinutes(1)), r.nextEligibleAt)
    }

    @Test
    fun onRateLimited_escalatesThroughLadder() {
        var s = SyncTypeState("hr")
        val expected = listOf(
            Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofMinutes(30),
            Duration.ofHours(1), Duration.ofHours(2),
        )
        expected.forEachIndexed { i, d ->
            s = SyncQueuePolicy.onRateLimited(s, now, nextCycle)
            assertEquals("retryCount after failure ${i + 1}", i + 1, s.retryCount)
            assertEquals("delay after failure ${i + 1}", now.plus(d), s.nextEligibleAt)
        }
    }

    @Test
    fun onRateLimited_afterLadderExhausted_reArmsForNextCycleAndResets() {
        var s = SyncTypeState("hr")
        repeat(5) { s = SyncQueuePolicy.onRateLimited(s, now, nextCycle) } // exhaust the 5-step ladder
        val r = SyncQueuePolicy.onRateLimited(s, now, nextCycle)           // 6th failure gives up
        assertEquals(0, r.retryCount)
        assertEquals(nextCycle, r.nextEligibleAt)
    }

    @Test
    fun eligible_includesNeverScheduledAndDue_excludesFuture_mostOverdueFirst() {
        val due = SyncTypeState("a", nextEligibleAt = now.minusSeconds(1))
        val fresh = SyncTypeState("b", nextEligibleAt = null)
        val future = SyncTypeState("c", nextEligibleAt = now.plusSeconds(60))
        val out = SyncQueuePolicy.eligible(listOf(future, due, fresh), now)
        assertEquals(listOf("b", "a"), out.map { it.type })
    }
}
