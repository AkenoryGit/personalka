package com.akenory.personalka.domain.report

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.MonthInput
import com.akenory.personalka.domain.money.MonthPay
import com.akenory.personalka.domain.money.PayRules
import com.akenory.personalka.domain.training.ClientOutcome
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.yearMonth

// «Отчёты → Клиент» (ТЗ 5.15, шаг 5.3к): тренировки одного клиента за период. Статус клиента — тот же итог, что в
// его карточке и кабинете (ClientOutcome, 4.1), поэтому числа сходятся с вкладкой «Тренировки» карточки. «В
// зарплату» — клиент оплачен (пришёл или списан) на тренировке, которую считает «Зарплата» (ReportRules.items).

/** Участие клиента в тренировке — из базы: статус, когда предупредил об отмене и причина. */
data class ClientParticipation(
    val trainingId: String,
    val status: ParticipationStatus,
    val notifiedAt: LocalDateTime? = null,
    val cancelReason: String? = null,
)

/**
 * Тренировка клиента в отчёте. [key] — пара «тип · место» (у групп — случай 4.8; группа коллеги, которую вёл
 * коллега, — [ReportKey.groupCase] `null`). [absent] — «не пришёл» на проведённую группу; [noticeMinutes] — за
 * сколько минут до начала предупредили об отмене (отрицательное — после начала). [inSalary] — клиент оплачен на
 * тренировке, которую считает «Зарплата».
 */
data class ClientVisit(
    val trainingId: String,
    val key: ReportKey,
    val kind: TrainingKind?,
    val start: LocalDateTime,
    val durationMin: Int,
    val outcome: ClientOutcome,
    val absent: Boolean = false,
    val noticeMinutes: Long? = null,
    val cancelReason: String? = null,
    val inSalary: Boolean = false,
) {
    /** Группа коллеги, которую вёл коллега: в «Зарплату» не идёт, строка — с пометкой. */
    val theirsByColleague: Boolean get() = kind == TrainingKind.GROUP && key.groupCase == null
}

/**
 * Строка отчёта по клиенту — пара «тип · место». [counts] — по итогу клиента: «Отменено» включает «не пришёл»
 * ([absent]), как счётчики карточки. [trainingIds] — его тренировки строки по времени.
 */
data class ClientReportRow(
    val key: ReportKey,
    val kind: TrainingKind?,
    val counts: ReportCounts = ReportCounts(),
    val absent: Int = 0,
    val trainingIds: List<String> = emptyList(),
) {
    /** Групповая: статусы участия «пришёл · не пришёл · отменено · со списанием» (4.1). */
    val attendance: Boolean get() = kind == TrainingKind.GROUP

    val theirsByColleague: Boolean get() = kind == TrainingKind.GROUP && key.groupCase == null
}

/**
 * Отчёт по клиенту [clientId] за [from]–[to]: [rows] — только непустые, [visits] — все его тренировки периода по
 * датам, [total] — итог по статусам («В зарплату: N из M» — [ReportCounts.inSalary] из [ReportCounts.all]).
 * [ahead] и [waiting] — как у отчёта «Тренировки».
 */
data class ClientReport(
    val clientId: String,
    val from: LocalDate,
    val to: LocalDate,
    val counted: LocalDate,
    val rows: List<ClientReportRow>,
    val visits: List<ClientVisit>,
    val total: ReportCounts,
    val ahead: Int,
    val waiting: Int,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    fun row(key: ReportKey): ClientReportRow? = rows.firstOrNull { it.key == key }
}

object ClientReportRules {

    /**
     * Тренировки клиента в месяце расчёта [input]: те, где у него есть участие в [mine] (по id тренировки). Пара и
     * «Зарплата считает тренировку» — из [ReportRules.items], тем же расчётом [pay], что «Зарплата».
     */
    fun visits(input: MonthInput, pay: MonthPay, mine: Map<String, ClientParticipation>): List<ClientVisit> {
        if (mine.isEmpty()) return emptyList()
        val types = input.types.associateBy { it.id }
        val items = ReportRules.items(input, pay).associateBy { it.trainingId }
        return PayRules.inMonth(input.trainings, input.month).mapNotNull { t ->
            val p = mine[t.id] ?: return@mapNotNull null
            val item = items[t.id]
            val outcome = ClientOutcome.of(t.status, p.status)
            val absent = t.status == TrainingStatus.DONE && p.status == ParticipationStatus.CANCELLED
            val cancelled = outcome == ClientOutcome.CANCELLED || outcome == ClientOutcome.CANCELLED_CHARGED
            ClientVisit(
                trainingId = t.id,
                key = item?.key ?: ReportKey(t.typeId, t.locationId),
                kind = types[t.typeId]?.kind,
                start = t.start,
                durationMin = t.durationMin,
                outcome = outcome,
                absent = absent,
                noticeMinutes = p.notifiedAt?.takeIf { cancelled && !absent }?.let { ClubTime.minutesBetween(it, t.start) },
                cancelReason = p.cancelReason?.trim()?.takeIf { cancelled && it.isNotEmpty() },
                inSalary = PayRules.isPaid(p.status) && item?.inSalary == true,
            )
        }
    }

    /**
     * Отчёт по клиенту за [from]–[to] из посчитанных месяцев [months] и его участий [mine]. Будущее не входит, как в
     * отчёте «Тренировки»: проведённые и отменённые — по сегодня ([now]), запланированные — числами «впереди» и «ещё
     * не отмечены». [placeOrder] — порядок мест тренера.
     */
    fun report(
        clientId: String,
        months: List<Pair<MonthInput, MonthPay>>,
        mine: Map<String, ClientParticipation>,
        from: LocalDate,
        to: LocalDate,
        now: LocalDateTime,
        placeOrder: List<String> = emptyList(),
    ): ClientReport {
        val counted = minOf(to, maxOf(from, now.date))
        val list = ArrayList<ClientVisit>()
        var typeOrder: Map<String, Int> = emptyMap()
        var ahead = 0
        var waiting = 0
        months.sortedBy { it.first.month }.forEach { (input, pay) ->
            if (input.month < from.yearMonth || input.month > to.yearMonth) return@forEach
            typeOrder = input.types.withIndex().associate { it.value.id to it.index }
            visits(input, pay, mine).forEach { v ->
                val date = v.start.date
                if (date < from || date > to) return@forEach
                when {
                    v.outcome == ClientOutcome.PLANNED -> if (v.start >= now) ahead++ else waiting++
                    date <= now.date -> list += v
                }
            }
        }
        list.sortBy { it.start }
        val rows = LinkedHashMap<ReportKey, ClientReportRow>()
        list.forEach { v ->
            val row = rows[v.key] ?: ClientReportRow(v.key, v.kind)
            rows[v.key] = row.copy(
                counts = row.counts + countsOf(v),
                absent = row.absent + if (v.absent) 1 else 0,
                trainingIds = row.trainingIds + v.trainingId,
            )
        }
        val places = placeOrder.withIndex().associate { it.value to it.index }
        val ordered = rows.values.sortedWith(
            compareBy<ClientReportRow> { typeOrder[it.key.typeId] ?: Int.MAX_VALUE }
                .thenBy { places[it.key.locationId] ?: Int.MAX_VALUE }
                .thenBy { it.key.locationId }
                .thenBy { it.key.groupCase?.ordinal ?: if (it.theirsByColleague) Int.MAX_VALUE else -1 },
        )
        return ClientReport(
            clientId = clientId,
            from = from,
            to = to,
            counted = counted,
            rows = ordered,
            visits = list,
            total = ordered.fold(ReportCounts()) { acc, row -> acc + row.counts },
            ahead = ahead,
            waiting = waiting,
        )
    }

    /** Числа одной тренировки: по итогу клиента, «не пришёл» — в «Отменено». */
    fun countsOf(v: ClientVisit): ReportCounts = ReportCounts(
        done = if (v.outcome == ClientOutcome.DONE) 1 else 0,
        charged = if (v.outcome == ClientOutcome.CANCELLED_CHARGED) 1 else 0,
        cancelled = if (v.outcome == ClientOutcome.CANCELLED) 1 else 0,
        inSalary = if (v.inSalary) 1 else 0,
    )
}
