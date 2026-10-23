package com.akenory.personalka.domain.home

import com.akenory.personalka.domain.format.HomeFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.ParticipantState
import com.akenory.personalka.domain.training.StatusSettings
import com.akenory.personalka.domain.training.TrainingState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Главная» без денег (шаг 1.16, ТЗ 5.2): ближайшая и «Нужно сделать». «Сейчас» — вт 20.10.2026 15:48, как на макетах 0.6. */
class HomeRulesTest {
    private val tuesday = LocalDate(2026, 10, 20)
    private val now = LocalDateTime(tuesday, LocalTime(15, 48))
    private val settings = StatusSettings()

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime(date, LocalTime(h, m))

    private fun t(
        id: String,
        start: LocalDateTime,
        kind: TrainingKind = TrainingKind.PERSONAL,
        status: TrainingStatus = TrainingStatus.PLANNED,
        people: List<ParticipationStatus> = listOf(ParticipationStatus.EXPECTED),
        minutes: Int = 50,
    ) = HomeTraining(
        id = id,
        state = TrainingState(kind, start, minutes, status, participants = people.mapIndexed { i, p -> ParticipantState("c$i", p) }),
        title = id,
        typeShort = "ПТ",
        placeShort = "ББ",
    )

    @Test
    fun nextSkipsGoingPersonalButShowsGoingGroup() {
        val day = listOf(
            t("Соколова В.", at(tuesday, 15, 0)),
            t("Ильин / Лаптев / Носова", at(tuesday, 16, 0), TrainingKind.GROUP),
            t("Орлов М.", at(tuesday, 17, 0)),
            t("Отменённая", at(tuesday, 17, 30), status = TrainingStatus.CANCELLED),
            t("Вт, Чт 18:00 ДБ (10)", at(tuesday, 18, 0), TrainingKind.GROUP),
            t("Дежурство", at(tuesday, 19, 30), TrainingKind.DUTY),
            t("Новикова П.", at(tuesday, 19, 0)),
            t("Ковалёва Н.", at(LocalDate(2026, 10, 21), 7, 0)),
        )
        // 15:48: ПТ 15:00 идёт — её нет; ближайшая — мини-группа 16:00, ещё сегодня 3, последняя в 19:00.
        val up = HomeRules.upcoming(day, now)
        assertEquals("Ильин / Лаптев / Носова", up.next?.id)
        assertEquals(3, up.moreToday)
        assertEquals(at(tuesday, 19, 0), up.lastToday)
        assertNull(up.later)
        assertEquals(s("через 12_мин"), HomeFormat.startsIn(up.next!!.start, now))
        assertEquals("Ещё сегодня: 3, последняя в 19:00", HomeFormat.moreToday(up.moreToday, up.lastToday!!))
        assertEquals("Ближайшая · 16:00–16:50", HomeFormat.nextHeader(up.next!!))
        // Группа ещё идёт в 16:20 — она по-прежнему ближайшая: детей отмечают, пока она идёт.
        assertEquals("Ильин / Лаптев / Носова", HomeRules.upcoming(day, at(tuesday, 16, 20)).next?.id)
        assertEquals(HomeFormat.ONGOING, HomeFormat.startsIn(at(tuesday, 16, 0), at(tuesday, 16, 20)))

        // 19:00 последняя — под ней первая завтрашняя.
        val evening = HomeRules.upcoming(day, at(tuesday, 18, 55))
        assertEquals("Новикова П.", evening.next?.id)
        assertEquals(0, evening.moreToday)
        assertEquals("Ковалёва Н.", evening.later?.id)
        assertEquals("Завтра первая в 07:00", HomeFormat.later(evening.later!!, tuesday))

        // 20:00: сегодня всё.
        val late = HomeRules.upcoming(day, at(tuesday, 20, 0))
        assertNull(late.next)
        assertEquals("Сегодня всё. Завтра первая в 07:00", HomeFormat.todayDone(late.later, tuesday))
        assertEquals(s("ср_21.10 · Ковалёва Н. · ПТ · ББ"), HomeFormat.laterRow(late.later!!))
    }

    @Test
    fun todayDoneWithEmptyTomorrowAndNothingAhead() {
        val thursday = t("Орлов М.", at(LocalDate(2026, 10, 22), 9, 0))
        val up = HomeRules.upcoming(listOf(thursday), now)
        assertNull(up.next)
        assertEquals(s("Сегодня всё. Следующая — чт_22.10 в 09:00"), HomeFormat.todayDone(up.later, tuesday))
        assertEquals(HomeFormat.NOTHING_AHEAD, HomeFormat.todayDone(HomeRules.upcoming(emptyList(), now).later, tuesday))
    }

    @Test
    fun markButtonFromFifteenMinutesBefore() {
        val pt = t("Орлов М.", at(tuesday, 16, 0))
        assertFalse(HomeRules.canMark(pt, at(tuesday, 15, 44)))
        assertTrue(HomeRules.canMark(pt, at(tuesday, 15, 45)))
        assertFalse(HomeRules.canMark(pt.copy(state = pt.state.copy(status = TrainingStatus.DONE)), at(tuesday, 15, 50)))
    }

    @Test
    fun unmarkedOnlyWhatDoesNotCloseByItself() {
        val list = listOf(
            // Группа, где никто не отмечен, висит до отметки (4.1).
            t("Вт, Чт 12:00 ДБ (10)", at(tuesday, 12, 0), TrainingKind.GROUP, people = List(3) { ParticipationStatus.EXPECTED }),
            // Отмечен хоть один — закроется сама.
            t("Отмечен один", at(tuesday, 13, 0), TrainingKind.GROUP, people = listOf(ParticipationStatus.ATTENDED, ParticipationStatus.EXPECTED)),
            // ПТ закрывается сама, пока «закрывать само» включено; дежурство — всегда.
            t("Орлов М.", at(tuesday, 9, 0)),
            t("Дежурство", at(tuesday, 7, 0), TrainingKind.DUTY),
            // Вчерашняя группа без отметки тоже здесь, первой.
            t("Пн 18:00 ББ (8)", at(LocalDate(2026, 10, 19), 18, 0), TrainingKind.GROUP),
            // Ещё идёт — не в списке.
            t("Идёт", at(tuesday, 15, 30), TrainingKind.GROUP),
        )
        assertEquals(listOf("Пн 18:00 ББ (8)", "Вт, Чт 12:00 ДБ (10)"), HomeRules.unmarked(list, now, settings).map { it.id })
        assertEquals(
            listOf("Пн 18:00 ББ (8)", "Орлов М.", "Вт, Чт 12:00 ДБ (10)"),
            HomeRules.unmarked(list, now, settings.copy(autoDonePersonal = false)).map { it.id },
        )

        val todo = HomeRules.todo(list, now, settings, backup = null).single()
        assertEquals(TodoKind.UNMARKED, todo.kind)
        assertEquals("Группы без отметки", todo.title)
        assertEquals(s("пн_19.10 18:00 · Пн 18:00 ББ (8), 12:00 · Вт, Чт 12:00 ДБ (10)"), todo.note)
        assertEquals(2, todo.count)
        assertEquals("Пн 18:00 ББ (8)", todo.trainingId)
        assertEquals("Без отметки", HomeRules.todo(list, now, settings.copy(autoDonePersonal = false), null).single().title)
    }

    @Test
    fun backupItemAppearsAndGoesAway() {
        // Папка не выбрана — «Выберите папку для копии».
        val folder = HomeRules.todo(emptyList(), now, settings, BackupFacts(folderChosen = false, last = null)).single()
        assertEquals(TodoKind.BACKUP_FOLDER, folder.kind)
        assertEquals(HomeFormat.BACKUP_FOLDER_NOTE, folder.note)
        assertNull(folder.count)
        // Папка есть, копия 6 дней назад — пункта нет; 7 дней — снова есть.
        assertTrue(HomeRules.todo(emptyList(), now, settings, BackupFacts(true, at(LocalDate(2026, 10, 14), 15, 49))).isEmpty())
        val old = HomeRules.todo(emptyList(), now, settings, BackupFacts(true, at(LocalDate(2026, 10, 13), 15, 48))).single()
        assertEquals(TodoKind.BACKUP_OLD, old.kind)
        assertEquals("Последняя — 13.10.2026", old.note)
        // Копия только что — пункт исчез.
        assertTrue(HomeRules.todo(emptyList(), now, settings, BackupFacts(true, now)).isEmpty())
    }

    @Test
    fun orderAndAndMore() {
        val groups = (1..4).map { t("Группа $it", at(tuesday, 8 + it, 0), TrainingKind.GROUP) }
        val todo = HomeRules.todo(groups, now, settings, BackupFacts(false, null))
        assertEquals(listOf(TodoKind.UNMARKED, TodoKind.BACKUP_FOLDER), todo.map { it.kind })
        assertEquals("09:00 · Группа 1, 10:00 · Группа 2 и ещё 2", todo.first().note)
    }
}
