package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Строки кабинета родителя (ТЗ 6.1, 6.3; макеты 0.7). Имена не склоняются и пол не используется: имя — только в
 * именительном падеже, дальше — «ребёнок». Весь текст на «вы».
 */
object CabinetFormat {
    private const val DOT = " · "

    const val TITLE = "Кабинет тренировок"
    const val UPCOMING = "Ближайшие тренировки"
    const val VISITS = "Посещения"
    const val HISTORY = "История"
    const val EARLIER = "Ранее"
    const val PLAN = "План"
    const val FACT = "Факт"
    const val BEHAVIOR = "Поведение"
    const val STATUS = "Статус"
    const val FOOTER = "По всем вопросам пишите тренеру напрямую."

    // --- Код доступа (6.1) ---
    const val CODE_TITLE = "Введите код"
    const val CODE_HINT = "Код из 4 цифр вам сообщил тренер."
    const val CODE_LABEL = "Код доступа"
    const val CODE_ENTER = "Войти"
    const val CODE_REMEMBER = "На этом устройстве код больше не спросят 30 дней."
    const val CODE_BLOCKED = "Слишком много попыток. Попробуйте через 15 минут."
    const val CODE_FORMAT = "Введите 4 цифры."

    // --- Недействительная ссылка и предел запросов (6.1, 6.2) ---
    const val INVALID_TITLE = "Ссылка больше не действует"
    const val INVALID_TEXT = "Попросите тренера прислать новую."
    const val TOO_MANY_TITLE = "Слишком много запросов"
    const val TOO_MANY_TEXT = "Подождите минуту и обновите страницу."
    const val ERROR_TITLE = "Не получилось открыть кабинет"
    const val ERROR_TEXT = "Попробуйте обновить страницу через несколько минут."

    /** «Неверный код. Осталось 3 попытки.» */
    fun wrongCode(left: Int): String = "Неверный код. Осталось ${RuText.count(left, "попытка", "попытки", "попыток")}."

    /** «Тренер: Дмитрий Дудник». */
    fun coach(name: String): String = "Тренер: $name"

    /** «Данные на 20.10.2026, 15:41» — время последней синхронизации тренера. */
    fun dataAt(at: LocalDateTime): String = "Данные на ${DateFormat.full(at.date)}, ${DateFormat.time(at.time)}"

    /** «вт 20.10 · 13:30». */
    fun whenText(start: LocalDateTime): String = DateFormat.short(start.date) + DOT + DateFormat.time(start.time)

    /** «Октябрь» — столбец счётчиков. */
    fun monthColumn(date: LocalDate): String = DateFormat.month(date).replaceFirstChar { it.uppercaseChar() }

    /** «Октябрь 2026» — месяц истории. */
    fun monthTitle(date: LocalDate): String = DateFormat.monthYear(date)

    /** «6 тренировок». */
    fun trainings(count: Int): String = RuText.count(count, "тренировка", "тренировки", "тренировок")

    /** Правило отмены (6.3, п. 4): время из настроек тренера полными словами — «1 час», «1 час 30 минут», «2 часа». */
    fun rule(minutes: Int): String =
        "Если вы предупреждаете об отмене меньше чем за ${DurationFormat.long(minutes)} до начала, " +
            "тренировка считается проведённой и списывается."

    /**
     * Под отменённой: причина, у отмены со списанием — ещё «предупредили за 40 мин» ([noticeMinutes] до начала;
     * отрицательное — после начала). Нет ни того, ни другого — строки нет.
     */
    fun cancelNote(reason: String?, noticeMinutes: Long?): String? {
        val notice = noticeMinutes?.let { if (it < 0) "предупредили после начала" else "предупредили за$NBSP" + DurationFormat.short(it) }
        return when {
            reason != null && notice != null -> reason + DOT + notice
            reason != null -> reason
            notice != null -> notice.replaceFirstChar { it.uppercaseChar() }
            else -> null
        }
    }
}
