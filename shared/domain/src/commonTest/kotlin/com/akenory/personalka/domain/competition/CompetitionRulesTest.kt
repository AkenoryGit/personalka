package com.akenory.personalka.domain.competition

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.result.ResultItem
import com.akenory.personalka.domain.result.ResultRules
import com.akenory.personalka.domain.result.SwimEvent
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Соревнования (шаг 5.38, ТЗ 5.21): примеры Дмитрия — мальчики 2010–2015, девочки 2008–2013. Имена условные. */
class CompetitionRulesTest {
    private val boys = EntryRule(Sex.MALE, 2010, 2015)
    private val girls = EntryRule(Sex.FEMALE, 2008, 2013)

    private fun kid(id: String, last: String, year: Int?, sex: Sex?, archived: Boolean = false) =
        Candidate(id, ClientName(last, "Имя$id"), year?.let { LocalDate(it, 5, 1) }, sex, archived)

    private val lev = kid("1", "Петров", 2014, Sex.MALE)
    private val mira = kid("2", "Соколова", 2012, Sex.FEMALE)
    private val ivan = kid("3", "Ковалёв", 2009, Sex.MALE) // старше мальчиков
    private val eva = kid("4", "Орлова", 2007, Sex.FEMALE) // старше девочек
    private val noYear = kid("5", "Белова", null, Sex.FEMALE)
    private val noSex = kid("6", "Ким", 2011, null) // по году подходит и мальчикам, и девочкам
    private val gone = kid("7", "Смирнов", 2013, Sex.MALE, archived = true)
    private val all = listOf(lev, mira, ivan, eva, noYear, noSex, gone)

    @Test
    fun fitByYearAndSex() {
        assertEquals(Fit.YES, CompetitionRules.fit(boys, LocalDate(2010, 1, 1), Sex.MALE))
        assertEquals(Fit.YES, CompetitionRules.fit(boys, LocalDate(2015, 12, 31), Sex.MALE))
        assertEquals(Fit.NO, CompetitionRules.fit(boys, LocalDate(2016, 1, 1), Sex.MALE))
        assertEquals(Fit.NO, CompetitionRules.fit(boys, LocalDate(2012, 1, 1), Sex.FEMALE))
        assertEquals(Fit.UNKNOWN, CompetitionRules.fit(boys, null, Sex.MALE))
        assertEquals(Fit.UNKNOWN, CompetitionRules.fit(boys, LocalDate(2012, 1, 1), null))
        // «Все» — пол не нужен.
        assertEquals(Fit.YES, CompetitionRules.fit(EntryRule(null, 2012, 2014), LocalDate(2012, 1, 1), null))
        // Год вне условия — «нет», даже без пола.
        assertEquals(Fit.NO, CompetitionRules.fit(boys, LocalDate(2005, 1, 1), null))
    }

    @Test
    fun pickSplitsByRulesAndKeepsUnknownApart() {
        val pick = CompetitionRules.pick(listOf(boys, girls), all, archived = false, query = "")
        assertEquals(listOf(boys, girls), pick.sections.map { it.rule })
        assertEquals(listOf("1"), pick.sections[0].people.map { it.id })
        assertEquals(listOf("2"), pick.sections[1].people.map { it.id })
        // Белова без года, Ким без пола — внизу; старшие и архивный — нигде.
        assertEquals(listOf("5", "6"), pick.unknown.map { it.id })
    }

    @Test
    fun pickWithArchivedSearchAndWithoutRules() {
        val archived = CompetitionRules.pick(listOf(boys, girls), all, archived = true, query = "")
        assertEquals(listOf("1", "7"), archived.sections[0].people.map { it.id })
        // Без условий — все (без архива) одним разделом, по фамилии.
        val plain = CompetitionRules.pick(emptyList(), all, archived = false, query = "")
        assertEquals(1, plain.sections.size)
        assertNull(plain.sections[0].rule)
        assertEquals(listOf("5", "6", "3", "4", "1", "2"), plain.sections[0].people.map { it.id })
        // Поиск — без условий: Ковалёв не подходит по году, но находится.
        val found = CompetitionRules.pick(listOf(boys), all, archived = false, query = "ковал")
        assertEquals(listOf("3"), found.sections.single().people.map { it.id })
        assertTrue(found.unknown.isEmpty())
    }

    @Test
    fun pickPutsPersonIntoFirstFittingRule() {
        val anyone = EntryRule(null, 2012, 2014)
        val pick = CompetitionRules.pick(listOf(boys, anyone), listOf(lev, mira, noSex), archived = false, query = "")
        assertEquals(listOf("1"), pick.sections[0].people.map { it.id })
        // Ким (2011) по «Все 2012–2014» не подходит, а мальчикам — без пола неизвестно.
        assertEquals(listOf("2"), pick.sections[1].people.map { it.id })
        assertEquals(listOf("6"), pick.unknown.map { it.id })
    }

    @Test
    fun storageRoundTrip() {
        val rules = listOf(boys, girls, EntryRule(null, 2012, 2014))
        assertEquals("M 2010 2015;F 2008 2013;* 2012 2014", CompetitionRules.encodeRules(rules))
        assertEquals(rules, CompetitionRules.decodeRules(CompetitionRules.encodeRules(rules)))
        assertEquals(emptyList(), CompetitionRules.decodeRules(""))
        val items = listOf(CollectItem("a", "Взнос", 150_000), CollectItem("b", "Справка\tмед"))
        val text = CompetitionRules.encodeItems(items)
        assertEquals("a\tВзнос\t150000\nb\tСправка мед", text)
        assertEquals(listOf(CollectItem("a", "Взнос", 150_000), CollectItem("b", "Справка мед")), CompetitionRules.decodeItems(text))
        assertEquals(emptyList(), CompetitionRules.decodeItems(""))
        assertEquals(setOf("a", "b"), CompetitionRules.decodeHanded(CompetitionRules.encodeHanded(setOf("b", "a"))))
        assertEquals("a b", CompetitionRules.encodeHanded(setOf("b", "a")))
    }

    @Test
    fun formChecks() {
        val ok = CompetitionRules.problems("Кубок", listOf(RuleInput(Sex.MALE, "2010", "2015")), listOf(ItemInput("a", "Взнос", "1500")))
        assertTrue(ok.isEmpty())
        val bad = CompetitionRules.problems(" ", listOf(RuleInput(Sex.MALE, "2015", "2010")), listOf(ItemInput("a", "", "1500")))
        assertEquals(setOf(CompetitionField.NAME, CompetitionField.RULES, CompetitionField.ITEMS), bad.keys)
        assertEquals(CompetitionRules.ITEM_BAD_AMOUNT, CompetitionRules.problems("К", emptyList(), listOf(ItemInput("a", "Взнос", "много")))[CompetitionField.ITEMS])
        assertNull(CompetitionRules.rule(RuleInput(null, "210", "2015")))
        // Пустая строка пункта просто отбрасывается.
        val draft = CompetitionRules.draft(
            " Кубок города ", LocalDate(2026, 12, 14), 50, " ",
            listOf(RuleInput(Sex.FEMALE, "2008", "2013")),
            listOf(ItemInput("a", "Взнос", "1 500"), ItemInput("b", "", ""), ItemInput("c", "Справка", "")),
        )!!
        assertEquals("Кубок города", draft.name)
        assertNull(draft.place)
        assertEquals(listOf(girls), draft.rules)
        assertEquals(listOf(CollectItem("a", "Взнос", 150_000), CollectItem("c", "Справка")), draft.items)
    }

    private val fee = CollectItem("fee", "Взнос", 150_000)
    private val paper = CollectItem("paper", "Справка")

    private fun p(id: String, last: String, vararg handed: String, starts: List<CompetitionStart> = emptyList(), year: Int? = 2014) =
        Participant("e$id", "c$id", ClientName(last, "Имя$id"), year, handed.toSet(), starts)

    @Test
    fun collecting() {
        val list = listOf(p("1", "Петров", "fee", "paper"), p("2", "Орлова", "paper"), p("3", "Соколова"))
        assertEquals(2, CompetitionRules.missing(fee, list))
        assertEquals(1, CompetitionRules.missing(paper, list))
        assertEquals(2, CompetitionRules.owing(listOf(fee, paper), list))
        assertEquals(0, CompetitionRules.owing(emptyList(), list))
        assertEquals(Collected(150_000, 450_000), CompetitionRules.collected(listOf(fee, paper), list))
        assertNull(CompetitionRules.collected(listOf(paper), list))
        assertEquals(setOf("fee"), CompetitionRules.keepHanded(setOf("fee", "gone"), listOf(fee, paper)))
    }

    private val day = LocalDate(2026, 12, 14)
    private val free50pool50 = SwimEvent(SwimStyle.FREESTYLE, 50, 50)
    private val free50pool25 = SwimEvent(SwimStyle.FREESTYLE, 50, 25)

    private fun r(id: String, date: LocalDate, event: SwimEvent, cs: Int) = ResultItem(id, date, event, cs, ResultKind.TRAINING)

    @Test
    fun entryTimeIsBestEverInSamePoolBeforeTheDay() {
        val results = listOf(
            r("1", LocalDate(2025, 3, 1), free50pool50, 3812), // давно, но лучшее
            r("2", LocalDate(2026, 10, 10), free50pool50, 3900),
            r("3", LocalDate(2026, 11, 1), free50pool25, 3500), // 25-метровый — не в счёт
            r("4", day, free50pool50, 3700), // сами соревнования — не в счёт
        )
        val start = CompetitionStart("s", free50pool50)
        val auto = CompetitionRules.entryTime(start, results, day)
        assertEquals(3812, auto.cs)
        assertEquals(false, auto.manual)
        assertEquals("1", auto.best?.id)
        val manual = CompetitionRules.entryTime(start.copy(entryCs = 3750), results, day)
        assertEquals(EntryTime(3750, true, auto.best), manual)
        // В 50-метровом нет ничего — пусто, 25-метровый не подставляется.
        assertNull(CompetitionRules.entryTime(start, listOf(results[2]), day).cs)
        // Совпало с лучшим — храним пусто (дальше обновляется само).
        assertNull(CompetitionRules.storedEntry(3812, auto.best))
        assertEquals(3750, CompetitionRules.storedEntry(3750, auto.best))
        assertNull(CompetitionRules.storedEntry(null, auto.best))
        assertEquals("1", ResultRules.best(results, free50pool50, day)?.id)
    }

    @Test
    fun gainAndPlace() {
        val results = listOf(r("1", LocalDate(2026, 10, 10), free50pool50, 3812), r("2", day, free50pool50, 3705))
        assertEquals(107, CompetitionRules.gain(results, free50pool50, 3705, day))
        assertNull(CompetitionRules.gain(results, free50pool50, 3900, day))
        assertNull(CompetitionRules.gain(emptyList(), free50pool50, 3705, day))
        assertEquals(2, CompetitionRules.place(" 2 "))
        assertNull(CompetitionRules.place("0"))
        assertNull(CompetitionRules.place("второе"))
        assertTrue(CompetitionRules.canFinish(day, day))
        assertTrue(!CompetitionRules.canFinish(day, LocalDate(2026, 12, 13)))
    }

    @Test
    fun upcomingAndPast() {
        val today = LocalDate(2026, 11, 27)
        val a = Competition("a", "Кубок", LocalDate(2026, 12, 14), 50)
        val b = Competition("b", "Первенство", today, 25)
        val c = Competition("c", "Осенний старт", LocalDate(2026, 10, 1), 25)
        val d = Competition("d", "Весна", LocalDate(2026, 4, 1), 25)
        assertEquals(listOf("b", "a"), CompetitionRules.upcoming(listOf(a, b, c, d), today).map { it.id })
        assertEquals(listOf("c", "d"), CompetitionRules.past(listOf(a, b, c, d), today).map { it.id })
    }

    @Test
    fun entryListByEventFastestFirst() {
        val back100 = SwimEvent(SwimStyle.BACKSTROKE, 100, 50)
        val lev = p("1", "Петров", starts = listOf(CompetitionStart("s1", free50pool50, entryCs = 3640), CompetitionStart("s2", back100, entryCs = 8490)))
        val mira = p("2", "Соколова", starts = listOf(CompetitionStart("s3", free50pool50)), year = 2012)
        val eva = p("3", "Орлова", starts = listOf(CompetitionStart("s4", back100)), year = 2012)
        val sofia = p("4", "Белова", year = 2009)
        val results = mapOf("c2" to listOf(r("r", LocalDate(2026, 10, 10), free50pool50, 3812)))
        val list = CompetitionRules.entryList(listOf(mira, eva, lev, sofia), { results[it].orEmpty() }, day)
        assertEquals(listOf(free50pool50, back100), list.map { it.first })
        assertEquals(listOf(3640, 3812), list[0].second.map { it.cs })
        assertEquals(listOf(8490, null), list[1].second.map { it.cs })
        assertEquals(listOf("e4"), CompetitionRules.withoutStarts(listOf(mira, eva, lev, sofia)).map { it.id })
    }
}
