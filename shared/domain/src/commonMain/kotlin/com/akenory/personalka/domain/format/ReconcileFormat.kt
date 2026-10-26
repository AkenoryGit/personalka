package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ReconcileItemKind
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.money.DutyCheck
import com.akenory.personalka.domain.money.DutyRow
import com.akenory.personalka.domain.report1c.Diff1c
import com.akenory.personalka.domain.report1c.Reconcile1c
import com.akenory.personalka.domain.report1c.Status1c
import kotlinx.datetime.LocalDate
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

    const val LOAD = "Загрузить отчёт"
    const val REPORT_HINT = "PDF «Отчет по тренировкам» из 1С: выберите файл или отправьте его в «Персоналку» через «Поделиться» из почты"
    const val NEXT = "Дальше"
    const val CANCEL = "Отмена"
    const val SERVICE_NOTE = "Спросим один раз — дальше такие строки сверяются сами"
    const val TYPE = "Тип"
    const val PLACE = "Место"
    const val NO_SUCH_CLIENT = "Нет такого клиента"
    const val NAME_NOTE = "Выбор запомнится — в следующий раз спрашивать не будем"
    const val MINE = "Да, это мой отчёт"
    const val NOT_MINE = "Нет, не сверять"

    /** «Отчёт прочитан не полностью: строк 280 из 289». */
    fun incomplete(read: Int, expected: Int): String = "$INCOMPLETE: строк $read из $expected"

    /** «В отчёте — Тренеров Иван Петрович. Сверить с вашими тренировками?» */
    fun otherCoach(coach: String): String = "В отчёте — $coach. Сверить с вашими тренировками?"

    /** Шаг подготовки: «Шаг 2 из 7». */
    fun step(n: Int, of: Int): String = "Шаг $n из $of"

    /** «Укажите тип и место для «Сплит. ББ»». */
    fun serviceQuestion(name: String): String = "Укажите тип и место для «$name»"

    /** «Кто это — «Дельфинурова Аделина Олеговна»?» */
    fun nameQuestion(name: String): String = "Кто это — «$name»?"

    /** Строка истории: «01.10–31.10.2026 · выгрузка 18.10 10:48». */
    fun period(from: LocalDate, to: LocalDate, exported: LocalDateTime?): String {
        val range = if (from.year == to.year) "${DateFormat.dayMonth(from)}–${DateFormat.full(to)}" else "${DateFormat.full(from)}–${DateFormat.full(to)}"
        return range + (exported?.let { "${DOT}выгрузка ${DateFormat.dayMonth(it.date)} ${DateFormat.time(it.time)}" } ?: "")
    }

    /** Сводка истории: «Совпало 200 · Расхождений 3», где расхождения — ещё не решённые. */
    fun summary(matched: Int, open: Int): String = "Совпало $matched${DOT}Расхождений $open"

    // --- Услуги и имена из 1С (шаг 4.2г) ---

    const val MAPPINGS_TITLE = "Услуги и имена из 1С"
    const val SERVICES = "Услуги"
    const val NAMES = "Имена"
    const val SERVICE_EDIT = "Услуга из 1С"
    const val NAME_EDIT = "Имя из 1С"
    const val SAVE = "Сохранить"
    const val DONE = "Готово"
    const val CURRENT = "выбрано сейчас"
    const val MAPPING_SAVED = "Сохранено — загрузите отчёт ещё раз, и сверка пересчитается"
    const val ALIAS_DELETED = "Связь убрана — при следующей загрузке имя сверится заново"
    const val MAPPINGS_NOTE = "Нажмите, чтобы поменять. Имена, которые совпали с клиентом сами, здесь не показываются."
    const val ALIAS_EDIT_NOTE = "«Нет такого клиента» уберёт связь — строки этого имени уйдут в «Только в 1С» или спросятся снова"

    /** Под строкой в карточке: «6 услуг · 2 имени». */
    fun mappingsNote(services: Int, names: Int): String = listOfNotNull(
        services.takeIf { it > 0 }?.let { RuText.count(it, "услуга", "услуги", "услуг") },
        names.takeIf { it > 0 }?.let { RuText.count(it, "имя", "имени", "имён") },
    ).joinToString(DOT)

    /** Пара имени: «это Ганиева Кира». */
    fun aliasNote(client: String): String = "это $client"

    /** Ключ имени 1С («ганеева кира») — с заглавных: «Ганеева Кира». */
    fun name1c(key: String): String = key.split(' ').joinToString(" ") { w -> w.split('-').joinToString("-") { it.replaceFirstChar(Char::uppercaseChar) } }

    // --- Расхождения (шаг 4.2в) ---

    const val ADD = "Добавить в приложение"
    const val FIX = "Исправить в приложении"
    const val ASKED = "Написал администратору"
    const val IGNORE = "Игнорировать"
    const val SEND_ADMIN = "Отправить администратору"
    const val REOPEN = "Вернуть в расхождения"
    const val ADDED = "Добавлено в приложение"
    const val FIXED = "Исправлено в приложении"
    const val ASKED_DONE = "Отмечено — через 3 дня напомню, если не исправят"
    const val IGNORED_DONE = "Пункт больше не показывается"
    const val NOTHING = "Расхождений нет — всё совпало"
    const val ADD_NOTE = "Тренировка появится в календаре со статусом из 1С"
    const val ONLY_APP_FIX_NOTE = "В приложении будет отмена без списания — как будто клиента не было"
    const val UNKNOWN_CLIENT_NOTE = "Такого клиента в приложении нет — «Добавить» заведёт его по ФИО из 1С"
    const val TODO_TITLE = "Расхождения с 1С"
    const val REPEAT_NOTE = "Исправили в 1С — загрузите отчёт ещё раз: исправленное закроется само"

    /** Строка пункта: «чт 08.10 · 09:00 · Пестов Егор». */
    fun itemTitle(at: LocalDateTime, name: String): String = listOf(DateFormat.short(at.date), DateFormat.time(at.time), name).joinToString(DOT)

    /** Как участие стоит в приложении: «был на тренировке», «отменил», «отмена со списанием», «не отмечен». */
    fun appStatus(status: ParticipationStatus, sex: Sex?): String = when (status) {
        ParticipationStatus.ATTENDED -> RuText.bySex(sex, "был на тренировке", "была на тренировке", "был(а) на тренировке")
        ParticipationStatus.CANCELLED_CHARGED -> "отмена со списанием"
        ParticipationStatus.CANCELLED -> RuText.bySex(sex, "отменил", "отменила", "отменил(а)")
        ParticipationStatus.EXPECTED -> RuText.bySex(sex, "не отмечен", "не отмечена", "не отмечен(а)")
    }

    /**
     * Подпись пункта: что в приложении и что в 1С. [app] — «ПТ · ББ» тренировки приложения, [status] — участие в ней сейчас,
     * [askedAt] — день «Написал администратору».
     */
    fun itemNote(
        kind: ReconcileItemKind,
        statusDiffers: Boolean,
        status1c: Status1c?,
        service1c: String?,
        app: String?,
        status: ParticipationStatus?,
        sex: Sex?,
        askedAt: LocalDate?,
    ): String {
        val in1c = listOfNotNull(status1c?.let { "в 1С «${status1c(it)}»" }, service1c).joinToString(DOT)
        val inApp = listOfNotNull(app, status?.let { appStatus(it, sex) }).joinToString(DOT)
        val text = when (kind) {
            ReconcileItemKind.ONLY_1C -> in1c
            ReconcileItemKind.UNKNOWN_CLIENT -> "клиента нет в приложении$DOT$in1c"
            ReconcileItemKind.UNKNOWN_SERVICE -> "услуге не указан тип$DOT$in1c"
            ReconcileItemKind.ONLY_APP -> "в приложении: $inApp$DOT" + "в 1С нет"
            ReconcileItemKind.STATUS_DIFF ->
                if (statusDiffers) "в приложении: $inApp$DOT" + "в 1С «${status1c?.let(::status1c).orEmpty()}»"
                else "в приложении: ${app.orEmpty()}$DOT" + "в 1С «${service1c.orEmpty()}»"
        }
        return text + (askedAt?.let { "${DOT}написал администратору ${DateFormat.dayMonth(it)}" } ?: "")
    }

    /** Под «Расхождения с 1С» на «Главной»: «открыто: 3 · администратор не ответил: 1». */
    fun todoNote(open: Int, late: Int): String = listOfNotNull(
        open.takeIf { it > 0 }?.let { "открыто: $it" },
        late.takeIf { it > 0 }?.let { "администратор не ответил: $it" },
    ).joinToString(DOT)

    /** Сводка: «Совпало 189 · Расхождений 4». */
    fun summary(result: Reconcile1c): String = summary(result.matched, result.diffs.size)

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
