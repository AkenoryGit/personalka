package com.akenory.personalka.domain

import com.akenory.personalka.domain.model.TimeRange
import kotlinx.datetime.LocalTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdsTest {

    private val now = 1_792_492_800_000L // 20.10.2026 15:40 по клубу

    @Test
    fun idIsUuidVersion7() {
        repeat(1000) {
            val id = Ids.newId(now)
            assertTrue(Ids.V7_PATTERN.matches(id), id)
            assertEquals(36, id.length)
        }
    }

    @Test
    fun idKeepsCreationMoment() {
        assertEquals(now, Ids.createdMillis(Ids.newId(now)))
        assertEquals(0L, Ids.createdMillis(Ids.newId(0L)))
    }

    @Test
    fun laterIdSortsAfterEarlierAsText() {
        val random = Random(7)
        val ids = (0 until 200).map { Ids.newId(now + it * 3L, random) }
        assertEquals(ids, ids.sorted())
    }

    @Test
    fun idsDoNotRepeatWithinOneMillisecond() {
        val ids = List(10_000) { Ids.newId(now) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun knownBytesGiveKnownText() {
        // Случайная часть — нули: видны только время, версия и вариант.
        val zeros = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
        }
        assertEquals("01a0c711-1a00-7000-8000-000000000000", Ids.newId(0x01A0C7111A00L, zeros))
    }

    @Test
    fun timeRangeIsHalfOpenAndReadsBack() {
        val morning = TimeRange.parse("07:00-12:00")
        assertTrue(LocalTime(7, 0) in morning)
        assertTrue(LocalTime(11, 59) in morning)
        assertFalse(LocalTime(12, 0) in morning)
        assertEquals("07:00-12:00", morning.toString())
        assertFailsWith<IllegalArgumentException> { TimeRange(LocalTime(12, 0), LocalTime(7, 0)) }
    }
}
