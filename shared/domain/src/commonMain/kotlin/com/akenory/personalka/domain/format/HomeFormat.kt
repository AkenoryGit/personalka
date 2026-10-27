package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.home.BackupTodo
import com.akenory.personalka.domain.money.AdvanceState
import com.akenory.personalka.domain.money.PlanPace
import com.akenory.personalka.domain.money.TierProgress
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

/** Строки «Главной» (ТЗ 5.2, шаг 1.16). */
object HomeFormat {
    private const val DOT = " · "

    // --- «Ближайшая» ---

    const val NEAREST = "Ближайшая"

    /** «Ближайшая · 16:00–16:50». */
    fun nearest(start: LocalDateTime, end: LocalDateTime): String = NEAREST + DOT + DateFormat.timeRange(start, end)

    /** Справа в шапке: «через 12 мин», «через 1 ч 20 мин»; идущая группа — «идёт». */
    fun startsIn(now: LocalDateTime, start: LocalDateTime): String =
        if (now >= start) ONGOING else "через " + DurationFormat.short(ClubTime.minutesBetween(now, start).coerceAtLeast(1))

    const val ONGOING = "идёт"

    /**
     * Строка под именем у группы: к «Мини-группа · ДБ» добавляется «ждём 2 из 3» — кого ждут из записанных; все —
     * «ждём 3». Никого не ждут (все отменены) — без добавки.
     */
    fun waiting(expected: Int, total: Int): String? = when {
        expected <= 0 -> null
        expected == total -> "ждём ${RuText.digits(expected.toLong())}"
        else -> "ждём ${RuText.digits(expected.toLong())} из ${RuText.digits(total.toLong())}"
    }

    /** «Ещё сегодня: 3, последняя в 19:00»; одна — «Ещё сегодня: 1, в 19:00». */
    fun laterToday(count: Int, last: LocalDateTime): String =
        "Ещё сегодня: ${RuText.digits(count.toLong())}, " + (if (count == 1) "" else "последняя ") + "в$NBSP" + DateFormat.time(last.time)

    /**
     * Сегодня больше ничего нет: «Сегодня всё. Завтра первая в 07:00»; следующая не завтра — «Сегодня всё. Следующая —
     * пт 24.10 в 07:00»; впереди ничего — «Сегодня всё. Впереди тренировок нет».
     */
    fun todayDone(next: LocalDateTime?, today: LocalDate): String = "Сегодня всё. " + when {
        next == null -> "Впереди тренировок нет"
        next.date == today.plus(1, DateTimeUnit.DAY) -> "Завтра первая в$NBSP" + DateFormat.time(next.time)
        else -> "Следующая — " + DateFormat.short(next.date) + " в$NBSP" + DateFormat.time(next.time)
    }

    /** Строка следующей тренировки под «Сегодня всё»: «ср 21.10 · Ковалёва Н. · ПТ · ББ». */
    fun nextLine(start: LocalDateTime, title: String, typeShort: String, placeShort: String): String =
        DateFormat.short(start.date) + DOT + title + DOT + typeShort + DOT + placeShort

    // --- Расписание дня на разложенном ---

    /** «Показать прошедшие · 7». */
    fun showPast(count: Int): String = "Показать прошедшие" + DOT + RuText.digits(count.toLong())

    const val HIDE_PAST = "Скрыть прошедшие"

    /** «Завтра: 9 тренировок, первая в 07:00»; пусто — «Завтра тренировок нет». */
    fun tomorrow(count: Int, first: LocalDateTime?): String =
        if (count == 0 || first == null) {
            "Завтра тренировок нет"
        } else {
            "Завтра: ${CalendarFormat.trainings(count)}, первая в$NBSP" + DateFormat.time(first.time)
        }

    /** Строка под именем в расписании дня: «ПТ · ДБ», у ближайшей — «ПТ · ДБ · через 12 мин». */
    fun dayLine(typeShort: String, placeShort: String, startsIn: String? = null): String =
        typeShort + DOT + placeShort + if (startsIn != null) DOT + startsIn else ""

    /** Расписание дня пусто. */
    const val NO_TRAININGS_TODAY = "Сегодня тренировок нет"

    // --- «Нужно сделать» ---

    const val TODO = "Нужно сделать"

    /** «Группа без отметки», несколько — «Группы без отметки». */
    fun unmarkedGroups(count: Int): String = if (count == 1) "Группа без отметки" else "Группы без отметки"

    /** Сколько групп видно в подписи; остальные — «и ещё N». */
    const val MAX_NAMES = 2

    /**
     * Подпись: одна — «18:00 · Вт, Чт 18:00 ДБ (10)» (не сегодня — «пн 19.10 18:00 · …»); несколько — время через
     * запятую: «пн 19.10 18:00, 18:00 и ещё 1».
     */
    fun unmarkedGroupsNote(groups: List<Pair<LocalDateTime, String>>, today: LocalDate): String {
        fun at(start: LocalDateTime) =
            if (start.date == today) DateFormat.time(start.time) else DateFormat.short(start.date) + " " + DateFormat.time(start.time)
        groups.singleOrNull()?.let { (start, title) -> return at(start) + DOT + title }
        val shown = groups.take(MAX_NAMES).joinToString(", ") { at(it.first) }
        val rest = groups.size - MAX_NAMES
        return if (rest > 0) "$shown и ещё ${RuText.digits(rest.toLong())}" else shown
    }

    /** Подпись пункта с именами: «Смирнова Анна, Орлов Пётр и ещё 3». */
    fun names(names: List<String>): String {
        val shown = names.take(MAX_NAMES).joinToString(", ")
        val rest = names.size - MAX_NAMES
        return if (rest > 0) "$shown и ещё ${RuText.digits(rest.toLong())}" else shown
    }

    const val NO_DIARY = "Без дневника"

    /**
     * Подпись «Без дневника»: «Ковалёв / Ковалёва 09:00 · Орлова К. 13:30», не сегодня — «Орлова К. пн 19.10»;
     * не влезли — «и ещё 3».
     */
    fun noDiaryNote(trainings: List<Pair<LocalDateTime, String>>, today: LocalDate): String {
        val shown = trainings.take(MAX_NAMES).joinToString(DOT) { (start, title) ->
            title + " " + if (start.date == today) DateFormat.time(start.time) else DateFormat.short(start.date)
        }
        val rest = trainings.size - MAX_NAMES
        return if (rest > 0) "$shown и ещё ${RuText.digits(rest.toLong())}" else shown
    }

    fun backup(todo: BackupTodo): String = when (todo) {
        BackupTodo.NO_FOLDER -> "Выберите папку для копии"
        BackupTodo.STALE -> "Нет копии больше 7 дней"
    }

    /** Подпись пункта про копию: «Пока данные только в этом телефоне»; давно нет копии — «последняя — 12.10.2026». */
    fun backupNote(todo: BackupTodo, last: LocalDateTime?): String = when {
        todo == BackupTodo.NO_FOLDER -> "Пока данные только в этом телефоне"
        last == null -> BackupFormat.NO_COPIES
        else -> "последняя — " + DateFormat.full(last.date)
    }

    /** Для TalkBack у счётчика дел рядом с заголовком: «Нужно сделать: 3». */
    fun todoCount(count: Int): String = "$TODO: $count"

    /** Пункт «Нужно сделать» с 25-го (4.11): фактическая сумма аванса ещё не введена. */
    const val ADVANCE = "Введите сумму аванса"

    /** Подпись пункта: «аванс 25.10 · ожидается 13 546,50 ₽»; ожидаемой суммы в схеме нет — «аванс 25.10». */
    fun advanceNote(advance: AdvanceState): String =
        "аванс " + DateFormat.dayMonth(advance.date) + (advance.expected?.let { DOT + "ожидается " + MoneyFormat.rub(it) } ?: "")

    // --- «Деньги» (шаг 3.9) ---

    const val MONEY = "Деньги"

    /** Заголовок карточки: «Деньги · октябрь». */
    fun moneyTitle(month: LocalDate): String = MONEY + DOT + DateFormat.month(month)

    /** Колонки «Денег» (5.2, просьба Дмитрия 26.10.2026): сегодня и месяц. */
    const val TODAY = "Сегодня"
    const val MONTH = "За месяц"

    /**
     * Под суммой колонки: сколько будет по расписанию — «будет ≈ 6 440 ₽»; всё уже проведено — «всё проведено»;
     * в расписании ничего (сумма и прогноз нули) — «тренировок нет».
     */
    fun willBe(earned: Long, forecast: Long): String = when {
        earned == 0L && forecast == 0L -> "тренировок нет"
        forecast == earned -> "всё проведено"
        else -> "будет " + MoneyFormat.approxRub(forecast)
    }

    /** ПТ колонки: «2 ПТ · будет 5»; всё проведено — «4 ПТ»; ПТ в расписании нет — «без ПТ». */
    fun ptLine(done: Int, total: Int): String = when {
        total <= 0 && done == 0 -> "без ПТ"
        total <= done -> RuText.digits(done.toLong()) + NBSP + "ПТ"
        else -> RuText.digits(done.toLong()) + NBSP + "ПТ" + DOT + "будет " + RuText.digits(total.toLong())
    }

    /** Отменённые тренировки колонки «Денег»: «1 отмена», «3 отмены»; отмен нет — строки нет (10.7). */
    fun cancelledLine(n: Int): String? = if (n <= 0) null else RuText.count(n, "отмена", "отмены", "отмен")

    /**
     * Плашка ступени у заголовка «Денег» (5.2, шаг 3.9с): «Ступень 1 · до бонуса 61 ПТ» — сколько ПТ до 101-й по
     * проведённым; «Ступень 2 · бонус»; «Ступень 3 · максимум». Ступени выключены ([tiers] `null`) — плашки нет.
     */
    fun tierChip(tiers: TierProgress?): String? = when (tiers?.tier) {
        null -> null
        1 -> "Ступень 1" + DOT + "до бонуса " + RuText.digits((tiers.t2From - tiers.count).toLong()) + NBSP + "ПТ"
        2 -> "Ступень 2" + DOT + "бонус"
        else -> "Ступень " + tiers.tier + DOT + "максимум"
    }

    /**
     * Оценка месяца под полосой плана (5.2, шаг 3.9с): «План выполнен: 104 %», «Хорошо идёте: прогноз 105 % плана»,
     * отстаёт — «Поднажать: до плана ещё 20 ПТ» (K из 4.12, [pts]); K не найден — «Поднажать: прогноз 84 % плана».
     */
    fun pace(pace: PlanPace, percent: Int, forecastPercent: Int, pts: Int?): String = when (pace) {
        PlanPace.DONE -> "План выполнен: $percent$NBSP%"
        PlanPace.ON_TRACK -> "Хорошо идёте: прогноз $forecastPercent$NBSP% плана"
        PlanPace.BEHIND -> if (pts != null) "Поднажать: до плана ещё " + RuText.digits(pts.toLong()) + NBSP + "ПТ" else "Поднажать: прогноз $forecastPercent$NBSP% плана"
    }

    /** Зарплата не настроена (5.18): вместо суммы — приглашение и кнопка. */
    const val SALARY_NOT_SET = "Зарплата ещё не настроена"
    const val SALARY_NOT_SET_NOTE = "Посчитаю заработок, ступень и выплаты по вашим тренировкам."
    const val SALARY_SETUP = "Настроить зарплату"
}
