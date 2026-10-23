package com.akenory.personalka.domain.backup

import com.akenory.personalka.domain.format.BackupFormat
import com.akenory.personalka.domain.format.RuText
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Правила и строки резервной копии (шаг 1.15, ТЗ 7.6, 1.5). */
class BackupRulesTest {
    private val now = LocalDateTime(2026, 10, 23, 15, 40)

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    @Test
    fun fileNamesByClubTime() {
        assertEquals("trener-backup-20261023-1540.zip", BackupRules.fileName(now))
        assertEquals("trener-backup-20260105-0907.zip", BackupRules.fileName(LocalDateTime(2026, 1, 5, 9, 7)))
        assertEquals("trener-calendar-20261023.ics", BackupRules.calendarFileName(now.date))
        assertTrue(BackupRules.isBackupName("trener-backup-20261023-1540.zip"))
        assertFalse(BackupRules.isBackupName("trener-backup-20261023-1540 (1).zip"))
    }

    @Test
    fun folderKeepsLastTenOwnCopies() {
        val copies = (1..12).map { "trener-backup-202610${it.toString().padStart(2, '0')}-2100.zip" }
        val delete = BackupRules.toDelete(copies.shuffled() + "фото.jpg")
        assertEquals(listOf("trener-backup-20261002-2100.zip", "trener-backup-20261001-2100.zip"), delete)
    }

    @Test
    fun autoCopyDailyOrWeekly() {
        assertTrue(BackupRules.autoDue(null, now, BackupFrequency.DAILY))
        assertFalse(BackupRules.autoDue(LocalDateTime(2026, 10, 23, 8, 0), now, BackupFrequency.DAILY))
        assertTrue(BackupRules.autoDue(LocalDateTime(2026, 10, 22, 23, 59), now, BackupFrequency.DAILY))
        assertFalse(BackupRules.autoDue(LocalDateTime(2026, 10, 17, 8, 0), now, BackupFrequency.WEEKLY))
        assertTrue(BackupRules.autoDue(LocalDateTime(2026, 10, 16, 23, 0), now, BackupFrequency.WEEKLY))
        assertEquals(BackupFrequency.DAILY, BackupFrequency.of(null))
        assertEquals(BackupFrequency.WEEKLY, BackupFrequency.of("week"))
    }

    @Test
    fun remindAfterSevenDays() {
        assertTrue(BackupRules.remind(null, now))
        assertFalse(BackupRules.remind(LocalDateTime(2026, 10, 16, 15, 41), now))
        assertTrue(BackupRules.remind(LocalDateTime(2026, 10, 16, 15, 40), now))
    }

    @Test
    fun passwordAndCalendarRange() {
        assertFalse(BackupRules.passwordOk("12345", "12345"))
        assertFalse(BackupRules.passwordOk("123456", "123457"))
        assertTrue(BackupRules.passwordOk("123456", "123456"))
        assertEquals(LocalDate(2026, 7, 23) to LocalDate(2027, 1, 23), BackupRules.calendarRange(now.date))
    }

    @Test
    fun screenLines() {
        assertEquals(s("Последняя копия — пт_23.10_15:40"), BackupFormat.last(now))
        assertEquals("Копий ещё не было", BackupFormat.last(null))
        assertEquals("Папка для копий не выбрана", BackupFormat.summary(now, folderChosen = false, BackupFrequency.DAILY))
        assertEquals(s("Копия пт_23.10_15:40 · раз в неделю"), BackupFormat.summary(now, folderChosen = true, BackupFrequency.WEEKLY))
        assertEquals(s("Копия от 23.10.2026_15:40"), BackupFormat.previewTitle(now))
        assertEquals(s("34_клиента · 1_тренировка"), BackupFormat.previewCounts(34, 1))
    }

    @Test
    fun icsInUtcWithEscapingAndFolding() {
        val text = IcsCalendar.build(
            listOf(
                IcsEvent("t2", LocalDateTime(2026, 10, 24, 9, 0), 60, "Группа · Утренняя группа выходного дня в большом бассейне (12)", "Большой бассейн", cancelled = true),
                IcsEvent("t1", LocalDateTime(2026, 10, 23, 23, 30), 50, "ПТ · Орлов М., Носова Е.; план", "ББ"),
            ),
            nowMillis = 0,
        )
        val lines = text.split("\r\n")
        assertEquals("BEGIN:VCALENDAR", lines.first())
        assertEquals("", lines.last(), "Каждая строка кончается CRLF")
        // 23:30 по Тюмени (UTC+5) — 18:30 UTC, конец через 50 минут.
        assertTrue("DTSTART:20261023T183000Z" in lines)
        assertTrue("DTEND:20261023T192000Z" in lines)
        assertTrue("SUMMARY:ПТ · Орлов М.\\, Носова Е.\\; план" in lines)
        // Тренировки по времени: первой — пятничная.
        assertTrue(lines.indexOf("UID:t1@personalka") < lines.indexOf("UID:t2@personalka"))
        assertTrue("STATUS:CANCELLED" in lines)
        assertTrue(lines.all { it.encodeToByteArray().size <= 75 }, "Длинные строки переносятся")
        assertTrue(text.contains("\r\n "), "Перенос — с пробела")
    }
}
