package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.training.PeriodProblem
import kotlinx.datetime.LocalDate

/** Строки «Не будет на период» (4.2а, шаг 5.3у). */
object PeriodCancelFormat {
    /** Пункт меню «…» карточки клиента. */
    const val MENU = "Не будет на период…"

    /** Ссылка внизу панели отмены тренировки. */
    const val LINK = "Не будет несколько дней — отменить на период"

    const val TITLE = "Не будет на период"
    const val FROM = "С"
    const val TO = "По"
    const val NONE = "В эти дни тренировок нет"
    const val NOTE = "Отменятся все тренировки клиента в эти дни: персональные — целиком, в сплите и группе — только он. " +
        "Тренировки серий дальше календаря появятся и тоже отменятся."

    fun problem(problem: PeriodProblem): String = when (problem) {
        PeriodProblem.TO_BEFORE_FROM -> "«По» раньше, чем «С»"
        PeriodProblem.FROM_IN_PAST -> "Прошедшие тренировки отменяются в их карточке"
        PeriodProblem.TOO_LONG -> "Не больше года"
    }

    /** «Отменится 6 тренировок · вт 03.11 – чт 26.11». */
    fun summary(count: Int, first: LocalDate, last: LocalDate): String =
        "Отменится " + RuText.count(count, "тренировка", "тренировки", "тренировок") + " · " +
            DateFormat.short(first) + (if (first == last) "" else " – " + DateFormat.short(last))

    /** Сколько из них со списанием — предупредили меньше чем за порог: «Со списанием: 1 — начинается меньше чем через час». */
    fun charged(count: Int): String =
        "Со списанием: " + count + " — до начала меньше, чем нужно предупредить"

    /** Кнопка: «Отменить 6 тренировок». */
    fun button(count: Int): String = "Отменить " + RuText.count(count, "тренировку", "тренировки", "тренировок")

    /** Сообщение: «Отменено 6 тренировок: 03.11–26.11». */
    fun done(count: Int, from: LocalDate, to: LocalDate): String =
        RuText.plural(count, "Отменена", "Отменены", "Отменено") + " " +
            RuText.count(count, "тренировка", "тренировки", "тренировок") + ": " +
            DateFormat.dayMonth(from) + (if (from == to) "" else "–" + DateFormat.dayMonth(to))
}
