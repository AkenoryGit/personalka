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

    /** Дежурство в окне: «ББ 11:00–15:00 — дежурство». */
    fun duty(place: String, startMinute: Int, endMinute: Int, typeName: String): String =
        withReason("$place " + DateFormat.timeRange(startMinute, endMinute), typeName)

    /** Подпись на заливке дежурства: «Дежурство · ББ». */
    fun dutyLabel(typeName: String, place: String): String = typeName + DOT + place

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
