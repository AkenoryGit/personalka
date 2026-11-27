package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.competition.CollectItem
import com.akenory.personalka.domain.competition.Collected
import com.akenory.personalka.domain.competition.Competition
import com.akenory.personalka.domain.competition.CompetitionRules
import com.akenory.personalka.domain.competition.CompetitionStart
import com.akenory.personalka.domain.competition.EntryRule
import com.akenory.personalka.domain.competition.EntryTime
import com.akenory.personalka.domain.competition.Participant
import com.akenory.personalka.domain.competition.StartResult
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.result.ResultItem
import com.akenory.personalka.domain.result.SwimEvent
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Строки соревнований (шаг 5.38, ТЗ 5.21): примеры — тексты «Поделиться» из ТЗ. Имена условные. */
class CompetitionFormatTest {
    private fun s(text: String) = text.replace('_', RuText.NBSP).replace('^', RuText.THIN)

    private val fee = CollectItem("fee", "Взнос", 150_000)
    private val paper = CollectItem("paper", "Справка")
    private val cup = Competition(
        "c", "Кубок города", LocalDate(2026, 12, 12), 50, "Бассейн «Олимп»",
        listOf(EntryRule(Sex.MALE, 2010, 2015), EntryRule(Sex.FEMALE, 2008, 2013)), listOf(fee, paper),
    )
    private val today = LocalDate(2026, 11, 27)
    private val free50 = SwimEvent(SwimStyle.FREESTYLE, 50, 50)
    private val back100 = SwimEvent(SwimStyle.BACKSTROKE, 100, 50)

    private fun p(id: String, last: String, first: String, year: Int?, vararg handed: String, starts: List<CompetitionStart> = emptyList()) =
        Participant("e$id", "c$id", ClientName(last, first), year, handed.toSet(), starts)

    private val lev = p("1", "Петров", "Лев", 2014, "paper", starts = listOf(CompetitionStart("s1", free50, 3640), CompetitionStart("s2", back100, 8490)))
    private val mira = p("2", "Соколова", "Мира", 2012, "fee", "paper", starts = listOf(CompetitionStart("s3", free50)))
    private val eva = p("3", "Орлова", "Ева", 2012, starts = listOf(CompetitionStart("s4", back100)))
    private val sofia = p("4", "Белова", "Софья", 2009, "fee", "paper")

    @Test
    fun cardAndListLines() {
        assertEquals(s("сб_12.12.2026 · бассейн 50_м · Бассейн «Олимп»"), CompetitionFormat.header(cup))
        assertEquals(s("сб_12.12 · 4_участника · не всё сдали 2"), CompetitionFormat.listLine(cup, listOf(lev, mira, eva, sofia), today))
        assertEquals(s("сб_12.12 · 1_участник · всё сдали"), CompetitionFormat.listLine(cup, listOf(mira), today))
        assertEquals(s("сб_12.12 · участников пока нет"), CompetitionFormat.listLine(cup, emptyList(), today))
        assertEquals(s("сб_12.12.2026 · 5_участников"), CompetitionFormat.listLine(cup.copy(items = emptyList()), List(5) { mira }, LocalDate(2025, 12, 1)))
        assertEquals("Мальчики 2010–2015 · Девочки 2008–2013", CompetitionFormat.rules(cup.rules))
        assertEquals("Все 2012", CompetitionFormat.rule(EntryRule(null, 2012, 2012)))
        assertEquals("Взнос · не сдали 4", CompetitionFormat.missing(fee, 4))
        assertEquals("Справка · все сдали", CompetitionFormat.missing(paper, 0))
        assertEquals(s("Собрано 9^000 из 12^000_₽"), CompetitionFormat.collected(Collected(900_000, 1_200_000)))
        assertEquals("Петров Лев, 2014", CompetitionFormat.person(lev.name, 2014))
        assertEquals(s("50_м вольный 36,40 · 100_м на спине 1:24,90"), CompetitionFormat.starts(listOf(free50 to 3640, back100 to 8490)))
        assertEquals("Дистанция не выбрана", CompetitionFormat.starts(emptyList()))
        assertEquals(s("Добавлено 3_участника"), CompetitionFormat.added(3))
        assertEquals(s("Добавлен 1_участник"), CompetitionFormat.added(1))
        assertEquals("Добавить 2", CompetitionFormat.addButton(2))
        assertEquals("Мальчики 2010–2015 · 7", CompetitionFormat.section(cup.rules[0], 7, search = false))
        assertEquals("Не указан год или пол · 3", CompetitionFormat.unknown(3))
    }

    @Test
    fun entryHint() {
        val best = ResultItem("r", LocalDate(2026, 10, 10), free50, 3812, ResultKind.TRAINING)
        assertEquals("Лучшее: 38,12 · 10.10.2026", CompetitionFormat.entryHint(EntryTime(3812, false, best), 50))
        assertEquals(
            s("В бассейне 50_м результатов на этой дистанции нет — введите время вручную"),
            CompetitionFormat.entryHint(EntryTime(null, false, null), 50),
        )
    }

    @Test
    fun entryText() {
        val all = listOf(lev, mira, eva, sofia)
        val results = mapOf("c2" to listOf(ResultItem("r", LocalDate(2026, 10, 10), free50, 3812, ResultKind.TRAINING)))
        val text = CompetitionFormat.entryText(cup, CompetitionRules.entryList(all, { results[it].orEmpty() }, cup.date), CompetitionRules.withoutStarts(all))
        assertEquals(
            s(
                """
                Кубок города · сб_12.12.2026 · бассейн 50_м

                50_м вольный стиль
                Петров Лев, 2014 — 36,40
                Соколова Мира, 2012 — 38,12

                100_м на спине
                Петров Лев, 2014 — 1:24,90
                Орлова Ева, 2012 — без времени

                Без дистанции: Белова Софья, 2009
                """.trimIndent(),
            ),
            text,
        )
        assertEquals(s("Кубок города · сб_12.12.2026 · бассейн 50_м\n\nУчастников пока нет"), CompetitionFormat.entryText(cup, emptyList(), emptyList()))
    }

    @Test
    fun debtText() {
        assertEquals(
            s(
                """
                Кубок города · сб_12.12.2026

                Не сдали:
                Взнос 1^500_₽ — Орлова Ева, Петров Лев
                Справка — Орлова Ева
                """.trimIndent(),
            ),
            CompetitionFormat.debtText(cup, listOf(lev, mira, eva, sofia)),
        )
        assertEquals(s("Кубок города · сб_12.12.2026\n\nВсе всё сдали"), CompetitionFormat.debtText(cup, listOf(mira, sofia)))
    }

    @Test
    fun resultsText() {
        val rows = listOf(
            lev to listOf(StartResult(free50, 3610, 30, 2), StartResult(back100, null, null, null)),
            mira to listOf(StartResult(free50, 3900, null, null)),
            sofia to emptyList(),
        )
        assertEquals(
            s(
                """
                Итоги · Кубок города · 12.12.2026

                Петров Лев
                50_м вольный стиль — 36,10, личный рекорд −_0,30, 2 место
                100_м на спине — результата нет

                Соколова Мира
                50_м вольный стиль — 39,00
                """.trimIndent(),
            ),
            CompetitionFormat.resultsText(cup, rows),
        )
        assertEquals("Итоги · Кубок города · 12.12.2026\n\nРезультатов пока нет", CompetitionFormat.resultsText(cup, listOf(sofia to emptyList())))
    }
}
