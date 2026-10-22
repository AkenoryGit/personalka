package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import kotlinx.datetime.LocalDateTime

/** Причины отмены — чипы панели отмены (ТЗ 5.6). Чипа «Болезнь» нет: сведения о здоровье не собираются (10.3). */
object CancelReasons {
    const val CANNOT_COME = "Не сможет"
    const val TRIP = "Отъезд"
    const val COMPETITION = "Соревнования"
    const val BY_COACH = "По инициативе тренера"
    const val BY_CLUB = "По инициативе клуба"

    val DEFAULT_CHIPS: List<String> = listOf(CANNOT_COME, TRIP, COMPETITION, BY_COACH, BY_CLUB)

    private val withoutCharge = setOf(BY_COACH.lowercase(), BY_CLUB.lowercase())

    /** «По инициативе тренера» и «По инициативе клуба» всегда без списания (4.2, п. 3). */
    fun isWithoutCharge(reason: String?): Boolean {
        val text = reason?.trim()?.lowercase() ?: return false
        return text in withoutCharge
    }

    /** Пустая причина — нет причины. */
    fun normalize(reason: String?): String? = reason?.trim()?.takeIf { it.isNotEmpty() }
}

/** Почему отмена со списанием или без — для итога в панели отмены (5.6). */
enum class ChargeCause {
    /** Предупредили позже начала. */
    AFTER_START,

    /** Предупредили меньше чем за `cancel_notice_minutes` до начала. */
    LATE,

    /** Предупредили вовремя. */
    IN_TIME,

    /** Отменил тренер или клуб. */
    NOT_BY_CLIENT,

    /** СПТ бесплатна для клиента — списывать нечего. */
    FREE_SPT,
}

/** Итог правила списания: статус участия, причина и запас в минутах («предупредили за 40 мин»). */
data class ChargeDecision(val charged: Boolean, val cause: ChargeCause, val noticeMinutes: Long) {
    val status: ParticipationStatus
        get() = if (charged) ParticipationStatus.CANCELLED_CHARGED else ParticipationStatus.CANCELLED
}

/** Правило списания (ТЗ 4.2). Пример 19: начало 10:00, порог 60 — 09:20 и 10:05 со списанием, 09:00 и 08:59 без. */
object ChargeRule {
    /**
     * Запас = [start] − [notifiedAt], оба по часам клуба с точностью до минуты. Запас меньше порога
     * или предупредили после начала — со списанием; ровно 60 минут при пороге 60 — без.
     */
    fun decide(
        kind: TrainingKind,
        start: LocalDateTime,
        notifiedAt: LocalDateTime,
        reason: String?,
        noticeThresholdMinutes: Int,
    ): ChargeDecision {
        val notice = ClubTime.minutesBetween(notifiedAt, start)
        return when {
            kind == TrainingKind.SPT -> ChargeDecision(false, ChargeCause.FREE_SPT, notice)
            CancelReasons.isWithoutCharge(reason) -> ChargeDecision(false, ChargeCause.NOT_BY_CLIENT, notice)
            notice < 0 -> ChargeDecision(true, ChargeCause.AFTER_START, notice)
            notice < noticeThresholdMinutes -> ChargeDecision(true, ChargeCause.LATE, notice)
            else -> ChargeDecision(false, ChargeCause.IN_TIME, notice)
        }
    }
}
