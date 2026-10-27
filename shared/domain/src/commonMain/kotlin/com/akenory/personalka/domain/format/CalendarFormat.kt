package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.calendar.CalendarBadge
import com.akenory.personalka.domain.calendar.CalendarView
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.reference.TypePresets
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Строки календаря (ТЗ 5.3): блок тренировки — имя, время, тип и место; строки «Месяца» и «Списка»; шапка. */
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

    /**
     * Имя на блоке, в «Месяце» и «Списке» (шаг 3.9д): клиенты полностью — «Морозов Даниил», двое и больше —
     * через косую черту: «Ковалёв Илья / Ковалёва Мира». Пробел между фамилией и именем обычный: в узкой колонке
     * имя переносится на вторую строку. Группа и тренировка без клиентов — как в [title].
     */
    fun blockName(clients: List<ClientName>, groupName: String?, groupSize: Int, typeName: String): String = when {
        groupName != null || clients.isEmpty() -> title(clients, groupName, groupSize, typeName)
        else -> clients.joinToString(" / ") { it.short }
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

    /** Вид в переключателе шапки: «День», «3 дня», «5 дней», «Месяц», «Список». */
    fun view(view: CalendarView): String = when (view) {
        CalendarView.Day -> "День"
        is CalendarView.Days -> RuText.count(view.count, "день", "дня", "дней")
        CalendarView.Month -> "Месяц"
        CalendarView.Agenda -> "Список"
    }

    /** Число тренировок дня справа от его заголовка в «Месяце» и «Списке»: «10 тренировок». */
    fun trainings(count: Int): String = RuText.count(count, "тренировка", "тренировки", "тренировок")

    /** Под заголовком дня без тренировок в «Месяце». */
    const val NO_TRAININGS = "Тренировок нет"

    /**
     * Вторая строка тренировки в «Месяце» и «Списке» (макет «Месяц»): полное название типа и место — «Сплит · ББ»;
     * длительность — если она не обычные 50 минут: «Грудничковое · ДБ · 30 мин»; у отменённой со списанием —
     * «ПТ · ББ · со списанием»: время строки показывает только начало, а списание — деньги клиента.
     */
    fun listLine(typeName: String, place: String, durationMin: Int, charged: Boolean, who: String? = null): String = buildString {
        append(typeName)
        append(DOT)
        append(place)
        if (durationMin != TypePresets.DEFAULT_DURATION) append(DOT).append(DurationFormat.minutes(durationMin))
        if (charged) append(DOT).append(CHARGED)
        // Кто ведёт группу (шаг 3.9л): «Группа · ББ · вдвоём · Лена».
        if (who != null) append(DOT).append(who.replaceFirstChar { it.lowercaseChar() })
    }

    private const val CHARGED = "со списанием"

    /**
     * Строка дежурства в «Месяце» и «Списке»: «ББ · до 15:00»; через полночь — «до 24:00»; за коллегу —
     * «ББ · до 15:00 · за Александра».
     */
    fun dutyLine(place: String, endMinute: Int, coverFor: String? = null): String =
        place + DOT + "до" + NBSP + DateFormat.minuteOfDay(endMinute) + (coverFor?.takeIf { it.isNotBlank() }?.let { DOT + ZoneFormat.coverFor(it) } ?: "")

    /** Значок тренировки словами — для TalkBack в строках «Месяца» и «Списка». */
    fun badge(badge: CalendarBadge): String = when (badge) {
        CalendarBadge.CHARGED -> "Отмена со списанием"
        CalendarBadge.OVER_RESTRICTION -> "Поверх запрета"
        CalendarBadge.NO_DIARY -> "Дневник не заполнен"
        CalendarBadge.BIRTHDAY -> ClientFormat.BIRTHDAY
        CalendarBadge.OVER_DUTY -> "Поверх дежурства"
        CalendarBadge.DONE -> "Проведено"
    }

    /** Кнопки листания мини-календаря. */
    const val PREVIOUS_MONTH = "Предыдущий месяц"
    const val NEXT_MONTH = "Следующий месяц"

    /** Клетка дня в «Месяце» для TalkBack: «вт 20.10, 10 тренировок»; без тренировок — только день. */
    fun monthDay(date: LocalDate, count: Int): String =
        DateFormat.short(date) + if (count > 0) ", " + trainings(count) else ""

    /** Фильтр мест без выбора: на кнопке сложенного экрана — «Все места», на чипе разложенного — «Все». */
    const val ALL_PLACES = "Все места"
    const val ALL = "Все"
}
