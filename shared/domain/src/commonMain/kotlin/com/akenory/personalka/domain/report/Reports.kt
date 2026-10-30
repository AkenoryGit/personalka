package com.akenory.personalka.domain.report

import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.GroupCase
import com.akenory.personalka.domain.money.MonthInput
import com.akenory.personalka.domain.money.MonthPay
import com.akenory.personalka.domain.money.PayRules
import com.akenory.personalka.domain.money.SalaryMonthRules
import com.akenory.personalka.domain.money.Versions
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

// «Отчёты» (ТЗ 5.15, шаг 5.3и): сколько каких тренировок проведено, списано и отменено за период. Денег здесь нет.
// Всё считается из того же расчёта месяца, что «Зарплата» (MonthInput → MonthPay): «В зарплату» по паре «тип · место»
// за месяц — ровно число тренировок этой пары в разбивке 4.10 (SalaryMonthRules.breakdown), «в счёт ступени» —
// счётчик N (4.6), часы дежурств — те же, что в «Сверках».

/** Итог тренировки в отчёте: проведена, отменена со списанием (хоть один клиент оплачен), отменена. */
enum class ReportStatus { DONE, CHARGED, CANCELLED }

/**
 * Строка отчёта — пара «тип · место» (как разбивка зарплаты 4.10: ставки зависят от места). У групп ещё случай 4.8:
 * своя группа, которую вёл один, — [GroupCase.MINE_ALONE]; с коллегой и замены — отдельными строками.
 */
data class ReportKey(val typeId: String, val locationId: String, val groupCase: GroupCase? = null)

/**
 * Тренировка в отчёте. [status] `null` — ещё запланирована. [inSalary] — её считает «Зарплата» (строка разбивки 4.10):
 * у групп — хоть один оплаченный ребёнок, у дежурств — проведено, у остальных — оплата или «нет ставки».
 * [children] — оплаченные клиенты у оплаты «за клиента», [countsPt] — идёт в счётчик ступени (4.6).
 */
data class ReportItem(
    val trainingId: String,
    val key: ReportKey,
    val kind: TrainingKind?,
    val payUnit: PayUnit?,
    val start: LocalDateTime,
    val durationMin: Int,
    val status: ReportStatus?,
    val inSalary: Boolean,
    val children: Int = 0,
    val countsPt: Boolean = false,
)

/** Числа строки (или итога): проведено, со списанием, отменено и сколько из них «Зарплата» считает оплаченными. */
data class ReportCounts(val done: Int = 0, val charged: Int = 0, val cancelled: Int = 0, val inSalary: Int = 0) {
    val all: Int get() = done + charged + cancelled

    operator fun plus(o: ReportCounts) = ReportCounts(done + o.done, charged + o.charged, cancelled + o.cancelled, inSalary + o.inSalary)
}

/**
 * Строка отчёта. У оплаты «за клиента» — [children] оплаченных клиентов на тренировках «в зарплату» и [doneChildren]
 * на проведённых (для «в среднем»); у дежурств — часы [doneMinutes] и [cancelledMinutes]; у СПТ — [sptClosed]: закрыто
 * покупкой в периоде (4.9). [trainingIds] — тренировки строки по времени: нажатие открывает их список.
 */
data class ReportRow(
    val key: ReportKey,
    val kind: TrainingKind?,
    val payUnit: PayUnit?,
    val counts: ReportCounts = ReportCounts(),
    val children: Int = 0,
    val doneChildren: Int = 0,
    val doneMinutes: Int = 0,
    val cancelledMinutes: Int = 0,
    val sptClosed: Int = 0,
    val trainingIds: List<String> = emptyList(),
) {
    /** Оплата за клиента (группа, мини-группа): показываются дети. */
    val perClient: Boolean get() = kind == TrainingKind.GROUP || payUnit == PayUnit.PER_CLIENT

    /** Дежурство или оплата за час: всё в часах. */
    val perHour: Boolean get() = kind == TrainingKind.DUTY || payUnit == PayUnit.PER_HOUR

    /** Детей в среднем на проведённую, в десятых (95 — «9,5»); проведённых нет — `null`. */
    val averageTenths: Int? get() = if (!perClient || counts.done == 0) null else ((doneChildren * 20L + counts.done) / (counts.done * 2L)).toInt()

    /** Минуты «в зарплату» у почасовой строки: проведённые. */
    val salaryMinutes: Int get() = doneMinutes
}

/**
 * Отчёт за [from]–[to]: строки — только непустые, в порядке типов тренера, потом мест, потом случаев групп.
 * [total] — все тренировки по статусам, [ptCount] — в счёт ступени (4.6), [ahead] — запланированные дальше «сейчас»
 * («Впереди по расписанию»), [waiting] — уже начались, но ещё не отмечены. [counted] — по какой день посчитано: у
 * текущего периода — сегодня.
 */
data class ReportTable(
    val from: LocalDate,
    val to: LocalDate,
    val counted: LocalDate,
    val rows: List<ReportRow>,
    val total: ReportCounts,
    val ptCount: Int,
    val ahead: Int,
    val waiting: Int,
    val items: Map<String, ReportItem> = emptyMap(),
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    fun row(key: ReportKey): ReportRow? = rows.firstOrNull { it.key == key }

    /** Тренировки строки [key] по времени — список под нажатием на строку. */
    fun itemsOf(key: ReportKey): List<ReportItem> = row(key)?.trainingIds.orEmpty().mapNotNull { items[it] }
}

/** Месяц в «Годе»: его отчёт и отметка «сверен» из «Сверок» (5.14). */
data class ReportYearMonth(val month: YearMonth, val table: ReportTable, val closed: Boolean = false)

/**
 * «Год» — сверка месяцев: [months] с января по текущий (у прошедшего года — все 12), [total] — весь год одним
 * отчётом. [keys] — пары, у которых за год есть тренировки, — выбор «Что считать» и строки полной таблицы на Fold.
 */
data class ReportYear(val year: Int, val months: List<ReportYearMonth>, val total: ReportTable) {
    val keys: List<ReportKey> get() = total.rows.map { it.key }

    /** Числа месяца по паре [key]; `null` — «Все». */
    fun counts(month: ReportYearMonth, key: ReportKey?): ReportCounts =
        if (key == null) month.table.total else month.table.row(key)?.counts ?: ReportCounts()

    /** Итог года по паре [key]; `null` — «Все». Равен сумме месяцев. */
    fun totalCounts(key: ReportKey?): ReportCounts = if (key == null) total.total else total.row(key)?.counts ?: ReportCounts()
}

/** Какой период отчёта: месяц, год или свои даты (5.15). */
enum class ReportPeriod { MONTH, YEAR, CUSTOM }

object ReportRules {

    /** Самый длинный свой период — год, как у «Заработка за период» (5.12). */
    const val MAX_DAYS = 366

    /**
     * Тренировки месяца расчёта [input] с итогом по отчёту. [pay] — тот же расчёт, что у «Зарплаты»: из него
     * берётся, считает ли она тренировку. Группа коллеги, которую вёл коллега, в отчёт не попадает — как в разбивке.
     */
    fun items(input: MonthInput, pay: MonthPay): List<ReportItem> {
        val types = input.types.associateBy { it.id }
        val ptIds = PayRules.ptTypeIds(Versions.activeIn(input.schemes, input.month) { it.validFrom }, input.types)
        val pays = pay.trainings.associateBy { it.trainingId }
        return PayRules.inMonth(input.trainings, input.month).mapNotNull { t ->
            val type = types[t.typeId]
            val kind = type?.kind
            val p = pays[t.id] ?: return@mapNotNull null
            val case = if (kind == TrainingKind.GROUP) SalaryMonthRules.groupCase(t) ?: return@mapNotNull null else null
            val paid = PayRules.paidClients(t)
            val status = when (t.status) {
                TrainingStatus.PLANNED -> null
                TrainingStatus.DONE -> ReportStatus.DONE
                TrainingStatus.CANCELLED -> if (kind != TrainingKind.DUTY && paid > 0) ReportStatus.CHARGED else ReportStatus.CANCELLED
            }
            val inSalary = when (kind) {
                TrainingKind.GROUP -> p.paidClients > 0
                TrainingKind.DUTY -> t.status == TrainingStatus.DONE
                else -> p.amount > 0 || p.rateMissing
            }
            val perClient = kind == TrainingKind.GROUP || type?.payUnit == PayUnit.PER_CLIENT
            ReportItem(
                trainingId = t.id,
                key = ReportKey(t.typeId, t.locationId, case),
                kind = kind,
                payUnit = type?.payUnit,
                start = t.start,
                durationMin = t.durationMin,
                status = status,
                inSalary = inSalary,
                children = if (perClient && inSalary) p.paidClients else 0,
                countsPt = t.typeId in ptIds && paid > 0,
            )
        }
    }

    /**
     * Отчёт за [from]–[to] из посчитанных месяцев [months] (месяцы вне периода пропускаются, каждый считается своим
     * расчётом — своей ступенью и своим списком «как ПТ»). Будущее не входит: проведённые и отменённые — по сегодня
     * ([now]), запланированные дальше «сейчас» — числом [ReportTable.ahead]. [placeOrder] — порядок мест тренера.
     */
    fun table(
        months: List<Pair<MonthInput, MonthPay>>,
        from: LocalDate,
        to: LocalDate,
        now: LocalDateTime,
        placeOrder: List<String> = emptyList(),
    ): ReportTable {
        val counted = minOf(to, maxOf(from, now.date))
        val items = ArrayList<ReportItem>()
        val closedSpt = HashMap<ReportKey, Int>()
        var typeOrder: Map<String, Int> = emptyMap()
        var ahead = 0
        var waiting = 0
        months.sortedBy { it.first.month }.forEach { (input, pay) ->
            val month = input.month
            if (month < from.yearMonth || month > to.yearMonth) return@forEach
            typeOrder = input.types.withIndex().associate { it.value.id to it.index }
            items(input, pay).forEach { item ->
                val date = item.start.date
                if (date < from || date > to) return@forEach
                when {
                    item.status == null -> if (item.start >= now) ahead++ else waiting++
                    date <= now.date -> items += item
                }
            }
            // СПТ, закрытые покупкой в этом месяце и периоде (4.9): строка — тип и место самой СПТ.
            input.spt.forEach { case ->
                val bought = case.purchaseDate ?: return@forEach
                if (case.status != SptCaseStatus.CLOSED || bought.yearMonth != month || bought < from || bought > counted) return@forEach
                val key = ReportKey(case.typeId, case.locationId)
                closedSpt[key] = (closedSpt[key] ?: 0) + 1
            }
        }
        val kinds = months.flatMap { it.first.types }.associateBy { it.id }
        val rows = LinkedHashMap<ReportKey, ReportRow>()
        items.sortedBy { it.start }.forEach { item ->
            val row = rows[item.key] ?: ReportRow(item.key, item.kind, item.payUnit)
            val add = ReportCounts(
                done = if (item.status == ReportStatus.DONE) 1 else 0,
                charged = if (item.status == ReportStatus.CHARGED) 1 else 0,
                cancelled = if (item.status == ReportStatus.CANCELLED) 1 else 0,
                inSalary = if (item.inSalary) 1 else 0,
            )
            rows[item.key] = row.copy(
                counts = row.counts + add,
                children = row.children + item.children,
                doneChildren = row.doneChildren + if (item.status == ReportStatus.DONE) item.children else 0,
                doneMinutes = row.doneMinutes + if (item.status == ReportStatus.DONE) item.durationMin else 0,
                cancelledMinutes = row.cancelledMinutes + if (item.status == ReportStatus.CANCELLED) item.durationMin else 0,
                trainingIds = row.trainingIds + item.trainingId,
            )
        }
        closedSpt.forEach { (key, n) ->
            val type = kinds[key.typeId]
            val row = rows[key] ?: ReportRow(key, type?.kind ?: TrainingKind.SPT, type?.payUnit)
            rows[key] = row.copy(sptClosed = row.sptClosed + n)
        }
        val places = placeOrder.withIndex().associate { it.value to it.index }
        val ordered = rows.values.sortedWith(
            compareBy<ReportRow> { typeOrder[it.key.typeId] ?: Int.MAX_VALUE }
                .thenBy { places[it.key.locationId] ?: Int.MAX_VALUE }
                .thenBy { it.key.locationId }
                .thenBy { it.key.groupCase?.ordinal ?: -1 },
        )
        return ReportTable(
            from = from,
            to = to,
            counted = counted,
            rows = ordered,
            total = ordered.fold(ReportCounts()) { acc, row -> acc + row.counts },
            ptCount = items.count { it.countsPt },
            ahead = ahead,
            waiting = waiting,
            items = items.associateBy { it.trainingId },
        )
    }

    /** Отчёт месяца [month] целиком (у текущего — по сегодня). */
    fun month(months: List<Pair<MonthInput, MonthPay>>, month: YearMonth, now: LocalDateTime, placeOrder: List<String> = emptyList()): ReportTable =
        table(months, month.firstDay, month.lastDay, now, placeOrder)

    /**
     * «Год» [year]: месяцы по одному (с отметками [closed] из «Сверок») и весь год одним отчётом. [months] —
     * посчитанные месяцы года по текущий.
     */
    fun year(
        year: Int,
        months: List<Pair<MonthInput, MonthPay>>,
        now: LocalDateTime,
        closed: Set<YearMonth> = emptySet(),
        placeOrder: List<String> = emptyList(),
    ): ReportYear {
        val list = months.map { it.first.month }.filter { it.year == year }.distinct().sorted()
        return ReportYear(
            year = year,
            months = list.map { ReportYearMonth(it, month(months, it, now, placeOrder), it in closed) },
            total = table(months, LocalDate(year, 1, 1), LocalDate(year, 12, 31), now, placeOrder),
        )
    }

    /** Месяцы, которые задевает период [from]–[to], по порядку. */
    fun monthsOf(from: LocalDate, to: LocalDate): List<YearMonth> {
        if (to < from) return emptyList()
        val list = ArrayList<YearMonth>()
        var m = from.yearMonth
        while (m <= to.yearMonth) {
            list += m
            m = if (m.month.ordinal == 11) YearMonth(m.year + 1, 1) else YearMonth(m.year, m.month.ordinal + 2)
        }
        return list
    }
}
