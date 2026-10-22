package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDateTime

/**
 * Участие клиента в тренировке — то, что меняют правила статусов (ТЗ 3.5, 4.1, 4.2).
 * Слой данных переводит его из `ParticipationEntity` и обратно; остальные поля записи правила не трогают.
 */
data class ParticipantState(
    val clientId: String,
    val status: ParticipationStatus = ParticipationStatus.EXPECTED,
    val cancelReason: String? = null,
    /** Когда предупредили, по часам клуба с точностью до минуты. Нет — значит, отменил не клиент. */
    val notifiedAt: LocalDateTime? = null,
    /** Списание поставлено вручную и расходится с правилом 4.2. */
    val chargeOverridden: Boolean = false,
) {
    val isCancelled: Boolean
        get() = status == ParticipationStatus.CANCELLED || status == ParticipationStatus.CANCELLED_CHARGED
}

/**
 * Тренировка для правил статусов (ТЗ 3.5): тип в расчётах, время клуба, статус и участники.
 * Слой данных собирает её из `TrainingEntity` и участий; `doneAt` там — момент UTC (`ClubTime.toMillis`).
 */
data class TrainingState(
    val kind: TrainingKind,
    val start: LocalDateTime,
    val durationMin: Int,
    val status: TrainingStatus = TrainingStatus.PLANNED,
    /** Причина отмены всей тренировки. */
    val cancelReason: String? = null,
    val participants: List<ParticipantState> = emptyList(),
    /** Когда стала «Проведено», по часам клуба: конец тренировки, если закрылась сама. */
    val doneAt: LocalDateTime? = null,
) {
    val end: LocalDateTime get() = TrainingTime.end(start, durationMin)

    fun participant(clientId: String): ParticipantState =
        requireNotNull(participants.firstOrNull { it.clientId == clientId }) { "Клиента $clientId нет в тренировке" }
}

/** Настройки тренера, от которых зависят статусы (ТЗ 3.2, `CoachSettings`). */
data class StatusSettings(
    /** Меньше этого до начала — отмена со списанием. */
    val cancelNoticeMinutes: Int = 60,
    /** Персональные без отмены становятся «Проведено» в момент окончания. */
    val autoDonePersonal: Boolean = true,
)
