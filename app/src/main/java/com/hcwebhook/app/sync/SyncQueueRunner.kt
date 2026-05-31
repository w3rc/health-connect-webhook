package com.hcwebhook.app.sync

import java.time.Instant

/** Wall clock, injected so passes are deterministic in tests. */
interface SyncClock {
    fun now(): Instant
}

/** Reads one data type's payload fragment over (since, now]. Empty map = no new data. Throws on failure. */
interface TypeReader {
    suspend fun read(type: String, since: Instant?, now: Instant): Map<String, Any?>
}

/** Posts the merged payload fragments in a single request. Throws on failure. */
interface PayloadPoster {
    suspend fun post(payload: Map<String, Any?>)
}

/** Loads/saves the per-type queue state. */
interface SyncStateStore {
    fun load(): List<SyncTypeState>
    fun save(states: List<SyncTypeState>)
}

/**
 * Outcome of one queue pass.
 * - [synced]: types read and successfully posted (lastSuccessAt advanced).
 * - [deferred]: types that hit a rate limit, backed off.
 * - [errored]: types that failed for another reason (incl. a failed POST), also backed off but distinguished for logs.
 * - [nextWakeAt]: earliest time any deferred/future type becomes eligible, for scheduling the next WorkRequest.
 */
data class PassResult(
    val synced: List<String>,
    val deferred: List<String>,
    val errored: List<String>,
    val nextWakeAt: Instant?,
)

/**
 * Runs one resilient sync pass over the enabled data types: read each eligible type
 * fast, batch the successes into one POST, and back off the failures per-type so one
 * stuck type never sinks the rest. Pure orchestration — all IO is injected.
 */
class SyncQueueRunner(
    private val clock: SyncClock,
    private val reader: TypeReader,
    private val poster: PayloadPoster,
    private val store: SyncStateStore,
    private val nextScheduledFireAt: () -> Instant,
) {
    suspend fun runPass(enabledTypes: List<String>): PassResult {
        val now = clock.now()
        val stored = store.load().associateBy { it.type }
        // Reconcile to currently-enabled types: new ones start fresh, disabled ones drop.
        val byType = LinkedHashMap<String, SyncTypeState>()
        for (t in enabledTypes) byType[t] = stored[t] ?: SyncTypeState(t)

        val readOk = mutableListOf<String>()
        val fragments = LinkedHashMap<String, Any?>()
        val deferred = mutableListOf<String>()
        val errored = mutableListOf<String>()

        // Read each due type fast; a failure backs off only that type.
        for (st in SyncQueuePolicy.eligible(byType.values.toList(), now)) {
            try {
                fragments.putAll(reader.read(st.type, st.lastSuccessAt, now))
                readOk += st.type
            } catch (e: Throwable) {
                byType[st.type] = SyncQueuePolicy.onRateLimited(byType.getValue(st.type), now, nextScheduledFireAt())
                if (RateLimit.isRateLimited(e)) deferred += st.type else errored += st.type
            }
        }

        // Batch the pass's successes into a single POST. Only advance state if it lands.
        var synced = emptyList<String>()
        if (readOk.isNotEmpty()) {
            val postOk = try {
                if (fragments.isNotEmpty()) poster.post(fragments)
                true
            } catch (e: Throwable) {
                false
            }
            if (postOk) {
                for (t in readOk) byType[t] = SyncQueuePolicy.onSuccess(byType.getValue(t), now)
                synced = readOk.toList()
            } else {
                for (t in readOk) byType[t] = SyncQueuePolicy.onRateLimited(byType.getValue(t), now, nextScheduledFireAt())
                errored += readOk
            }
        }

        store.save(byType.values.toList())
        val nextWakeAt = byType.values.mapNotNull { it.nextEligibleAt }.minOrNull()
        return PassResult(synced, deferred, errored, nextWakeAt)
    }
}
