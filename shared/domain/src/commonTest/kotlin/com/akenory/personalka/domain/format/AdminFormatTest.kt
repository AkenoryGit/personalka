package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.cabinet.CabinetUsage
import com.akenory.personalka.domain.cabinet.CabinetUse
import com.akenory.personalka.domain.cabinet.CabinetUseLine
import com.akenory.personalka.domain.cabinet.CabinetVisits
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Строки админки на сайте (шаг 5.2б). */
class AdminFormatTest {
    @Test
    fun emailIsHidden() {
        assertEquals("d…@gmail.com", AdminFormat.hide("dmitry@gmail.com"))
        assertEquals("Отправили код на d…@gmail.com — действует 15${NBSP}минут", AdminFormat.codeSent("dmitry@gmail.com", 15))
    }

    @Test
    fun logLine() {
        assertEquals(
            "27.10 09:14 · Елена Соколова · приостановлен",
            AdminFormat.log(LocalDateTime(2026, 10, 27, 9, 14), 2026, listOf("Елена Соколова", AdminFormat.SUSPENDED)),
        )
        assertEquals(
            "22.10.2025 21:40 · Вход в админку · 10.0.0.1",
            AdminFormat.log(LocalDateTime(2025, 10, 22, 21, 40), 2026, listOf(AdminFormat.SIGNED_IN, "", "10.0.0.1")),
        )
        assertEquals("Тренеры · 4", AdminFormat.coaches(4))
    }

    @Test
    fun featuresLine() {
        assertEquals("всё закрыто", AdminFormat.features(emptyMap()))
        assertEquals(
            "Копия на сервере, Кабинеты родителей до 31.12.2026",
            AdminFormat.features(linkedMapOf(Feature.SERVER_COPY to null, Feature.CABINETS to LocalDate(2026, 12, 31))),
        )
        assertEquals("Новым тренерам: Копия на сервере", AdminFormat.defaults(setOf(Feature.SERVER_COPY)))
    }

    private val gb = 1024L * 1024 * 1024
    private val now = LocalDateTime(2026, 10, 28, 16, 20)

    private fun status(backup: BackupMark?, used: Long = 7_946_000_000, total: Long = 21_045_000_000) =
        ServerStatus("0.1.377", used, total, backup)

    @Test
    fun serverLineWithFreshBackup() {
        val s = status(BackupMark(LocalDateTime(2026, 10, 28, 3, 30), ok = true, detail = "28.10.2026: trener.dump.age"))
        assertEquals("Сервер 0.1.377 · диск 7,4 из 19,6${NBSP}ГБ (38${NBSP}%) · копия базы сегодня 03:30", AdminFormat.server(s, now))
        assertFalse(AdminFormat.serverAlarm(s, now))
        // Ночью до новой копии вчерашняя ещё свежая; днём без сегодняшней — уже нет (36 часов).
        val night = LocalDateTime(2026, 10, 28, 2, 0)
        val yesterday = status(BackupMark(LocalDateTime(2026, 10, 27, 3, 30), ok = true, detail = ""))
        assertTrue(AdminFormat.server(yesterday, night).endsWith("копия базы вчера 03:30"))
        assertFalse(AdminFormat.serverAlarm(yesterday, night))
        assertTrue(AdminFormat.server(yesterday, now).endsWith("копия базы вчера 03:30 — новой нет больше суток"))
        assertTrue(AdminFormat.serverAlarm(yesterday, now))
    }

    @Test
    fun serverLineAlarms() {
        val failed = status(BackupMark(LocalDateTime(2026, 10, 28, 3, 31), ok = false, detail = "шифрование"))
        assertTrue(AdminFormat.server(failed, now).endsWith("копия базы не сделана сегодня 03:31 — шифрование"))
        assertTrue(AdminFormat.serverAlarm(failed, now))

        val old = status(BackupMark(LocalDateTime(2026, 10, 26, 3, 30), ok = true, detail = ""))
        assertTrue(AdminFormat.server(old, now).endsWith("копия базы 26.10 03:30 — новой нет больше суток"))
        assertTrue(AdminFormat.serverAlarm(old, now))

        val none = status(null)
        assertTrue(AdminFormat.server(none, now).endsWith("копий базы ещё не было"))
        assertTrue(AdminFormat.serverAlarm(none, now))

        val full = status(BackupMark(LocalDateTime(2026, 10, 28, 3, 30), ok = true, detail = ""), used = 17 * gb, total = 20 * gb)
        assertTrue(AdminFormat.server(full, now).contains("диск 17,0 из 20,0${NBSP}ГБ (85${NBSP}%)"))
        assertTrue(AdminFormat.serverAlarm(full, now))
    }

    @Test
    fun serverLineWithoutDisk() {
        val s = status(BackupMark(LocalDateTime(2025, 12, 30, 3, 30), ok = true, detail = ""), used = 0, total = 0)
        assertEquals("Сервер 0.1.377 · копия базы 30.12.2025 03:30 — новой нет больше суток", AdminFormat.server(s, now))
    }

    // --- Кабинеты клиентов (шаг 5.35) ---

    private val today = LocalDate(2026, 11, 26)

    private fun line(recent: Int, recentDays: Int, total: Int) = CabinetUseLine(
        CabinetVisits("c", LocalDate(2026, 10, 1), LocalDateTime(2026, 11, 25, 21, 14), emptyMap()),
        CabinetUse.ACTIVE, recent, recentDays, total,
    )

    @Test
    fun cabinetUsageLine() {
        assertEquals(
            "Кабинеты: 7 из 12 открывали за 30${NBSP}дней, 2${NBSP}— давно, 3${NBSP}— ни разу",
            AdminFormat.usage(CabinetUsage(total = 12, active = 7, idle = 2, never = 3)),
        )
        assertEquals("Кабинеты: 4 из 4 открывали за 30${NBSP}дней", AdminFormat.usage(CabinetUsage(4, 4, 0, 0)))
        assertEquals("Кабинеты: 0 из 2 открывали за 30${NBSP}дней, 2${NBSP}— ни разу", AdminFormat.usage(CabinetUsage(2, 0, 0, 2)))
        assertEquals(null, AdminFormat.usage(CabinetUsage(0, 0, 0, 0)), "без кабинетов строки нет")
    }

    @Test
    fun cabinetVisitsLine() {
        assertEquals("4${NBSP}дня из 30 · 5${NBSP}посещений · всего 23", AdminFormat.visits(line(5, 4, 23)))
        assertEquals("1${NBSP}день из 30 · 1${NBSP}посещение", AdminFormat.visits(line(1, 1, 1)), "всего — только когда больше")
        assertEquals("За 30${NBSP}дней не заходили · всего 21${NBSP}посещение", AdminFormat.visits(line(0, 0, 21)))
        assertEquals(null, AdminFormat.visits(line(0, 0, 0)), "посещений ещё не считали — строки нет")
    }

    @Test
    fun cabinetTitlesAndNotes() {
        assertEquals("Заходят · 7", AdminFormat.useTitle(CabinetUse.ACTIVE, 7))
        assertEquals("Давно не заходили · 2", AdminFormat.useTitle(CabinetUse.IDLE, 2))
        assertEquals("Ни разу не открывали · 3", AdminFormat.useTitle(CabinetUse.NEVER, 3))
        assertEquals("Ссылка выдана 03.11", AdminFormat.issued(LocalDate(2026, 11, 3), today))
        assertEquals("Ссылка выдана 22.10.2025", AdminFormat.issued(LocalDate(2025, 10, 22), today))
        assertTrue(AdminFormat.visitsNote(LocalDate(2026, 11, 27)).endsWith("Посещения считаются с 27.11.2026."))
        assertTrue(AdminFormat.visitsNote(null).endsWith("«Как видит родитель» не в счёт."))
    }
}
