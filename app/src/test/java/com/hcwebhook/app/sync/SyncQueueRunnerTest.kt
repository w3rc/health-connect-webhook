package com.hcwebhook.app.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

private val NOW: Instant = Instant.parse("2026-05-31T08:00:00Z")
private val NEXT_CYCLE: Instant = Instant.parse("2026-05-31T21:00:00Z")

private class FakeClock(private val t: Instant = NOW) : SyncClock {
    override fun now() = t
}

private class FakeReader(
    val fragments: MutableMap<String, Map<String, Any?>> = mutableMapOf(),
    val errors: MutableMap<String, Throwable> = mutableMapOf(),
) : TypeReader {
    val reads = mutableListOf<String>()
    override suspend fun read(type: String, since: Instant?, now: Instant): Map<String, Any?> {
        reads += type
        errors[type]?.let { throw it }
        return fragments[type] ?: emptyMap()
    }
}

private class FakePoster(var fail: Boolean = false) : PayloadPoster {
    val posted = mutableListOf<Map<String, Any?>>()
    override suspend fun post(payload: Map<String, Any?>) {
        if (fail) throw RuntimeException("post failed 500")
        posted += payload
    }
}

private class FakeStore(initial: List<SyncTypeState> = emptyList()) : SyncStateStore {
    var states: List<SyncTypeState> = initial
    override fun load() = states
    override fun save(states: List<SyncTypeState>) { this.states = states }
}

private fun runner(
    reader: FakeReader,
    poster: FakePoster,
    store: FakeStore,
    clock: SyncClock = FakeClock(),
) = SyncQueueRunner(clock, reader, poster, store) { NEXT_CYCLE }

class SyncQueueRunnerTest {

    @Test
    fun allTypesReadAndPostedOnce_advancesState() = runBlocking {
        val reader = FakeReader(fragments = mutableMapOf(
            "steps" to mapOf("steps" to listOf(1)),
            "hr" to mapOf("heart_rate" to listOf(2)),
        ))
        val poster = FakePoster()
        val store = FakeStore()
        val res = runner(reader, poster, store).runPass(listOf("steps", "hr"))

        assertEquals(setOf("steps", "hr"), res.synced.toSet())
        assertTrue(res.deferred.isEmpty() && res.errored.isEmpty())
        assertEquals(1, poster.posted.size)
        assertEquals(setOf("steps", "heart_rate"), poster.posted[0].keys)
        assertTrue(store.states.all { it.lastSuccessAt == NOW && it.retryCount == 0 && it.nextEligibleAt == null })
    }

    @Test
    fun rateLimitedType_isDeferredWithBackoff_othersStillPost() = runBlocking {
        val reader = FakeReader(
            fragments = mutableMapOf("steps" to mapOf("steps" to listOf(1))),
            errors = mutableMapOf("hr" to RuntimeException("Rate limited request quota exceeded")),
        )
        val poster = FakePoster()
        val store = FakeStore()
        val res = runner(reader, poster, store).runPass(listOf("steps", "hr"))

        assertEquals(listOf("steps"), res.synced)
        assertEquals(listOf("hr"), res.deferred)
        assertTrue(res.errored.isEmpty())
        assertEquals(setOf("steps"), poster.posted[0].keys)
        val hr = store.states.first { it.type == "hr" }
        assertEquals(1, hr.retryCount)
        assertEquals(NOW.plus(Duration.ofMinutes(1)), hr.nextEligibleAt)
    }

    @Test
    fun nonRateLimitReadError_isErrored_notDeferred_othersUnaffected() = runBlocking {
        val reader = FakeReader(
            fragments = mutableMapOf("steps" to mapOf("steps" to listOf(1))),
            errors = mutableMapOf("hr" to RuntimeException("device offline")),
        )
        val poster = FakePoster()
        val store = FakeStore()
        val res = runner(reader, poster, store).runPass(listOf("steps", "hr"))

        assertEquals(listOf("steps"), res.synced)
        assertEquals(listOf("hr"), res.errored)
        assertTrue(res.deferred.isEmpty())
    }

    @Test
    fun postFailure_advancesNothing_backsOffReadTypes() = runBlocking {
        val reader = FakeReader(fragments = mutableMapOf(
            "steps" to mapOf("steps" to listOf(1)),
            "hr" to mapOf("heart_rate" to listOf(2)),
        ))
        val poster = FakePoster(fail = true)
        val store = FakeStore()
        val res = runner(reader, poster, store).runPass(listOf("steps", "hr"))

        assertTrue(res.synced.isEmpty())
        assertEquals(setOf("steps", "hr"), res.errored.toSet())
        assertTrue("no success may be recorded when the POST failed",
            store.states.all { it.lastSuccessAt == null && it.retryCount == 1 })
    }

    @Test
    fun typeNotYetEligible_isSkipped() = runBlocking {
        val store = FakeStore(listOf(
            SyncTypeState("hr", nextEligibleAt = NOW.plusSeconds(600)),
        ))
        val reader = FakeReader(fragments = mutableMapOf("steps" to mapOf("steps" to listOf(1))))
        val poster = FakePoster()
        val res = runner(reader, poster, store).runPass(listOf("steps", "hr"))

        assertFalse("future-eligible type must not be read", reader.reads.contains("hr"))
        assertEquals(listOf("steps"), res.synced)
        val hr = store.states.first { it.type == "hr" }
        assertEquals(NOW.plusSeconds(600), hr.nextEligibleAt) // unchanged
        assertEquals(NOW.plusSeconds(600), res.nextWakeAt)     // earliest future eligibility
    }

    @Test
    fun disabledType_isDroppedFromSavedState() = runBlocking {
        val store = FakeStore(listOf(
            SyncTypeState("steps", lastSuccessAt = NOW.minusSeconds(3600)),
            SyncTypeState("oldtype", lastSuccessAt = NOW.minusSeconds(3600)),
        ))
        val reader = FakeReader(fragments = mutableMapOf("steps" to mapOf("steps" to listOf(1))))
        val res = runner(reader, FakePoster(), store).runPass(listOf("steps"))

        assertEquals(setOf("steps"), store.states.map { it.type }.toSet())
        assertEquals(listOf("steps"), res.synced)
    }

    @Test
    fun emptyData_doesNotPost_butStillMarksSuccess() = runBlocking {
        val reader = FakeReader(fragments = mutableMapOf()) // every read returns empty
        val poster = FakePoster()
        val store = FakeStore()
        val res = runner(reader, poster, store).runPass(listOf("steps", "hr"))

        assertEquals(setOf("steps", "hr"), res.synced.toSet())
        assertTrue("no payload to post", poster.posted.isEmpty())
        assertTrue(store.states.all { it.lastSuccessAt == NOW })
        assertNull(res.nextWakeAt)
    }
}
