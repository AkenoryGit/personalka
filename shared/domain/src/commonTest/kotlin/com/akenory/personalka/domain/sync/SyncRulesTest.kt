package com.akenory.personalka.domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncRulesTest {
    private val phone = "0193a1b2-aaaa"
    private val tablet = "0193a1b2-bbbb"

    @Test
    fun laterEditWins() {
        assertEquals(SyncOrder.NEWER, SyncRules.order(SyncStamp(2_000, phone), SyncStamp(1_000, tablet)))
        assertEquals(SyncOrder.OLDER, SyncRules.order(SyncStamp(1_000, tablet), SyncStamp(2_000, phone)))
        assertEquals(SyncOrder.NEWER, SyncRules.order(SyncStamp(1_000, phone), null))
    }

    @Test
    fun sameMomentIsDecidedByDevice() {
        assertEquals(SyncOrder.NEWER, SyncRules.order(SyncStamp(1_000, tablet), SyncStamp(1_000, phone)))
        assertEquals(SyncOrder.OLDER, SyncRules.order(SyncStamp(1_000, phone), SyncStamp(1_000, tablet)))
        assertEquals(SyncOrder.SAME, SyncRules.order(SyncStamp(1_000, phone), SyncStamp(1_000, phone)))
    }

    @Test
    fun futureMomentBecomesNow() {
        val now = 1_000_000L
        assertEquals(now + SyncRules.FUTURE_MS, SyncRules.acceptedAt(now + SyncRules.FUTURE_MS, now))
        assertEquals(now, SyncRules.acceptedAt(now + SyncRules.FUTURE_MS + 1, now))
        assertEquals(5L, SyncRules.acceptedAt(5, now))
    }

    @Test
    fun currentAndPreviousSchemaAreSupported() {
        assertNull(SyncRules.schemaProblem(3, current = 3))
        assertNull(SyncRules.schemaProblem(2, current = 3))
        assertEquals(SchemaProblem.TOO_OLD, SyncRules.schemaProblem(1, current = 3))
        assertEquals(SchemaProblem.TOO_OLD, SyncRules.schemaProblem(null, current = 3))
        assertEquals(SchemaProblem.TOO_NEW, SyncRules.schemaProblem(4, current = 3))
    }

    @Test
    fun deletedRowsLiveNinetyDays() {
        val day = 24 * 60 * 60 * 1000L
        assertEquals(10 * day, SyncRules.purgeBefore(100 * day))
    }
}
