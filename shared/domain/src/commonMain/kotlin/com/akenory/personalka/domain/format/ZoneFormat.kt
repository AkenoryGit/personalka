package com.akenory.personalka.domain.format

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

/** Строки зон календаря (ТЗ 5.3): подписи на запретах и дежурствах и окно по нажатию на зону. */
object ZoneFormat {
    private const val DOT = " · "

    /** Заголовок окна запрета. */
    const val RESTRICTION = "Запрет"

    /** Запрет в окне: «ДБ 17:00–18:00 — групповая тренировка»; без причины — «ДБ 17:00–18:00». */
    fun restriction(place: String, startMinute: Int, endMinute: Int, reason: String): String =
        withReason("$place " + DateFormat.timeRange(startMinute, endMinute), reason)

    /** Подпись на штриховке: «ДБ · групповая тренировка»; без причины — «ДБ». */
    fun restrictionLabel(place: String, reason: String): String =
        if (reason.isBlank()) place else place + DOT + inPhrase(reason)

    /** Дежурство в окне: «ББ 11:00–15:00 — дежурство»; за коллегу — «ББ 11:00–15:00 — дежурство за Александра». */
    fun duty(place: String, startMinute: Int, endMinute: Int, typeName: String, coverFor: String? = null): String =
        withReason("$place " + DateFormat.timeRange(startMinute, endMinute), typeName) + coverFor.tail(" ")

    /** Подпись на заливке дежурства: «Дежурство · ББ»; за коллегу — «Дежурство · ББ · за Александра». */
    fun dutyLabel(typeName: String, place: String, coverFor: String? = null): String = typeName + DOT + place + coverFor.tail(DOT)

    /** За кого дежурство: «за Александра»; «за» не отрывается от имени переносом. */
    fun coverFor(name: String): String = "за" + RuText.NBSP + name

    /** Поле «За кого» в форме дежурства (5.5). */
    const val COVER_FOR_LABEL = "За кого"
    const val COVER_FOR_PLACEHOLDER = "Необязательно, например: Александра"

    private fun String?.tail(separator: String): String = if (isNullOrBlank()) "" else separator + coverFor(this)

    /**
     * Как повторяется запрет: «Каждый вт и чт», «Каждую ср», «Каждое вс», «Каждый день», «Каждый пн, ср и пт до 31.12.2026»;
     * разовый — «Только в этот день». Род — по первому дню: каждый вторник, каждую среду, каждое воскресенье.
     */
    fun repeat(weekdays: Set<DayOfWeek>, validTo: LocalDate?): String {
        if (weekdays.isEmpty()) return "Только в этот день"
        val days = weekdays.sortedBy { it.isoDayNumber }
        val text = if (days.size == 7) {
            "Каждый день"
        } else {
            val every = when (days.first()) {
                DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY -> "Каждую"
                DayOfWeek.SUNDAY -> "Каждое"
                else -> "Каждый"
            }
            val names = days.map { DateFormat.weekdayShort(it) }
            val list = if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " и " + names.last()
            "$every $list"
        }
        return if (validTo == null) text else "$text до ${DateFormat.full(validTo)}"
    }

    /** Окно, когда тренировку ставят под запрет (4.5): вопрос и две кнопки. */
    const val CONFLICT_QUESTION = "Поставить всё равно?"
    const val CONFLICT_PLACE = "Поставить"
    const val CONFLICT_CANCEL = "Отмена"

    /**
     * Запрет в окне «Поставить всё равно?» (4.5) — полным названием места, как в форме: «Малый бассейн
     * 17:00–18:30: групповая тренировка»; без причины — «Малый бассейн 17:00–18:30».
     */
    fun conflict(placeName: String, startMinute: Int, endMinute: Int, reason: String): String {
        val head = "$placeName " + DateFormat.timeRange(startMinute, endMinute)
        return if (reason.isBlank()) head else "$head: ${inPhrase(reason)}"
    }

    // --- Удаление из окна зоны (шаг 5.3р) ---

    const val DELETE = "Удалить"
    const val KEEP = "Не удалять"

    const val RESTRICTION_DELETE_TITLE = "Удалить запрет?"

    /** «Только этот день — сб 31.10»: запрет снят на один день, как «Пропустить этот день». */
    fun onlyThisDay(date: LocalDate): String = "Только этот день — ${DateFormat.short(date)}"
    const val THIS_AND_FOLLOWING = "Этот и все следующие"

    /** «Запрет удалён с сб 31.10». */
    fun restrictionEnded(date: LocalDate): String = "Запрет удалён с ${DateFormat.short(date)}"
    const val RESTRICTION_DELETED = "Запрет удалён"

    const val DUTY_DELETE_TITLE = "Удалить дежурство?"
    const val DUTY_DELETE_DONE_TITLE = "Удалить прошедшее дежурство?"
    const val DUTY_DELETE_TEXT = "Вернуть его можно только сразу — кнопкой «Отменить» в сообщении."
    const val DUTY_DELETE_DONE_TEXT = "Оно уйдёт из зарплаты. Вернуть его можно только сразу — кнопкой «Отменить» в сообщении."

    /** «Только это — сб 31.10». */
    fun dutyOnly(date: LocalDate): String = "Только это — ${DateFormat.short(date)}"
    const val DUTY_FOLLOWING = "Это и все следующие"
    const val DUTY_FOLLOWING_TITLE = "Удалить это и все следующие?"

    /** «Дежурства серии с сб 31.10 — всего 9 дежурств. Новые по ней больше не появятся. Прошедшие не меняются.» */
    fun dutyFollowingText(count: Int, from: LocalDate): String =
        "Дежурства серии с ${DateFormat.short(from)} — всего ${duties(count)}. Новые по ней больше не появятся. " +
            "Прошедшие не меняются."

    /** «Удалить 9 дежурств». */
    fun deleteDuties(count: Int): String = "Удалить ${duties(count)}"

    const val DUTY_DELETED = "Дежурство удалено"

    /** «Удалено: 9 дежурств с сб 31.10». */
    fun dutiesDeleted(count: Int, from: LocalDate): String = "Удалено: ${duties(count)} с ${DateFormat.short(from)}"

    private fun duties(n: Int): String = RuText.count(n, "дежурство", "дежурства", "дежурств")

    /** Сообщение после «Пропустить этот день»: «Запрет снят на вт 20.10». */
    fun skipped(date: LocalDate): String = "Запрет снят на ${DateFormat.short(date)}"

    /**
     * Причина посреди фразы — со строчной: «Групповая тренировка» → «групповая тренировка». Сокращение
     * («ДЮСШ», «МБ закрыт») остаётся как есть: вторая буква у него заглавная.
     */
    fun inPhrase(text: String): String {
        val clean = text.trim()
        if (clean.length < 2 || !clean[1].isLowerCase()) return clean
        return clean.first().lowercase() + clean.substring(1)
    }

    private fun withReason(head: String, reason: String): String =
        if (reason.isBlank()) head else "$head — ${inPhrase(reason)}"
}
