package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.training.ClientOutcome
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Поиск, похожие клиенты, пол, возраст и проверка формы (ТЗ 5.8, 4.17). Имена придуманы. */
class ClientRulesTest {
    private val today = LocalDate(2026, 10, 20)

    private val adelina = ClientName("Дельфенурова", "Аделина")
    private val artem = ClientName("Ковалёв", "Артём", "Сергеевич")

    // Поиск

    @Test
    fun searchByStartOfLastOrFirstNameIgnoringCaseAndYo() {
        assertTrue(ClientRules.matches("ков", artem))
        assertTrue(ClientRules.matches("Ковалев", artem))
        assertTrue(ClientRules.matches("АРТЕМ", artem))
        assertTrue(ClientRules.matches("ков ар", artem))
        assertTrue(ClientRules.matches("Серг", artem))
        assertTrue(ClientRules.matches("  ", artem))
        assertFalse(ClientRules.matches("алёв", artem))
        assertFalse(ClientRules.matches("ков ал", artem))
    }

    @Test
    fun sortPutsYoWithYe() {
        val names = listOf(ClientName("Ёлкин", "Иван"), ClientName("Яковлева", "Татьяна"), ClientName("Елисеев", "Олег"), ClientName("Ежов", "Пётр"))
        assertEquals(listOf("Ежов", "Елисеев", "Ёлкин", "Яковлева"), names.sortedBy { ClientRules.sortKey(it) }.map { it.lastName })
    }

    // Похожие

    @Test
    fun delfenurovaAndDelfinurovaAreSimilar() {
        val existing = listOf(adelina, ClientName("Дельфенурова", "Алиса"), ClientName("Петрова", "Аделина"))
        val found = ClientRules.similar(ClientName("Дельфинурова", "Аделина"), existing) { it }
        assertEquals(listOf(adelina), found)
    }

    @Test
    fun similarCountsUpToTwoLettersAndNearestFirst() {
        val one = ClientName("Иванова", "Мария")
        val two = ClientName("Иваненко", "Мария")
        val same = ClientName("ИВАНОВА", "мария")
        val far = ClientName("Ивашкевич", "Мария")
        assertEquals(listOf(one, same), ClientRules.similar(ClientName("Иванова", "Мария"), listOf(far, one, same), { it }))
        assertEquals(listOf(one), ClientRules.similar(ClientName("Иваново", "Мария"), listOf(one, two), { it }))
        assertEquals(2, ClientRules.distance("иваненко", "иваненва"))
        assertEquals(4, ClientRules.distance("иванова", "иваненко"))
        assertEquals(listOf(ClientName("Ковалёва", "Алиса")), ClientRules.similar(ClientName("Ковалева", "Алиса"), listOf(ClientName("Ковалёва", "Алиса")), { it }))
        assertTrue(ClientRules.similar(ClientName("", "Мария"), listOf(one), { it }).isEmpty())
        assertTrue(ClientRules.similar(ClientName("Иванова", "Марина"), listOf(one), { it }).isEmpty())
    }

    @Test
    fun similarFindsNameTypedTheOtherWayRound() {
        val adelina = ClientName("Дельфенурова", "Аделина")
        assertEquals(listOf(adelina), ClientRules.similar(ClientName("Аделина", "Дельфенурова"), listOf(adelina), { it }))
        assertTrue(ClientRules.similar(ClientName("Аделина", "Дельфинурова"), listOf(adelina), { it }).isEmpty())
    }

    @Test
    fun levenshteinDistance() {
        assertEquals(0, ClientRules.distance("", ""))
        assertEquals(3, ClientRules.distance("", "абв"))
        assertEquals(1, ClientRules.distance("дельфенурова", "дельфинурова"))
        assertEquals(1, ClientRules.distance("смирнов", "смирнова"))
        assertEquals(2, ClientRules.distance("кот", "ток"))
    }

    // Пол (4.17)

    @Test
    fun sexByMiddleNameFirst() {
        assertEquals(SexGuess(Sex.MALE, true), ClientRules.guessSex("Сергеевич", "Ли"))
        assertEquals(SexGuess(Sex.MALE, true), ClientRules.guessSex("Ильич", ""))
        assertEquals(SexGuess(Sex.FEMALE, true), ClientRules.guessSex("Петровна", "Шевченко"))
        assertEquals(SexGuess(Sex.FEMALE, true), ClientRules.guessSex("Никитична", ""))
        assertEquals(SexGuess(Sex.FEMALE, true), ClientRules.guessSex("Ильинична", "Иванов"))
    }

    @Test
    fun sexByLastNameWithoutMiddleName() {
        val female = listOf("Иванова", "Ковалёва", "Ласкина", "Сицына", "Толстая", "Яковлева")
        val male = listOf("Иванов", "Ковалёв", "Ласкин", "Сицын", "Вишневский", "Толстой")
        female.forEach { assertEquals(SexGuess(Sex.FEMALE, false), ClientRules.guessSex(null, it), it) }
        male.forEach { assertEquals(SexGuess(Sex.MALE, false), ClientRules.guessSex("", it), it) }
        listOf("Ли", "Шевченко", "Дюма", "Ким").forEach { assertNull(ClientRules.guessSex(null, it), it) }
    }

    // Возраст

    @Test
    fun ageInFullYearsAndMonths() {
        assertEquals(9, ClientRules.age(LocalDate(2017, 5, 12), today))
        assertEquals(9, ClientRules.age(LocalDate(2016, 10, 21), today))
        assertEquals(10, ClientRules.age(LocalDate(2016, 10, 20), today))
        assertEquals(0, ClientRules.age(LocalDate(2026, 2, 3), today))
        assertEquals(8, ClientRules.ageMonths(LocalDate(2026, 2, 3), today))
        assertEquals(7, ClientRules.ageMonths(LocalDate(2026, 2, 21), today))
    }

    @Test
    fun adultFromBirthDateOtherwiseManual() {
        assertTrue(ClientRules.isAdult(LocalDate(2008, 10, 20), manual = false, today = today))
        assertFalse(ClientRules.isAdult(LocalDate(2008, 10, 21), manual = true, today = today))
        assertTrue(ClientRules.isAdult(null, manual = true, today = today))
        assertFalse(ClientRules.isAdult(null, manual = false, today = today))
    }

    // Форма

    @Test
    fun formProblemsInCoachWords() {
        assertEquals(
            mapOf(ClientField.LAST_NAME to "Введите фамилию", ClientField.FIRST_NAME to "Введите имя"),
            ClientRules.problems(ClientDraft(), "", today),
        )
        val ok = ClientDraft("Дельфинурова", "Аделина")
        assertTrue(ClientRules.problems(ok, "12.05.2017", today).isEmpty())
        assertEquals("Дата в виде 12.05.2017", ClientRules.problems(ok, "12 мая", today)[ClientField.BIRTH_DATE])
        assertEquals("Дата в виде 12.05.2017", ClientRules.problems(ok, "31.02.2017", today)[ClientField.BIRTH_DATE])
        assertEquals("Эта дата ещё не наступила", ClientRules.problems(ok, "21.10.2026", today)[ClientField.BIRTH_DATE])
        assertEquals("Проверьте год", ClientRules.problems(ok, "01.01.1901", today)[ClientField.BIRTH_DATE])
        assertEquals("Проверьте номер", ClientRules.problems(ok.copy(contactPhone = "12"), "", today)[ClientField.PHONE])
        assertTrue(ClientRules.problems(ok.copy(contactPhone = "+7 912 384-20-17"), "", today).isEmpty())
    }

    @Test
    fun phoneNormalizedToPlusSeven() {
        assertEquals("+79123842017", ClientRules.normalizePhone("8 912 384-20-17"))
        assertEquals("+79123842017", ClientRules.normalizePhone("+7 (912) 384 20 17"))
        assertEquals("+79123842017", ClientRules.normalizePhone("9123842017"))
        assertEquals("+7 3452 12-34-56", ClientRules.normalizePhone(" +7 3452 12-34-56 "))
        assertEquals("+375 29 123-45-67", ClientRules.normalizePhone("+375 29 123-45-67"))
    }

    // Счётчики карточки (5.9)

    @Test
    fun statsCountOutcomesAndAveragePerMonth() {
        val outcomes = listOf(
            LocalDate(2026, 4, 21) to ClientOutcome.DONE,
            LocalDate(2026, 5, 5) to ClientOutcome.CANCELLED,
            LocalDate(2026, 6, 2) to ClientOutcome.CANCELLED_CHARGED,
            LocalDate(2026, 10, 15) to ClientOutcome.DONE,
            LocalDate(2026, 10, 22) to ClientOutcome.PLANNED,
        ) + List(44) { LocalDate(2026, 7, 1) to ClientOutcome.DONE }
        val stats = ClientStats.of(outcomes, today)
        assertEquals(46, stats.done)
        assertEquals(1, stats.cancelled)
        assertEquals(1, stats.charged)
        // 21.04–20.10 — 183 дня, 6,01 месяца: 46 / 6,01 = 7,65.
        assertEquals(77, stats.perMonthTenths)
        assertFalse(stats.isEmpty)
    }

    @Test
    fun firstMonthCountsAsWholeMonth() {
        val stats = ClientStats.of(listOf(LocalDate(2026, 10, 12) to ClientOutcome.DONE), today)
        assertEquals(10, stats.perMonthTenths)
        assertTrue(ClientStats.of(listOf(LocalDate(2026, 10, 22) to ClientOutcome.PLANNED), today).isEmpty)
    }
}
