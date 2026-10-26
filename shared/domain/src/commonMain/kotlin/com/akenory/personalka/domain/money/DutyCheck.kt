package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Дежурство месяца в сверке (5.14): [done] — отмечено проведённым и идёт в часы и деньги, иначе — пока по расписанию.
 * [amount] — заработано за него (у непроведённого 0), [coverFor] — за кого дежурил, как написал тренер.
 */
data class DutyRow(
    val trainingId: String,
    val start: LocalDateTime,
    val durationMin: Int,
    val locationId: String,
    val done: Boolean,
    val amount: Long,
    val coverFor: String? = null,
)

/** Отметка «Дежурства сверены»: нет, сверены на нынешних часах или сверены, а часы потом изменились. */
enum class DutyCheckState { NONE, CHECKED, CHANGED }

/**
 * Сверка дежурств месяца (5.14, шаг 3.9в): проведённые и запланированные дежурства по порядку, часы и деньги
 * проведённых — те же, что в «Зарплате» (4.7). [checkedMinutes] — часы, на которых тренер отметил «Сверены».
 */
data class DutyCheck(
    val rows: List<DutyRow>,
    val checkedMinutes: Int?,
    val canCheck: Boolean,
) {
    val doneMinutes: Int get() = rows.filter { it.done }.sumOf { it.durationMin }
    val doneAmount: Long get() = rows.filter { it.done }.sumOf { it.amount }
    val plannedMinutes: Int get() = rows.filterNot { it.done }.sumOf { it.durationMin }

    val state: DutyCheckState
        get() = when (checkedMinutes) {
            null -> DutyCheckState.NONE
            doneMinutes -> DutyCheckState.CHECKED
            else -> DutyCheckState.CHANGED
        }

    /** Показывать ли сверку: есть дежурства или осталась отметка (дежурства потом удалили — видно, что изменилось). */
    val visible: Boolean get() = rows.isNotEmpty() || checkedMinutes != null
}

object DutyCheckRules {

    /**
     * Дежурства месяца [input] без отменённых; деньги — из [pay] того же месяца, поэтому сумма проведённых
     * совпадает с дежурствами в разбивке «Зарплаты». Сверять можно прошедший и текущий месяц, как «Месяц сверен».
     */
    fun build(input: MonthInput, pay: MonthPay, today: LocalDate, checkedMinutes: Int?): DutyCheck {
        val duty = input.types.filter { it.kind == TrainingKind.DUTY }.map { it.id }.toSet()
        val amounts = pay.trainings.associate { it.trainingId to it.earned }
        val rows = PayRules.inMonth(input.trainings, input.month)
            .filter { it.typeId in duty && it.status != TrainingStatus.CANCELLED }
            .sortedWith(compareBy({ it.start }, { it.id }))
            .map { t ->
                val done = t.status == TrainingStatus.DONE
                DutyRow(t.id, t.start, t.durationMin, t.locationId, done, if (done) amounts[t.id] ?: 0 else 0, t.coverFor)
            }
        val canCheck = SalaryMonthRules.period(input.month, today) != MonthPeriod.FUTURE
        return DutyCheck(rows, checkedMinutes, canCheck)
    }
}
