package com.hcwebhook.app.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

/** Serializes per-type queue state to/from a string for SharedPreferences. Tolerant of bad input. */
object SyncStateCodec {

    @Serializable
    private data class Dto(val t: String, val ls: Long? = null, val rc: Int = 0, val ne: Long? = null)

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(states: List<SyncTypeState>): String =
        json.encodeToString(
            states.map { Dto(it.type, it.lastSuccessAt?.toEpochMilli(), it.retryCount, it.nextEligibleAt?.toEpochMilli()) }
        )

    fun decode(s: String): List<SyncTypeState> {
        if (s.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<Dto>>(s).map {
                SyncTypeState(it.t, it.ls?.let(Instant::ofEpochMilli), it.rc, it.ne?.let(Instant::ofEpochMilli))
            }
        }.getOrDefault(emptyList())
    }
}
