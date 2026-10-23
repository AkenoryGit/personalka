package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.calendar.CalendarView
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.LocalDateTime

/** Строки блока тренировки в календаре (ТЗ 5.3): имя, время, тип и место. */
object CalendarFormat {
    private const val DOT = " · "

    /** Один клиент: «Иванов И.». */
    fun client(name: ClientName): String {
        val initial = name.firstName.trim().firstOrNull()?.let { "$NBSP$it." }.orEmpty()
        return name.lastName.trim() + initial
    }

    /**
     * Имя блока: группа — «Сб 9:00 ББ (12)», один клиент — «Иванов И.», двое и больше — фамилии через
     * косую черту: «Петров / Сидоров». Без клиентов и группы — название типа.
     *
     * @param clients клиенты тренировки в порядке, в котором их добавляли.
     * @param groupSize сколько детей ждут на тренировку группы.
     */
    fun title(clients: List<ClientName>, groupName: String?, groupSize: Int, typeName: String): String = when {
        groupName != null -> "$groupName ($groupSize)"
        clients.size == 1 -> client(clients.single())
        clients.isNotEmpty() -> clients.joinToString(" / ") { it.lastName.trim() }
        else -> typeName
    }

    /** Вторая строка блока 40–56 dp: «08:00 · ББ». */
    fun startPlace(start: LocalDateTime, place: String): String = DateFormat.time(start.time) + DOT + place

    /** Блок ниже 40 dp — одной строкой: «Белова Е., 10:00». */
    fun oneLine(title: String, start: LocalDateTime): String = "$title, ${DateFormat.time(start.time)}"

    /** Третья строка блока от 56 dp: «ПТ · ББ», «Группа · ДБ». */
    fun typePlace(type: String, place: String): String = type + DOT + place

    /** Метка над скрытыми наложениями: «+2». */
    fun more(count: Int): String = "+$count"

    /** Заголовок списка под меткой «+N»: «Ещё 2 тренировки». */
    fun moreTitle(count: Int): String = "Ещё ${RuText.count(count, "тренировка", "тренировки", "тренировок")}"

    /** Строка тренировки в списке: «10:00–10:50 · ББ». */
    fun timePlace(start: LocalDateTime, end: LocalDateTime, place: String): String = DateFormat.timeRange(start, end) + DOT + place

    /** День и время посреди фразы: «вт 22.10, 17:15». */
    fun at(start: LocalDateTime): String = DateFormat.short(start.date) + ", " + DateFormat.time(start.time)

    /** Подсказка над перетаскиваемым блоком: «Вт 22.10 · 17:15–18:15». */
    fun dragHint(start: LocalDateTime, end: LocalDateTime): String =
        DateFormat.short(start.date).replaceFirstChar { it.uppercaseChar() } + DOT + DateFormat.timeRange(start, end)

    /** Сообщение после переноса (5.20): «Перенесено на вт 22.10, 17:15». */
    fun moved(start: LocalDateTime): String = "Перенесено на ${at(start)}"

    /** Вид в переключателе шапки: «День», «3 дня», «5 дней». */
    fun view(view: CalendarView): String = when (view) {
        CalendarView.Day -> "День"
        is CalendarView.Days -> RuText.count(view.count, "день", "дня", "дней")
    }

    /** Фильтр мест без выбора: на кнопке сложенного экрана — «Все места», на чипе разложенного — «Все». */
    const val ALL_PLACES = "Все места"
    const val ALL = "Все"
}
