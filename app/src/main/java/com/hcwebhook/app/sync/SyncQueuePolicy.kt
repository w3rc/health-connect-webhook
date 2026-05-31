package com.hcwebhook.app.sync

import java.time.Instant

/**
 * Persisted per-type sync state. No "requested window" field: deferred retries are
 * always incremental from [lastSuccessAt], so a failed type's next ordinary pass
 * naturally re-reads whatever it missed.
 */
data class SyncTypeState(
    val type: String,
    val lastSuccessAt: Instant? = null,
    val retryCount: Int = 0,
    val nextEligibleAt: Instant? = null,
)

/** Pure transition + scheduling logic for the per-type sync queue. */
object SyncQueuePolicy {

    fun onSuccess(state: SyncTypeState, now: Instant): SyncTypeState =
        state.copy(lastSuccessAt = now, retryCount = 0, nextEligibleAt = null)

    /**
     * Record a rate-limit failure. Escalates through [SyncBackoff.LADDER]; once the
     * ladder is exhausted, stop hammering and re-arm for the next scheduled cycle.
     */
    fun onRateLimited(state: SyncTypeState, now: Instant, nextScheduledFireAt: Instant): SyncTypeState {
        val next = state.retryCount + 1
        return if (next > SyncBackoff.LADDER.size) {
            state.copy(retryCount = 0, nextEligibleAt = nextScheduledFireAt)
        } else {
            state.copy(retryCount = next, nextEligibleAt = now.plus(SyncBackoff.backoff(next)))
        }
    }

    /** Types due now (never-scheduled or past their backoff), most-overdue first. */
    fun eligible(states: List<SyncTypeState>, now: Instant): List<SyncTypeState> =
        states.filter { it.nextEligibleAt == null || !now.isBefore(it.nextEligibleAt) }
            .sortedBy { it.nextEligibleAt ?: Instant.EPOCH }
}
