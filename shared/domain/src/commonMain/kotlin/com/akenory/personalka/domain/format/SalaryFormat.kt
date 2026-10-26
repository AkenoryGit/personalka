package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.money.BreakdownLine
import com.akenory.personalka.domain.money.BreakdownSection
import com.akenory.personalka.domain.money.GroupCase
import com.akenory.personalka.domain.money.MonthPeriod
import com.akenory.personalka.domain.money.PayoutKind
import com.akenory.personalka.domain.money.PeriodPart
import com.akenory.personalka.domain.money.SalaryYear
import com.akenory.personalka.domain.money.Shortfall
import com.akenory.personalka.domain.money.SplitMark
import com.akenory.personalka.domain.money.TierBeyond
import com.akenory.personalka.domain.money.TierProgress
import com.akenory.personalka.domain.money.YearMonthRow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth

/** Строки экрана «Зарплата» (ТЗ 5.12) и плана с прогнозом (4.12): экран и главная берут их отсюда. */
object SalaryFormat {
    private const val DOT = " · "

    const val TAB_MONTH = "Месяц"
    const val TAB_PAYOUTS = "Выплаты"
    const val EARNED = "Заработано"
    const val PLAN = "План"
    const val NO_PLAN = "План не задан"
    const val SET_PLAN = "Задать план"
    const val PLAN_TITLE = "План на месяц"
    const val PLAN_FIELD = "Сколько заработать до налогов, ₽"
    const val PLAN_NOTE = "Следующие месяцы предложат этот же план."
    const val PLAN_SAVED = "План сохранён"
    const val TIER = "Ступень"
    const val TIER_TOP = "Ступень 3 — выше некуда."
    const val BREAKDOWN = "Разбивка"
    const val ADJUSTMENTS = "Доплаты и удержания"
    const val ADD_ADJUSTMENT = "Доплата"
    const val ADJUST_TITLE = "Доплата или удержание"
    const val BONUS = "Доплата"
    const val DEDUCTION = "Удержание"
    const val ADJUST_AMOUNT = "Сумма, ₽"
    const val ADJUST_FOR = "За что"
    const val ADJUST_FOR_EMPTY = "Напишите, за что"
    const val IN_CLUB = "Входит в начисление клуба"
    const val IN_CLUB_NOTE = "С неё считаются налог и выплаты"
    const val NOT_IN_CLUB = "не входит в начисление клуба"
    const val SAVE = "Сохранить"
    const val DELETE = "Удалить"
    const val CANCEL = "Отмена"
    const val AMOUNT_EMPTY = "Впишите сумму"
    const val ACCRUED = "Начислено клубом"
    const val NDFL_NOTE = "точная сумма — в расчётном листке"
    const val WHEN = "Когда придут"
    const val ADVANCE = "Аванс на карту"
    const val ADVANCE_EXPECTED = "ожидается"
    const val ADVANCE_RECEIVED = "получен"
    const val CARD_REST = "Остаток на карту"
    const val CASH = "Наличными"
    const val WHOLE = "Выплата"
    const val SETTLEMENT_NOTE = "взаиморасчёт, не через клуб"
    const val ADVANCE_EXCEEDS = "Аванс больше суммы на карту"
    const val ON_HAND = "На руки"
    const val ADVANCE_FIELD = "Пришло, ₽"
    const val NO_DATE = "—"
    const val COLLEAGUE = "коллега"

    /** «Октябрь 2026» — заголовок месяца. */
    fun month(month: YearMonth): String = DateFormat.monthYear(month.firstDay).replaceFirstChar { it.uppercaseChar() }

    /** «сегодня +6 780 ₽ · впереди ≈ 5 820 ₽»; ничего сегодня — `null`. */
    fun today(earned: Long?, ahead: Long?): String? {
        val parts = buildList {
            if (earned != null && earned != 0L) add("сегодня ${MoneyFormat.signedRub(earned)}")
            if (ahead != null && ahead != 0L) add("${if (isEmpty()) "сегодня " else ""}впереди ${MoneyFormat.approxRub(ahead)}")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(DOT)
    }

    /** «выполнено 60 %». */
    fun done(percent: Int): String = "выполнено $percent$NBSP%"

    /** «Прогноз ≈ 203 490 ₽». */
    fun forecast(amount: Long): String = "Прогноз ${MoneyFormat.approxRub(amount)}"

    /** «97 % плана». */
    fun ofPlan(percent: Int): String = "$percent$NBSP% плана"

    /** Под планом, если он взят из прошлого месяца (4.12). */
    const val PLAN_CARRIED = "как в прошлом месяце"

    /**
     * «До плана не хватает 10 800 ₽ — это 6 ПТ: со 151-й весь месяц пойдёт по ступени 3». Без перехода ступени —
     * без второй части; число ПТ не найдено (нет ставки ПТ · ББ) — только сумма.
     */
    fun shortfall(s: Shortfall): String {
        val head = "До плана не хватает ${MoneyFormat.rub(s.missing)}"
        val pts = s.pts ?: return head
        val tail = if (s.newTier != null && s.tierFrom != null) {
            ": со$NBSP${s.tierFrom}-й весь месяц пойдёт по ступени ${s.newTier}"
        } else {
            ""
        }
        return "$head — это ${RuText.digits(pts.toLong())}${NBSP}ПТ$tail"
    }

    /** Справа от «Ступень»: «1 сейчас · 2 по расписанию»; совпадают или месяц прошёл — «ступень 2». */
    fun tierHeader(t: TierProgress, period: MonthPeriod): String = when {
        period == MonthPeriod.PAST -> "ступень ${t.tier}"
        period == MonthPeriod.FUTURE || t.tier == t.forecastTier -> "ступень ${t.forecastTier}${if (period == MonthPeriod.FUTURE) " по расписанию" else ""}"
        else -> "${t.tier} сейчас$DOT${t.forecastTier} по расписанию"
    }

    /** Подписи полосы: «94 проведено», «51 в расписании». */
    fun tierDone(n: Int): String = "${RuText.digits(n.toLong())} проведено"

    fun tierPlanned(n: Int): String = "${RuText.digits(n.toLong())} в расписании"

    /** «До 101 ещё 7 ПТ — это +40 ₽ к каждой ПТ месяца.»; ступень 3 — `null`. */
    fun toNext(t: TierProgress): String? {
        val from = t.nextFrom ?: return null
        val left = t.toNext ?: return null
        val head = "До$NBSP$from ещё ${RuText.digits(left.toLong())}${NBSP}ПТ"
        return if (t.perPt != null && t.perPt > 0) "$head — это ${MoneyFormat.signedRub(t.perPt)} к каждой ПТ месяца." else "$head."
    }

    /** «По расписанию 145. Ещё 6 ПТ сверх него дадут 11 650 ₽: 6 000 ₽ за них и 5 650 ₽ — ступень 3 на весь месяц.» */
    fun beyond(forecastCount: Int, b: TierBeyond): String {
        val rest = b.gain - b.own
        val head = "По расписанию ${RuText.digits(forecastCount.toLong())}. Ещё ${RuText.digits(b.pts.toLong())}${NBSP}ПТ сверх него дадут ${MoneyFormat.rub(b.gain)}"
        return if (rest > 0) {
            "$head: ${MoneyFormat.rub(b.own)} за них и ${MoneyFormat.rub(rest)} — ступень ${b.tier} на весь месяц."
        } else {
            "$head."
        }
    }

    /** «Считаются: ПТ, сплит, грудничковое». */
    fun counted(names: List<String>): String? = names.takeIf { it.isNotEmpty() }?.let { "Считаются: ${it.joinToString(", ")}" }

    /** Справа от «Разбивки»: «по ступени 1». */
    fun byTier(tier: Int): String = "по ступени $tier"

    /** Подпись раздела разбивки: «В счёт ступени · 94 ПТ», «Стартовые», «Группы». */
    fun section(section: BreakdownSection, ptCount: Int): String = when (section) {
        BreakdownSection.PT -> "В счёт ступени$DOT${RuText.digits(ptCount.toLong())}${NBSP}ПТ"
        BreakdownSection.SPT -> "Стартовые"
        BreakdownSection.OTHER -> "Остальные"
        BreakdownSection.GROUPS -> "Группы"
        BreakdownSection.DUTY -> "Дежурства"
    }

    /** Заголовок строки разбивки: «ПТ · ББ», «Дежурства · ББ», «Закрыты покупкой блока». */
    fun lineTitle(line: BreakdownLine, type: String, place: String): String = when {
        line.sptClosed > 0 || (line.section == BreakdownSection.SPT && line.typeId == null) -> "Закрыты покупкой блока"
        else -> "$type$DOT$place"
    }

    /**
     * Пояснение строки: «61 × 920 ₽», «1 × 920 ₽ · один клиент, как ПТ», «моя группа · 5 трен. · 46 детей × 270 ₽»,
     * «группа коллеги: Лена · вдвоём · 3 трен. · 33 ребёнка · ½ от 8 910 ₽», «24 ч × 200 ₽», «вт 14.10 · за: Лена · 3 ч».
     * [colleague] — имя коллеги строки группы.
     */
    fun lineNote(line: BreakdownLine, colleague: String?): String {
        if (line.sptClosed > 0 || (line.section == BreakdownSection.SPT && line.typeId == null)) {
            return "доплата за ${RuText.count(line.sptClosed, "СПТ", "СПТ", "СПТ")}"
        }
        if (line.missing) return "${trainings(line.count)}$DOT${MISSING}"
        return when (line.section) {
            BreakdownSection.GROUPS -> groupNote(line, colleague ?: COLLEAGUE)
            BreakdownSection.DUTY -> {
                val hours = hours(line.minutes)
                val rate = line.unit?.let { " × ${MoneyFormat.rub(it)}" } ?: ""
                if (line.date != null) {
                    "${DateFormat.short(line.date)}${line.coverFor?.let { "${DOT}за: $it" } ?: ""}$DOT$hours$rate"
                } else {
                    "$hours$rate"
                }
            }
            else -> {
                val head = "${RuText.digits(line.count.toLong())} × ${MoneyFormat.rub(line.unit ?: 0)}"
                when (line.splitMark) {
                    SplitMark.AS_PT -> "$head${DOT}один клиент, как ПТ"
                    SplitMark.HALF -> "$head${DOT}один клиент, половина"
                    null -> head
                }
            }
        }
    }

    private const val MISSING = "нет ставки"

    private fun trainings(n: Int): String = RuText.count(n, "тренировка", "тренировки", "тренировок")

    private fun groupNote(line: BreakdownLine, colleague: String): String {
        val case = when (line.groupCase) {
            GroupCase.MINE_ALONE, null -> "моя группа"
            GroupCase.MINE_TOGETHER -> "моя группа${DOT}вдвоём: $colleague"
            GroupCase.MINE_REPLACED -> "моя группа${DOT}замена: $colleague"
            GroupCase.THEIRS_TOGETHER -> "группа коллеги: $colleague${DOT}вдвоём"
            GroupCase.THEIRS_BY_ME -> "группа коллеги: $colleague${DOT}замена"
        }
        val count = "${RuText.digits(line.count.toLong())}${NBSP}трен."
        val children = RuText.count(line.children, "ребёнок", "ребёнка", "детей")
        val money = when (line.groupCase) {
            GroupCase.MINE_TOGETHER, GroupCase.THEIRS_TOGETHER -> "½ от ${MoneyFormat.rub(line.gross)}"
            GroupCase.MINE_REPLACED -> "начислено ${MoneyFormat.rub(line.gross)}"
            else -> null
        }
        val childrenPart = if (money == null && line.unit != null) "$children × ${MoneyFormat.rub(line.unit)}" else children
        return listOfNotNull(case, count, childrenPart, money).joinToString(DOT)
    }

    /** Часы дежурств: «24 ч», «3 ч 30 мин», «45 мин» — без дней, как считает клуб. */
    fun hours(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m${NBSP}мин"
            m == 0 -> "$h${NBSP}ч"
            else -> "$h${NBSP}ч $m${NBSP}мин"
        }
    }

    /** Пояснение доплаты: входит ли в начисление клуба. */
    fun adjustmentNote(inClub: Boolean): String = if (inClub) IN_CLUB.lowercase() else NOT_IN_CLUB

    /** «Добавить доплату» / «Добавить удержание». */
    fun addAdjustment(deduction: Boolean): String = if (deduction) "Добавить удержание" else "Добавить доплату"

    /** «Заработано станет 128 645 ₽». */
    fun earnedWillBe(amount: Long): String = "Заработано станет ${MoneyFormat.rub(amount)}"

    /** «Доплата добавлена», «Удержание добавлено». */
    fun adjustmentSaved(deduction: Boolean): String = if (deduction) "Удержание добавлено" else "Доплата добавлена"

    fun adjustmentDeleted(deduction: Boolean): String = if (deduction) "Удержание удалено" else "Доплата удалена"

    /**
     * Под «Начислено клубом»: «по проведённым на вт 20.10 · половина официально»; у будущего месяца — «по расписанию»,
     * у прошедшего — только схема.
     */
    fun accruedNote(period: MonthPeriod, today: LocalDate, kind: PaySchemeKind): String {
        val scheme = SalarySetupFormat.scheme(kind).lowercase()
        return when (period) {
            MonthPeriod.PAST -> SalarySetupFormat.scheme(kind)
            MonthPeriod.CURRENT -> "по проведённым на ${DateFormat.short(today)}$DOT$scheme"
            MonthPeriod.FUTURE -> "проведённых ещё нет$DOT$scheme"
        }
    }

    /** «Официально · 50 %». */
    fun official(shareBp: Int): String = "Официально$DOT${percentBp(shareBp)}"

    /** Пояснение официальной части. */
    fun officialNote(shareBp: Int): String = if (shareBp in 1 until 10_000) "на карту; остальное — наличными" else "на карту"

    /** «НДФЛ 13 % · оценка». */
    fun ndfl(ndflBp: Int): String = "НДФЛ ${percentBp(ndflBp)}${DOT}оценка"

    /** Название выплаты в «Когда придут». */
    fun payout(kind: PayoutKind): String = when (kind) {
        PayoutKind.ADVANCE -> ADVANCE
        PayoutKind.CARD_REST -> CARD_REST
        PayoutKind.CASH -> CASH
        PayoutKind.WHOLE -> WHOLE
    }

    /** «54 679,50 ₽ на карту минус аванс». */
    fun cardRestNote(card: Long): String = "${MoneyFormat.rub(card)} на карту минус аванс"

    /** Дата выплаты: «вс 25.10». */
    fun payoutDate(date: LocalDate): String = DateFormat.short(date)

    /**
     * Итог взаиморасчёта с коллегой (4.8): «Лена отдаёт вам», «Вы отдаёте Лене». Пол коллеги приложение не знает,
     * поэтому глагол — без рода, а не «должна / должен»; имя склоняется [RuText.dative].
     */
    fun settlement(name: String, amount: Long): String =
        if (amount >= 0) "$name отдаёт вам" else "Вы отдаёте ${RuText.dative(name)}"

    /** «Если всё пройдёт по расписанию — ≈ 190 270 ₽». */
    fun ifScheduled(amount: Long): String = "Если всё пройдёт по расписанию — ${MoneyFormat.approxRub(amount)}"

    /** Заголовок окна аванса: «Аванс 25.10». */
    fun advanceTitle(date: LocalDate): String = "Аванс ${DateFormat.dayMonth(date)}"

    /** «Ожидался 13 546,50 ₽». */
    fun advanceExpected(amount: Long): String = "Ожидался ${MoneyFormat.rub(amount)}"

    const val ADVANCE_SAVED = "Аванс сохранён"

    // --- График по дням и «Месяц сверен» (шаг 3.7б) ---

    const val CHART = "По дням"
    const val CHART_EARNED = "заработано"
    const val CHART_FORECAST = "прогноз"
    const val CHART_PACE = "темп плана"

    /** Подпись линии сетки: «100 тыс.»; шаг меньше 10 000 ₽ — «5 000 ₽». */
    fun axis(kopecks: Long): String {
        val rub = kopecks / 100
        return if (rub % 1000 == 0L && rub >= 10_000) "${RuText.digits(rub / 1000)}${NBSP}тыс." else MoneyFormat.rub(kopecks)
    }

    /** Для TalkBack: «График по дням: заработано 126 645 ₽ к 20.10, прогноз ≈ 203 490 ₽, план 210 000 ₽». */
    fun chartDescription(earned: Long, day: LocalDate?, forecast: Long?, plan: Long?): String = buildList {
        add("заработано ${MoneyFormat.rub(earned)}" + (day?.let { " к ${DateFormat.dayMonth(it)}" } ?: ""))
        forecast?.let { add("прогноз ${MoneyFormat.approxRub(it)}") }
        plan?.let { add("план ${MoneyFormat.rub(it)}") }
    }.joinToString(", ", prefix = "График по дням: ")

    const val CLOSE_MONTH = "Месяц сверен"
    const val REOPEN_MONTH = "Снять сверку"
    const val MONTH_CLOSED = "Сверен"

    /** Под кнопкой: «После сверки правки тренировок октября спросят подтверждение». */
    fun closeNote(month: YearMonth): String = "После сверки правки тренировок ${DateFormat.monthGenitive(month.firstDay)} спросят подтверждение"

    /** У сверенного: «Правки тренировок октября спрашивают подтверждение». */
    fun closedNote(month: YearMonth): String = "Правки тренировок ${DateFormat.monthGenitive(month.firstDay)} спрашивают подтверждение"

    /** «Октябрь сверен». */
    fun closedMessage(month: YearMonth): String = "${DateFormat.month(month.firstDay).replaceFirstChar { it.uppercaseChar() }} сверен"

    const val REOPENED = "Сверка снята"

    /** Под отметкой «Сверен» в «Зарплате»: сверки с шага 3.9в — отдельный раздел. */
    const val CLOSED_WHERE = "Снять сверку — в «Ещё → Сверки»"

    // --- Заработок за день и за период (шаг 3.7в) ---

    const val PERIOD = "Период"
    const val PERIOD_TITLE = "Заработок за период"
    const val PERIOD_FROM = "С"
    const val PERIOD_TO = "По"
    const val PERIOD_EARNED = "Заработано за период"
    const val PERIOD_NOTE = "Проведённые тренировки, каждая — по ступени своего месяца"
    const val PERIOD_REVERSED = "Дата «С» позже, чем «По»"
    const val PERIOD_TOO_LONG = "Период — не больше года"
    const val PERIOD_EMPTY = "За эти дни ничего не заработано"
    const val PERIOD_MONTHS = "По месяцам"
    const val CLOSE = "Закрыть"
    const val DAY_HINT = "Нажмите на день графика — покажу, что заработано в этот день"
    const val DAY_EMPTY = "В этот день ничего не заработано"
    const val DAY_SPT = "Доплата за СПТ — блок куплен"

    /** Над списком дня: «вт 22.10 — 3 680 ₽». */
    fun dayTitle(date: LocalDate, total: Long): String = "${DateFormat.short(date)} — ${MoneyFormat.rub(total)}"

    /** Тренировка дня: «10:00 ПТ · ББ». */
    fun dayTraining(time: LocalTime, type: String, place: String): String =
        "${DateFormat.time(time)} " + listOf(type, place).filter { it.isNotBlank() }.joinToString(DOT)

    /** Часть периода: «Октябрь» целиком, «Октябрь, 1–25» — часть месяца. */
    fun periodPart(part: PeriodPart): String {
        val name = DateFormat.month(part.month.firstDay).replaceFirstChar { it.uppercaseChar() }
        if (part.whole) return name
        return if (part.from == part.to) "$name, ${part.from.day}" else "$name, ${part.from.day}${RuText.DASH}${part.to.day}"
    }

    /** Под заголовком окна периода: «01.10.2026 – 25.10.2026 · 25 дней». */
    fun periodRange(from: LocalDate, to: LocalDate): String {
        val days = to.toEpochDays() - from.toEpochDays() + 1
        return "${DateFormat.full(from)} ${RuText.DASH} ${DateFormat.full(to)}$DOT${RuText.count(days, "день", "дня", "дней")}"
    }

    // --- Год (шаг 3.8б) ---

    const val TAB_YEAR = "Год"
    const val YEAR_ON_HAND = "на руки"
    const val YEAR_TAX = "налог"
    const val YEAR_PT = "ПТ"
    const val YEAR_PLAN = "план"
    const val MONTH_GOING = "идёт"
    const val YEAR_EMPTY = "В этом году заработка ещё нет. Месяцы появятся здесь, как только будут проведённые тренировки."
    const val YEAR_NO_PLAN = "без плана"

    /** «Заработано за 2026». */
    fun yearTitle(year: Int): String = "Заработано за $year"

    /** «план выполнен в 5 из 8 месяцев · октябрь идёт»; без плана — только «октябрь идёт». */
    fun yearPlan(y: SalaryYear): String? = buildList {
        if (y.planMonths > 0) {
            val months = if (y.planMonths % 10 == 1 && y.planMonths % 100 != 11) "месяца" else "месяцев"
            add("план выполнен в ${y.planMetMonths} из ${y.planMonths} $months")
        }
        if (y.current != null && y.rows.any { it.month == y.current }) add("${DateFormat.month(y.current.firstDay)} $MONTH_GOING")
    }.takeIf { it.isNotEmpty() }?.joinToString(DOT)

    /** Месяц на разложенном Fold слева (шаг 3.8а), для TalkBack: «Октябрь: 126 645 ₽, 60 % плана». */
    fun yearSideDescription(row: YearMonthRow): String = buildList {
        add(MoneyFormat.rub(row.earned))
        add(row.percent?.let { ofPlan(it) } ?: YEAR_NO_PLAN)
        if (row.period == MonthPeriod.CURRENT) add(MONTH_GOING)
    }.joinToString(", ", prefix = "${monthName(row.month)}: ")

    /** Подпись столбца: «янв», «май». */
    fun monthShort(month: YearMonth): String = DateFormat.month(month.firstDay).take(3)

    /** Название месяца в списке: «Октябрь». */
    fun monthName(month: YearMonth): String = DateFormat.month(month.firstDay).replaceFirstChar { it.uppercaseChar() }

    /** Под месяцем года: «ПТ 94 · детей 125 · дежурств 24 ч · налог 8 170,50 ₽ · на руки 118 413,08 ₽»; нули не пишутся. */
    fun yearRow(row: YearMonthRow): String = buildList {
        if (row.ptCount > 0) add("ПТ ${RuText.digits(row.ptCount.toLong())}")
        if (row.groupChildren > 0) add("детей ${RuText.digits(row.groupChildren.toLong())}")
        if (row.dutyMinutes > 0) add("дежурств ${hours(row.dutyMinutes)}")
        if (row.ndfl != 0L) add("налог ${MoneyFormat.rub(row.ndfl)}")
        add("на руки ${MoneyFormat.rub(row.onHand)}")
    }.joinToString(DOT)

    /** Итог года одной строкой для TalkBack и подписи: «ПТ 1 111 · детей 640 · дежурств 120 ч». */
    fun yearCounts(y: SalaryYear): String? = buildList {
        if (y.groupChildren > 0) add("детей в группах ${RuText.digits(y.groupChildren.toLong())}")
        if (y.dutyMinutes > 0) add("дежурств ${hours(y.dutyMinutes)}")
    }.takeIf { it.isNotEmpty() }?.joinToString(DOT)

    /** Для TalkBack: «Октябрь: заработано 126 645 ₽, прогноз ≈ 203 490 ₽, план 210 000 ₽». */
    fun yearBarDescription(row: YearMonthRow): String = buildList {
        add("заработано ${MoneyFormat.rub(row.earned)}")
        row.forecast?.let { add("прогноз ${MoneyFormat.approxRub(it)}") }
        row.plan?.let { add("план ${MoneyFormat.rub(it)}") }
    }.joinToString(", ", prefix = "${monthName(row.month)}: ")

    /** «13 %», «50 %», «6,5 %» — из десятитысячных. */
    private fun percentBp(bp: Int): String {
        val whole = bp / 100
        val rest = bp % 100
        val text = if (rest == 0) "$whole" else "$whole,${rest.toString().padStart(2, '0').trimEnd('0')}"
        return "$text$NBSP%"
    }
}
