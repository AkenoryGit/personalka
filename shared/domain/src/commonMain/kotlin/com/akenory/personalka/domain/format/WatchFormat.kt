package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.watch.WatchSlot
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Строки часов (шаг 5.3т): ячейки циферблата и плитка. Короткая ячейка вмещает около 7 знаков — поэтому там только
 * время и тип, а деньги без «₽».
 */
object WatchFormat {
    private const val DOT = " · "

    /** Название источника в списке ячеек циферблата и плитки. */
    const val APP = "Персоналка"

    /** Данных ещё нет: часы не получили ни одного расписания. */
    const val NO_DATA = "Откройте Персоналку на телефоне"

    /** Кнопка плитки у ПТ и сплита. */
    const val MARK_DONE = "Проведено"

    /** Кнопка плитки у группы. */
    const val ALL_CAME = "Все пришли"

    /** Подпись ячейки «к вечеру». */
    const val EVENING = "к вечеру"

    /** Подпись ячейки плана. */
    const val PLAN = "план"

    /** Подпись ячейки дел. */
    const val TODO = "дела"

    /** Переключатель в «Ещё → Настройки → Часы». */
    const val NAMES_SWITCH = "Имена клиентов на часах"

    /** Пояснение под переключателем. */
    const val NAMES_NOTE = "Часы видят все рядом, и на выключенном экране тоже. Выключите — на часах будут только время, тип и место."

    /** Раздел настроек. */
    const val SETTINGS_TITLE = "Часы"

    /** Короткая ячейка: «16:00 ПТ». */
    fun short(slot: WatchSlot): String = DateFormat.time(slot.start.time) + NBSP + slot.typeShort

    /** Длинная ячейка: «16:00 ПТ · Никитин А. · ББ», без имён — «16:00 ПТ · ББ». */
    fun long(slot: WatchSlot): String =
        listOfNotNull(short(slot), slot.name, slot.placeShort.ifEmpty { null }).joinToString(DOT)

    /** Строка под временем на плитке: «ПТ · ББ». */
    fun typePlace(slot: WatchSlot): String = listOf(slot.typeShort, slot.placeShort).filter { it.isNotEmpty() }.joinToString(DOT)

    /** Кнопка плитки: «Проведено» у ПТ, «Все пришли» у группы. */
    fun mark(slot: WatchSlot): String = if (slot.group) ALL_CAME else MARK_DONE

    /** Заголовок плитки: «Ближайшая · 16:00–16:50», уже идёт — «Сейчас · 16:00–16:50». */
    fun title(slot: WatchSlot, now: LocalDateTime): String = HomeFormat.nearest(slot.start, slot.end, now)

    /** «через 12 мин» / «до конца 35 мин». */
    fun startsIn(slot: WatchSlot, now: LocalDateTime): String = HomeFormat.startsIn(now, slot.start, slot.end)

    /** «Сегодня всё. Завтра первая в 07:00». */
    fun todayDone(next: LocalDateTime?, today: LocalDate): String = HomeFormat.todayDone(next, today)

    /** «Ещё сегодня: 3, последняя в 19:00». */
    fun laterToday(count: Int, last: LocalDateTime): String = HomeFormat.laterToday(count, last)

    /** Плитка: «Сегодня +6 780 ₽ · к вечеру ≈ 12 600 ₽»; ничего не заработано — «К вечеру ≈ 12 600 ₽». */
    fun money(earned: Long, evening: Long): String = when {
        earned <= 0 -> "К вечеру " + MoneyFormat.approxRub(evening)
        earned >= evening -> "Сегодня " + MoneyFormat.signedRub(earned)
        else -> "Сегодня " + MoneyFormat.signedRub(earned) + DOT + EVENING + " " + MoneyFormat.approxRub(evening)
    }

    /** Короткая ячейка денег: «12 600» — к вечеру, округлено до 10 ₽, без знака рубля. */
    fun moneyShort(evening: Long): String = MoneyFormat.amount(MoneyFormat.roundTo10Rub(evening))

    /** Ячейка плана: «97 %». */
    fun percent(percent: Int): String = "${RuText.digits(percent.toLong())}$NBSP%"

    /** Ячейка дел: «3». */
    fun todo(count: Int): String = RuText.digits(count.toLong())

    /** Голос для ячейки дел: «Нужно сделать: 3». */
    fun todoDescription(count: Int): String = HomeFormat.todoCount(count)
}
