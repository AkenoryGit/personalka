package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ReconcileItemKind
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.money.DutyCheck
import com.akenory.personalka.domain.money.DutyRow
import com.akenory.personalka.domain.report1c.Diff1c
import com.akenory.personalka.domain.report1c.Reconcile1c
import com.akenory.personalka.domain.report1c.Status1c
import com.akenory.personalka.domain.training.TrainingTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth

/**
 * Строки раздела «Сверки» (ТЗ 5.14, шаг 3.9в): месяц зарплаты, дежурства месяца и отчёт 1С (ТЗ 8.4, шаг 4.2).
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

    // --- Отчёт 1С (ТЗ 8, шаг 4.2) ---

    const val REPORT_TITLE = "Отчёт 1С"
    const val NOT_A_REPORT = "Это не похоже на отчёт 1С по тренировкам"
    const val INCOMPLETE = "Отчёт прочитан не полностью"
    const val OTHER_COACH = "Это отчёт другого тренера?"
    const val ONLY_1C = "Только в 1С"
    const val ONLY_APP = "Только в приложении"
    const val DIFFER = "Статус или услуга расходятся"

    /** Сводка: «Совпало 189 · Расхождений 4». */
    fun summary(result: Reconcile1c): String = "Совпало ${result.matched}${DOT}Расхождений ${result.diffs.size}"

    /** Статус строки 1С, как его пишет 1С: «Пришел», «Не пришел», «Отменил». */
    fun status1c(status: Status1c): String = when (status) {
        Status1c.CAME -> "Пришел"
        Status1c.NO_SHOW -> "Не пришел"
        Status1c.CANCELLED -> "Отменил"
    }

    /**
     * Строка для администратора (ТЗ 8.4): «08.10 09:00, группа, Пестов Егор — был на тренировке, в 1С «Отменил». Прошу
     * исправить». [what] — что за тренировка словами тренера («группа», «ПТ»), [name] — фамилия и имя, [service1c] —
     * как услугу назвала 1С. Пунктам «Только в 1С» и неотмеченным в приложении администратор не нужен — `null`.
     */
    fun adminLine(diff: Diff1c, what: String, name: String, sex: Sex?): String? {
        val visit = diff.visit ?: return null
        val at = "${DateFormat.dayMonth(visit.start.date)} ${DateFormat.time(visit.start.time)}"
        val app = when (visit.status) {
            ParticipationStatus.ATTENDED -> RuText.bySex(sex, "был на тренировке", "была на тренировке", "был(а) на тренировке")
            ParticipationStatus.CANCELLED_CHARGED -> "отмена со списанием"
            ParticipationStatus.CANCELLED -> RuText.bySex(sex, "отменил", "отменила", "отменил(а)")
            ParticipationStatus.EXPECTED -> return null
        }
        val row = diff.row
        val tail = when {
            diff.kind == ReconcileItemKind.ONLY_APP -> "в 1С нет. Прошу добавить"
            row == null -> return null
            diff.statusDiffers -> "в 1С «${status1c(row.status)}». Прошу исправить"
            diff.serviceDiffers -> "в 1С «${row.serviceName}». Прошу исправить"
            else -> return null
        }
        return "$at, $what, $name — $app, $tail"
    }

    /** Выбранные пункты одним текстом — по строке на пункт. */
    fun adminText(lines: List<String>): String = lines.joinToString("\n")

    private fun end(row: DutyRow): LocalDateTime = TrainingTime.end(row.start, row.durationMin)

    private fun capital(text: String): String = text.replaceFirstChar { it.uppercaseChar() }
}
