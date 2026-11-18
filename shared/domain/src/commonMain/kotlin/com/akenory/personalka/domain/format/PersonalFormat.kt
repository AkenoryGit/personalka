package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.restriction.DayHours
import com.akenory.personalka.domain.restriction.PersonalRules
import com.akenory.personalka.domain.restriction.ReminderRepeat
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber

/**
 * Строки рабочего графика (шаг 5.10д), личных дел (5.10е) и напоминаний (5.10ж) — макет «Персоналка — личные дела и
 * рабочий график», согласован 18.11.2026.
 */
object PersonalFormat {
    private const val DOT = " · "
    private const val MINUTES_PER_DAY = 1440L

    // --- Форма ---
    const val MODE = "Личное"
    const val TASK = "Дело"
    const val REMINDER = "Напоминание"
    const val NEW_TITLE = "Новое личное"
    const val EDIT_TASK_TITLE = "Личное дело"
    const val EDIT_REMINDER_TITLE = "Напоминание"
    const val TASK_NAME = "Название"
    const val TASK_NAME_PLACEHOLDER = "Например, стоматолог"
    const val REMINDER_NAME = "Что напомнить"
    const val REMINDER_NAME_PLACEHOLDER = "Например, позвонить в сервис"
    const val WHEN = "Когда"
    const val REPEAT = "Повтор"
    const val ALLOW_TRAININGS = "Можно ставить тренировки"
    const val ALLOW_TRAININGS_NOTE = "Выключено — время занято: слоты его обходят, при записи на него спросим"
    const val TRAVEL = "Дорога до и после"
    const val TASK_NOTIFY = "Напомнить"
    const val REMINDER_NOTIFY = "Уведомление"
    const val REMINDER_NOTE = "Напоминание не занимает время: тренировки и слоты его не видят."
    const val DELETE_TASK = "Удалить дело"
    const val DELETE_REMINDER = "Удалить напоминание"
    const val TASK_SAVED = "Дело записано"
    const val REMINDER_SAVED = "Напоминание записано"
    const val TASK_DELETED = "Дело удалено"
    const val REMINDER_DELETED = "Напоминание удалено"
    const val NO = "Нет"
    const val AT_TIME = "В это время"

    /** «15 мин», «1 ч», «1,5 ч». */
    fun travel(minutes: Int): String = if (minutes == 0) NO else PersonalRules.travelText(minutes)

    /** Чип «Напомнить» у дела: «Нет», «5 мин», «1 ч». */
    fun taskNotify(minutes: Int?): String = if (minutes == null) NO else PersonalRules.travelText(minutes)

    /** Чип «Уведомление» у напоминания: «В это время», «за 5 мин», «за 1 ч». */
    fun reminderNotify(minutes: Int): String = if (minutes == 0) AT_TIME else "за ${PersonalRules.travelText(minutes)}"

    /** Подпись под «Напомнить» у дела с дорогой: «До выхода с учётом дороги — в 12:45». */
    fun leaveAt(start: LocalTime, travel: Int, notify: Int?): String? {
        if (notify == null || travel == 0) return null
        val m = ((start.hour * 60 + start.minute - travel - notify) % 1440 + 1440) % 1440
        return "До выхода с учётом дороги — в ${DateFormat.time(LocalTime(m / 60, m % 60))}"
    }

    fun repeat(r: ReminderRepeat): String = when (r) {
        ReminderRepeat.NONE -> "Не повторять"
        ReminderRepeat.DAILY -> "Каждый день"
        ReminderRepeat.WEEKDAYS -> "По будням"
        ReminderRepeat.WEEKLY -> "Каждую неделю"
        ReminderRepeat.MONTHLY -> "Каждый месяц"
    }

    // --- Календарь ---
    /** Подпись дороги на сетке: «дорога · 1 ч». */
    fun travelLabel(minutes: Int): String = "дорога$DOT${PersonalRules.travelText(minutes)}"

    /** Подпись дела, на которое можно ставить тренировки: «Перекус · можно ставить». */
    fun freeLabel(title: String): String = "$title${DOT}можно ставить"

    /** Подпись напоминания на сетке: «12:00 Позвонить в сервис». */
    fun reminderLabel(start: Int, title: String): String = "${DateFormat.time(LocalTime(start / 60, start % 60))} $title"

    /** Строка дела в списке дня: «Личное · до 15:00», «Личное · до 17:30 · можно ставить». */
    fun listLine(end: Int, free: Boolean): String =
        MODE + DOT + "до " + DateFormat.minuteOfDay(end) + if (free) DOT + "можно ставить" else ""

    /** Время вне рабочего графика на сетке. */
    const val OFF_HOURS = "Вне рабочего графика"

    /** Окно дела по нажатию: «Стоматолог 14:00–15:00 · дорога 1 ч». */
    fun taskText(title: String, startMinute: Int, endMinute: Int, travel: Int): String =
        "$title " + DateFormat.timeRange(startMinute, endMinute) + if (travel > 0) DOT + travelLabel(travel) else ""

    // --- Уведомления ---
    const val CHANNEL = "Личные дела и напоминания"
    const val DONE_ACTION = "Готово"
    const val SNOOZE_ACTION = "Через 10 мин"
    const val SNOOZE_MINUTES = 10

    /** Текст уведомления: «Через 10 мин, в 12:00»; в момент начала — «Сейчас, в 12:00»; у дела с дорогой — «Выезд …». */
    fun alertText(at: LocalDateTime, start: LocalDateTime, travel: Int): String {
        val time = DateFormat.time(start.time)
        val minutes = ((start.date.toEpochDays() - at.date.toEpochDays()) * MINUTES_PER_DAY + start.hour * 60 + start.minute - at.hour * 60 - at.minute).toInt()
        val lead = when {
            minutes <= 0 -> "Сейчас, в $time"
            else -> "Через ${PersonalRules.travelText(minutes)}, в $time"
        }
        return if (travel > 0) lead + DOT + "дорога ${PersonalRules.travelText(travel)}" else lead
    }

    // --- Настройки ---
    const val SECTION_SCHEDULE = "Мой рабочий график"
    const val SCHEDULE = "Рабочий график"
    const val SCHEDULE_NOTE = "Свободные слоты — только в эти часы. Тренировку вне графика поставить можно — спросим"
    const val DAY_OFF = "выходной"
    const val WORK_DAY = "Рабочий день"
    const val GRID_HOURS = "Часы сетки"
    const val GRID_HOURS_NOTE = "Что видно в календаре — часы клуба. Тренировку можно поставить и вне них — сетка этого дня расширится."
    const val SECTION_PERSONAL = "Личное"
    const val PERSONAL = "Личные дела и напоминания"
    const val PERSONAL_NOTE = "Свои дела и напоминания в этом календаре. Видите только вы — не в кабинете, не в зарплате"
    const val COLOR = "Цвет личных дел"

    const val DONE = "Готово"

    /** Заголовок окна дня графика: «Понедельник». */
    fun weekday(day: DayOfWeek): String = WEEKDAYS[day.isoDayNumber - 1]

    private val WEEKDAYS = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")

    /** Строка дня графика: «07:00–21:00» или «выходной». */
    fun day(hours: DayHours?): String = hours?.let { DateFormat.timeRange(it.startMinute, it.endMinute) } ?: DAY_OFF
}
