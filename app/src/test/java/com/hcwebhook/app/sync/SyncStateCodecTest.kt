package com.hcwebhook.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncStateCodecTest {

    @Test
    fun encodeThenDecode_roundTripsAllFields() {
        val states = listOf(
            SyncTypeState("steps", lastSuccessAt = Instant.parse("2026-05-31T08:00:00Z")),
            SyncTypeState("hr", retryCount = 2, nextEligibleAt = Instant.parse("2026-05-31T08:30:00Z")),
            SyncTypeState("sleep"), // all defaults / nulls
        )
        assertEquals(states, SyncStateCodec.decode(SyncStateCodec.encode(states)))
    }

    @Test
    fun decode_blankOrEmpty_returnsEmpty() {
        assertTrue(SyncStateCodec.decode("").isEmpty())
        assertTrue(SyncStateCodec.decode("   ").isEmpty())
    }

    @Test
    fun decode_malformed_returnsEmptyRatherThanThrowing() {
        assertTrue(SyncStateCodec.decode("not json at all {").isEmpty())
    }

    @Test
    fun encode_emptyList_decodesBackToEmpty() {
        assertTrue(SyncStateCodec.decode(SyncStateCodec.encode(emptyList())).isEmpty())
    }
}
