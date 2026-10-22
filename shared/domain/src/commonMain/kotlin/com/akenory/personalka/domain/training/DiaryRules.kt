package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.model.TrainingStatus

/** Дневник тренировки (ТЗ 5.7). */
object DiaryRules {
    /**
     * Нет дневника — оранжевая точка в календаре (5.3) и пункт «Нужно сделать» (5.2): тренировка проведена,
     * дневник включён в настройках (3.2), у типа ведётся факт (3.3), а факт пуст. План пишут до занятия,
     * поведение — по желанию, поэтому дневник считается заполненным по факту. У дежурства факта нет.
     */
    fun missing(status: TrainingStatus, diaryEnabled: Boolean, keepsFact: Boolean, fact: String?): Boolean =
        status == TrainingStatus.DONE && diaryEnabled && keepsFact && fact.isNullOrBlank()
}
