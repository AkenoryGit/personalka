package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.model.TrainingKind
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Проведённая тренировка клиента, на которой он был: начало, вид, тип и место (4.15). */
data class Visit(
    val start: LocalDateTime,
    val kind: TrainingKind,
    val typeId: String,
    val locationId: String,
)

/**
 * Что нужно знать о клиенте для «Без записи» (4.15). [visits] — проведённые тренировки, где он был, любого вида;
 * [booked] — есть ли будущая запланированная тренировка индивидуального формата, где его ждут. [contactedOn] —
 * день, когда нажали «Написал»; [pausedUntil] — «Пауза до…». [waiting] — он в листе ожидания (4.16).
 * [dismissedAt] — когда нажали «Убрать из списка» (шаг 3.9н), время клуба.
 */
data class FollowupInput(
    val clientId: String,
    val active: Boolean,
    val visits: List<Visit>,
    val booked: Boolean,
    val contactedOn: LocalDate? = null,
    val pausedUntil: LocalDate? = null,
    val waiting: Boolean = false,
    val dismissedAt: LocalDateTime? = null,
)

/**
 * Клиент в «Не записаны дальше». [last] — последнее посещение индивидуального формата; [afterSpt] — дата СПТ, если
 * последней была СПТ (4.9); [contactedOn] — «писали 18.10», когда «Написал» нажали после последнего посещения.
 */
data class NotBooked(
    val clientId: String,
    val last: Visit,
    val afterSpt: LocalDate?,
    val contactedOn: LocalDate?,
)

/**
 * Клиенты без записи (ТЗ 4.15): ходит, а дальше не записан. Список не хранится — считается заново из тренировок.
 *
 * Группы не считаются ни в посещениях, ни в записи: ребёнка «записывает» расписание группы, а клиент, который ходит
 * и на ПТ, и в группу, без следующей ПТ попадает в список, хотя группа у него впереди.
 */
object FollowupRules {
    /** N по умолчанию: посещение за последние 14 дней, включая сегодня (`followup_days`). */
    const val DEFAULT_DAYS = 14

    /** «Написал» скрывает клиента на 3 дня. */
    const val CONTACT_HIDE_DAYS = 3

    /** Индивидуальные форматы: ПТ, сплит, СПТ. */
    val INDIVIDUAL = setOf(TrainingKind.PERSONAL, TrainingKind.SPLIT, TrainingKind.SPT)

    /** Последнее посещение индивидуального формата; групп и прочего нет — `null`. */
    fun lastVisit(visits: List<Visit>): Visit? = visits.filter { it.kind in INDIVIDUAL }.maxByOrNull { it.start }

    /**
     * Клиент без записи по правилу 4.15, без «Написал» и «Пауза»: активный, был на индивидуальной за последние
     * [days] дней и не записан дальше. Такому в карточке — метка «Нет записи». Клиент из листа ожидания — не здесь.
     *
     * «Убрать из списка» (шаг 3.9н) — тренер знает, что всё в порядке (перешёл в группу): клиента нет ни в списке,
     * ни с меткой, пока не придёт на новую индивидуальную после нажатия, — тогда это новая ПТ без записи.
     */
    fun notBooked(input: FollowupInput, today: LocalDate, days: Int = DEFAULT_DAYS): Visit? {
        if (!input.active || input.booked || input.waiting) return null
        val last = lastVisit(input.visits) ?: return null
        if (input.dismissedAt != null && last.start <= input.dismissedAt) return null
        return last.takeIf { it.start.date >= firstDay(today, days) }
    }

    /**
     * «Не записаны дальше» (4.15, 5.8): сначала «после СПТ», дальше по последнему посещению, старые выше. Скрыты те,
     * кому писали меньше 3 дней назад, и те, у кого пауза ещё идёт (до выбранного дня, в сам день он возвращается).
     */
    fun list(inputs: List<FollowupInput>, today: LocalDate, days: Int = DEFAULT_DAYS): List<NotBooked> =
        inputs.mapNotNull { input ->
            val last = notBooked(input, today, days) ?: return@mapNotNull null
            val contacted = input.contactedOn?.takeIf { it >= last.start.date }
            if (contacted != null && contacted.daysUntil(today) < CONTACT_HIDE_DAYS) return@mapNotNull null
            if (input.pausedUntil != null && today < input.pausedUntil) return@mapNotNull null
            NotBooked(
                clientId = input.clientId,
                last = last,
                afterSpt = last.start.date.takeIf { last.kind == TrainingKind.SPT },
                contactedOn = contacted,
            )
        }.sortedWith(compareBy<NotBooked> { it.afterSpt == null }.thenBy { it.last.start }.thenBy { it.clientId })

    /** Первый день окна: 14 дней, включая сегодня, — с 10.10 при сегодня 23.10. */
    fun firstDay(today: LocalDate, days: Int): LocalDate = today.minus(days - 1, DateTimeUnit.DAY)

    /**
     * «Записать» (5.8): тот же день недели и время, что у последнего посещения, — ближайший такой день после
     * [now]. Сегодня подходит, если время ещё не наступило.
     */
    fun bookingStart(last: Visit, now: LocalDateTime): LocalDateTime {
        val time = last.start.time
        var date = now.date
        while (date.dayOfWeek != last.start.dayOfWeek || LocalDateTime(date, time) <= now) date = date.plus(1, DateTimeUnit.DAY)
        return LocalDateTime(date, time)
    }
}
