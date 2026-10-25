package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.money.Shortfall

/** Строки плана и прогноза месяца (ТЗ 4.12): экран «Зарплата» и главная берут их отсюда. */
object SalaryFormat {

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
}
