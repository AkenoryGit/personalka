package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.calendar.DaySpan
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Форма тренировки (ТЗ 5.5, макет «Новая тренировка»): «сейчас» — вт 20.10.2026 15:40. */
class TrainingFormTest {
    private val now = LocalDateTime(2026, 10, 20, 15, 40)
    private val today = now.date
    private val pt = FormType("pt", "ПТ", TrainingKind.PERSONAL, 1, 50, 1)
    private val split = FormType("split", "Сплит", TrainingKind.SPLIT, 2, 50, 2)
    private val mini = FormType("mini", "Мини-группа", TrainingKind.GROUP, 3, 50, null)
    private val group = FormType("group", "Группа", TrainingKind.GROUP, 4, 60, null)
    private val spt = FormType("spt", "СПТ", TrainingKind.SPT, 3, 50, 1)
    private val baby = FormType("baby", "Грудничковое", TrainingKind.PERSONAL, 2, 30, 1)
    private val three = FormType("three", "Тройка", TrainingKind.OTHER, 2, 50, 3)
    private val open = LocalTime(7, 0)
    private val close = LocalTime(23, 0)

    private fun at(time: String, date: LocalDate = today): LocalDateTime =
        time.split(':').map { it.toInt() }.let { (h, m) -> LocalDateTime(date, LocalTime(h, m)) }

    private fun span(from: String, to: String): DaySpan {
        fun minute(text: String) = text.split(':').map { it.toInt() }.let { (h, m) -> h * 60 + m }
        return DaySpan("$from-$to", minute(from), minute(to))
    }

    @Test
    fun lastUsedTypeFirst() {
        val types = listOf(pt, split, mini, group, spt, baby)
        assertEquals(listOf(spt, pt, split, mini, group, baby), TrainingFormRules.typeOrder(types, "spt"))
        assertEquals(types, TrainingFormRules.typeOrder(types, null))
        // Тип ушёл в архив — порядок как в настройках.
        assertEquals(types, TrainingFormRules.typeOrder(types, "gone"))
    }

    @Test
    fun clientsByKind() {
        assertEquals(1, TrainingFormRules.clientLimit(pt))
        assertEquals(1, TrainingFormRules.clientLimit(spt))
        assertEquals(2, TrainingFormRules.clientLimit(split))
        assertEquals(null, TrainingFormRules.clientLimit(mini))
        assertEquals(3, TrainingFormRules.clientLimit(three))

        // ПТ: новый заменяет прежнего, повторное нажатие снимает.
        assertEquals(listOf("b"), TrainingFormRules.toggleClient(listOf("a"), "b", 1))
        assertEquals(emptyList(), TrainingFormRules.toggleClient(listOf("a"), "a", 1))
        // Сплит: двое; третий встаёт вторым, первый остаётся.
        assertEquals(listOf("a", "b"), TrainingFormRules.toggleClient(listOf("a"), "b", 2))
        assertEquals(listOf("a", "c"), TrainingFormRules.toggleClient(listOf("a", "b"), "c", 2))
        assertEquals(listOf("b"), TrainingFormRules.toggleClient(listOf("a", "b"), "a", 2))
        // Группа — сколько угодно.
        assertEquals(listOf("a", "b", "c"), TrainingFormRules.toggleClient(listOf("a", "b"), "c", null))
    }

    @Test
    fun typeChangeKeepsWhatFits() {
        val draft = TrainingDraft(at("17:15"), typeId = split.id, clientIds = listOf("a", "b"), durationMin = 50)
        // Сплит → ПТ: остаётся первый клиент; длительность из типа.
        val toBaby = TrainingFormRules.withType(draft, baby, keepDuration = false)
        assertEquals(listOf("a"), toBaby.clientIds)
        assertEquals(30, toBaby.durationMin)
        // Длительность, которую тренер выбрал сам, не меняется.
        assertEquals(45, TrainingFormRules.withType(draft.copy(durationMin = 45), group, keepDuration = true).durationMin)
        // Группа остаётся только у группового типа.
        val withGroup = draft.copy(typeId = group.id, clientIds = emptyList(), groupId = "g")
        assertEquals("g", TrainingFormRules.withType(withGroup, mini, keepDuration = false).groupId)
        assertEquals(null, TrainingFormRules.withType(withGroup, pt, keepDuration = false).groupId)
        assertEquals(60, TrainingFormRules.withType(draft, group, keepDuration = false).durationMin)
    }

    @Test
    fun nearestFreeHourLikeMockup() {
        // Макет «Новая тренировка»: в 15:40 с 16:00 до 19:50 всё занято — 20:00–20:50.
        val busy = listOf(span("16:00", "16:50"), span("16:30", "17:20"), span("17:15", "18:05"), span("18:00", "18:50"), span("19:00", "19:50"))
        assertEquals(at("20:00"), TrainingFormRules.nearestFreeHour(now, 50, open, close) { if (it == today) busy else emptyList() })
        // Свободно — следующий целый час.
        assertEquals(at("16:00"), TrainingFormRules.nearestFreeHour(now, 50, open, close) { emptyList() })
        // Ровно в 16:00 — 16:00; в 16:00:30 — уже 17:00.
        assertEquals(at("16:00"), TrainingFormRules.nearestFreeHour(at("16:00"), 50, open, close) { emptyList() })
        assertEquals(at("17:00"), TrainingFormRules.nearestFreeHour(LocalDateTime(2026, 10, 20, 16, 0, 30), 50, open, close) { emptyList() })
        // Конец тренировки касается начала другой — не мешает: 16:00–16:50, а следующая с 16:50.
        assertEquals(at("16:00"), TrainingFormRules.nearestFreeHour(now, 50, open, close) { listOf(span("16:50", "17:40")) })
        // До начала работы — первый час работы.
        assertEquals(at("07:00"), TrainingFormRules.nearestFreeHour(at("05:10"), 50, open, close) { emptyList() })
    }

    @Test
    fun nearestFreeHourTomorrow() {
        val tomorrow = LocalDate(2026, 10, 21)
        // В 22:30 сегодня до 23:00 не поместиться — завтра с 07:00; занято — 08:00.
        assertEquals(at("07:00", tomorrow), TrainingFormRules.nearestFreeHour(at("22:30"), 50, open, close) { emptyList() })
        assertEquals(at("08:00", tomorrow), TrainingFormRules.nearestFreeHour(at("22:30"), 50, open, close) { listOf(span("07:00", "07:50")) })
        // Завтра всё занято — начало работы.
        assertEquals(at("07:00", tomorrow), TrainingFormRules.nearestFreeHour(at("22:30"), 50, open, close) { listOf(span("00:00", "24:00")) })
        // Конец работы 00:00 — это 24:00: в 22:30 тренировка 23:00–23:50 помещается.
        assertEquals(at("23:00"), TrainingFormRules.nearestFreeHour(at("22:30"), 50, open, LocalTime(0, 0)) { emptyList() })
    }

    @Test
    fun requiredFields() {
        val empty = TrainingDraft(at("17:15"))
        assertEquals(setOf(FormField.TYPE, FormField.PLACE), TrainingFormRules.problems(empty, null, now).keys)

        val pt = TrainingDraft(at("17:15"), typeId = pt.id, locationId = "bb")
        assertEquals(mapOf(FormField.CLIENTS to "Выберите клиента"), TrainingFormRules.problems(pt, this.pt, now))
        assertEquals(emptyMap(), TrainingFormRules.problems(pt.copy(clientIds = listOf("a")), this.pt, now))

        val split = pt.copy(typeId = split.id)
        assertEquals("Выберите одного или двух клиентов", TrainingFormRules.problems(split, this.split, now)[FormField.CLIENTS])
        assertEquals(emptyMap(), TrainingFormRules.problems(split.copy(clientIds = listOf("a")), this.split, now))

        // Группа — группа или разовый список.
        val group = pt.copy(typeId = group.id)
        assertEquals("Выберите группу или детей", TrainingFormRules.problems(group, this.group, now)[FormField.CLIENTS])
        assertEquals(emptyMap(), TrainingFormRules.problems(group.copy(groupId = "g"), this.group, now))
        assertEquals(emptyMap(), TrainingFormRules.problems(group.copy(clientIds = listOf("a", "b")), this.group, now))

        assertEquals("Не больше 3", TrainingFormRules.problems(pt.copy(typeId = three.id, clientIds = listOf("a", "b", "c", "d")), three, now)[FormField.CLIENTS])
    }

    @Test
    fun timeDurationAndRepeat() {
        val ok = TrainingDraft(at("17:15"), typeId = pt.id, clientIds = listOf("a"), locationId = "bb")
        assertEquals("От 5 до 600 минут", TrainingFormRules.problems(ok.copy(durationMin = 4), pt, now)[FormField.DURATION])
        assertEquals("От 5 до 600 минут", TrainingFormRules.problems(ok.copy(durationMin = 601), pt, now)[FormField.DURATION])
        // Конец не позже 24:00: 23:10 на 50 минут — ровно полночь, можно; 23:15 — нельзя.
        assertEquals(emptyMap(), TrainingFormRules.problems(ok.copy(start = at("23:10")), pt, now))
        assertEquals("Тренировка должна закончиться до 24:00", TrainingFormRules.problems(ok.copy(start = at("23:15")), pt, now)[FormField.TIME])

        val weekly = ok.copy(repeat = Repeat(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)))
        assertEquals(emptyMap(), TrainingFormRules.problems(weekly, pt, now))
        assertEquals("Выберите дни недели", TrainingFormRules.problems(ok.copy(repeat = Repeat(emptySet())), pt, now)[FormField.WEEKDAYS])
        assertEquals(
            "Не раньше даты тренировки",
            TrainingFormRules.problems(weekly.copy(repeat = Repeat(setOf(DayOfWeek.TUESDAY), LocalDate(2026, 10, 19))), pt, now)[FormField.UNTIL],
        )
        // Последний день — сам день тренировки: одна тренировка, можно.
        assertEquals(emptyMap(), TrainingFormRules.problems(weekly.copy(repeat = Repeat(setOf(DayOfWeek.TUESDAY), today)), pt, now))
    }

    @Test
    fun markedTrainingStaysInPast() {
        // Проведённую — как при переносе: задним числом можно, в будущее (позже «сейчас» + 15 минут) — нет.
        val done = TrainingDraft(at("12:00"), typeId = pt.id, clientIds = listOf("a"), locationId = "bb")
        assertEquals(emptyMap(), TrainingFormRules.problems(done.copy(start = at("10:00")), pt, now, TrainingStatus.DONE, marked = true))
        assertEquals(emptyMap(), TrainingFormRules.problems(done.copy(start = at("15:55")), pt, now, TrainingStatus.DONE, marked = true))
        val future = TrainingFormRules.problems(done.copy(start = at("16:00")), pt, now, TrainingStatus.DONE, marked = true)
        assertEquals("Проведённую тренировку нельзя перенести в будущее", future[FormField.TIME])
        assertTrue(TrainingFormRules.problems(done.copy(start = at("16:00")), pt, now).isEmpty())
    }

    @Test
    fun dutyKeepsEndWhenStartMoves() {
        val duty = FormType("duty", "Дежурство", TrainingKind.DUTY, 1, 240, 0)
        // Дежурство на 4 часа — ББ 11:00–15:00; позже 20:00 — до полуночи.
        val draft = TrainingFormRules.newDuty(at("11:00"), duty, "bb")
        assertEquals(240, draft.durationMin)
        assertEquals(LocalTime(15, 0), TrainingFormRules.endTime(draft))
        assertEquals(180, TrainingFormRules.newDuty(at("21:00"), duty, "bb").durationMin)
        assertEquals(LocalTime(0, 0), TrainingFormRules.endTime(TrainingFormRules.newDuty(at("21:00"), duty, "bb")))

        // Начало сдвинули на 12:00 — конец остался 15:00; конец «до 00:00» — до полуночи.
        val later = TrainingFormRules.withDutyStart(draft, at("12:00"))
        assertEquals(180, later.durationMin)
        assertEquals(LocalTime(15, 0), TrainingFormRules.endTime(later))
        assertEquals(720, TrainingFormRules.withDutyEnd(later, LocalTime(0, 0)).durationMin)
    }

    @Test
    fun dutyCoverFor() {
        val draft = TrainingDraft(start = at("11:00"), typeId = "duty", locationId = "bb", durationMin = 240)
        assertNull(TrainingFormRules.coverFor(draft), "Своё дежурство — без пометки")
        assertNull(TrainingFormRules.coverFor(draft.copy(coverFor = "   ")))
        assertEquals("Александра", TrainingFormRules.coverFor(draft.copy(coverFor = "  Александра ")))
        // «за» в начале пишут строки календаря — второй раз его не будет.
        assertEquals("Дмитрия Мальцева", TrainingFormRules.coverFor(draft.copy(coverFor = "За Дмитрия Мальцева")))
        assertEquals("Зарину", TrainingFormRules.coverFor(draft.copy(coverFor = "Зарину")))
        assertEquals(TrainingFormRules.COVER_FOR_MAX, TrainingFormRules.coverFor(draft.copy(coverFor = "А".repeat(60)))?.length)
        // У повторяющегося дежурства пометки нет.
        assertNull(TrainingFormRules.coverFor(draft.copy(coverFor = "Александра", repeat = Repeat(setOf(DayOfWeek.TUESDAY)))))
    }

    @Test
    fun dutyProblems() {
        val draft = TrainingDraft(start = at("11:00"), typeId = "duty", locationId = "bb", durationMin = 240)
        assertEquals(emptyMap(), TrainingFormRules.dutyProblems(draft))
        assertEquals(setOf(FormField.PLACE), TrainingFormRules.dutyProblems(draft.copy(locationId = null)).keys)
        assertEquals("Конец должен быть позже начала", TrainingFormRules.dutyProblems(TrainingFormRules.withDutyEnd(draft, LocalTime(10, 0)))[FormField.TIME])
        assertEquals("Не дольше 10 часов", TrainingFormRules.dutyProblems(draft.copy(durationMin = 660))[FormField.TIME])
        assertEquals(setOf(FormField.WEEKDAYS), TrainingFormRules.dutyProblems(draft.copy(repeat = Repeat(emptySet()))).keys)
    }
}
