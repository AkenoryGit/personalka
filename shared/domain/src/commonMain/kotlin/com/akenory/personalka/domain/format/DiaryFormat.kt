package com.akenory.personalka.domain.format

import kotlinx.datetime.LocalDateTime

/** Строки дневника тренировки (ТЗ 5.7, шаг 2.1). */
object DiaryFormat {
    private const val DOT = " · "

    const val TITLE = "Дневник"

    /** Справа в шапке: «ещё 2 без дневника»; других нет — `null`, строки нет. */
    fun left(count: Int): String? = if (count <= 0) null else "ещё ${RuText.digits(count.toLong())} без дневника"

    /** Под именем: «вт 20.10 · 13:30–14:20 · ПТ · ББ». */
    fun whenLine(start: LocalDateTime, end: LocalDateTime, typeShort: String, placeShort: String): String =
        DateFormat.short(start.date) + DOT + DateFormat.timeRange(start, end) + DOT + typeShort + DOT + placeShort

    /** Подсказка у полей (10.3): сведения о здоровье не собираются. */
    const val NO_HEALTH = "Без диагнозов и сведений о здоровье"

    /** Под фактом группы: факт видят родители всех детей в кабинете (6.2). */
    const val GROUP_HINT = "Видят родители всех детей — не пишите имён"

    const val PLAN_WAS = "План был"
    const val FACT = TrainingCardFormat.FACT
    const val BEHAVIOR = TrainingCardFormat.BEHAVIOR

    /** «Поведение · Ковалёв Т.» — у сплита у каждого. */
    fun behaviorOf(name: String): String = TrainingCardFormat.behaviorOf(name)

    /**
     * «План на следующую · чт 22.10, 13:30»; следующей нет — план ждёт первую новую тренировку (4.3):
     * «План на следующую · в первую новую».
     */
    fun nextPlan(next: LocalDateTime?): String = "План на следующую" + DOT +
        if (next == null) "в первую новую" else DateFormat.short(next.date) + ", " + DateFormat.time(next.time)

    const val EDIT_PLAN = "Исправить план"
    const val VOICE = "Голосовой ввод"
    const val SAVED = "Черновик сохранён"
    const val NEXT = "Следующая без дневника"
    const val DONE = "Готово"
}
