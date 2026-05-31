package com.hcwebhook.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hcwebhook.app.PreferencesManager
import com.hcwebhook.app.SyncManager
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Runs one resilient sync pass over the enabled data types, then schedules a follow-up
 * pass for any deferred type. Drives all background syncs (periodic + alarm). Manual
 * "Sync Now" stays on the existing SyncManager.performSync path.
 */
class SyncQueueWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        return try {
            val prefs = PreferencesManager(ctx)
            val enabled = prefs.getEnabledDataTypes().map { it.name }
            if (enabled.isEmpty()) return Result.success()

            val syncManager = SyncManager(ctx)
            val runner = SyncQueueRunner(
                clock = SystemSyncClock,
                reader = HealthConnectTypeReader(syncManager),
                poster = WebhookPayloadPoster(syncManager),
                store = SharedPrefsSyncStateStore(ctx),
                // Ladder-exhausted types re-arm for the next ordinary cycle.
                nextScheduledFireAt = { Instant.now().plus(NEXT_CYCLE_FALLBACK) },
            )

            val result = runner.runPass(enabled)
            result.nextWakeAt?.let { scheduleNext(ctx, Duration.between(Instant.now(), it)) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Whole-pass failure (not a single type): let WorkManager retry.
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "hc-sync-queue"
        private val NEXT_CYCLE_FALLBACK: Duration = Duration.ofHours(6)

        /** Enqueue a pass to run as soon as possible (alarm fire / boot / manual trigger). */
        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SyncQueueWorker>().build(),
            )
        }

        /** Schedule the next pass after [delay] (for deferred-type retries). */
        fun scheduleNext(context: Context, delay: Duration) {
            val safe = if (delay.isNegative) Duration.ZERO else delay
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SyncQueueWorker>()
                    .setInitialDelay(safe.toMinutes(), TimeUnit.MINUTES)
                    .build(),
            )
        }
    }
}
