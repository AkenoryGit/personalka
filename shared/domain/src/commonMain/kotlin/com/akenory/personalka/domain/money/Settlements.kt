package com.akenory.personalka.domain.money

import kotlinx.datetime.LocalDateTime

// Вкладка «Коллеги» (ТЗ 5.12, 4.8): взаиморасчёт месяца по каждому коллеге — случаи групп с их тренировками, итог,
// итог по расписанию и отметка «Рассчитались». Суммы считает PayRules.share; здесь только сборка того, что показать.

/**
 * Тренировка во взаиморасчёте: [children] — оплаченных детей, [gross] — G группы, [amount] — взаиморасчёт по ней
 * (больше нуля — коллега отдаёт мне), [tax] — доля налога владельца w в стомиллионных (`PayRules.ownerTax`).
 */
data class SettlementTraining(
    val trainingId: String,
    val start: LocalDateTime,
    val children: Int,
    val gross: Long,
    val amount: Long,
    val tax: Long,
)

/** Случай 4.8 одной группы ([groupId]; без группы — по типу и месту): тренировки по порядку. */
data class SettlementCase(
    val case: GroupCase,
    val groupId: String?,
    val typeId: String,
    val locationId: String,
    val trainings: List<SettlementTraining>,
) {
    val amount: Long get() = trainings.sumOf { it.amount }
    val children: Int get() = trainings.sumOf { it.children }

    /** Ключ случая — для раскрытого списка на экране. */
    val key: String get() = "${case.name}:${groupId ?: "$typeId@$locationId"}"

    /** w одна у всех тренировок случая — подпись «½ × (1 − 0,065) от каждой»; разная (схема сменилась) — `null`. */
    val tax: Long? get() = trainings.map { it.tax }.distinct().singleOrNull()

    /** Вели вдвоём: доля — половина. */
    val together: Boolean get() = case == GroupCase.MINE_TOGETHER || case == GroupCase.THEIRS_TOGETHER
}

/**
 * Взаиморасчёт с коллегой [colleagueId] за месяц: [total] — по проведённым (больше нуля — коллега отдаёт мне),
 * [forecast] — если всё пройдёт по расписанию, [settled] — сумма, на которой отметили «Рассчитались» (`null` — нет).
 */
data class ColleagueSettlement(
    val colleagueId: String,
    val cases: List<SettlementCase>,
    val total: Long,
    val forecast: Long,
    val settled: Long? = null,
) {
    /** Рассчитались, и с тех пор расчёт не менялся. */
    val isSettled: Boolean get() = settled != null && settled == total

    /** Рассчитались, а потом расчёт изменился (тренировку отметили или поправили): на сколько. */
    val changedSince: Long? get() = settled?.let { total - it }?.takeIf { it != 0L }
}

object SettlementRules {

    /**
     * Взаиморасчёт месяца [pay] по каждому коллеге: случаи групп 4.8 с тренировками (только с ненулевым
     * взаиморасчётом), итог — как в «Выплатах», итог по расписанию и отметки [settled] (id коллеги → сумма).
     * Коллега без проведённых, но с запланированными или с отметкой, — тоже здесь. Порядок коллег — по первому
     * случаю; экран сортирует по имени.
     */
    fun colleagues(input: MonthInput, pay: MonthPay, settled: Map<String, Long> = emptyMap()): List<ColleagueSettlement> {
        val byId = PayRules.inMonth(input.trainings, input.month).associateBy { it.id }
        val rows = pay.trainings.mapNotNull { p ->
            val colleague = p.colleagueId ?: return@mapNotNull null
            val t = byId[p.trainingId] ?: return@mapNotNull null
            val case = SalaryMonthRules.groupCase(t) ?: return@mapNotNull null
            if (p.settlement == 0L) return@mapNotNull null
            Row(colleague, case, t, SettlementTraining(t.id, t.start, p.paidClients, p.amount, p.settlement, PayRules.ownerTax(t, input.owners)))
        }
        val cases = rows.groupBy { it.colleague }.mapValues { (_, list) ->
            list.groupBy { Triple(it.case, it.training.groupId, if (it.training.groupId == null) "${it.training.typeId}@${it.training.locationId}" else "") }
                .map { (key, group) ->
                    val first = group.minBy { it.training.start }.training
                    SettlementCase(key.first, key.second, first.typeId, first.locationId, group.map { it.item }.sortedBy { it.start })
                }
                .sortedWith(compareBy<SettlementCase> { it.case.ordinal }.thenBy { it.trainings.first().start })
        }
        val ids = (cases.keys + pay.forecastSettlements.filterValues { it != 0L }.keys + settled.keys).distinct()
        return ids.map { id ->
            ColleagueSettlement(id, cases[id].orEmpty(), pay.settlements[id] ?: 0, pay.forecastSettlements[id] ?: 0, settled[id])
        }
    }

    private data class Row(val colleague: String, val case: GroupCase, val training: PayTraining, val item: SettlementTraining)
}
