package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.calendar.FreeWindow
import com.akenory.personalka.domain.calendar.SlotDay
import com.akenory.personalka.domain.format.RuText.NBSP

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
    const val TAP_HINT = "Нажмите на окно — откроется новая тренировка на его начало"

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

    /** «Пн 03.11» — заголовок дня. */
    fun day(day: SlotDay): String = DateFormat.short(day.date).replaceFirstChar { it.uppercase() }

    /** Подпись под заголовком у «Найти время»: «Время для: Петров К. Нажмите на окно, чтобы записать». */
    fun forWait(name: String): String = "Время для: $name. Нажмите на окно, чтобы записать"

    /** «ждёт: Петров К., Смирнова И.». */
    fun waiting(names: List<String>): String = "ждёт: " + names.joinToString(", ")

    /**
     * Текст для клиента (4.13): «Свободное время:» и строки по дням — без имён и чужих тренировок, без пометок листа
     * ожидания. Окна в нескольких помещениях — с коротким названием перед каждым: «Пн 03.11: ББ 13:00–15:00,
     * 18:00–19:00; ДБ 13:00–14:00»; в одном — «Пн 03.11: 13:00–15:00, 18:00–19:00». [placeShort] — «ББ» по id места.
     */
    fun copyText(days: List<SlotDay>, placeShort: (String) -> String = { "" }): String = buildString {
        val several = days.flatMap { d -> d.windows.map { it.locationId } }.distinct().size > 1
        append("Свободное время:")
        days.forEach { d ->
            append('\n').append(day(d)).append(": ")
            if (several) {
                append(
                    d.windows.groupBy { it.locationId }.entries.joinToString("; ") { (place, windows) ->
                        place?.let(placeShort).orEmpty() + " " + windows.joinToString(", ") { window(it) }
                    },
                )
            } else {
                append(d.windows.joinToString(", ") { window(it) })
            }
        }
    }

    /** Подпись под заголовком: «12 окон в 5 днях». */
    fun summary(days: List<SlotDay>): String {
        val windows = days.sumOf { it.windows.size }
        return RuText.count(windows, "окно", "окна", "окон") + " · " + RuText.count(days.size, "день", "дня", "дней")
    }
}
