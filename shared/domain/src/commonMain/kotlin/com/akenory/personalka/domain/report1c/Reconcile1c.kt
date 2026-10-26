package com.akenory.personalka.domain.report1c

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ReconcileItemKind
import com.akenory.personalka.domain.model.ReconcileResolution
import com.akenory.personalka.domain.model.TrainingKind
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.math.abs

/**
 * Участие клиента в тренировке — сторона приложения в сверке с 1С (ТЗ 8.3). [kind] и [owner] — тип тренировки и на ком
 * она числится: дежурства и группы коллег не сверяются.
 */
data class AppVisit(
    val trainingId: String,
    val clientId: String,
    val start: LocalDateTime,
    val typeId: String,
    val locationId: String,
    val kind: TrainingKind,
    val owner: Owner,
    val status: ParticipationStatus,
)

/** Клиент приложения, с которым сравнивается имя из 1С. */
data class Client1c(val id: String, val name: ClientName, val archived: Boolean = false)

/** Тип и место, которым соответствует «Наименование услуги» 1С (ServiceMapping1C, ТЗ 3.7). */
data class ServiceTarget(val typeId: String, val locationId: String)

/** Кто в приложении стоит за именем из 1С (ТЗ 8.3). */
sealed interface NameMatch {
    /** Сохранённая связь или единственное точное совпадение — принимается само. */
    data class Found(val clientId: String) : NameMatch

    /** Похожие или несколько тёзок — тренер выбирает один раз, выбор запоминается. Ближайшие первыми. */
    data class Ask(val clientIds: List<String>) : NameMatch

    /** «Неизвестный клиент» — можно создать. */
    data object Unknown : NameMatch
}

/**
 * Пункт сверки (ТЗ 8.4). У [ReconcileItemKind.STATUS_DIFF] есть и строка, и участие, а что разошлось — [statusDiffers] и
 * [serviceDiffers]; у остальных — только одно из двух. [key] одинаков у того же пункта при повторной сверке.
 */
data class Diff1c(
    val kind: ReconcileItemKind,
    val row: Row1c? = null,
    val visit: AppVisit? = null,
    val statusDiffers: Boolean = false,
    val serviceDiffers: Boolean = false,
) {
    val key: String
        get() = when (kind) {
            ReconcileItemKind.ONLY_APP -> "app|${visit!!.trainingId}|${visit.clientId}"
            ReconcileItemKind.STATUS_DIFF -> "pair|${visit!!.trainingId}|${visit.clientId}|${row!!.at}"
            else -> "1c|${Names1c.key(row!!.client)}|${row.at}|${row.serviceName}"
        }
}

/**
 * Итог сверки: [matched] — сколько строк 1С и участий сошлось (вместе с отменами, которых нет на другой стороне),
 * [diffs] — расхождения по порядку времени.
 */
data class Reconcile1c(val matched: Int, val diffs: List<Diff1c>) {
    /** «Только в 1С»: строки без пары, в том числе с неизвестным клиентом или услугой. */
    val onlyIn1c: List<Diff1c> get() = diffs.filter { it.kind != ReconcileItemKind.ONLY_APP && it.kind != ReconcileItemKind.STATUS_DIFF }
    val onlyInApp: List<Diff1c> get() = diffs.filter { it.kind == ReconcileItemKind.ONLY_APP }
    val differ: List<Diff1c> get() = diffs.filter { it.kind == ReconcileItemKind.STATUS_DIFF }
}

/**
 * Сопоставление отчёта 1С с приложением (ТЗ 8.3): имена, услуги, пары «строка — участие» и статусы. Деньги не
 * сравниваются: «Стоимость» — цена для клиента, а не оплата тренера.
 */
object Reconcile1cRules {
    /** Пара ищется у того же клиента в тот же день не дальше полутора часов. */
    const val PAIR_WINDOW_MIN = 90

    /** Названия услуг, которым ещё не указаны тип и место, — по порядку первого появления в отчёте. */
    fun unknownServices(report: Report1c, known: Set<String>): List<String> =
        report.rows.map { it.serviceName }.distinct().filter { it !in known }

    /**
     * Кто в приложении за именем [name1c]: сохранённая связь из [aliases] (ключ [Names1c.key] → клиент) → единственное
     * точное совпадение фамилии и имени → похожие (до двух букв в фамилии при том же имени, `ClientRules.similar`) и
     * тёзки на выбор → неизвестный.
     */
    fun match(name1c: String, aliases: Map<String, String>, clients: List<Client1c>): NameMatch {
        val key = Names1c.key(name1c)
        aliases[key]?.let { id -> if (clients.any { it.id == id }) return NameMatch.Found(id) }
        val exact = clients.filter { Names1c.key(it.name.short) == key }
        if (exact.size == 1) return NameMatch.Found(exact.single().id)
        val name = nameOf(name1c) ?: return NameMatch.Unknown
        val similar = ClientRules.similar(name, clients) { it.name }
        val ask = (exact + similar).distinctBy { it.id }.map { it.id }
        return if (ask.isEmpty()) NameMatch.Unknown else NameMatch.Ask(ask)
    }

    /** Все имена отчёта: ключ → первое ФИО, как оно написано в 1С, и кто за ним. */
    fun names(report: Report1c, aliases: Map<String, String>, clients: List<Client1c>): Map<String, Pair<String, NameMatch>> =
        report.rows.map { it.client }.distinctBy(Names1c::key).associate { Names1c.key(it) to (it to match(it, aliases, clients)) }

    /** «Фамилия Имя Отчество» из 1С → имя клиента; меньше двух слов — `null`. */
    fun nameOf(name1c: String): ClientName? {
        val words = name1c.split(' ', ' ', '\t').filter { it.isNotEmpty() }
        if (words.size < 2) return null
        return ClientName(words[0], words[1], words.drop(2).joinToString(" ").ifEmpty { null })
    }

    /**
     * До какого времени сверяются тренировки приложения: время выгрузки из имени файла, без него — [now], но не позже
     * конца периода отчёта. Тренировки позже выгрузки в отчёт ещё не могли попасть.
     */
    fun until(report: Report1c, exportedAt: LocalDateTime?, now: LocalDateTime): LocalDateTime {
        val end = LocalDateTime(report.to, LocalTime(23, 59, 59))
        return minOf(exportedAt ?: now, end)
    }

    /** Участия, которые сверяются (ТЗ 8.3): тренировки тренера, не дежурства, в периоде отчёта и начатые до [until]. */
    fun visitsToCheck(visits: List<AppVisit>, report: Report1c, until: LocalDateTime): List<AppVisit> =
        visits.filter {
            it.owner == Owner.SELF && it.kind != TrainingKind.DUTY &&
                it.start.date >= report.from && it.start.date <= report.to && it.start < until
        }

    /**
     * Сверка: [clientOf] — ключ имени из 1С → клиент (найденные и выбранные тренером), [services] — название услуги →
     * тип и место, [visits] — уже отобранные [visitsToCheck]. Пары подбираются от самых близких по времени.
     *
     * Без пары не считаются расхождением отмены: строка 1С «Отменил» или «Не пришел» и отменённое участие без
     * списания — денег за них нет ни там, ни там.
     */
    fun compare(
        report: Report1c,
        visits: List<AppVisit>,
        clientOf: Map<String, String>,
        services: Map<String, ServiceTarget>,
    ): Reconcile1c {
        val diffs = ArrayList<Diff1c>()
        var matched = 0
        val rows = ArrayList<Pair<Row1c, String>>()
        for (row in report.rows) {
            val client = clientOf[Names1c.key(row.client)]
            when {
                client == null -> diffs += Diff1c(ReconcileItemKind.UNKNOWN_CLIENT, row = row)
                row.serviceName !in services -> diffs += Diff1c(ReconcileItemKind.UNKNOWN_SERVICE, row = row)
                else -> rows += row to client
            }
        }
        val candidates = ArrayList<Triple<Int, Int, Long>>()
        rows.forEachIndexed { r, (row, client) ->
            visits.forEachIndexed { v, visit ->
                if (visit.clientId == client && visit.start.date == row.at.date) {
                    val gap = abs(minutes(visit.start) - minutes(row.at))
                    if (gap <= PAIR_WINDOW_MIN) candidates += Triple(r, v, gap)
                }
            }
        }
        val rowPair = arrayOfNulls<Int>(rows.size)
        val visitTaken = BooleanArray(visits.size)
        candidates.sortedWith(compareBy({ it.third }, { it.first }, { it.second })).forEach { (r, v, _) ->
            if (rowPair[r] == null && !visitTaken[v]) {
                rowPair[r] = v
                visitTaken[v] = true
            }
        }
        rows.forEachIndexed { r, (row, _) ->
            val v = rowPair[r]
            if (v == null) {
                if (row.status == Status1c.CAME) diffs += Diff1c(ReconcileItemKind.ONLY_1C, row = row) else matched++
                return@forEachIndexed
            }
            val visit = visits[v]
            val status = !sameStatus(row.status, visit.status)
            val service = services.getValue(row.serviceName).let { it.typeId != visit.typeId || it.locationId != visit.locationId }
            if (status || service) diffs += Diff1c(ReconcileItemKind.STATUS_DIFF, row, visit, status, service) else matched++
        }
        visits.forEachIndexed { v, visit ->
            if (visitTaken[v]) return@forEachIndexed
            if (visit.status == ParticipationStatus.CANCELLED) matched++ else diffs += Diff1c(ReconcileItemKind.ONLY_APP, visit = visit)
        }
        return Reconcile1c(matched, diffs.sortedWith(compareBy({ it.row?.at ?: it.visit!!.start }, { it.kind.ordinal })))
    }

    /**
     * Статусы (ТЗ 8.3): «Пришел» — проведена или отмена со списанием, «Отменил» и «Не пришел» — отмена. Не отмеченное в
     * приложении (`EXPECTED`) — всегда расхождение: сверяются только начавшиеся тренировки.
     */
    fun sameStatus(status: Status1c, app: ParticipationStatus): Boolean = when (status) {
        Status1c.CAME -> app == ParticipationStatus.ATTENDED || app == ParticipationStatus.CANCELLED_CHARGED
        Status1c.CANCELLED, Status1c.NO_SHOW -> app == ParticipationStatus.CANCELLED
    }

    /** Статус участия, с которым строка 1С добавляется в приложение или исправляется «как в 1С». */
    fun appStatus(status: Status1c): ParticipationStatus =
        if (status == Status1c.CAME) ParticipationStatus.ATTENDED else ParticipationStatus.CANCELLED

    /**
     * Повторная сверка того же периода (ТЗ 8.4): решения прошлой сверки [previous] (ключ пункта → решение) переходят к тем
     * же пунктам [diffs]; пункт, которого больше нет, — исправлен и закрывается сам. Возвращает решение для каждого
     * нового пункта и ключи закрытых.
     */
    fun carry(previous: Map<String, ReconcileResolution>, diffs: List<Diff1c>): Carry {
        val now = diffs.map { it.key }.toSet()
        val kept = diffs.associate { it.key to (previous[it.key]?.takeIf { r -> r != ReconcileResolution.FIXED_IN_APP } ?: ReconcileResolution.OPEN) }
        val closed = previous.filter { (key, r) -> key !in now && r != ReconcileResolution.FIXED_IN_APP && r != ReconcileResolution.IGNORED }.keys
        return Carry(kept, closed)
    }

    data class Carry(val resolutions: Map<String, ReconcileResolution>, val closed: Set<String>)

    /** Минута дня: пары ищутся только в один день. */
    private fun minutes(at: LocalDateTime): Long = at.hour * 60L + at.minute
}
