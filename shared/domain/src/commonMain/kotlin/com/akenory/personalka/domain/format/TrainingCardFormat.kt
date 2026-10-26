package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.training.CardAction
import com.akenory.personalka.domain.training.CardMenu
import com.akenory.personalka.domain.training.ChargeCause
import com.akenory.personalka.domain.training.ChargeDecision
import com.akenory.personalka.domain.training.ClientOutcome
import com.akenory.personalka.domain.training.TrainingPhase
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth

/** Строки карточки тренировки (ТЗ 5.4) и панели отмены (5.6). */
object TrainingCardFormat {
    private const val DOT = " · "

    /** Вторая строка шапки: «вт 20.10 · 12:00–12:50 · 50 мин». */
    fun whenLine(start: LocalDateTime, end: LocalDateTime, durationMin: Int): String =
        DateFormat.short(start.date) + DOT + DateFormat.timeRange(start, end) + DOT + DurationFormat.minutes(durationMin)

    /** Третья строка: «ПТ · ББ · каждый вт и чт»; разовая — без повтора. */
    fun typeLine(typeShort: String, placeShort: String, weekdays: Set<DayOfWeek>?): String =
        typeShort + DOT + placeShort + if (weekdays.isNullOrEmpty()) "" else DOT + ZoneFormat.repeat(weekdays, null).replaceFirstChar { it.lowercaseChar() }

    // --- Статус словом (5.4) ---

    const val ONGOING = "Идёт"

    /**
     * Метка статуса. Начатая — «Идёт» (в базе это PLANNED); у одного клиента — его итог («Отмена со списанием»),
     * у нескольких — итог тренировки.
     */
    fun statusLabel(outcome: ClientOutcome, phase: TrainingPhase): String =
        if (outcome == ClientOutcome.PLANNED && phase == TrainingPhase.ONGOING) ONGOING else outcome.label

    /**
     * Пояснение рядом с меткой: «через 1 ч 20 мин», «до конца 30 мин», у группы — «пришли 5 из 12», у проведённой —
     * «отмечено само в 09:50» или «отмечено в 12:47»; закончилась без отметки — «ждёт отметки». Иначе `null`.
     *
     * @param came сколько детей группы пришло; `null` — не группа.
     * @param doneAt когда проведена; [end] — конец: закрылась сама в конце.
     */
    fun statusNote(
        outcome: ClientOutcome,
        phase: TrainingPhase,
        now: LocalDateTime,
        start: LocalDateTime,
        end: LocalDateTime,
        came: Int?,
        expected: Int,
        doneAt: LocalDateTime?,
    ): String? = when (outcome) {
        ClientOutcome.PLANNED -> when (phase) {
            TrainingPhase.UPCOMING, TrainingPhase.STARTING_SOON -> "через " + DurationFormat.short(ClubTime.minutesBetween(now, start).coerceAtLeast(1))
            TrainingPhase.ONGOING -> if (came != null) cameOf(came, expected) else "до конца " + DurationFormat.short(ClubTime.minutesBetween(now, end).coerceAtLeast(1))
            TrainingPhase.ENDED -> if (came != null && came > 0) cameOf(came, expected) else WAITS_MARK
        }
        ClientOutcome.DONE -> when {
            came != null -> cameOf(came, expected)
            doneAt == null -> null
            doneAt == end -> "отмечено само в " + DateFormat.time(doneAt.time)
            else -> "отмечено в " + DateFormat.time(doneAt.time)
        }
        else -> null
    }

    /** Закончилась, а «Проведено» нет — пункт «Нужно сделать» (5.2). */
    const val WAITS_MARK = "ждёт отметки"

    /** «пришли 5 из 12». */
    fun cameOf(came: Int, total: Int): String = "пришли $came из ${RuText.digits(total.toLong())}"

    // --- Предупреждения (5.4) ---

    /** «Поверх запрета · ДБ 17:00–18:00 — групповая тренировка». */
    fun overRestriction(restriction: String): String = "Поверх запрета" + DOT + restriction

    /** «Поверх дежурства · ББ 11:00–15:00». */
    fun overDuty(place: String, startMinute: Int, endMinute: Int): String =
        "Поверх дежурства" + DOT + place + " " + DateFormat.timeRange(startMinute, endMinute)

    // --- Разделы ---

    const val CLIENTS = "Клиенты"
    const val PLAN = "План"
    const val FACT = "Факт"
    const val BEHAVIOR = "Поведение"

    /** Сообщение после правки «Числится на» и «Проводим» в карточке (шаг 3.7г). */
    const val SHARING_SAVED = "Кто ведёт — сохранено"

    /** Поведение у сплита — у каждого: «Поведение · Ковалёв Т.». */
    fun behaviorOf(name: String): String = BEHAVIOR + DOT + name
    const val REASON = "Причина"
    const val NOTIFIED = "Предупредили"

    /** «Состав · 12», с детьми «на раз» — «Состав · 12 · 1 на раз» (как в карточке группы, 5.10). */
    fun members(count: Int, guests: Int = 0): String =
        "Состав" + DOT + RuText.digits(count.toLong()) + if (guests > 0) DOT + GroupFormat.guests(guests) else ""

    /** «Пришли · 5». */
    fun cameSection(count: Int): String = "Пришли" + DOT + RuText.digits(count.toLong())

    /** «Ещё не отмечены · 7». */
    fun notMarkedSection(count: Int): String = "Ещё не отмечены" + DOT + RuText.digits(count.toLong())

    /** «Не пришли · 2» — в проведённой группе. */
    fun noShowSection(count: Int): String = "Не пришли" + DOT + RuText.digits(count.toLong())

    /** «Отменены · 1». */
    fun cancelledSection(count: Int): String = "Отменены" + DOT + RuText.digits(count.toLong())

    const val MARK_HINT = "Нажмите на пришедшего — он поднимется в «Пришли». Кого не отметите до конца — «не пришёл»."

    /** Строка «Предупредили» у отменённой: «вт 20.10, 09:20 — за 40 мин»; после начала — «… — после начала». */
    fun notified(at: LocalDateTime, start: LocalDateTime): String {
        val notice = ClubTime.minutesBetween(at, start)
        return CalendarFormat.at(at) + " — " + if (notice < 0) AFTER_START else "за$NBSP" + DurationFormat.short(notice)
    }

    private const val AFTER_START = "после начала"

    // --- Кнопки и меню ---

    fun action(action: CardAction): String = when (action) {
        CardAction.DONE -> "Проведено"
        CardAction.ALL_CAME -> "Все пришли"
        CardAction.CANCEL -> "Отменить"
        CardAction.EDIT -> "Изменить"
        CardAction.RETURN -> "Вернуть"
        CardAction.DIARY -> DiaryFormat.TITLE
    }

    fun menu(item: CardMenu): String = when (item) {
        CardMenu.UNMARK_DONE -> "Снять «Проведено»"
        CardMenu.RESULT -> ResultFormat.MENU
        CardMenu.EDIT -> "Изменить"
        CardMenu.COPY -> "Копировать"
        CardMenu.SKIP_DATE -> "Пропустить дату серии"
        CardMenu.DELETE -> "Удалить"
    }

    const val MORE = "Ещё"

    /** Значки в строке клиента — для TalkBack: «Отменить: Носова Е.», «Вернуть: Носова Е.». */
    fun cancelClient(name: String): String = "Отменить: $name"
    fun returnClient(name: String): String = "Вернуть: $name"

    // --- Удаление ---

    const val DELETE_TITLE = "Удалить тренировку?"
    const val DELETE = "Удалить"
    const val KEEP = "Не удалять"

    /** «вт 20.10, 17:00 · Орлов М. Вернуть её можно только сразу — кнопкой «Отменить» в сообщении.» */
    fun deleteText(title: String, start: LocalDateTime): String =
        CalendarFormat.at(start) + DOT + title + ". Вернуть её можно только сразу — кнопкой «Отменить» в сообщении."

    // --- Удаление тренировки серии (шаг 3.9к) ---

    /** Кнопка «Только эту — вт 20.10» — как в вопросе об изменении серии. */
    fun deleteOnly(date: LocalDate): String = TrainingFormat.onlyThis(date)

    const val DELETE_FOLLOWING = "Эту и все следующие"
    const val FOLLOWING_TITLE = "Удалить эту и все следующие?"

    /**
     * «Тренировки серии с вт 20.10 — всего 9 тренировок. Новые по ней больше не появятся. Прошедшие и проведённые
     * не меняются. План из удалённых — в первую новую тренировку.»
     */
    fun followingText(count: Int, from: LocalDate): String =
        "Тренировки серии с ${DateFormat.short(from)} — всего ${trainings(count)}. Новые по ней больше не появятся. " +
            "Прошедшие и проведённые не меняются. План из удалённых — в первую новую тренировку."

    /** «Удалить 9 тренировок». */
    fun deleteFollowing(count: Int): String = "Удалить ${trainings(count)}"

    /** «Удалено: 9 тренировок с вт 20.10». */
    fun deletedFollowing(count: Int, from: LocalDate): String = "Удалено: ${trainings(count)} с ${DateFormat.short(from)}"

    private fun trainings(n: Int): String = RuText.count(n, "тренировка", "тренировки", "тренировок")

    // --- Закрытый месяц (4.1, шаг 3.7б) ---

    const val CLOSED_TITLE = "Изменится зарплата за закрытый месяц"
    const val CLOSED_CHANGE = "Изменить"
    const val CLOSED_KEEP = "Не менять"

    /** «Октябрь 2026 отмечен в «Зарплате» как сверенный. Правка этой тренировки изменит его заработок.» */
    fun closedText(month: YearMonth): String =
        "${DateFormat.monthYear(month.firstDay)} отмечен в «Зарплате» как сверенный. " +
            "Правка этой тренировки изменит его заработок."

    // --- Сообщения (5.20) ---

    const val MARKED_DONE = "Проведено"
    const val UNMARKED_DONE = "Отметка «Проведено» снята"
    const val RETURNED = "Тренировка возвращена"
    const val DELETED = "Тренировка удалена"

    /** «Отменено: вт 20.10, 17:00»; со списанием — «Отмена со списанием: …». */
    fun cancelled(start: LocalDateTime, charged: Boolean): String =
        (if (charged) ClientOutcome.CANCELLED_CHARGED.label else ClientOutcome.CANCELLED.label) + ": " + CalendarFormat.at(start)

    /** Отмена с переносом плана (4.3, макет «После отмены»): «Отменено. План перенесён на вт 27.10». */
    fun cancelledCarried(charged: Boolean, to: LocalDate): String =
        (if (charged) ClientOutcome.CANCELLED_CHARGED.label else ClientOutcome.CANCELLED.label) + ". " + planCarried(to)

    /** Следующей нет (4.3): «Отменено. План — в первую новую тренировку». */
    fun cancelledPending(charged: Boolean): String =
        (if (charged) ClientOutcome.CANCELLED_CHARGED.label else ClientOutcome.CANCELLED.label) + ". " + PLAN_PENDING

    /** «Тренировка возвращена. План убран из вт 27.10» — перенесённый текст ушёл из следующей (4.3). */
    fun returnedUncarried(from: LocalDate): String = "$RETURNED. План убран из ${DateFormat.short(from)}"

    // --- Перенос плана (4.3) ---

    /** «План перенесён на вт 27.10» — в сообщении и в карточке отменённой (макет «Отмена со списанием»). */
    fun planCarried(to: LocalDate): String = "План перенесён на ${DateFormat.short(to)}"

    const val PLAN_PENDING = "План — в первую новую тренировку"

    /** Вопрос «У следующей уже есть план» (макет «У следующей уже есть план»). */
    const val CARRY_TITLE = "У следующей тренировки уже есть план"
    const val CARRY_MOVING = "Переносится"
    const val CARRY_EXISTING = "Уже записан"
    const val CARRY_ADD = "Добавить с новой строки"
    const val CARRY_REPLACE = "Заменить"
    const val CARRY_KEEP = "Оставить как есть"

    /** Подпись под заголовком вопроса: «Орлов М. · вт 27.10, 17:00». */
    fun carryWhom(title: String, start: LocalDateTime): String = title + DOT + CalendarFormat.at(start)

    /** Вопрос при «Вернуть», если перенесённый план правили (4.3). */
    const val UNCARRY_TITLE = "План следующей тренировки уже правили"
    const val UNCARRY_CARRIED = "Перенесено"
    const val UNCARRY_NOW = "Сейчас"
    const val UNCARRY_REMOVE = "Убрать перенесённое"

    /** «Отменено: Носова Е.» — фамилия после двоеточия не склоняется (10.6). */
    fun cancelledFor(names: List<String>): String = "Отменено: " + names.joinToString(", ")

    /** «Добавили на раз: Белов А.» — без рода: фамилии не склоняются. */
    fun guestAdded(name: String): String = "Добавили на раз: $name"

    /** «Убрали: Белов А.». */
    fun guestRemoved(name: String): String = "Убрали: $name"

    // --- Ребёнок «на раз» (5.4, 5.10) ---

    const val ADD_GUEST = "Добавить на раз"
    const val GUEST_TITLE = "Ребёнок на раз"
    const val GUEST_HINT = "Только на эту тренировку — в состав группы он не попадёт."
    const val GUEST_SEARCH = "Фамилия или имя"

    /** Метка в строке ребёнка «на раз». */
    const val GUEST = "на раз"

    /** «Убрать: Белов А.» — подпись значка в строке. */
    fun removeGuest(name: String): String = "Убрать: $name"

    /** Строка клиента, удалённого полностью (5.9): в прошедшей тренировке он остаётся для зарплаты без имени. */
    const val ERASED_CLIENT = "Удалённый клиент"

    /** «Новый клиент…», с набранным — «Новый клиент: Белов…». */
    fun newClient(query: String): String = if (query.isBlank()) "Новый клиент…" else "Новый клиент: ${query.trim()}…"

    const val LAST_NAME = "Фамилия"
    const val FIRST_NAME = "Имя"
    const val ADD = "Добавить"

    /** «Дата пропущена: вт 27.10». */
    fun skipped(date: LocalDate): String = "Дата пропущена: ${DateFormat.short(date)}"

    // --- Панель отмены (5.6) ---

    const val CANCEL_TITLE = "Отменить тренировку"
    const val CANCEL_CLIENT_TITLE = "Отменить"
    const val WHO = "Кого отменяем"
    const val WHOLE = "Всю тренировку"
    const val OWN_REASON = "Свой текст"
    const val REASON_HINT = "Без диагнозов и сведений о здоровье"
    const val WHEN_NOTIFIED = "Когда предупредили"
    const val OTHER_TIME = "Другое время"
    const val CHARGE_SWITCH = "Со списанием"
    const val CANCEL_TRAINING = "Отменить тренировку"
    const val MOVE_INSTEAD = "Перенести вместо отмены"

    /** Чип «Сейчас, 15:40». */
    fun nowChip(now: LocalDateTime): String = "Сейчас, " + DateFormat.time(now.time)

    /** Чип выбранного времени: «вт 20.10, 09:20». */
    fun timeChip(at: LocalDateTime): String = CalendarFormat.at(at)

    /** Кнопка отмены не всех клиентов: «Отменить: Носова Е.» — тренировка остаётся. */
    fun cancelFor(names: List<String>): String = "Отменить: " + names.joinToString(", ")

    /** Метка итога: «Отмена со списанием» или «Отменено». */
    fun summaryLabel(charged: Boolean): String = if (charged) ClientOutcome.CANCELLED_CHARGED.label else ClientOutcome.CANCELLED.label

    /**
     * Пояснение итога: «предупредили за 40 мин», «предупредили после начала», «отменил тренер», «по инициативе клуба»,
     * у СПТ — «СПТ бесплатна». Вся группа — `null`.
     */
    fun summaryNote(decision: ChargeDecision?): String? = when (decision?.cause) {
        null -> null
        ChargeCause.AFTER_START -> "предупредили после начала"
        ChargeCause.LATE, ChargeCause.IN_TIME -> "предупредили за" + NBSP + DurationFormat.short(decision.noticeMinutes)
        ChargeCause.NOT_BY_CLIENT -> "отмена не по вине клиента"
        ChargeCause.FREE_SPT -> "СПТ бесплатна для клиента"
    }
}
