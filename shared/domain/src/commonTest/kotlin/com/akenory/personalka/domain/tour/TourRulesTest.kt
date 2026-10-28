package com.akenory.personalka.domain.tour

import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TourFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Знакомство с приложением (шаг 5.3б, ТЗ 5.18): порядок остановок, пустое место первой остановки, тексты. */
class TourRulesTest {
    @Test
    fun sixStopsInOrder() {
        assertEquals(6, TourStop.COUNT)
        var stop: TourStop? = TourStop.CALENDAR
        val passed = mutableListOf<TourStop>()
        while (stop != null) {
            passed += stop
            stop = TourRules.next(stop, salaryNeeded = true)
        }
        assertEquals(TourStop.entries.toList(), passed.toList())
    }

    @Test
    fun salaryAlreadySetIsSkipped() {
        assertEquals(TourStop.BACKUP, TourRules.next(TourStop.HOME, salaryNeeded = false))
        assertEquals(TourStop.SALARY, TourRules.next(TourStop.HOME, salaryNeeded = true))
        assertNull(TourRules.next(TourStop.BACKUP, salaryNeeded = true))
    }

    @Test
    fun skipAsksOnlyWithoutFolder() {
        assertTrue(TourRules.asksAboutBackup(folderChosen = false))
        assertFalse(TourRules.asksAboutBackup(folderChosen = true))
    }

    @Test
    fun slotIsNextFreeFullHour() {
        val h = { hour: Int, minute: Int -> hour * 60 + minute }
        // 15:40, пусто — 16:00.
        assertEquals(h(16, 0), TourRules.slotMinute(h(15, 40), h(7, 0), h(23, 0), emptyList()))
        // 16:00–16:50 занято — 17:00; тренировка 17:30 мешает 17:00 — 18:00.
        val busy = listOf(h(16, 0)..h(16, 50), h(17, 30)..h(18, 20))
        assertEquals(h(19, 0), TourRules.slotMinute(h(15, 40), h(7, 0), h(23, 0), busy))
        // Раньше начала сетки — первый её час.
        assertEquals(h(7, 0), TourRules.slotMinute(h(5, 10), h(7, 0), h(23, 0), emptyList()))
        // 22:30: 23:00 уже за сеткой — места сегодня нет.
        assertNull(TourRules.slotMinute(h(22, 30), h(7, 0), h(23, 0), emptyList()))
        // Конец тренировки ровно в начале часа ему не мешает.
        assertEquals(h(17, 0), TourRules.slotMinute(h(16, 5), h(7, 0), h(23, 0), listOf(h(16, 10)..h(17, 0))))
    }

    @Test
    fun noTrainingSkipsTheCard() {
        assertEquals(TourStop.CLIENTS, TourRules.next(TourStop.CALENDAR, salaryNeeded = true, hasTraining = false))
        assertEquals(TourStop.CARD, TourRules.next(TourStop.CALENDAR, salaryNeeded = true, hasTraining = true))
    }

    @Test
    fun texts() {
        assertEquals("Знакомство · 1 из 6", TourFormat.step(TourStop.CALENDAR))
        assertEquals("Знакомство · 6 из 6", TourFormat.step(TourStop.BACKUP))
        val nb = RuText.NBSP
        assertEquals(
            "Клиент отменил — нажмите «Отменить». Предупредил хотя бы за 1${nb}час — отмена без списания, позже — " +
                "со списанием. Это время вы задаёте сами: «Ещё → Календарь».",
            TourFormat.cardCancel(60),
        )
        assertTrue(TourFormat.cardCancel(90).contains("1${nb}час 30${nb}минут"))
        // Дневник выключен по умолчанию — о нём ни слова; включён — есть.
        assertFalse(TourFormat.homeText(diary = false).contains("дневник"))
        assertTrue(TourFormat.homeText(diary = true).contains("дневник"))
        assertTrue(TourFormat.cardDone(autoDone = false).contains("нажмите «Проведено»"))
    }
}
