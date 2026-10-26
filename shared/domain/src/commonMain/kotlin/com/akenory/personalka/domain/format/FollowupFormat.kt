package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.model.Sex
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Строки «Без записи» (4.15, 5.8, макет ClNoBooking): раздел, строка клиента, действия, метка, пункт «Главной». */
object FollowupFormat {
    private const val DOT = " · "

    /** Чип списка клиентов: «Без записи · 6». */
    fun chip(count: Int): String = "Без записи$DOT${RuText.digits(count.toLong())}"

    const val SECTION = "Не записаны дальше"

    /** Подпись раздела: «Ходили за последние 14 дней, будущих тренировок нет». */
    fun sectionNote(days: Int): String = "Ходили за последние ${RuText.count(days, "день", "дня", "дней")}, будущих тренировок нет"

    /** Раздел пуст — так и сказать, а не прятать: чип выбран. */
    const val EMPTY = "Все, кто ходит, записаны дальше"

    /**
     * Последнее посещение: «была пн 12.10 · СПТ ББ», сегодня — «был сегодня 15:00 · ПТ ДБ»; пол неизвестен —
     * «последняя вт 15.10 · ПТ ББ».
     */
    fun lastVisit(sex: Sex?, start: LocalDateTime, typeShort: String, placeShort: String, today: LocalDate): String {
        val word = RuText.bySex(sex, "был", "была", "последняя")
        val day = if (start.date == today) "сегодня ${DateFormat.time(start.time)}" else DateFormat.short(start.date)
        val what = listOf(typeShort, placeShort).filter { it.isNotBlank() }.joinToString(NBSP.toString())
        return "$word $day" + if (what.isNotEmpty()) DOT + what else ""
    }

    /** Пометка «после СПТ 12.10» (4.9): такие идут первыми. */
    fun afterSpt(date: LocalDate): String = "после СПТ ${DateFormat.dayMonth(date)}"

    /** Пометка «писали 18.10». */
    fun contacted(date: LocalDate): String = "писали ${DateFormat.dayMonth(date)}"

    /** Метка в шапке карточки клиента. */
    const val NO_BOOKING = "Нет записи"

    /** Метка в карточке, пока идёт пауза: «Пауза до 01.11 · отпуск». Причина видна только тренеру. */
    fun paused(until: LocalDate, note: String?): String =
        "Пауза до ${DateFormat.dayMonth(until)}" + (note?.trim()?.takeIf { it.isNotEmpty() }?.let { DOT + it } ?: "")

    // --- Действия ---

    const val BOOK = "Записать"
    const val CALL = "Позвонить"
    const val CONTACTED = "Написал"
    const val PAUSE = "Пауза до…"
    const val GONE = "Больше не ходит"
    const val DISMISS = "Убрать из списка"
    const val PHONE_COPIED = "Номер скопирован"

    /** После «Написал»: клиент скрыт на 3 дня. */
    fun contactedMessage(name: String): String = "$name — скрыт на 3 дня"

    /** После «Убрать из списка»: «Соколова В. — убрали из списка». */
    fun dismissedMessage(name: String): String = "$name — убрали из списка"

    const val PAUSE_TITLE = "Пауза"
    const val PAUSE_UNTIL = "До какого дня"
    const val PAUSE_NOTE = "Причина — видна только вам"
    const val PAUSE_NOTE_HINT = "Отпуск, уехал — без диагнозов"
    const val PAUSE_SAVE = "Поставить на паузу"

    /** После «Пауза до…»: «Соколова В. — пауза до 01.11». */
    fun pausedMessage(name: String, until: LocalDate): String = "$name — пауза до ${DateFormat.dayMonth(until)}"

    // --- «Нужно сделать» (5.2) ---

    /** Пункт «Главной»: «Клиент без записи», несколько — «Клиенты без записи». */
    fun todo(count: Int): String = if (count == 1) "Клиент без записи" else "Клиенты без записи"

    /** Подпись пункта: первые имена, остальные — «и ещё N»: «Яковлева Т., Захаров Н. и ещё 1». */
    fun todoNote(names: List<ClientName>): String {
        val shown = names.take(HomeFormat.MAX_NAMES).joinToString(", ") { CalendarFormat.client(it) }
        val rest = names.size - HomeFormat.MAX_NAMES
        return if (rest > 0) "$shown и ещё ${RuText.digits(rest.toLong())}" else shown
    }
}
