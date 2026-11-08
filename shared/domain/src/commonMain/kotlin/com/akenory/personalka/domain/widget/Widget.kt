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
import com.akenory.personalka.domain.money.SalaryMonth
import com.akenory.personalka.domain.money.SalaryPlan
import com.akenory.personalka.domain.watch.WatchSlot
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
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
)

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
    fun money(view: SalaryMonth, today: LocalDate): WidgetMoney {
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
        )
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
        if (nearest == null) {
            return WidgetView(
                phase = WidgetPhase.EMPTY, header = HomeFormat.todayDone(null, today), right = null, label = TODAY_DONE,
                id = null, name = null, typePlace = null, color = null, whenText = null, progress = null,
                next = emptyList(), more = null, count = null, money = money, todo = todo,
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
    private const val VERSION = "g1"
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
