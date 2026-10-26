package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.money.DutyCheck
import com.akenory.personalka.domain.money.DutyRow
import com.akenory.personalka.domain.training.TrainingTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth

/**
 * Строки раздела «Сверки» (ТЗ 5.14, шаг 3.9б): месяц зарплаты и дежурства месяца; отчёт 1С добавится на шаге 4.2.
 * Кнопка и отметки «Месяц сверен» — те же, что были в «Зарплате» (`SalaryFormat`).
 */
object ReconcileFormat {
    private const val DOT = " · "

    const val TITLE = "Сверки"

    const val SALARY_TITLE = "Зарплата за месяц"
    const val SALARY_NOTE = "Сверьте заработано с расчётом клуба. Сверенный месяц защищён от случайных правок."

    const val DUTY_TITLE = "Дежурства"
    const val DUTY_PLANNED = "по расписанию"
    const val CHECK_DUTIES = "Дежурства сверены"
    const val DUTIES_CHECKED = "Сверены"
    const val UNCHECK = "Снять сверку"
    const val DUTIES_UNCHECKED = "Сверка дежурств снята"
    const val SHARE = "Поделиться"
    const val COPIED = "Список скопирован — вставьте его в сообщение"

    /** Будущий месяц: «Ноябрь ещё не начался — сверять нечего». */
    fun future(month: YearMonth): String = "${capital(DateFormat.month(month.firstDay))} ещё не начался — сверять нечего"

    /** Под заголовком зарплаты: «Заработано 126 645 ₽». */
    fun earned(amount: Long): String = "Заработано ${MoneyFormat.rub(amount)}"

    /** Строка дежурства: «вт 06.10 · 08:00–12:00 · ББ». */
    fun dutyTitle(row: DutyRow, place: String?): String =
        listOfNotNull(DateFormat.short(row.start.date), DateFormat.timeRange(row.start, end(row)), place).joinToString(DOT)

    /** Под строкой: «за кого: Александра» — имя как написал тренер, без склонения. */
    fun coverFor(name: String): String = "за кого: $name"

    /** Итог: «Проведено 24 ч · 4 800 ₽»; без денег (ставки нет) — только часы. */
    fun doneTotal(check: DutyCheck): String =
        "Проведено ${SalaryFormat.hours(check.doneMinutes)}" + if (check.doneAmount > 0) "$DOT${MoneyFormat.rub(check.doneAmount)}" else ""

    /** «Впереди по расписанию 8 ч» — у текущего месяца. */
    fun plannedTotal(minutes: Int): String = "Впереди по расписанию ${SalaryFormat.hours(minutes)}"

    /** Под кнопкой: «Сверьте часы с графиком дежурств клуба». */
    const val CHECK_NOTE = "Сверьте часы с графиком дежурств клуба"

    /** Часы изменились после сверки: «Сверены на 24 ч, потом часы изменились — теперь 26 ч». */
    fun changed(checked: Int, now: Int): String =
        "Сверены на ${SalaryFormat.hours(checked)}, потом часы изменились — теперь ${SalaryFormat.hours(now)}"

    /** «Дежурства октября сверены». */
    fun checkedMessage(month: YearMonth): String = "Дежурства ${DateFormat.monthGenitive(month.firstDay)} сверены"

    /**
     * Текст для администратора или коллеги: месяц, проведённые дежурства по строке, итог часов. Денег нет — у клуба
     * своя ставка, а сверяются часы.
     */
    fun shareText(month: YearMonth, check: DutyCheck, place: (String) -> String?): String = buildString {
        appendLine("Дежурства за ${DateFormat.month(month.firstDay)} ${month.year}")
        check.rows.filter { it.done }.forEach { row ->
            append("${DateFormat.short(row.start.date)}, ${DateFormat.timeRange(row.start, end(row))}")
            place(row.locationId)?.let { append(", $it") }
            append(" — ${SalaryFormat.hours(row.durationMin)}")
            row.coverFor?.let { append(" (${coverFor(it)})") }
            appendLine()
        }
        append("Итого: ${SalaryFormat.hours(check.doneMinutes)}")
    }

    private fun end(row: DutyRow): LocalDateTime = TrainingTime.end(row.start, row.durationMin)

    private fun capital(text: String): String = text.replaceFirstChar { it.uppercaseChar() }
}
