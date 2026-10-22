package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.model.ParticipationStatus.ATTENDED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED
import com.akenory.personalka.domain.model.ParticipationStatus.CANCELLED_CHARGED
import com.akenory.personalka.domain.model.ParticipationStatus.EXPECTED
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDateTime

/**
 * Переходы статусов тренировки и участия (ТЗ 4.1, 4.2). Каждая функция получает тренировку и
 * возвращает новую, ничего не сохраняя; «сейчас» — время клуба (`ClubTime.now`).
 * Недопустимый переход — `IllegalArgumentException`: экран не показывает кнопку, если `can…` даёт `false`.
 *
 * Тренировка с клиентами «по одному» — ПТ, сплит, СПТ и `OTHER`: закрываются «Проведено» или сами в конце.
 * Группа (и мини-группа) — отметкой детей. Дежурство — без клиентов.
 */
object StatusRules {
    /** «Проведено», «Все пришли» и отметка детей — с начала минус 15 минут. */
    const val MARK_BEFORE_START_MINUTES: Int = 15

    fun markableFrom(training: TrainingState): LocalDateTime =
        ClubTime.plusMinutes(training.start, -MARK_BEFORE_START_MINUTES)

    // --- Проведено ---

    fun canMarkDone(training: TrainingState, now: LocalDateTime): Boolean =
        training.status == TrainingStatus.PLANNED && now >= markableFrom(training)

    /**
     * «Проведено», у группы — «Все пришли»: все ожидаемые клиенты приходят, тренировка проведена.
     * Отменённые клиенты остаются отменёнными (сплит с одним ребёнком, ребёнок группы с причиной).
     */
    fun markDone(training: TrainingState, now: LocalDateTime): TrainingState {
        require(canMarkDone(training, now)) { "«Проведено» доступно с ${markableFrom(training)}, статус ${training.status}" }
        return training.copy(
            status = TrainingStatus.DONE,
            doneAt = now,
            participants = training.participants.map { if (it.status == EXPECTED) attended(it) else it },
        )
    }

    /**
     * Нажатие на ребёнка в группе (5.4). До закрытия группы: «пришёл» ⇄ «ещё не отмечен»,
     * отменённый ребёнок, который всё же пришёл, — «пришёл». В проведённой группе (исправление прошлого):
     * «пришёл» ⇄ «не пришёл».
     */
    fun toggleAttendance(training: TrainingState, clientId: String, now: LocalDateTime): TrainingState {
        require(training.kind == TrainingKind.GROUP) { "Отметка по одному — только у групп" }
        val p = training.participant(clientId)
        val next = when (training.status) {
            TrainingStatus.PLANNED -> {
                require(now >= markableFrom(training)) { "Отмечать можно с ${markableFrom(training)}" }
                if (p.status == ATTENDED) reset(p) else attended(p)
            }
            TrainingStatus.DONE -> if (p.status == ATTENDED) noShow(p) else attended(p)
            TrainingStatus.CANCELLED -> throw IllegalArgumentException("Группа отменена — сначала «Вернуть»")
        }
        return training.replace(next)
    }

    /**
     * Что происходит в момент окончания. Вызывается для каждой прошедшей `PLANNED` — при открытии
     * приложения и по таймеру, поэтому повторный вызов ничего не меняет.
     *
     * - ПТ, сплит, СПТ и `OTHER` при `autoDonePersonal` — «Проведено», ожидаемые клиенты пришли.
     *   Сплит с одним отменённым ребёнком тоже: второй пришёл.
     * - Группа: отмечен хоть один — «Проведено», неотмеченные — «не пришёл»; не отмечен никто — ждёт отметки.
     * - Дежурство — «Проведено»: отмечать в нём нечего.
     *
     * `doneAt` — конец тренировки, даже если приложение открыли позже.
     */
    fun closeAtEnd(training: TrainingState, now: LocalDateTime, settings: StatusSettings): TrainingState {
        if (training.status != TrainingStatus.PLANNED || now < training.end) return training
        val end = training.end
        return when (training.kind) {
            TrainingKind.DUTY -> training.copy(status = TrainingStatus.DONE, doneAt = end)
            TrainingKind.GROUP ->
                if (training.participants.none { it.status == ATTENDED }) training
                else training.copy(
                    status = TrainingStatus.DONE,
                    doneAt = end,
                    participants = training.participants.map { if (it.status == EXPECTED) noShow(it) else it },
                )
            else ->
                if (!settings.autoDonePersonal) training
                else training.copy(
                    status = TrainingStatus.DONE,
                    doneAt = end,
                    participants = training.participants.map { if (it.status == EXPECTED) attended(it) else it },
                )
        }
    }

    /** Прошла, а «Проведено» не стоит — пункт в «Нужно сделать» (5.2). Проверять после [closeAtEnd]. */
    fun needsMarking(training: TrainingState, now: LocalDateTime): Boolean =
        training.status == TrainingStatus.PLANNED && now >= training.end

    // --- Отмена (4.2) ---

    /**
     * Отмена отдельных клиентов: сплит и группа — по одному, ПТ и СПТ — их единственный клиент.
     * Каждому — статус по правилу 4.2; [charged] — переключатель «Со списанием», если тренер изменил итог.
     * Отменены все клиенты — отменена и тренировка, с этой причиной.
     */
    fun cancelClients(
        training: TrainingState,
        clientIds: Collection<String>,
        reason: String?,
        notifiedAt: LocalDateTime,
        settings: StatusSettings,
        charged: Boolean? = null,
    ): TrainingState {
        require(training.status != TrainingStatus.CANCELLED) { "Тренировка уже отменена" }
        require(training.kind != TrainingKind.DUTY) { "В дежурстве нет клиентов" }
        require(clientIds.isNotEmpty()) { "Не выбран ни один клиент" }
        clientIds.forEach { training.participant(it) }
        val why = CancelReasons.normalize(reason)
        val notified = ClubTime.toMinute(notifiedAt)
        val updated = training.participants.map { p ->
            if (p.clientId !in clientIds) {
                p
            } else {
                val auto = ChargeRule.decide(training.kind, training.start, notified, why, settings.cancelNoticeMinutes).charged
                val final = charged ?: auto
                p.copy(
                    status = if (final) CANCELLED_CHARGED else CANCELLED,
                    cancelReason = why,
                    notifiedAt = notified,
                    chargeOverridden = final != auto,
                )
            }
        }
        val allCancelled = updated.all { it.isCancelled }
        return if (allCancelled) {
            training.copy(status = TrainingStatus.CANCELLED, cancelReason = why, doneAt = null, participants = updated)
        } else {
            training.copy(participants = updated)
        }
    }

    /**
     * Общая кнопка «Отменить» — «Всю тренировку».
     * - ПТ, СПТ, сплит, `OTHER`: каждый ещё не отменённый клиент — по правилу 4.2, как в [cancelClients].
     * - Группа: тренировка и все дети — «Отменено» без списания, причина общая; время предупреждения
     *   детям не пишется — списания здесь нет.
     * - Дежурство: без причины и списания.
     */
    fun cancelTraining(
        training: TrainingState,
        reason: String?,
        notifiedAt: LocalDateTime,
        settings: StatusSettings,
        charged: Boolean? = null,
    ): TrainingState {
        require(training.status != TrainingStatus.CANCELLED) { "Тренировка уже отменена" }
        val why = CancelReasons.normalize(reason)
        return when (training.kind) {
            TrainingKind.DUTY -> training.copy(status = TrainingStatus.CANCELLED, cancelReason = null, doneAt = null)
            TrainingKind.GROUP -> training.copy(
                status = TrainingStatus.CANCELLED,
                cancelReason = why,
                doneAt = null,
                participants = training.participants.map {
                    it.copy(status = CANCELLED, cancelReason = why, notifiedAt = null, chargeOverridden = false)
                },
            )
            else -> {
                val open = training.participants.filterNot { it.isCancelled }.map { it.clientId }
                if (open.isEmpty()) {
                    training.copy(status = TrainingStatus.CANCELLED, cancelReason = why, doneAt = null)
                } else {
                    cancelClients(training, open, why, notifiedAt, settings, charged)
                }
            }
        }
    }

    /** Списание по правилу для уже отменённого клиента: без времени предупреждения — без списания. */
    fun autoCharged(training: TrainingState, clientId: String, settings: StatusSettings): Boolean {
        val p = training.participant(clientId)
        val notified = p.notifiedAt ?: return false
        return ChargeRule.decide(training.kind, training.start, notified, p.cancelReason, settings.cancelNoticeMinutes).charged
    }

    /** Переключатель «Со списанием» у отменённого клиента; `chargeOverridden` — если итог расходится с правилом. */
    fun setCharge(training: TrainingState, clientId: String, charged: Boolean, settings: StatusSettings): TrainingState {
        val p = training.participant(clientId)
        require(p.isCancelled) { "Клиент не отменён" }
        val auto = autoCharged(training, clientId, settings)
        return training.replace(
            p.copy(status = if (charged) CANCELLED_CHARGED else CANCELLED, chargeOverridden = charged != auto),
        )
    }

    // --- Вернуть (4.1) ---

    fun canReturn(training: TrainingState): Boolean = training.status == TrainingStatus.CANCELLED

    /**
     * «Вернуть» у отменённой тренировки: все клиенты снова в ней. Ещё не закончилась — «Запланировано».
     * Закончилась — «Проведено», клиенты пришли; группа — ждёт отметки детей («Нужно сделать»),
     * потому что кто пришёл, неизвестно. Перенесённый план возвращается отдельно (4.3).
     */
    fun returnTraining(training: TrainingState, now: LocalDateTime): TrainingState {
        require(canReturn(training)) { "Вернуть можно только отменённую" }
        val planned = training.copy(
            status = TrainingStatus.PLANNED,
            cancelReason = null,
            doneAt = null,
            participants = training.participants.map(::reset),
        )
        if (now < training.end || training.kind == TrainingKind.GROUP) return planned
        return planned.copy(
            status = TrainingStatus.DONE,
            doneAt = now,
            participants = planned.participants.map(::attended),
        )
    }

    /**
     * «Вернуть» у одного отменённого клиента. В проведённой — он пришёл; в запланированной — снова ожидается.
     * Если тренировка была отменена целиком — она возвращается: до конца — «Запланировано»,
     * после конца — «Проведено» с этим клиентом.
     */
    fun returnClient(training: TrainingState, clientId: String, now: LocalDateTime): TrainingState {
        val p = training.participant(clientId)
        require(p.isCancelled) { "Клиент не отменён" }
        return when (training.status) {
            TrainingStatus.DONE -> training.replace(attended(p))
            TrainingStatus.PLANNED -> training.replace(reset(p))
            TrainingStatus.CANCELLED ->
                if (now < training.end) {
                    training.copy(status = TrainingStatus.PLANNED, cancelReason = null).replace(reset(p))
                } else {
                    training.copy(status = TrainingStatus.DONE, cancelReason = null, doneAt = now).replace(attended(p))
                }
        }
    }

    // --- Внутреннее ---

    private fun attended(p: ParticipantState) =
        p.copy(status = ATTENDED, cancelReason = null, notifiedAt = null, chargeOverridden = false)

    private fun reset(p: ParticipantState) =
        p.copy(status = EXPECTED, cancelReason = null, notifiedAt = null, chargeOverridden = false)

    /** «Не пришёл» в проведённой группе: отмена без причины и списания. */
    private fun noShow(p: ParticipantState) =
        p.copy(status = CANCELLED, cancelReason = null, notifiedAt = null, chargeOverridden = false)

    private fun TrainingState.replace(p: ParticipantState): TrainingState =
        copy(participants = participants.map { if (it.clientId == p.clientId) p else it })
}
