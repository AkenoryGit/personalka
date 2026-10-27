package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.calendar.DayStarts
import com.akenory.personalka.domain.calendar.FreeWindow
import com.akenory.personalka.domain.calendar.PlaceStarts
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.LocalDate

/** Строки экрана «Свободные слоты» (ТЗ 5.13, 4.13). */
object SlotFormat {
    const val TITLE = "Свободные слоты"
    const val PERIOD = "Период"
    const val DAYS = "Дни"
    const val TIME = "Время начала"
    const val PLACE = "Место"
    const val ANY_PLACE = "Все"
    const val DURATION = "Длительность"
    const val GAP = "Перерыв"
    const val NO_GAP = "Любой"
    const val GAP_HINT = "Сколько можно ждать до или после своей тренировки или дежурства"
    const val MORE = "Дни, время, запреты"
    const val RESTRICTIONS = "Учитывать запреты"
    const val DUTY_BUSY = "Дежурство — занятое время"
    const val DUTY_BUSY_HINT = "Обычно нет: во время дежурства вы уже в бассейне"
    const val EMPTY_DAYS = "Дни без тренировок"
    const val EMPTY_DAYS_HINT = "Показывать дни, где у вас ещё нет тренировок"
    const val COPY = "Скопировать список"
    const val SHARE = "Поделиться"
    const val COPIED = "Список скопирован"
    const val NONE = "Свободных окон нет"
    const val NONE_HINT = "Увеличьте период, уберите ограничение перерыва или включите дни без тренировок"
    const val FIND_TIME = "Найти время"
    const val FIND_SLOTS = "Найти свободные слоты"
    const val TAP_HINT = "Нажмите на время — откроется новая тренировка"
    const val CUSTOM_PERIOD = "Свои даты"
    const val FROM = "С"
    const val TO = "По"

    /** Чипы периода — дней от сегодня. */
    val PERIODS = listOf(7, 14, 31)

    /** Чипы длительности, минуты. */
    val DURATIONS = listOf(30, 45, 50, 60, 90)

    /** Чипы перерыва Gmax, минуты; `null` — без ограничения. */
    val GAPS = listOf<Int?>(null, 30, 60, 120)

    /** «Неделя», «2 недели», «Месяц». */
    fun period(days: Int): String = when (days) {
        7 -> "Неделя"
        14 -> "2 недели"
        else -> "Месяц"
    }

    /** Чип перерыва: «Любой», «до 30 мин», «до 1 ч», «до 2 ч». */
    fun gap(minutes: Int?): String = when {
        minutes == null -> NO_GAP
        minutes % 60 == 0 -> "до ${minutes / 60}${NBSP}ч"
        else -> "до $minutes${NBSP}мин"
    }

    /** «13:00–15:00». */
    fun window(w: FreeWindow): String = DateFormat.timeRange(w.startMinute, w.endMinute)

    /** Подпись под заголовком у «Найти время»: «Время для: Петров К. Нажмите на окно, чтобы записать». */
    fun forWait(name: String): String = "Время для: $name. Нажмите на время, чтобы записать"

    /** «ждёт: Петров К., Смирнова И.». */
    fun waiting(names: List<String>): String = "ждёт: " + names.joinToString(", ")

    /** Начало занятия, как пишет Дмитрий: «8.00», «15.30» (шаг 4.3б). */
    fun start(minute: Int): String = "${minute / 60}.${(minute % 60).toString().padStart(2, '0')}"

    /** День в тексте и на экране: «Вторник (27.10.2026)». */
    fun dayLong(date: LocalDate): String =
        DateFormat.weekday(date.dayOfWeek).replaceFirstChar { it.uppercase() } + " (" + DateFormat.full(date) + ")"

    /** Строка дня: «Вторник (27.10.2026): 8.00 9.00 15.00 16.00». */
    fun dayLine(day: DayStarts): String = dayLong(day.date) + ": " + day.starts.joinToString(" ") { start(it) }

    /**
     * Текст для клиента (4.13, шаг 4.3б) — отдельно по каждому помещению, время начала занятий:
     * «ББ:» и строки «Вторник (27.10.2026): 8.00 9.00 15.00», между помещениями — пустая строка. Без имён и чужих
     * тренировок, без пометок листа ожидания. [placeShort] — «ББ» по id места.
     */
    fun copyText(places: List<PlaceStarts>, placeShort: (String) -> String = { "" }): String =
        places.joinToString("\n\n") { place ->
            val title = place.locationId?.let(placeShort).orEmpty()
            val lines = place.days.joinToString("\n") { dayLine(it) }
            if (title.isEmpty()) lines else "$title:\n$lines"
        }

    /** Подпись над списком: «5 дней со свободным временем». */
    fun summary(places: List<PlaceStarts>): String {
        val days = places.flatMap { p -> p.days.map { it.date } }.distinct().size
        return RuText.count(days, "день", "дня", "дней") + " со свободным временем"
    }
}
