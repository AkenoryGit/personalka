package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ScheduleLine
import com.akenory.personalka.domain.group.Absence
import com.akenory.personalka.domain.group.GroupAbsence
import com.akenory.personalka.domain.model.Sex

/** Строки о группах: список, карточка, состав (5.10). */
object GroupFormat {
    private const val DOT = " · "

    /** «1 группа», «2 группы», «5 групп». */
    fun groups(count: Int): String = RuText.count(count, "группа", "группы", "групп")

    /** «1 ребёнок», «3 ребёнка», «12 детей». */
    fun children(count: Int): String = RuText.count(count, "ребёнок", "ребёнка", "детей")

    /**
     * Строка группы в списке: дни и время, место, сколько детей — «Сб 09:00 · ББ · 12 детей».
     * Без серии — «без расписания».
     */
    fun subtitle(schedule: List<ScheduleLine>, placeShort: String?, count: Int): String {
        val days = schedule.sortedWith(compareBy({ line -> DateFormat.WEEK.indexOfFirst { it in line.weekdays } }, { it.start }))
            .joinToString("; ") { ClientFormat.daysTime(it) }
            .ifEmpty { "без расписания" }
        return listOfNotNull(days, placeShort, children(count)).joinToString(DOT)
    }

    /** Нажатие на ребёнка в составе (шаг 3.9т): состав — просто список, без дат. */
    const val LEAVE = "Убрать из группы"
    const val LEAVE_NOTE = "Прошедшие тренировки его помнят, в следующих его больше не ждут."

    /** Сообщение с «Отменить»: «Белова Ева убрана из группы». */
    fun left(name: String, sex: Sex?): String = RuText.bySex(sex, "$name убран из группы", "$name убрана из группы", "$name — не в группе")

    /** Блок карточки группы и пункт «Нужно сделать» (шаг 3.9у): дети, которые давно не ходят. */
    const val ABSENT_TITLE = "Не ходят 30 дней"
    const val ABSENT_TODO = "Не ходят в группу"
    const val ARCHIVE = "В архив"
    const val KEEP = "Оставить"

    /** Заголовок блока: есть те, кто ходит редко (шаг 3.14б), — «Не ходят или ходят редко». */
    fun absentTitle(list: List<Absence>): String = if (list.any { it.rarePercent != null }) "Не ходят или ходят редко" else ABSENT_TITLE

    /** «не была с 19.09», «ни разу не был»; ходит редко — «ходит на 40 % занятий из 20». */
    fun absentSince(absence: Absence, sex: Sex?): String {
        absence.rarePercent?.let { p ->
            return "ходит на $p${RuText.NBSP}% занятий из ${RuText.digits(absence.rareTotal.toLong())}"
        }
        val verb = RuText.bySex(sex, "не был", "не была", "не был(а)")
        return if (absence.attendedEver) "$verb с ${DateFormat.dayMonth(absence.since)}" else "ни разу $verb"
    }

    /** После «Оставить»: [keeps] — сколько раз уже оставили (с этим нажатием). */
    fun kept(keeps: Int): String = if (keeps >= GroupAbsence.KEEPS) "Оставили. Больше не спрошу" else "Оставили. Спрошу ещё раз через ${GroupAbsence.DAYS} дней"

    /** Подпись пункта «Нужно сделать»: «Белова Ева (Сб 9:00 ББ), Гусев Борис (Вт 18:00 ДБ) и ещё 1». */
    fun absentNote(names: List<String>): String = when {
        names.size <= 2 -> names.joinToString(", ")
        else -> names.take(2).joinToString(", ") + " и ещё ${names.size - 2}"
    }

    /** Посещаемость проведённой: «пришли 5 из 12». */
    fun attended(attended: Int, total: Int): String = "пришли $attended из $total"

    /** Дети «на раз»: «1 на раз». */
    fun guests(count: Int): String = "$count на раз"
}
