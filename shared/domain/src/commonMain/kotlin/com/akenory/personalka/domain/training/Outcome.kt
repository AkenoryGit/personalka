package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDateTime

/**
 * Один итоговый статус клиента на тренировке — его видят тренер и родители (ТЗ 4.1).
 * Слова одни и те же в приложении и в кабинете.
 */
enum class ClientOutcome(val label: String) {
    PLANNED("Запланировано"),
    DONE("Проведено"),

    /** Отменено заранее — или ребёнок не пришёл на проведённую группу. */
    CANCELLED("Отменено"),
    CANCELLED_CHARGED("Отмена со списанием"),
    ;

    companion object {
        fun of(training: TrainingStatus, participation: ParticipationStatus): ClientOutcome = when (participation) {
            ParticipationStatus.CANCELLED_CHARGED -> CANCELLED_CHARGED
            ParticipationStatus.CANCELLED -> CANCELLED
            ParticipationStatus.ATTENDED -> DONE
            ParticipationStatus.EXPECTED -> if (training == TrainingStatus.CANCELLED) CANCELLED else PLANNED
        }
    }
}

/**
 * Где сейчас тренировка относительно времени — от этого зависят кнопки карточки и «Ближайшей» (5.2, 5.4):
 * до начала минус 15 минут — «Отменить», «Изменить»; дальше — «Проведено» и «Дневник»; с начала — «Идёт».
 */
enum class TrainingPhase {
    /** Раньше чем за 15 минут до начала. */
    UPCOMING,

    /** Последние 15 минут до начала: уже можно отмечать. */
    STARTING_SOON,

    /** Идёт: статус «Идёт · до конца N мин» (в базе — `PLANNED`). */
    ONGOING,

    /** Закончилась. */
    ENDED,
    ;

    companion object {
        fun of(training: TrainingState, now: LocalDateTime): TrainingPhase = when {
            now >= training.end -> ENDED
            now >= training.start -> ONGOING
            now >= StatusRules.markableFrom(training) -> STARTING_SOON
            else -> UPCOMING
        }
    }
}
