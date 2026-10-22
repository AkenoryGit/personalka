package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.model.Sex
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Форматы ТЗ 5.20 и 10.6 на примерах из макетов. В ожидаемых строках «_» — неразрывный пробел,
 * «^» — узкий неразрывный пробел между разрядами; так видно, где какой пробел стоит.
 */
class FormatTest {

    private fun s(text: String) = text.replace('_', RuText.NBSP).replace('^', RuText.THIN)

    // Склонение числительных

    @Test
    fun pluralFollowsRussianRules() {
        val forms = listOf(0, 1, 2, 4, 5, 11, 12, 14, 15, 21, 22, 25, 101, 111, 112, 1001)
            .map { RuText.plural(it, "тренировка", "тренировки", "тренировок") }
        assertEquals(
            listOf(
                "тренировок", "тренировка", "тренировки", "тренировки", "тренировок", "тренировок", "тренировок",
                "тренировок", "тренировок", "тренировка", "тренировки", "тренировок", "тренировка", "тренировок",
                "тренировок", "тренировка",
            ),
            forms,
        )
    }

    @Test
    fun pluralOfNegativeIsLikePositive() {
        assertEquals("часа", RuText.plural(-2, "час", "часа", "часов"))
        assertEquals("часов", RuText.plural(-11, "час", "часа", "часов"))
    }

    @Test
    fun countKeepsNumberWithWord() {
        assertEquals(s("1_тренировка"), RuText.count(1, "тренировка", "тренировки", "тренировок"))
        assertEquals(s("5_часов"), RuText.count(5, "час", "часа", "часов"))
        assertEquals(s("1^350_детей"), RuText.count(1350, "ребёнок", "ребёнка", "детей"))
    }

    @Test
    fun digitsGroupedByThinSpace() {
        assertEquals("0", RuText.digits(0))
        assertEquals("830", RuText.digits(830))
        assertEquals(s("1^000"), RuText.digits(1000))
        assertEquals(s("126^645"), RuText.digits(126_645))
        assertEquals(s("1^478^495"), RuText.digits(1_478_495))
        assertEquals(s("−8^170"), RuText.digits(-8170))
    }

    @Test
    fun wordsBySex() {
        assertEquals("был", RuText.bySex(Sex.MALE, "был", "была", "последняя"))
        assertEquals("была", RuText.bySex(Sex.FEMALE, "был", "была", "последняя"))
        assertEquals("Это тот же клиент?", RuText.bySex(null, "Это он?", "Это она?", "Это тот же клиент?"))
    }

    // Деньги

    @Test
    fun moneyWithoutKopecksWhenThereAreNone() {
        assertEquals(s("126^645_₽"), MoneyFormat.rub(12_664_500))
        assertEquals(s("830_₽"), MoneyFormat.rub(83_000))
        assertEquals(s("124^800_₽"), MoneyFormat.rub(12_480_000))
        assertEquals(s("0_₽"), MoneyFormat.rub(0))
    }

    @Test
    fun moneyWithKopecksWhereTheyAre() {
        assertEquals(s("8^170,50_₽"), MoneyFormat.rub(817_050))
        assertEquals(s("118^413,08_₽"), MoneyFormat.rub(11_841_308))
        assertEquals(s("0,05_₽"), MoneyFormat.rub(5))
        assertEquals(s("−4^165,43_₽"), MoneyFormat.rub(-416_543))
    }

    @Test
    fun signedMoneyShowsPlus() {
        assertEquals(s("+883,58_₽"), MoneyFormat.signedRub(88_358))
        assertEquals(s("−8^170,50_₽"), MoneyFormat.signedRub(-817_050))
        assertEquals(s("0_₽"), MoneyFormat.signedRub(0))
    }

    @Test
    fun amountWithoutRubleSign() {
        assertEquals(s("62^850"), MoneyFormat.amount(6_285_000))
        assertEquals(s("−8^170,50"), MoneyFormat.amount(-817_050))
    }

    @Test
    fun forecastsRoundTo10Rubles() {
        // Цифры прогнозов с макетов шага 0.6.
        assertEquals(s("≈_203^490_₽"), MoneyFormat.approxRub(20_349_250))
        assertEquals(s("≈_5^820_₽"), MoneyFormat.approxRub(581_500))
        assertEquals(s("≈_6^510_₽"), MoneyFormat.approxRub(650_750))
        assertEquals(s("≈_950_₽"), MoneyFormat.approxRub(95_000))
        assertEquals(s("≈_−5^820_₽"), MoneyFormat.approxRub(-581_500))
        assertEquals(58_000L, MoneyFormat.roundTo10Rub(58_499))
    }

    // Даты

    @Test
    fun shortAndFullDates() {
        val thursday = LocalDate(2026, 10, 22)
        assertEquals(s("чт_22.10"), DateFormat.short(thursday))
        assertEquals("22.10.2026", DateFormat.full(thursday))
        assertEquals("05.03", DateFormat.dayMonth(LocalDate(2027, 3, 5)))
        assertEquals("05.03.2027", DateFormat.full(LocalDate(2027, 3, 5)))
    }

    @Test
    fun allWeekdaysShort() {
        val days = (19..25).map { DateFormat.short(LocalDate(2026, 10, it)) }
        assertEquals(
            listOf("пн_19.10", "вт_20.10", "ср_21.10", "чт_22.10", "пт_23.10", "сб_24.10", "вс_25.10").map(::s),
            days,
        )
    }

    @Test
    fun headerAndMonth() {
        assertEquals(s("Вторник, 20_октября"), DateFormat.header(LocalDate(2026, 10, 20)))
        assertEquals(s("Воскресенье, 1_марта"), DateFormat.header(LocalDate(2026, 3, 1)))
        assertEquals(s("Октябрь_2026"), DateFormat.monthYear(LocalDate(2026, 10, 20)))
        assertEquals("мая", DateFormat.monthGenitive(LocalDate(2026, 5, 9)))
    }

    @Test
    fun weekStartsOnMonday() {
        val monday = LocalDate(2026, 10, 19)
        assertEquals(monday, DateFormat.weekStart(monday))
        assertEquals(monday, DateFormat.weekStart(LocalDate(2026, 10, 22)))
        assertEquals(monday, DateFormat.weekStart(LocalDate(2026, 10, 25)))
        // Неделя через границу года.
        assertEquals(LocalDate(2026, 12, 28), DateFormat.weekStart(LocalDate(2027, 1, 3)))
        assertEquals(DayOfWeek.MONDAY, DateFormat.WEEK.first())
        assertEquals(DayOfWeek.SUNDAY, DateFormat.WEEK.last())
    }

    // Время

    @Test
    fun timeIs24Hours() {
        assertEquals("07:05", DateFormat.time(LocalTime(7, 5)))
        assertEquals("19:00", DateFormat.time(LocalTime(19, 0)))
        assertEquals("00:00", DateFormat.minuteOfDay(0))
        assertEquals("24:00", DateFormat.minuteOfDay(1440))
        assertFailsWith<IllegalArgumentException> { DateFormat.minuteOfDay(1441) }
    }

    @Test
    fun ranges() {
        assertEquals("16:00–16:50", DateFormat.timeRange(960, 1010))
        // Запрет ДБ 17:00–18:00 (окно шага 1.4) и запрет до 00:00 — это 24:00.
        assertEquals("17:00–18:00", DateFormat.timeRange(LocalTime(17, 0), LocalTime(18, 0)))
        assertEquals("23:00–24:00", DateFormat.timeRange(LocalTime(23, 0), LocalTime(0, 0)))
        assertEquals("00:00–24:00", DateFormat.timeRange(LocalTime(0, 0), LocalTime(0, 0)))
        // Тренировка 23:10 + 50 мин кончается в полночь — «24:00», а не «00:00».
        assertEquals(
            "23:10–24:00",
            DateFormat.timeRange(LocalDateTime(2026, 10, 20, 23, 10), LocalDateTime(2026, 10, 21, 0, 0)),
        )
        assertEquals(
            "13:30–14:20",
            DateFormat.timeRange(LocalDateTime(2026, 10, 20, 13, 30), LocalDateTime(2026, 10, 20, 14, 20)),
        )
    }

    // Длительности

    @Test
    fun shortDurations() {
        assertEquals(s("0_мин"), DurationFormat.short(0))
        assertEquals(s("12_мин"), DurationFormat.short(12))
        assertEquals(s("40_мин"), DurationFormat.short(40))
        assertEquals(s("1_ч"), DurationFormat.short(60))
        // «предупредили за 1 ч 20 мин» с макета отмены.
        assertEquals(s("1_ч 20_мин"), DurationFormat.short(80))
        assertEquals(s("23_ч 59_мин"), DurationFormat.short(1439))
        assertEquals(s("1_день"), DurationFormat.short(1440))
        assertEquals(s("1_день 1_ч"), DurationFormat.short(1500))
        assertEquals(s("2_дня"), DurationFormat.short(2880 + 30))
        assertEquals(s("5_дней 4_ч"), DurationFormat.short(5 * 1440 + 4 * 60))
        assertEquals(s("40_мин"), DurationFormat.short(-40))
    }

    @Test
    fun longDurationsForCabinetRule() {
        // Правило отмены в кабинете (6.3): 60 → «1 час», 90 → «1 час 30 минут», 120 → «2 часа».
        assertEquals(s("1_час"), DurationFormat.long(60))
        assertEquals(s("1_час 30_минут"), DurationFormat.long(90))
        assertEquals(s("2_часа"), DurationFormat.long(120))
        assertEquals(s("5_часов"), DurationFormat.long(300))
        assertEquals(s("24_часа"), DurationFormat.long(1440))
        assertEquals(s("45_минут"), DurationFormat.long(45))
        assertEquals(s("21_минута"), DurationFormat.long(21))
        assertEquals(s("2_часа 2_минуты"), DurationFormat.long(122))
    }
}
