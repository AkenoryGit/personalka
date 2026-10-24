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

    @Test
    fun phoneAppliesWhatTheServerDecided() {
        // Записи нет — берётся любая.
        assertEquals(true, SyncRules.applyIncoming(SyncStamp(1_000, tablet), null, pending = false, device = phone))
        // Своя правка отправлена: сервер уже решил — берётся новая и та же самая, старая — нет.
        assertEquals(true, SyncRules.applyIncoming(SyncStamp(2_000, tablet), 1_000, pending = false, device = phone))
        assertEquals(true, SyncRules.applyIncoming(SyncStamp(1_000, phone), 1_000, pending = false, device = phone))
        assertEquals(false, SyncRules.applyIncoming(SyncStamp(500, tablet), 1_000, pending = false, device = phone))
        // Своя правка ещё в очереди: побеждает поздняя, при равном моменте — большее устройство.
        assertEquals(false, SyncRules.applyIncoming(SyncStamp(1_000, tablet), 2_000, pending = true, device = phone))
        assertEquals(true, SyncRules.applyIncoming(SyncStamp(3_000, tablet), 2_000, pending = true, device = phone))
        assertEquals(true, SyncRules.applyIncoming(SyncStamp(2_000, tablet), 2_000, pending = true, device = phone))
        assertEquals(false, SyncRules.applyIncoming(SyncStamp(2_000, phone), 2_000, pending = true, device = tablet))
    }

    @Test
    fun retryPauseGrowsUpTo15Minutes() {
        assertEquals(listOf(0L, 30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L), (0..7).map { SyncRules.retryDelay(it) })
        assertEquals(SyncRules.PERIOD_MS, SyncRules.retryDelay(100))
    }

    @Test
    fun nextExchange() {
        assertEquals(SyncRules.PERIOD_MS, SyncRules.nextPause(failures = 0, pending = 0, refused = false))
        assertEquals(SyncRules.QUIET_MS, SyncRules.nextPause(failures = 0, pending = 3, refused = false))
        assertEquals(60_000L, SyncRules.nextPause(failures = 2, pending = 3, refused = false))
        assertEquals(SyncRules.PERIOD_MS, SyncRules.nextPause(failures = 0, pending = 3, refused = true), "Отказ сервера — не стучаться каждые 5 секунд")
    }
}
