package com.akenory.personalka.domain.report

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.group.GroupMember
import com.akenory.personalka.domain.group.GroupRules
import com.akenory.personalka.domain.group.membersOn
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

// «Отчёты → Группы» (ТЗ 5.15, шаг 5.3о): посещаемость детей в группах за период — кто ходит, кто пропадает. Только
// посещения, без денег. Статусы участия — как в карточке тренировки группы (4.1): «пришёл», «не пришёл» (на проведённой,
// никто не предупреждал), «отменено» (предупредили), «со списанием». История ребёнка в группе — с первого «пришёл»
// (4.4, шаг 5.3н): поэтому «из N» у него совпадает с вкладкой «Клиент».

/** Группа для отчёта: [colleague] — имя коллеги у группы «Каждый своих» (4.8), иначе `null`. */
data class GroupReportGroup(val id: String, val name: String, val colleague: String? = null)

/** Участие ребёнка в тренировке группы. [notified] — предупредили об отмене (тогда это «отменено», а не «не пришёл»). */
data class GroupReportPerson(val clientId: String, val status: ParticipationStatus, val notified: Boolean = false)

/** Тренировка группы: все, не только периода, — по ним считается начало истории ребёнка. */
data class GroupReportTraining(
    val id: String,
    val groupId: String,
    val start: LocalDateTime,
    val status: TrainingStatus,
    val people: List<GroupReportPerson>,
)

/** Что с ребёнком на проведённой тренировке — буква журнала в Excel. */
enum class GroupMark { CAME, NO_SHOW, CANCELLED, CHARGED }

/**
 * Ребёнок в блоке группы: [total] — проведённые тренировки периода, где у него есть участие, с его первого «пришёл»;
 * [last] — последний «пришёл» (за всё время); [missedInRow] — сколько последних проведённых подряд он пропустил.
 * [colleague] — ребёнок коллеги в группе «Каждый своих».
 */
data class GroupReportKid(
    val clientId: String,
    val name: ClientName,
    val total: Int,
    val came: Int,
    val noShow: Int,
    val cancelled: Int,
    val charged: Int,
    val last: LocalDate?,
    val missedInRow: Int,
    val colleague: Boolean = false,
    /** Отметки по проведённым тренировкам периода (id тренировки → буква); до начала истории — нет. */
    val marks: Map<String, GroupMark> = emptyMap(),
) {
    /** Процент «пришёл» из N, вниз до целого; N = 0 — `null`. */
    val percent: Int? get() = if (total == 0) null else came * 100 / total
}

/** Ребёнок «на раз»: в составе группы не был ни разу; [came] — сколько раз пришёл в периоде. */
data class GroupReportGuest(val clientId: String, val name: ClientName, val came: Int)

/**
 * Блок группы: [conducted] — проведено в периоде, [averageTenths] — в среднем пришли (в десятых), [size] — в составе на
 * последней проведённой; [notMarked] — начатые и не отмеченные, [cancelledWhole] — отменённые целиком. [kids] — в
 * составе сейчас, [left] — уже не в группе, [guests] — «на раз». [dates] — проведённые тренировки по времени (для журнала).
 */
data class GroupReportBlock(
    val group: GroupReportGroup,
    val conducted: Int,
    val averageTenths: Int,
    val size: Int,
    val notMarked: Int,
    val cancelledWhole: Int,
    val kids: List<GroupReportKid>,
    val left: List<GroupReportKid>,
    val guests: List<GroupReportGuest>,
    val dates: List<Pair<String, LocalDateTime>>,
)

/** Отчёт по группам за [from]–[to]: блоки выбранных групп с тренировками в периоде, по названию. */
data class GroupReport(val from: LocalDate, val to: LocalDate, val blocks: List<GroupReportBlock>) {
    val isEmpty: Boolean get() = blocks.isEmpty()
}

object GroupReportRules {
    /** С скольких пропусков подряд подпись «пропустил 3 подряд» (5.15). */
    const val MISSED_IN_ROW = 3

    /**
     * Отчёт за [from]–[to] на «сейчас» [now]. [chosen] — выбранные группы (`null` — все); группы без тренировок в
     * периоде не показываются. [members] — состав по группам, [names] — имена детей. Будущее не входит: проведённые
     * и отменённые — по сегодня, «не отмечено» — начатые до «сейчас».
     */
    fun report(
        groups: List<GroupReportGroup>,
        trainings: List<GroupReportTraining>,
        members: Map<String, List<GroupMember>>,
        names: Map<String, ClientName>,
        from: LocalDate,
        to: LocalDate,
        now: LocalDateTime,
        chosen: Set<String>? = null,
    ): GroupReport {
        val byGroup = trainings.groupBy { it.groupId }
        val blocks = groups.filter { chosen == null || it.id in chosen }.mapNotNull { g ->
            block(g, byGroup[g.id].orEmpty(), members[g.id].orEmpty(), names, from, to, now)
        }.sortedBy { ClientRules.key(it.group.name) }
        return GroupReport(from, to, blocks)
    }

    /** Группы, у которых в периоде были тренировки, — для окна выбора. */
    fun withTrainings(trainings: List<GroupReportTraining>, from: LocalDate, to: LocalDate, now: LocalDateTime): Set<String> =
        trainings.filter { inPeriod(it, from, to, now) }.map { it.groupId }.toSet()

    private fun inPeriod(t: GroupReportTraining, from: LocalDate, to: LocalDate, now: LocalDateTime): Boolean =
        t.start.date in from..to && t.start <= now

    private fun block(
        g: GroupReportGroup,
        all: List<GroupReportTraining>,
        members: List<GroupMember>,
        names: Map<String, ClientName>,
        from: LocalDate,
        to: LocalDate,
        now: LocalDateTime,
    ): GroupReportBlock? {
        val period = all.filter { inPeriod(it, from, to, now) }.sortedBy { it.start }
        if (period.isEmpty()) return null
        val done = period.filter { it.status == TrainingStatus.DONE }
        // Начало истории каждого ребёнка — по всем тренировкам группы, не только периода (4.4).
        val starts = GroupRules.historyStarts(
            all.filter { it.status == TrainingStatus.DONE }.flatMap { t -> t.people.map { Triple(it.clientId, t.start.date, it.status == ParticipationStatus.ATTENDED) } },
        )
        val lastCame = all.filter { it.status == TrainingStatus.DONE }
            .flatMap { t -> t.people.filter { it.status == ParticipationStatus.ATTENDED }.map { it.clientId to t.start.date } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, d) -> d.max() }
        val came = done.map { t -> t.people.count { it.status == ParticipationStatus.ATTENDED } }
        val today = now.date
        val inGroup = members.membersOn(today).toSet()
        val everMember = members.map { it.clientId }.toSet()

        val people = done.flatMap { it.people.map { p -> p.clientId } }.distinct()
        val kids = ArrayList<GroupReportKid>()
        val left = ArrayList<GroupReportKid>()
        val guests = ArrayList<GroupReportGuest>()
        people.forEach { id ->
            val name = names[id] ?: return@forEach
            if (id !in everMember) {
                val n = done.count { t -> t.people.any { it.clientId == id && it.status == ParticipationStatus.ATTENDED } }
                if (n > 0) guests += GroupReportGuest(id, name, n)
                return@forEach
            }
            val start = starts[id] ?: return@forEach
            val marks = LinkedHashMap<String, GroupMark>()
            done.forEach { t ->
                if (t.start.date < start) return@forEach
                val p = t.people.firstOrNull { it.clientId == id } ?: return@forEach
                marks[t.id] = when (p.status) {
                    ParticipationStatus.ATTENDED -> GroupMark.CAME
                    ParticipationStatus.CANCELLED_CHARGED -> GroupMark.CHARGED
                    ParticipationStatus.CANCELLED -> if (p.notified) GroupMark.CANCELLED else GroupMark.NO_SHOW
                    ParticipationStatus.EXPECTED -> GroupMark.NO_SHOW
                }
            }
            if (marks.isEmpty()) return@forEach
            val list = marks.values.toList()
            val kid = GroupReportKid(
                clientId = id,
                name = name,
                total = list.size,
                came = list.count { it == GroupMark.CAME },
                noShow = list.count { it == GroupMark.NO_SHOW },
                cancelled = list.count { it == GroupMark.CANCELLED },
                charged = list.count { it == GroupMark.CHARGED },
                last = lastCame[id],
                missedInRow = list.takeLastWhile { it != GroupMark.CAME }.size,
                colleague = members.lastOrNull { it.clientId == id }?.coach == Owner.COLLEAGUE,
                marks = marks,
            )
            if (id in inGroup) kids += kid else left += kid
        }
        val order = compareBy<GroupReportKid> { ClientRules.sortKey(it.name) }
        val lastDone = done.lastOrNull()
        return GroupReportBlock(
            group = g,
            conducted = done.size,
            averageTenths = GroupRules.averageTenths(came),
            // «из 12» — кого ждали на последней проведённой: пришли, не пришли; предупредившие об отмене — нет (4.1).
            size = lastDone?.people?.count { it.status == ParticipationStatus.ATTENDED || it.status == ParticipationStatus.EXPECTED || (it.status == ParticipationStatus.CANCELLED && !it.notified) }
                ?: inGroup.size,
            notMarked = period.count { it.status == TrainingStatus.PLANNED },
            cancelledWhole = period.count { it.status == TrainingStatus.CANCELLED },
            kids = kids.sortedWith(order),
            left = left.sortedWith(order),
            guests = guests.sortedBy { ClientRules.sortKey(it.name) },
            dates = done.map { it.id to it.start },
        )
    }
}
