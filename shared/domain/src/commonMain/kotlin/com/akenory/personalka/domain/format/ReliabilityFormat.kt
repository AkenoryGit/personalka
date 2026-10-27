package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.Reliability
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.LocalDateTime

/** Строки надёжности клиента (шаг 3.14): карточка клиента, карточка тренировки, пункт «Главной». */
object ReliabilityFormat {
    private const val DOT = " · "

    const val TITLE = "Надёжность"
    const val NEW = "новый"

    /** Значение: «84 %», меньше пяти тренировок — «новый». */
    fun value(r: Reliability): String = r.percent?.let { "$it$NBSP%" } ?: NEW

    /** Метка: «Надёжность 84 %», «Надёжность: новый». */
    fun label(r: Reliability): String = if (r.isNew) "$TITLE: $NEW" else "$TITLE ${value(r)}"

    /** Из чего посчитано: «8 отмен из 50», «без отмен из 12»; у нового — «2 тренировки» (ни одной — пусто). */
    fun basis(r: Reliability): String = when {
        r.isNew -> if (r.total == 0) "" else RuText.count(r.total, "тренировка", "тренировки", "тренировок")
        r.cancelled == 0 -> "без отмен из ${RuText.digits(r.total.toLong())}"
        else -> RuText.count(r.cancelled, "отмена", "отмены", "отмен") + " из " + RuText.digits(r.total.toLong())
    }

    const val CONFIRM_TODO = "Уточнить, придёт ли"

    /** Подпись пункта: «Иванов И., 17:00 · Петров П., 18:30 и ещё 1». */
    fun confirmNote(visits: List<Pair<String, LocalDateTime>>): String {
        val shown = visits.take(HomeFormat.MAX_NAMES).joinToString(DOT) { (name, start) -> name + ", " + DateFormat.time(start.time) }
        val rest = visits.size - HomeFormat.MAX_NAMES
        return if (rest > 0) "$shown и ещё ${RuText.digits(rest.toLong())}" else shown
    }
}
