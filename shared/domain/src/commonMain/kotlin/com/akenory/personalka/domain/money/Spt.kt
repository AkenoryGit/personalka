package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.model.TrainingKind
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth

// СПТ и её закрытие покупкой блока (ТЗ 4.9): окно, статусы, доплата в месяц покупки, подсказки и конверсия.
// Базовая ставка СПТ — в PayRules.pay, как у остальных форматов.

/**
 * Стартовая тренировка для расчёта (ТЗ 3.5, SptCase). [typeId] и [locationId] — тип и место самой СПТ: по ним
 * берётся доплата `spt_close_bonus` из ставки. [status] — как записан в базе; действующий — [SptRules.statusOn].
 */
data class SptCase(
    val id: String,
    val clientId: String,
    val sptTrainingId: String,
    val typeId: String,
    val locationId: String,
    val sptDate: LocalDate,
    val windowUntil: LocalDate = SptRules.windowUntil(sptDate),
    val purchaseDate: LocalDate? = null,
    val status: SptCaseStatus = SptCaseStatus.OPEN,
)

/** Почему дату покупки нельзя сохранить как покупку со скидкой. */
enum class PurchaseProblem { BEFORE_SPT, AFTER_WINDOW }

/** Вопрос тренеру на первой оплаченной ПТ клиента после СПТ (ТЗ 4.9, п. 4–5). */
sealed interface SptPrompt {
    /** Окно открыто: «Засчитать СПТ закрытой?», дата покупки по умолчанию — день ПТ. */
    data class CloseNow(val case: SptCase, val purchaseDate: LocalDate) : SptPrompt

    /** Окно прошло: «Блок был куплен со скидкой до {window_until}?». */
    data class AskRetro(val case: SptCase, val windowUntil: LocalDate) : SptPrompt
}

/** Проведённая СПТ в базе (4.9, п. 1): тренировка, её клиент и день. По ней должно быть одно дело. */
data class SptDone(val trainingId: String, val clientId: String, val date: LocalDate)

/**
 * Что записать, чтобы дела СПТ совпали с проведёнными СПТ ([SptRules.reconcile]): [opened] — новые дела `OPEN`,
 * [changed] — дела с новым днём, клиентом или статусом, [removed] — дела без проведённой СПТ и повторы.
 */
data class SptChanges(val opened: List<SptDone>, val changed: List<SptCase>, val removed: List<SptCase>) {
    val isEmpty: Boolean get() = opened.isEmpty() && changed.isEmpty() && removed.isEmpty()
}

/** Конверсия стартовых за период: закрытые / (закрытые + незакрытые); открытые не считаются. */
data class SptConversion(val closed: Int, val notClosed: Int) {
    val total: Int get() = closed + notClosed

    /** Доля закрытых в процентах, до целого, половина вверх; нет решённых СПТ — `null`. */
    val percent: Int? get() = if (total == 0) null else (closed * 200 + total) / (total * 2)
}

object SptRules {

    /** Сколько дней до конца окна напоминать «Уточните, купил ли … блок». */
    const val REMIND_DAYS = 3

    /** Конец окна — та же дата следующего месяца; такой даты нет — последний день месяца (31.01 → 28/29.02). */
    fun windowUntil(sptDate: LocalDate): LocalDate = sptDate.plus(1, DateTimeUnit.MONTH)

    /** СПТ отмечена проведённой → новое дело `OPEN` с окном до той же даты следующего месяца. */
    fun open(id: String, clientId: String, sptTrainingId: String, typeId: String, locationId: String, sptDate: LocalDate) =
        SptCase(id, clientId, sptTrainingId, typeId, locationId, sptDate)

    /**
     * Дела СПТ по проведённым СПТ на день [today] (шаг 3.7а): СПТ отметили проведённой — новое дело `OPEN`; её
     * вернули, отменили или удалили — дело убирается; перенесли на другой день — окно считается от нового дня, а
     * дата покупки вне нового окна снимается; окно прошло без покупки — `NOT_CLOSED` ([expired]). Два дела одной СПТ
     * (два телефона отметили её вместе) — остаётся дело с покупкой, иначе с меньшим id: так решат оба телефона.
     */
    fun reconcile(done: List<SptDone>, cases: List<SptCase>, today: LocalDate): SptChanges {
        val doneById = done.associateBy { it.trainingId }
        val known = cases.map { it.sptTrainingId }.toSet()
        val changed = mutableListOf<SptCase>()
        val removed = mutableListOf<SptCase>()
        for ((trainingId, list) in cases.groupBy { it.sptTrainingId }) {
            val spt = doneById[trainingId]
            if (spt == null) {
                removed += list
                continue
            }
            val keep = list.sortedWith(compareBy({ it.purchaseDate == null }, { it.id })).first()
            removed += list.filter { it !== keep }
            var case = keep
            if (case.sptDate != spt.date || case.clientId != spt.clientId) {
                case = case.copy(clientId = spt.clientId, sptDate = spt.date, windowUntil = windowUntil(spt.date))
                val bought = case.purchaseDate
                if (bought != null && purchaseProblem(case, bought) != null) case = reopen(case, today)
            }
            if (case.purchaseDate == null) {
                val status = if (today > case.windowUntil) SptCaseStatus.NOT_CLOSED else SptCaseStatus.OPEN
                if (case.status != status) case = case.copy(status = status)
            }
            if (case != keep) changed += case
        }
        val opened = done.filter { it.trainingId !in known }
        return SptChanges(opened, changed, removed)
    }

    /**
     * Оплаченная ПТ клиента для вопроса 4.9, п. 4–5: персональная или сплит, клиент пришёл или отменил со списанием.
     * Сплит считается: блок мог быть куплен на сплит.
     */
    fun isPaidPt(kind: TrainingKind, status: ParticipationStatus): Boolean =
        (kind == TrainingKind.PERSONAL || kind == TrainingKind.SPLIT) && PayRules.isPaid(status)

    /** День для «Купил блок со скидкой» по умолчанию: сегодня, но в пределах окна — от дня СПТ до его конца. */
    fun defaultPurchase(case: SptCase, today: LocalDate): LocalDate = case.purchaseDate ?: when {
        today < case.sptDate -> case.sptDate
        today > case.windowUntil -> case.windowUntil
        else -> today
    }

    /** Можно ли сохранить [date] как покупку блока со скидкой: не раньше СПТ и не позже конца окна. */
    fun purchaseProblem(case: SptCase, date: LocalDate): PurchaseProblem? = when {
        date < case.sptDate -> PurchaseProblem.BEFORE_SPT
        date > case.windowUntil -> PurchaseProblem.AFTER_WINDOW
        else -> null
    }

    /**
     * «Купил блок со скидкой» с датой [date] → `CLOSED`; закрыть можно и незакрытую задним числом (п. 5).
     * Дату вне окна сохранить нельзя — `null`, дело остаётся как было.
     */
    fun close(case: SptCase, date: LocalDate): SptCase? =
        if (purchaseProblem(case, date) != null) null
        else case.copy(purchaseDate = date, status = SptCaseStatus.CLOSED)

    /** Снять отметку покупки: дело снова открыто, если окно не прошло к [today], иначе — не закрыто. */
    fun reopen(case: SptCase, today: LocalDate): SptCase =
        case.copy(purchaseDate = null, status = if (today > case.windowUntil) SptCaseStatus.NOT_CLOSED else SptCaseStatus.OPEN)

    /** Статус на день [today]: окно прошло без покупки — `NOT_CLOSED`, даже если в базе ещё `OPEN`. */
    fun statusOn(case: SptCase, today: LocalDate): SptCaseStatus = when {
        case.purchaseDate != null && case.status == SptCaseStatus.CLOSED -> SptCaseStatus.CLOSED
        case.status == SptCaseStatus.NOT_CLOSED || today > case.windowUntil -> SptCaseStatus.NOT_CLOSED
        else -> SptCaseStatus.OPEN
    }

    /** Дела, у которых окно прошло к [today], а в базе всё ещё `OPEN`, — с новым статусом `NOT_CLOSED` для записи. */
    fun expired(cases: List<SptCase>, today: LocalDate): List<SptCase> =
        cases.filter { it.status == SptCaseStatus.OPEN && statusOn(it, today) == SptCaseStatus.NOT_CLOSED }
            .map { it.copy(status = SptCaseStatus.NOT_CLOSED) }

    /** Напоминание «Уточните, купил ли … блок»: дело открыто и до конца окна [REMIND_DAYS] дня или меньше. */
    fun remind(case: SptCase, today: LocalDate): Boolean =
        statusOn(case, today) == SptCaseStatus.OPEN && today >= case.windowUntil.minus(REMIND_DAYS, DateTimeUnit.DAY)

    /**
     * Вопрос на ПТ клиента в день [ptDate] (п. 4–5): только на первой оплаченной ПТ после СПТ — [paidPtDates] —
     * все дни оплаченных ПТ этого клиента у тренера. В окне — «Засчитать закрытой?», после окна — «Был ли куплен
     * до …?»; закрытому делу вопроса нет.
     */
    fun prompt(case: SptCase, ptDate: LocalDate, paidPtDates: List<LocalDate>): SptPrompt? {
        if (case.status == SptCaseStatus.CLOSED) return null
        val first = paidPtDates.filter { it >= case.sptDate }.minOrNull() ?: return null
        if (ptDate != first) return null
        return if (ptDate <= case.windowUntil) SptPrompt.CloseNow(case, ptDate) else SptPrompt.AskRetro(case, case.windowUntil)
    }

    /** Доплата за закрытие — `spt_close_bonus` ставки типа и места СПТ на день СПТ; не закрыто или нет суммы — 0. */
    fun bonus(case: SptCase, rates: List<PayRate>): Long {
        if (case.status != SptCaseStatus.CLOSED || case.purchaseDate == null) return 0
        val rate = Versions.rateFor(rates, case.typeId, case.locationId, case.sptDate, { it.typeId }, { it.locationId }, { it.validFrom })
        return rate?.sptCloseBonus ?: 0
    }

    /** Доплаты месяца [month]: закрытые дела с покупкой в этом месяце (п. 3 — доплата идёт в месяц покупки). */
    fun bonusIn(month: YearMonth, cases: List<SptCase>, rates: List<PayRate>): Long =
        cases.filter { it.purchaseDate?.yearMonth == month }.sumOf { bonus(it, rates) }

    /** Конверсия СПТ, проведённых с [from] по [to] включительно, на день [today]. */
    fun conversion(cases: List<SptCase>, from: LocalDate, to: LocalDate, today: LocalDate): SptConversion {
        val statuses = cases.filter { it.sptDate in from..to }.map { statusOn(it, today) }
        return SptConversion(statuses.count { it == SptCaseStatus.CLOSED }, statuses.count { it == SptCaseStatus.NOT_CLOSED })
    }
}
