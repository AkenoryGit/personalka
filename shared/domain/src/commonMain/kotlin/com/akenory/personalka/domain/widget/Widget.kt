package com.akenory.personalka.domain.widget

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.HomeFormat
import com.akenory.personalka.domain.format.MoneyFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.home.HomeRules
import com.akenory.personalka.domain.home.HomeSlot
import com.akenory.personalka.domain.money.PlanPace
import com.akenory.personalka.domain.money.Payout
import com.akenory.personalka.domain.money.SalaryMonth
import com.akenory.personalka.domain.money.SalaryPlan
import com.akenory.personalka.domain.watch.WatchSlot
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/**
 * «Деньги» для виджета (шаг 5.9): готовые строки — те же функции, что у карточки «Деньги» на «Главной» (ТЗ 5.2), чтобы
 * цифры в виджете и в приложении не расходились. Полоса плана — в тысячных долях плана: [done] проведено, [forecast]
 * прогноз; плана нет — `null`, полосы и оценки нет.
 */
data class WidgetMoney(
    val title: String,
    val monthName: String,
    val tierChip: String?,
    val tier: Int,
    val today: String,
    val todayWill: String,
    val todayPt: String,
    val todayCancelled: String?,
    val month: String,
    val monthWill: String,
    val monthPt: String,
    val monthCancelled: String?,
    val done: Int?,
    val forecast: Int?,
    val pace: String?,
    val paceShort: String?,
    val behind: Boolean,
    val ptTier: String,
    val todaySigned: String = today,
    val planPercent: String? = null,
    val poolNote: String? = null,
    val planDone: Boolean = false,
    val ptCount: Int = 0,
    val tierNext: Int? = null,
    val tierToNext: Int? = null,
    val hasTiers: Boolean = false,
    val paydayDate: LocalDate? = null,
    val paydayAmount: String? = null,
    val paydayFrom: LocalDate? = null,
)

/** Настроение талисмана «Тюля» в ярком виджете (шаг 5.9б): машет ластой или спит. */
enum class WidgetMood { WAVE, SLEEP }

/** Талисман: крупная фраза [title], строка [line] и плашка [pill] — имя следующей или заработок дня. */
data class WidgetMascot(val mood: WidgetMood, val title: String, val line: String, val pill: String?)

/** Ближайшая выплата: [date], сумма [amount] в копейках, прошлая выплата [from]. */
data class WidgetPayday(val date: LocalDate, val amount: Long, val from: LocalDate?)

/**
 * Тренировка в виджете: то же, что на часах, плюс ключ цвета [color] — полоска слева, как в расписании «Главной».
 */
data class WidgetSlot(val slot: WatchSlot, val color: String)

/**
 * Что приложение кладёт для виджетов (шаг 5.9): расписание на несколько дней — виджет сам переключает «Сейчас» и
 * «Следующую» по времени; деньги дня [date]; счётчик дел [todo]. Зарплата не настроена — [money] `null`.
 */
data class WidgetDay(
    val date: LocalDate,
    val slots: List<WidgetSlot>,
    val money: WidgetMoney?,
    val todo: Int = 0,
)

/** Строка расписания под «Сейчас»: «12:00 ▍ Морозов Д. · ПТ · ББ». */
data class WidgetRow(val id: String, val time: String, val name: String, val typePlace: String, val color: String)

/** Состояние «Ближайшей» в виджете. */
enum class WidgetPhase { NOW, NEXT, DONE, EMPTY }

/**
 * Что показывает виджет в минуту [WidgetRules.view]: шапка «Сейчас · 11:00–11:50» и справа «до 11:50»; имя и
 * «ПТ · ББ»; [progress] — сколько прошло у идущей, в тысячных; [next] — следующие сегодня, сколько влезает, и [more] — «ещё 7 ·
 * последняя в 20:00». Сегодня всё — шапка «Сегодня всё · завтра первая», ниже первая следующая.
 */
data class WidgetView(
    val phase: WidgetPhase,
    val header: String,
    val right: String?,
    val label: String,
    val id: String?,
    val name: String?,
    val typePlace: String?,
    val color: String?,
    val whenText: String?,
    val progress: Int?,
    val next: List<WidgetRow>,
    val more: String?,
    val count: String?,
    val money: WidgetMoney?,
    val todo: Int?,
    val todayTotal: Int = 0,
    val todayDone: Int = 0,
    val laneSub: String? = null,
    val dayLine: String? = null,
    val mascot: WidgetMascot? = null,
)

/** Правила виджетов (шаг 5.9). Выбор «Ближайшей» — тем же правилом, что на «Главной» (ТЗ 5.2). */
object WidgetRules {
    private const val DOT = " · "

    /** Сколько следующих тренировок под «Сейчас» у большого виджета наименьшей высоты; выше — больше (шаг 5.9а). */
    const val NEXT_ROWS: Int = 2

    /** Больше строк большой виджет не показывает: остальное — в «ещё N · последняя в …». */
    const val MAX_ROWS: Int = 8

    /** Пока тренировка идёт, полоска хода обновляется так часто — минутами. */
    const val RUNNING_REFRESH_MIN: Int = 10

    /** Деньги для виджета из месяца «Главной» (зарплата настроена, месяц текущий — проверяет приложение). */
    fun money(view: SalaryMonth, today: LocalDate, payouts: List<Payout> = emptyList()): WidgetMoney {
        val earnedToday = view.today ?: 0
        val donePt = view.todayPt ?: 0
        val plan = view.plan
        val pace = SalaryPlan.pace(view.pay.earned, view.pay.forecast, plan)
        val (done, forecast) = if (plan != null && plan > 0 && pace != null) {
            val d = permille(view.pay.earned, plan)
            d to maxOf(d, permille(view.pay.forecast, plan))
        } else {
            null to null
        }
        val tier = view.tiers?.tier ?: 1
        return WidgetMoney(
            title = HomeFormat.moneyTitle(today),
            monthName = DateFormat.month(today).replaceFirstChar { it.uppercaseChar() },
            tierChip = HomeFormat.tierChip(view.tiers),
            tier = tier,
            today = MoneyFormat.rub(earnedToday),
            todayWill = HomeFormat.willBe(earnedToday, earnedToday + (view.todayAhead ?: 0)),
            todayPt = HomeFormat.ptLine(donePt, donePt + (view.todayPtAhead ?: 0)),
            todayCancelled = HomeFormat.cancelledLine(view.todayCancelled ?: 0),
            month = MoneyFormat.rub(view.pay.earned),
            monthWill = HomeFormat.willBe(view.pay.earned, view.pay.forecast),
            monthPt = HomeFormat.ptLine(view.pay.ptCount, view.pay.likelyPtCount),
            monthCancelled = HomeFormat.cancelledLine(view.monthCancelled),
            done = done,
            forecast = forecast,
            pace = pace?.let { HomeFormat.pace(it, view.percent ?: 0, view.forecastPercent ?: 0, view.shortfall?.pts) },
            paceShort = pace?.let { paceShort(it, view.percent ?: 0, view.forecastPercent ?: 0) },
            behind = pace == PlanPace.BEHIND,
            ptTier = RuText.digits(view.pay.ptCount.toLong()) + NBSP + "ПТ" + (view.tiers?.let { DOT + "ступень " + it.tier } ?: ""),
            todaySigned = MoneyFormat.signedRub(earnedToday),
            planPercent = view.percent?.let { "$it$NBSP% плана" },
            poolNote = pace?.let { poolNote(it, view.forecastPercent ?: 0) },
            planDone = pace == PlanPace.DONE,
            ptCount = view.pay.ptCount,
            tierNext = view.tiers?.nextFrom,
            tierToNext = view.tiers?.toNext,
            hasTiers = view.tiers != null,
        ).withPayday(payday(payouts, today))
    }

    /**
     * Ближайшая выплата для «До получки» (шаг 5.9б): из выплат прошлого и текущего месяца — первая с сегодняшнего дня,
     * сумма всех выплат этого дня (остаток на карту и наличные 10-го — вместе); [WidgetPayday.from] — прошлая выплата,
     * от неё полоска дней.
     */
    fun payday(payouts: List<Payout>, today: LocalDate): WidgetPayday? {
        val paid = payouts.filter { it.amount > 0 }
        val next = paid.filter { it.date >= today }.minByOrNull { it.date } ?: return null
        val amount = paid.filter { it.date == next.date }.sumOf { it.amount }
        return WidgetPayday(next.date, amount, paid.filter { it.date < today }.maxOfOrNull { it.date })
    }

    private fun WidgetMoney.withPayday(p: WidgetPayday?): WidgetMoney =
        if (p == null) this else copy(paydayDate = p.date, paydayAmount = MoneyFormat.approxRub(p.amount), paydayFrom = p.from)

    /** «Бассейн плана»: «Прогноз 115 % — план переплывёте», выполнен — «План выполнен — вода через край». */
    fun poolNote(pace: PlanPace, forecastPercent: Int): String = when (pace) {
        PlanPace.DONE -> "План выполнен — вода через край"
        PlanPace.ON_TRACK -> "Прогноз $forecastPercent$NBSP% — план переплывёте"
        PlanPace.BEHIND -> "Прогноз $forecastPercent$NBSP% — поднажмём"
    }

    /** «До получки»: крупное число дней и подпись — «2» «дня»; в день выплаты — пусто и «сегодня!». */
    fun paydayDays(date: LocalDate, today: LocalDate): Pair<String, String> {
        val days = today.daysUntil(date)
        return if (days <= 0) "" to "сегодня!" else RuText.digits(days.toLong()) to RuText.plural(days.toLong(), "день", "дня", "дней")
    }

    /** Под числом дней: «10.11 · ≈ 103 980 ₽». */
    fun paydayLine(m: WidgetMoney): String? {
        val date = m.paydayDate ?: return null
        return DateFormat.dayMonth(date) + DOT + m.paydayAmount.orEmpty()
    }

    /** Полоска «До получки»: всего дней между выплатами и сколько прошло; прошлой выплаты нет — `null`. */
    fun paydayBar(m: WidgetMoney, today: LocalDate): Pair<Int, Int>? {
        val from = m.paydayFrom ?: return null
        val to = m.paydayDate ?: return null
        val total = from.daysUntil(to)
        if (total <= 0) return null
        return total to from.daysUntil(today).coerceIn(0, total)
    }

    /**
     * «Заряд бонуса»: доля кольца в тысячных, крупно «63 ПТ» и подпись «до бонуса»; на ступени 2 — «до ступени 3»,
     * на последней — полное кольцо и «максимум». Ступеней нет — `null`.
     */
    fun bonus(m: WidgetMoney): Triple<Int, String, String>? {
        if (!m.hasTiers) return null
        val next = m.tierNext
        val left = m.tierToNext
        if (next == null || left == null) return Triple(1000, "Ступень " + m.tier, "максимум")
        val part = (m.ptCount.coerceAtLeast(0) * 1000 / next.coerceAtLeast(1)).coerceIn(0, 1000)
        return Triple(part, RuText.digits(left.toLong()) + NBSP + "ПТ", if (m.tier <= 1) "до бонуса" else "до ступени " + (m.tier + 1))
    }

    /** Короткая оценка для маленького виджета: «прогноз 115 % плана», «выполнено 104 %». */
    fun paceShort(pace: PlanPace, percent: Int, forecastPercent: Int): String = when (pace) {
        PlanPace.DONE -> "выполнено $percent$NBSP%"
        else -> "прогноз $forecastPercent$NBSP% плана"
    }

    private fun permille(part: Long, whole: Long): Int = ((part * 1000) / whole).coerceIn(0, 1000).toInt()

    /**
     * Что показать в минуту [now]; под «Сейчас» — до [rows] следующих сегодня. Деньги и дела — только за свой день:
     * вчерашние не показываются.
     */
    fun view(day: WidgetDay, now: LocalDateTime, rows: Int = NEXT_ROWS): WidgetView {
        val today = now.date
        val active = day.slots
        val nearest = nearest(active, now)
        val money = day.money.takeIf { day.date == today }
        val todo = day.todo.takeIf { day.date == today && it > 0 }
        val todaySlots = active.filter { it.slot.start.date == today }
        val todayTotal = todaySlots.size
        val todayDone = todaySlots.count { it.slot.marked || it.slot.end <= now }
        val dayLine = if (todayTotal == 0) null else RuText.digits(todayDone.toLong()) + " из " + RuText.digits(todayTotal.toLong()) + DOT + when (todayDone) {
            0 -> "день впереди"
            todayTotal -> "день закрыт!"
            else -> "так держать"
        }
        if (nearest == null) {
            return WidgetView(
                phase = WidgetPhase.EMPTY, header = HomeFormat.todayDone(null, today), right = null, label = TODAY_DONE,
                id = null, name = null, typePlace = null, color = null, whenText = null, progress = null,
                next = emptyList(), more = null, count = null, money = money, todo = todo,
                todayTotal = todayTotal, todayDone = todayDone, dayLine = dayLine,
                mascot = if (todayTotal > 0) {
                    WidgetMascot(WidgetMood.SLEEP, TODAY_DONE, "Отдыхайте! Впереди тренировок нет", money?.let { it.todaySigned + " за день" })
                } else {
                    WidgetMascot(WidgetMood.SLEEP, "Выходной", "Тренировок впереди нет", null)
                },
            )
        }
        val s = nearest.slot
        if (s.start.date != today) {
            val dayCount = active.count { it.slot.start.date == s.start.date }
            return WidgetView(
                phase = WidgetPhase.DONE,
                header = TODAY_DONE + DOT + firstLabel(s.start.date, today),
                right = DateFormat.short(s.start.date) + NBSP + DateFormat.time(s.start.time),
                label = TODAY_DONE,
                id = s.id, name = name(s), typePlace = typePlace(s), color = nearest.color,
                whenText = firstLabel(s.start.date, today),
                progress = null, next = emptyList(),
                more = dayLabel(s.start.date, today) + " " + RuText.count(dayCount, "тренировка", "тренировки", "тренировок"),
                count = dayLabel(s.start.date, today) + " " + RuText.digits(dayCount.toLong()),
                money = money, todo = todo,
                todayTotal = todayTotal, todayDone = todayDone, dayLine = dayLine,
                laneSub = typePlace(s) + DOT + firstLabel(s.start.date, today) + " в" + NBSP + DateFormat.time(s.start.time),
                mascot = WidgetMascot(
                    WidgetMood.SLEEP,
                    TODAY_DONE,
                    "Отдыхайте! " + HomeFormat.todayDone(s.start, today).removePrefix("Сегодня всё. "),
                    money?.takeIf { todayTotal > 0 }?.let { it.todaySigned + " за день" },
                ),
            )
        }
        val running = now >= s.start
        val later = laterToday(active, nearest, now)
        val fit = rows.coerceIn(0, MAX_ROWS)
        val shown = later.take(fit)
        val rest = later.drop(fit)
        val todayCount = day.slots.count { it.slot.start.date == today }
        return WidgetView(
            phase = if (running) WidgetPhase.NOW else WidgetPhase.NEXT,
            header = (if (running) HomeFormat.NOW else NEXT) + DOT + DateFormat.timeRange(s.start, s.end),
            right = if (running) "до" + NBSP + DateFormat.time(s.end.time) else "в" + NBSP + DateFormat.time(s.start.time),
            label = if (running) HomeFormat.NOW else NEXT,
            id = s.id, name = name(s), typePlace = typePlace(s), color = nearest.color,
            whenText = if (running) "до" + NBSP + DateFormat.time(s.end.time) else "в" + NBSP + DateFormat.time(s.start.time),
            progress = if (running) progress(s, now) else null,
            next = shown.map { row(it) },
            more = if (rest.isEmpty()) {
                null
            } else {
                "ещё " + RuText.digits(rest.size.toLong()) + DOT + "последняя в" + NBSP + DateFormat.time(rest.last().slot.start.time)
            },
            count = "сегодня " + RuText.digits(todayCount.toLong()),
            money = money, todo = todo,
            todayTotal = todayTotal, todayDone = todayDone, dayLine = dayLine,
            laneSub = typePlace(s) + if (later.isEmpty()) DOT + "последняя" else DOT + "ещё " + RuText.digits(later.size.toLong()) + " сегодня",
            mascot = mascot(running, todayDone, todayTotal, later.size, s, money),
        )
    }

    /**
     * Когда перерисовать виджет: ближайшее из начала и конца тренировок, полуночи и — пока тренировка идёт — через
     * [RUNNING_REFRESH_MIN] минут для полоски хода. Деньги после конца тренировки пересчитывает приложение.
     */
    fun nextUpdate(day: WidgetDay, now: LocalDateTime): LocalDateTime {
        val midnight = LocalDateTime(now.date.plus(1, DateTimeUnit.DAY), LocalTime(0, 0))
        val edges = day.slots.flatMap { listOf(it.slot.start, it.slot.end) }.filter { it > now }
        val running = day.slots.any { it.slot.start <= now && now < it.slot.end && !it.slot.marked }
        val tick = if (running) ClubTime.plusMinutes(now, RUNNING_REFRESH_MIN) else null
        return (edges + listOfNotNull(tick, midnight)).min()
    }

    /** Талисман днём: утром до первой — «Поехали!», во время — «Плывём!», между — «Отлично!» или «Поднажмём!». */
    private fun mascot(running: Boolean, done: Int, total: Int, later: Int, s: WatchSlot, money: WidgetMoney?): WidgetMascot {
        val pill = name(s) + DOT + typePlace(s)
        val left = if (later == 0) "последняя" else "ещё " + RuText.digits(later.toLong())
        return when {
            running -> WidgetMascot(WidgetMood.WAVE, "Плывём!", "до" + NBSP + DateFormat.time(s.end.time) + DOT + left, pill)
            done == 0 -> WidgetMascot(
                WidgetMood.WAVE,
                "Поехали!",
                "Сегодня " + RuText.count(total, "тренировка", "тренировки", "тренировок") + ", первая в" + NBSP + DateFormat.time(s.start.time),
                pill,
            )
            money?.behind == true -> WidgetMascot(WidgetMood.WAVE, "Поднажмём!", "Дальше в" + NBSP + DateFormat.time(s.start.time) + DOT + left, pill)
            else -> WidgetMascot(WidgetMood.WAVE, "Отлично!", "Дальше в" + NBSP + DateFormat.time(s.start.time) + DOT + left, pill)
        }
    }

    /** iPhone: шаг кадров, пока идёт тренировка, — для Тюли на «Дорожке» и строки дня. */
    const val TIMELINE_TICK_MIN: Int = 5

    /** iPhone: больше кадров не готовим — система всё равно перечитывает виджет раз в несколько часов. */
    const val TIMELINE_MAX: Int = 240

    /**
     * Минуты, в которые iPhone сменит кадр виджета (шаг 5.10): сейчас, начало и конец каждой тренировки, полночь
     * каждого дня расписания и, пока тренировка идёт, каждые [TIMELINE_TICK_MIN] минут. На Android вместо этого —
     * будильник ([nextUpdate]): iPhone не будит приложение, кадры готовятся заранее.
     */
    fun timeline(day: WidgetDay, now: LocalDateTime): List<LocalDateTime> {
        val out = mutableSetOf<LocalDateTime>()
        out += ClubTime.toMinute(now)
        day.slots.forEach { w ->
            val s = w.slot
            if (s.start > now) out += s.start
            if (s.end > now) out += s.end
            var t = ClubTime.plusMinutes(s.start, TIMELINE_TICK_MIN)
            while (t < s.end) {
                if (t > now) out += t
                t = ClubTime.plusMinutes(t, TIMELINE_TICK_MIN)
            }
        }
        var d = now.date.plus(1, DateTimeUnit.DAY)
        repeat(WIDGET_DAYS) {
            out += LocalDateTime(d, LocalTime(0, 0))
            d = d.plus(1, DateTimeUnit.DAY)
        }
        return out.sorted().take(TIMELINE_MAX)
    }

    private const val WIDGET_DAYS = 3

    /** «Дорожка»: подпись над именем — «СЕЙЧАС», «СЛЕДУЮЩАЯ», «СЕГОДНЯ ВСЁ», «ТРЕНИРОВОК НЕТ». */
    fun laneLabel(phase: WidgetPhase): String = when (phase) {
        WidgetPhase.NOW -> "СЕЙЧАС"
        WidgetPhase.NEXT -> "СЛЕДУЮЩАЯ"
        WidgetPhase.DONE -> "СЕГОДНЯ ВСЁ"
        WidgetPhase.EMPTY -> "ТРЕНИРОВОК НЕТ"
    }

    /** «Дорожка»: где Тюля на дорожке, в тысячных — идущая по ходу, следующая на старте, остальное на финише. */
    fun laneProgress(view: WidgetView): Int = when (view.phase) {
        WidgetPhase.NOW -> view.progress ?: 0
        WidgetPhase.NEXT -> 0
        else -> 1000
    }

    const val PAYDAY = "до получки"
    const val NO_PAYDAY = "Выплат впереди нет"
    const val NO_TIERS = "Ступеней нет"
    const val TODAY = "Сегодня"
    const val NO_PLAN = "План не задан"
    const val NO_TRAININGS_TODAY = "Сегодня тренировок нет"

    const val NEXT = "Следующая"
    const val TODAY_DONE = "Сегодня всё"
    const val TODO = "Нужно сделать"
    const val NO_DATA = "Откройте Персоналку"
    const val SALARY_NOT_SET = HomeFormat.SALARY_NOT_SET

    private fun firstLabel(date: LocalDate, today: LocalDate): String =
        if (date == today.plus(1, DateTimeUnit.DAY)) "завтра первая" else "следующая " + DateFormat.short(date)

    private fun dayLabel(date: LocalDate, today: LocalDate): String =
        if (date == today.plus(1, DateTimeUnit.DAY)) "завтра" else DateFormat.short(date)

    private fun name(s: WatchSlot): String = s.name ?: s.typeShort

    private fun typePlace(s: WatchSlot): String = listOf(s.typeShort, s.placeShort).filter { it.isNotEmpty() }.joinToString(DOT)

    private fun row(w: WidgetSlot) = WidgetRow(w.slot.id, DateFormat.time(w.slot.start.time), name(w.slot), typePlace(w.slot), w.color)

    private fun progress(s: WatchSlot, now: LocalDateTime): Int {
        val total = s.durationMin.coerceAtLeast(1)
        val gone = ClubTime.minutesBetween(s.start, now).coerceIn(0L, total.toLong()).toInt()
        return gone * 1000 / total
    }

    private fun nearest(slots: List<WidgetSlot>, now: LocalDateTime): WidgetSlot? {
        val id = HomeRules.nearest(slots.map { it.home() }, now)?.id ?: return null
        return slots.first { it.slot.id == id }
    }

    private fun laterToday(slots: List<WidgetSlot>, nearest: WidgetSlot, now: LocalDateTime): List<WidgetSlot> {
        val ids = HomeRules.laterToday(slots.map { it.home() }, nearest.home(), now.date).map { it.id }.toSet()
        return slots.filter { it.slot.id in ids && !it.slot.marked }.sortedWith(compareBy({ it.slot.start }, { it.slot.id }))
    }

    private fun WidgetSlot.home() = HomeSlot(slot.id, slot.start, slot.durationMin, slot.group, cancelled = false, marked = slot.marked)
}

/**
 * Передача [WidgetDay] от приложения виджетам (шаг 5.9): простой текст, как у часов. Первая строка — версия, день и
 * дела; вторая — деньги (пустая — зарплата не настроена); дальше — тренировка на строку. Чужая версия — `null`.
 */
object WidgetCodec {
    private const val VERSION = "g2"
    private const val TAB = '\t'

    fun encode(day: WidgetDay): String = buildString {
        append(listOf(VERSION, day.date.toString(), day.todo.toString()).joinToString(TAB.toString()))
        append('\n')
        day.money?.let { m ->
            append(
                listOf(
                    m.title, m.monthName, m.tierChip, m.tier, m.today, m.todayWill, m.todayPt, m.todayCancelled,
                    m.month, m.monthWill, m.monthPt, m.monthCancelled, m.done, m.forecast, m.pace, m.paceShort,
                    if (m.behind) "1" else "0", m.ptTier,
                    m.todaySigned, m.planPercent, m.poolNote, if (m.planDone) "1" else "0", m.ptCount, m.tierNext, m.tierToNext,
                    if (m.hasTiers) "1" else "0", m.paydayDate, m.paydayAmount, m.paydayFrom,
                ).joinToString(TAB.toString()) { it?.toString()?.let(::clean).orEmpty() },
            )
        }
        day.slots.forEach { w ->
            val s = w.slot
            append('\n')
            append(
                listOf(
                    s.id, s.start.toString(), s.durationMin.toString(), clean(s.typeShort), clean(s.placeShort),
                    s.name?.let(::clean).orEmpty(), flag(s.group), flag(s.marked), clean(w.color),
                ).joinToString(TAB.toString()),
            )
        }
    }

    fun decode(text: String): WidgetDay? = runCatching {
        val lines = text.split('\n')
        val head = lines[0].split(TAB)
        if (head[0] != VERSION) return null
        val m = lines[1].takeIf { it.isNotEmpty() }?.split(TAB)
        WidgetDay(
            date = LocalDate.parse(head[1]),
            todo = head[2].toInt(),
            money = m?.let {
                WidgetMoney(
                    title = it[0], monthName = it[1], tierChip = it[2].ifEmpty { null }, tier = it[3].toInt(),
                    today = it[4], todayWill = it[5], todayPt = it[6], todayCancelled = it[7].ifEmpty { null },
                    month = it[8], monthWill = it[9], monthPt = it[10], monthCancelled = it[11].ifEmpty { null },
                    done = it[12].toIntOrNull(), forecast = it[13].toIntOrNull(),
                    pace = it[14].ifEmpty { null }, paceShort = it[15].ifEmpty { null }, behind = it[16] == "1",
                    ptTier = it[17],
                    todaySigned = it[18], planPercent = it[19].ifEmpty { null }, poolNote = it[20].ifEmpty { null },
                    planDone = it[21] == "1", ptCount = it[22].toInt(), tierNext = it[23].toIntOrNull(),
                    tierToNext = it[24].toIntOrNull(), hasTiers = it[25] == "1",
                    paydayDate = it[26].ifEmpty { null }?.let(LocalDate::parse), paydayAmount = it[27].ifEmpty { null },
                    paydayFrom = it[28].ifEmpty { null }?.let(LocalDate::parse),
                )
            },
            slots = lines.drop(2).filter { it.isNotEmpty() }.map { line ->
                val f = line.split(TAB)
                WidgetSlot(
                    WatchSlot(
                        id = f[0],
                        start = LocalDateTime.parse(f[1]),
                        durationMin = f[2].toInt(),
                        typeShort = f[3],
                        placeShort = f[4],
                        name = f[5].ifEmpty { null },
                        group = f[6] == "1",
                        marked = f[7] == "1",
                    ),
                    color = f[8],
                )
            },
        )
    }.getOrNull()

    private fun clean(s: String) = s.replace(TAB, ' ').replace('\n', ' ').replace('\r', ' ')

    private fun flag(b: Boolean) = if (b) "1" else "0"
}
