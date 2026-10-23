package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.MINUS
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.result.ResultItem
import com.akenory.personalka.domain.result.ResultTime
import com.akenory.personalka.domain.result.SwimEvent
import kotlinx.datetime.LocalDate

/** Строки результатов (ТЗ 5.11, 5.9; макет ClCardResults). */
object ResultFormat {
    private const val DOT = " · "

    /** «вольный стиль» — в названии дистанции. */
    fun style(style: SwimStyle): String = when (style) {
        SwimStyle.FREESTYLE -> "вольный стиль"
        SwimStyle.BACKSTROKE -> "на спине"
        SwimStyle.BREASTSTROKE -> "брасс"
        SwimStyle.BUTTERFLY -> "баттерфляй"
        SwimStyle.MEDLEY -> "комплекс"
    }

    /** Чип стиля в форме: «Вольный», «На спине». */
    fun styleChip(style: SwimStyle): String = when (style) {
        SwimStyle.FREESTYLE -> "Вольный"
        else -> style(style).replaceFirstChar { it.uppercaseChar() }
    }

    /** «50 м». */
    fun meters(distanceM: Int): String = "$distanceM${NBSP}м"

    /** «50 м вольный стиль». */
    fun event(event: SwimEvent): String = meters(event.distanceM) + " " + style(event.style)

    /** «Бассейн 25 м». */
    fun pool(poolM: Int): String = "Бассейн ${meters(poolM)}"

    /** «52,16», «1:03,17». */
    fun time(cs: Int): String = ResultTime.format(cs)

    /** «Личный рекорд, − 7,00» (5.11). */
    fun record(gainCs: Int): String = "Личный рекорд, $MINUS$NBSP${time(gainCs)}"

    /** Где показан: название старта, иначе «тренировка» или «соревнования». */
    private fun where(item: ResultItem): String =
        item.eventName?.takeIf { it.isNotBlank() } ?: if (item.kind == ResultKind.COMPETITION) "соревнования" else "тренировка"

    /** Строка рекорда: «соревнования · 10.10»; другой год — «10.10.2025». */
    fun recordLine(item: ResultItem, today: LocalDate): String =
        where(item) + DOT + if (item.date.year == today.year) DateFormat.dayMonth(item.date) else DateFormat.full(item.date)

    /** Строка в «Все результаты»: «тренировка · чт 15.10»; другой год — «15.10.2025». */
    fun listLine(item: ResultItem, today: LocalDate): String =
        where(item) + DOT + if (item.date.year == today.year) DateFormat.short(item.date) else DateFormat.full(item.date)

    /** Подпись оси графика: «40», «40,5», «1:05». */
    fun tick(cs: Int): String {
        if (cs % 100 != 0) return time(cs).trimEnd('0')
        val seconds = cs / 100
        return if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else seconds.toString()
    }

    /** Подпись поля времени, если набранное не читается; пусто или ещё набирается — `null`. */
    fun timeProblem(digits: String): String? =
        if (digits.length >= 3 && ResultTime.parse(digits) == null) BAD_TIME else null

    /** Для чтения с экрана: «50 м вольный стиль, 39,62, соревнования, 10.10». */
    fun spoken(item: ResultItem, today: LocalDate): String = event(item.event) + ", " + time(item.timeCs) + ", " + recordLine(item, today).replace(DOT, ", ")

    const val ADD = "Результат"
    const val TAB = "Результаты"
    const val NEW_TITLE = "Новый результат"
    const val EDIT_TITLE = "Результат"
    const val WHO = "Кто"
    const val DATE = "Дата"
    const val STYLE = "Стиль"
    const val DISTANCE = "Дистанция"
    const val POOL = "Бассейн"
    const val TIME = "Время"
    const val TIME_HINT = "Цифрами подряд: 5216 — 52,16, 10317 — 1:03,17"
    const val BAD_TIME = "Так время не читается: секунд не больше 59"
    const val WHERE = "Где"
    const val TRAINING = "Тренировка"
    const val COMPETITION = "Соревнования"
    const val EVENT_NAME = "Название старта"
    const val OPTIONAL = "Необязательно"
    const val SAVE = "Сохранить"
    const val DELETE = "Удалить"
    const val CANCEL = "Отмена"
    const val SAVED = "Результат сохранён"
    const val DELETED = "Результат удалён"
    const val RECORDS = "Личные рекорды"
    const val ALL = "Все результаты"
    const val LEGEND_COMPETITION = "соревнования"
    const val LEGEND_TRAINING = "тренировки"
    const val CHART_HINT = "Чем ниже точка, тем быстрее"
    const val CHART_EVENT = "Дистанция графика"
    const val MENU = "Добавить результат"
}
