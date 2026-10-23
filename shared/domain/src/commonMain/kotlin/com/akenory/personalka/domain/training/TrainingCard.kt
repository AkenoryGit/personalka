package com.akenory.personalka.domain.training

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDateTime

/** Кнопка внизу карточки тренировки (ТЗ 5.4). */
enum class CardAction {
    /** «Проведено» — главная с начала минус 15 минут. */
    DONE,

    /** «Все пришли» — то же у группы. */
    ALL_CAME,
    CANCEL,
    EDIT,

    /** «Дневник» (5.7) — у идущей и проведённой; у проведённой без дневника — главная. */
    DIARY,

    /** «Вернуть» — единственная кнопка отменённой. */
    RETURN,
}

/** Пункт меню «…» карточки. */
enum class CardMenu { EDIT, COPY, SKIP_DATE, DELETE }

/** Кнопки карточки — не больше трёх, по важности; остальное — в меню «…» (10.7). */
data class CardButtons(val buttons: List<CardAction>, val menu: List<CardMenu>)

/**
 * Карточка тренировки (ТЗ 5.4): какие кнопки, пункты меню и действия в строках клиентов доступны в каком
 * состоянии. Сами переходы статусов — [StatusRules].
 */
object TrainingCardRules {
    /**
     * - До начала минус 15 минут: «Отменить», «Изменить».
     * - С начала минус 15 минут: «Проведено» (у группы — «Все пришли»), «Дневник», «Отменить»; «Изменить» — в «…».
     * - Проведённая: «Дневник», «Изменить», «Отменить»; удалить её нельзя — только отменить.
     * - Отменённая: только «Вернуть».
     *
     * «Дневник» — если он ведётся у этого типа и включён в настройках ([keepsDiary], [DiaryRules.available]).
     * В «…» — «Копировать», «Пропустить дату серии» (будущая тренировка серии, [skippable]) и «Удалить».
     */
    fun buttons(training: TrainingState, now: LocalDateTime, skippable: Boolean, keepsDiary: Boolean = false): CardButtons {
        val skip = listOfNotNull(CardMenu.SKIP_DATE.takeIf { skippable })
        val diary = listOfNotNull(CardAction.DIARY.takeIf { DiaryRules.available(training, now, keepsDiary) })
        return when {
            training.status == TrainingStatus.CANCELLED -> CardButtons(listOf(CardAction.RETURN), listOf(CardMenu.COPY, CardMenu.DELETE))
            training.status == TrainingStatus.DONE -> CardButtons(diary + listOf(CardAction.EDIT, CardAction.CANCEL), listOf(CardMenu.COPY))
            StatusRules.canMarkDone(training, now) -> CardButtons(
                listOf(if (training.kind == TrainingKind.GROUP) CardAction.ALL_CAME else CardAction.DONE) + diary + CardAction.CANCEL,
                listOf(CardMenu.EDIT, CardMenu.COPY) + skip + CardMenu.DELETE,
            )
            else -> CardButtons(listOf(CardAction.CANCEL, CardAction.EDIT), listOf(CardMenu.COPY) + skip + CardMenu.DELETE)
        }
    }

    /** Удалить можно всё, кроме проведённой (5.4). */
    fun canDelete(status: TrainingStatus): Boolean = status != TrainingStatus.DONE

    /**
     * Нажатие на ребёнка в группе (5.4): с начала минус 15 минут, пока группа не отменена; в проведённой —
     * исправление «пришёл» ⇄ «не пришёл».
     */
    fun canToggle(training: TrainingState, now: LocalDateTime): Boolean =
        training.kind == TrainingKind.GROUP && when (training.status) {
            TrainingStatus.PLANNED -> now >= StatusRules.markableFrom(training)
            TrainingStatus.DONE -> true
            TrainingStatus.CANCELLED -> false
        }

    /** В строке клиента — значок отмены, у сплита и группы (и у любой тренировки с несколькими клиентами). */
    fun perClient(training: TrainingState): Boolean =
        training.kind == TrainingKind.GROUP || training.participants.size > 1

    /** Значок отмены у клиента: он не отменён, тренировка не отменена. */
    fun canCancelClient(training: TrainingState, clientId: String): Boolean =
        perClient(training) && training.status != TrainingStatus.CANCELLED &&
            !training.participant(clientId).isCancelled

    /** «Не пришёл» на проведённую группу: отмена без времени предупреждения (4.1). */
    fun noShow(training: TrainingState, p: ParticipantState): Boolean =
        training.status == TrainingStatus.DONE && p.status == ParticipationStatus.CANCELLED && p.notifiedAt == null

    /** Из скольких детей группы считать «пришли 5 из 12»: кого ждут, кто пришёл и кто не пришёл; отменённых нет. */
    fun groupTotal(training: TrainingState): Int =
        training.participants.count { it.status == ParticipationStatus.EXPECTED || it.came || noShow(training, it) }

    /** «Добавить на раз» (5.4, 5.10): в тренировку группы, пока она не отменена; в проведённую — забытого ребёнка. */
    fun canAddGuest(training: TrainingState): Boolean =
        training.kind == TrainingKind.GROUP && training.status != TrainingStatus.CANCELLED

    /**
     * С каким статусом приходит ребёнок «на раз»: когда отмечать уже можно — сразу «пришёл» (его добавляют, потому
     * что он стоит у бортика; в проведённую — потому что был), раньше — ждём, как остальных.
     */
    fun guestStatus(training: TrainingState, now: LocalDateTime): ParticipationStatus =
        if (canToggle(training, now)) ParticipationStatus.ATTENDED else ParticipationStatus.EXPECTED

    /** «Убрать» в строке ребёнка «на раз» — добавили по ошибке; у отменённой группы ничего не меняется. */
    fun canRemoveGuest(training: TrainingState, guest: Boolean): Boolean =
        guest && training.kind == TrainingKind.GROUP && training.status != TrainingStatus.CANCELLED

    /** У отменённого клиента в строке — «Вернуть» его одного; отменённую целиком возвращает общая «Вернуть». */
    fun canReturnClient(training: TrainingState, clientId: String): Boolean {
        val p = training.participant(clientId)
        return perClient(training) && training.status != TrainingStatus.CANCELLED && p.isCancelled && !noShow(training, p)
    }
}

/**
 * Итог в панели отмены (5.6): со списанием или нет и почему. [decision] — правило 4.2; `null` — списания здесь
 * не бывает (вся группа целиком): ни «Когда предупредили», ни переключателя.
 *
 * @param charged итог с учётом переключателя.
 * @param switchable переключатель «Со списанием» виден: у СПТ списывать нечего.
 */
data class CancelSummary(val charged: Boolean, val decision: ChargeDecision?, val switchable: Boolean)

/** Панель отмены (ТЗ 5.6): кого можно выбрать и какой итог. */
object CancelRules {
    /** Выбор «Кого отменяем» — у сплита и группы: отдельные клиенты или «Всю тренировку». */
    fun asksWho(training: TrainingState): Boolean = TrainingCardRules.perClient(training) && open(training).isNotEmpty()

    /** Клиенты, которых ещё можно отменить, в порядке тренировки. */
    fun open(training: TrainingState): List<String> = training.participants.filterNot { it.isCancelled }.map { it.clientId }

    /**
     * Итог сразу, как в макете: «Отмена со списанием — предупредили за 40 минут». [whole] — «Всю тренировку»;
     * [override] — переключатель «Со списанием», если тренер его трогал.
     */
    fun summary(
        training: TrainingState,
        whole: Boolean,
        reason: String?,
        notifiedAt: LocalDateTime,
        settings: StatusSettings,
        override: Boolean? = null,
    ): CancelSummary {
        if (whole && training.kind == TrainingKind.GROUP) return CancelSummary(charged = false, decision = null, switchable = false)
        val decision = ChargeRule.decide(training.kind, training.start, ClubTime.toMinute(notifiedAt), CancelReasons.normalize(reason), settings.cancelNoticeMinutes)
        val switchable = decision.cause != ChargeCause.FREE_SPT
        return CancelSummary(charged = if (switchable) override ?: decision.charged else false, decision = decision, switchable = switchable)
    }

    /**
     * Отменить: [clientIds] пусто — «Всю тренировку» ([StatusRules.cancelTraining]), иначе этих клиентов
     * ([StatusRules.cancelClients]); [override] — переключатель, если тренер его трогал.
     */
    fun apply(
        training: TrainingState,
        clientIds: Collection<String>,
        reason: String?,
        notifiedAt: LocalDateTime,
        settings: StatusSettings,
        override: Boolean? = null,
    ): TrainingState =
        if (clientIds.isEmpty()) {
            StatusRules.cancelTraining(training, reason, notifiedAt, settings, override)
        } else {
            StatusRules.cancelClients(training, clientIds, reason, notifiedAt, settings, override)
        }
}

/** Отмечен ли ребёнок «пришёл» — раздел «Пришли» в карточке группы. */
val ParticipantState.came: Boolean get() = status == ParticipationStatus.ATTENDED
