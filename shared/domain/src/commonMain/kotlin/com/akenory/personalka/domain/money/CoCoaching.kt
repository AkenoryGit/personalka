package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.PaySchemeKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/**
 * Кто ведёт группу или групповую тренировку (ТЗ 3.5, 4.8): на ком числится и кто проводит. [ownerColleagueId] —
 * если числится на коллеге; [colleagueId] — с кем или кто заменил, у группы коллеги — сам владелец.
 */
data class Sharing(
    val owner: Owner = Owner.SELF,
    val ownerColleagueId: String? = null,
    val conductedBy: ConductedBy = ConductedBy.SELF,
    val colleagueId: String? = null,
) {
    /** Коллега, которого нужно выбрать: владелец у группы коллеги, иначе тот, кто ведёт со мной или вместо меня. */
    val colleague: String? get() = if (owner == Owner.COLLEAGUE) ownerColleagueId else colleagueId

    companion object {
        /** Своя группа, веду один — так всё, пока совместное ведение выключено (10.7). */
        val SELF = Sharing()
    }
}

/** Коллега в форме «Коллеги» (5.16): имя и схема выплат — только для доли налога владельца группы (4.8). */
data class ColleagueDraft(val name: String = "", val kind: PaySchemeKind = PaySchemeKind.NONE)

/** Правила совместного ведения групп (ТЗ 4.8, 5.4, 5.10, 5.16, шаг 3.7г). */
object CoCoachingRules {
    /**
     * Схемы коллеги на выбор: у всех тренеров клуба — «Вся сумма» (w = 0), у кого открыта — «Официально с налогом
     * 6,5 %» (w = 0,065). Вторая видна всегда: коллега, на ком числится группа, может быть и с ней.
     */
    val SCHEMES: List<PaySchemeKind> = listOf(PaySchemeKind.NONE, PaySchemeKind.HALF_OFFICIAL)

    /** Имя коллеги: не пустое и не повторяется среди [others] (без учёта регистра и «ё»). */
    fun nameProblem(name: String, others: List<String>): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "Введите имя"
            others.any { ClientRules.key(it) == ClientRules.key(trimmed) } -> "Такой коллега уже есть"
            else -> null
        }
    }

    /** Первая схема нового коллеги — с 1 января: прошлые месяцы года считаются по ней же. */
    fun firstFrom(today: LocalDate): LocalDate = LocalDate(today.year, 1, 1)

    /** Смена схемы коллеги — с 1-го числа выбранного месяца, прошлые месяцы — по прежней. */
    fun changeFrom(month: YearMonth): LocalDate = month.firstDay

    /**
     * Приводит выбор к одному виду: своя группа без коллеги-владельца, «веду один» в своей — без коллеги, у группы
     * коллеги коллега — её владелец (так его берёт расчёт, 4.8).
     */
    fun normalize(s: Sharing): Sharing = when (s.owner) {
        Owner.SELF -> s.copy(ownerColleagueId = null, colleagueId = s.colleagueId.takeIf { s.conductedBy != ConductedBy.SELF })
        Owner.COLLEAGUE -> s.copy(colleagueId = s.ownerColleagueId)
    }

    /** Чего не хватает, чтобы сохранить: коллега не выбран — «Выберите коллегу». */
    fun problem(s: Sharing): String? {
        val n = normalize(s)
        val needs = n.owner == Owner.COLLEAGUE || n.conductedBy != ConductedBy.SELF
        return if (needs && n.colleague == null) "Выберите коллегу" else null
    }

    /**
     * Правка «На ком числится» и «Кто обычно ведёт» у группы переходит на будущую неотмеченную тренировку, если та
     * ещё по прежним значениям группы [old]; изменённую в карточке отдельно — не трогает.
     */
    fun follows(training: Sharing, old: Sharing): Boolean = normalize(training) == normalize(old)
}
