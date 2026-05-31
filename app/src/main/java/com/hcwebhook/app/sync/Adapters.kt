package com.hcwebhook.app.sync

import android.content.Context
import androidx.core.content.edit
import com.hcwebhook.app.HealthDataType
import com.hcwebhook.app.SyncManager
import java.time.Instant

/** Reads one data type's payload fragment by delegating to SyncManager's proven read+serialize. */
class HealthConnectTypeReader(private val syncManager: SyncManager) : TypeReader {
    override suspend fun read(type: String, since: Instant?, now: Instant): Map<String, Any?> =
        syncManager.readTypeFragment(HealthDataType.valueOf(type), since, now)
}

/** Posts the merged fragments via SyncManager's webhook path; throws on failure so the runner backs off. */
class WebhookPayloadPoster(private val syncManager: SyncManager) : PayloadPoster {
    override suspend fun post(payload: Map<String, Any?>) {
        syncManager.postMergedFragment(payload).getOrThrow()
    }
}

/** Per-type queue state persisted in its own SharedPreferences file. */
class SharedPrefsSyncStateStore(context: Context) : SyncStateStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    override fun load(): List<SyncTypeState> = SyncStateCodec.decode(prefs.getString(KEY, "").orEmpty())
    override fun save(states: List<SyncTypeState>) {
        prefs.edit { putString(KEY, SyncStateCodec.encode(states)) }
    }
    private companion object {
        const val PREFS = "hc_sync_queue_state"
        const val KEY = "states"
    }
}

/** Wall clock backed by the real system time. */
object SystemSyncClock : SyncClock {
    override fun now(): Instant = Instant.now()
}
