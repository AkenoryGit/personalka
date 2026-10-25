package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.money.Sharing

/** Слова совместного ведения групп (ТЗ 4.8, 5.4, 5.10, 5.16, шаг 3.7г). */
object CoCoachingFormat {
    private const val DOT = " · "

    const val COLLEAGUES = "Коллеги"
    const val NEW = "Новый коллега"
    const val NAME = "Имя"
    const val NAME_HINT = "Как вы зовёте коллегу: «Лена»"
    const val SCHEME = "Схема выплат коллеги"
    const val SCHEME_NOTE = "Нужна для доли налога, когда группа числится на коллеге."
    const val EMPTY = "Коллег пока нет"
    const val EMPTY_TEXT = "Добавьте тех, с кем ведёте группы или кто вас заменяет."
    const val ADD = "Коллега"
    const val ARCHIVE = "Убрать в архив"
    const val UNARCHIVE = "Вернуть из архива"
    const val ARCHIVED = "В архиве"
    const val NO_COLLEAGUES = "Сначала добавьте коллегу: «Ещё → Настройки → Коллеги»."

    const val OWNER = "На ком числится"
    const val DEFAULT_CONDUCTED = "Кто обычно ведёт"
    const val CONDUCTED = "Проводим"
    const val WHO = "Кто ведёт"
    const val ME = "Я"
    const val COLLEAGUE = "Коллега"
    const val WHICH = "Какой коллега"
    const val GROUP_NOTE = "Будущие тренировки группы получат новые значения; изменённые в карточке — останутся."

    fun conducted(c: ConductedBy): String = when (c) {
        ConductedBy.SELF -> "Один"
        ConductedBy.SELF_AND_COLLEAGUE -> "С коллегой"
        ConductedBy.COLLEAGUE -> "Коллега (замена)"
    }

    /** «2 коллеги» в строке настроек; нет — «Пока нет». */
    fun count(n: Int): String = if (n == 0) "Пока нет" else RuText.count(n, "коллега", "коллеги", "коллег")

    /** Строка «Числится на: я» / «Числится на: Лена». */
    fun ownerLine(s: Sharing, names: Map<String, String>): String =
        "Числится на: " + if (s.owner == Owner.SELF) "я" else name(s.ownerColleagueId, names)

    /** Строка «Проводим: один» / «Проводим: с коллегой · Лена» / «Проводим: коллега (замена) · Лена». */
    fun conductedLine(s: Sharing, names: Map<String, String>, prefix: String = "Проводим: "): String {
        val what = conducted(s.conductedBy).replaceFirstChar { it.lowercaseChar() }
        val who = if (s.conductedBy == ConductedBy.SELF) null else name(s.colleague, names)
        return prefix + listOfNotNull(what, who).joinToString(DOT)
    }

    /** В списке групп у группы коллеги: «числится: Лена»; своя — ничего. */
    fun listNote(s: Sharing, names: Map<String, String>): String? =
        if (s.owner == Owner.COLLEAGUE) "числится: " + name(s.ownerColleagueId, names) else null

    private fun name(id: String?, names: Map<String, String>): String = id?.let { names[it] } ?: SalaryFormat.COLLEAGUE
}
