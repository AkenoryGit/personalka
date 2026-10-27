package com.akenory.personalka.domain.home

import com.akenory.personalka.domain.format.HomeFormat
import com.akenory.personalka.domain.format.RuText
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Главная» без денег (шаг 1.16, ТЗ 5.2): примеры — макеты «День», «Вечер», «Утро» и «Разложенный Fold» шага 0.6. */
class HomeRulesTest {
    private val today = LocalDate(2026, 10, 20)

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun at(hour: Int, minute: Int = 0, day: LocalDate = today) = LocalDateTime(day.year, day.month, day.day, hour, minute)

    private fun slot(
        id: String,
        hour: Int,
        minute: Int = 0,
        day: LocalDate = today,
        group: Boolean = false,
        cancelled: Boolean = false,
        marked: Boolean = false,
        duration: Int = 50,
    ) = HomeSlot(id, at(hour, minute, day), duration, group, cancelled, marked)

    // День макета «Разложенный Fold»: 7 прошедших с 08:00, ПТ в 15:00 идёт, мини-группа в 16:00, ещё 17, 18, 19.
    private val day = (8..14).map { slot("past$it", it, marked = true) } + listOf(
        slot("pt15", 15),
        slot("mini16", 16, group = true),
        slot("pt17", 17),
        slot("group18", 18, group = true),
        slot("spt19", 19),
    )

    @Test
    fun nearestIsNextByStart() {
        // 14:50: ПТ 15:00 ещё не началась — ближайшая через 10 минут.
        val now = at(14, 50)
        val nearest = HomeRules.nearest(day, now)
        assertEquals("pt15", nearest?.id)
        assertEquals(s("через 10_мин"), HomeFormat.startsIn(now, nearest!!.start, nearest.end))
        assertEquals("Ближайшая · 15:00–15:50", HomeFormat.nearest(nearest.start, nearest.end, now))
        val later = HomeRules.laterToday(day, nearest, today)
        assertEquals(listOf("mini16", "pt17", "group18", "spt19"), later.map { it.id })
        assertEquals(s("Ещё сегодня: 4, последняя в_19:00"), HomeFormat.laterToday(later.size, later.last().start))
        assertFalse(HomeRules.todayDone(nearest, today))
    }

    @Test
    fun ongoingPtStaysUntilItsEnd() {
        // Шаг 3.9т: 15:48 — ПТ 15:00 идёт, на «Главной» она и её план, а не мини-группа 16:00.
        val now = at(15, 48)
        val nearest = HomeRules.nearest(day, now)
        assertEquals("pt15", nearest?.id)
        assertEquals("Сейчас · 15:00–15:50", HomeFormat.nearest(nearest!!.start, nearest.end, now))
        assertEquals(s("до конца 2_мин"), HomeFormat.startsIn(now, nearest.start, nearest.end))
        assertEquals(listOf("mini16", "pt17", "group18", "spt19"), HomeRules.laterToday(day, nearest, today).map { it.id })
        // Закончилась (отметится сама) — ближайшая мини-группа 16:00 через 10 минут.
        assertEquals("mini16", HomeRules.nearest(day, at(15, 50))?.id)
        assertEquals(s("через 10_мин"), HomeFormat.startsIn(at(15, 50), at(16), at(16, 50)))
        // «Проведено» нажали раньше конца — тоже следующая.
        val done = day.map { if (it.id == "pt15") it.copy(marked = true) else it }
        assertEquals("mini16", HomeRules.nearest(done, now)?.id)
    }

    @Test
    fun ongoingGroupStaysUntilMarked() {
        val now = at(16, 10)
        assertEquals("mini16", HomeRules.nearest(day, now)?.id)
        assertEquals(s("до конца 40_мин"), HomeFormat.startsIn(now, at(16), at(16, 50)))
        // Отметили хоть одного — группа уходит, ближайшая — ПТ 17:00.
        val marked = day.map { if (it.id == "mini16") it.copy(marked = true) else it }
        assertEquals("pt17", HomeRules.nearest(marked, now)?.id)
    }

    @Test
    fun cancelledAndDoneEarlyAreSkipped() {
        // ПТ 17:00 отменили, а «Проведено» у группы 18:00 нажали заранее — ближайшая СПТ 19:00, но «ещё сегодня» её нет.
        val list = day.map {
            when (it.id) {
                "pt17" -> it.copy(cancelled = true)
                "group18" -> it.copy(marked = true)
                "mini16" -> it.copy(marked = true)
                else -> it
            }
        }
        val nearest = HomeRules.nearest(list, at(16, 55))
        assertEquals("spt19", nearest?.id)
        assertEquals(emptyList(), HomeRules.laterToday(list, nearest!!, today))
        assertEquals(s("Ещё сегодня: 1, в_19:00"), HomeFormat.laterToday(1, at(19)))
    }

    @Test
    fun eveningTodayDoneAndTomorrowFirst() {
        // Макет «Вечер»: сегодня всё, завтра первая в 07:00 — «ср 21.10 · Ковалёва Н. · ПТ · ББ».
        val tomorrow = LocalDate(2026, 10, 21)
        val list = day + slot("wed7", 7, day = tomorrow) + slot("wed9", 9, day = tomorrow)
        val nearest = HomeRules.nearest(list, at(20, 5))
        assertEquals("wed7", nearest?.id)
        assertTrue(HomeRules.todayDone(nearest, today))
        assertEquals(s("Сегодня всё. Завтра первая в_07:00"), HomeFormat.todayDone(nearest!!.start, today))
        assertEquals(
            s("ср_21.10 · Ковалёва_Н. · ПТ · ББ"),
            HomeFormat.nextLine(nearest.start, s("Ковалёва_Н."), "ПТ", "ББ"),
        )
        assertEquals(s("Завтра: 2_тренировки, первая в_07:00"), HomeFormat.tomorrow(2, HomeRules.tomorrow(list, today).first().start))
        assertEquals("Завтра тренировок нет", HomeFormat.tomorrow(0, null))
    }

    @Test
    fun nextNotTomorrowOrNothingAhead() {
        val friday = LocalDate(2026, 10, 23)
        assertEquals(s("Сегодня всё. Следующая — пт_23.10 в_07:00"), HomeFormat.todayDone(at(7, day = friday), today))
        assertNull(HomeRules.nearest(emptyList(), at(9)))
        assertTrue(HomeRules.todayDone(null, today))
        assertEquals("Сегодня всё. Впереди тренировок нет", HomeFormat.todayDone(null, today))
    }

    @Test
    fun daySchedulePastFoldedAndOngoing() {
        val now = at(15, 48)
        val list = HomeRules.day(day + slot("wed7", 7, day = LocalDate(2026, 10, 21)), today)
        assertEquals(12, list.size)
        val past = list.filter { HomeRules.isPast(it, now) }
        assertEquals(7, past.size)
        assertEquals("Показать прошедшие · 7", HomeFormat.showPast(past.size))
        assertTrue(HomeRules.isOngoing(list.first { it.id == "pt15" }, now))
        assertFalse(HomeRules.isOngoing(list.first { it.id == "mini16" }, now))
        assertEquals("ПТ · ДБ", HomeFormat.dayLine("ПТ", "ДБ"))
        assertEquals(s("Мини-группа · ДБ · через 12_мин"), HomeFormat.dayLine("Мини-группа", "ДБ", HomeFormat.startsIn(now, at(16), at(16, 50))))
    }

    @Test
    fun groupWaitingLine() {
        assertEquals("ждём 2 из 3", HomeFormat.waiting(2, 3))
        assertEquals("ждём 12", HomeFormat.waiting(12, 12))
        assertNull(HomeFormat.waiting(0, 3))
    }

    @Test
    fun unmarkedGroupsAfterEndUntilMarked() {
        // Макет «Вечер»: группа 18:00 закончилась и не отмечена — «18:00 · Вт, Чт 18:00 ДБ (10)».
        val now = at(20, 5)
        val groups = HomeRules.unmarkedGroups(day, now)
        assertEquals(listOf("mini16", "group18"), groups.map { it.id })
        assertEquals(emptyList(), HomeRules.unmarkedGroups(day, at(18, 30)).filter { it.id == "group18" })
        assertEquals("Группа без отметки", HomeFormat.unmarkedGroups(1))
        assertEquals("Группы без отметки", HomeFormat.unmarkedGroups(2))
        assertEquals("18:00 · Вт, Чт 18:00 ДБ (10)", HomeFormat.unmarkedGroupsNote(listOf(at(18) to "Вт, Чт 18:00 ДБ (10)"), today))
        val yesterday = LocalDate(2026, 10, 19)
        assertEquals(
            s("пн_19.10 18:00, 16:00 и ещё 1"),
            HomeFormat.unmarkedGroupsNote(listOf(at(18, day = yesterday) to "a", at(16) to "b", at(18) to "c"), today),
        )
        // Отменённая и отмеченная — не пункт.
        val list = listOf(slot("c", 10, group = true, cancelled = true), slot("m", 11, group = true, marked = true), slot("pt", 12))
        assertEquals(emptyList(), HomeRules.unmarkedGroups(list, now))
    }

    @Test
    fun backupItemAppearsAndGoesAway() {
        val now = at(15, 48)
        assertEquals(BackupTodo.NO_FOLDER, HomeRules.backup(folderChosen = false, last = at(9), now = now))
        assertEquals("Выберите папку для копии", HomeFormat.backup(BackupTodo.NO_FOLDER))
        assertEquals("Пока данные только в этом телефоне", HomeFormat.backupNote(BackupTodo.NO_FOLDER, null))
        // Папка есть, копия вчера — пункта нет; через 7 дней после копии — «Нет копии больше 7 дней».
        assertNull(HomeRules.backup(folderChosen = true, last = at(9, day = LocalDate(2026, 10, 19)), now = now))
        val old = at(15, 48, LocalDate(2026, 10, 13))
        assertEquals(BackupTodo.STALE, HomeRules.backup(true, old, now))
        assertEquals("последняя — 13.10.2026", HomeFormat.backupNote(BackupTodo.STALE, old))
        assertEquals(BackupTodo.STALE, HomeRules.backup(true, null, now))
        assertEquals("Копий ещё не было", HomeFormat.backupNote(BackupTodo.STALE, null))
    }

    @Test
    fun todoOrderFollowsTz() {
        assertTrue(TodoKind.UNMARKED_GROUPS < TodoKind.NO_DIARY)
        assertTrue(TodoKind.NOT_BOOKED < TodoKind.BACKUP)
        assertTrue(TodoKind.BACKUP < TodoKind.CONSENT_REVOKED)
    }
}
