package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.WaitProblem
import com.akenory.personalka.domain.client.WaitWant
import com.akenory.personalka.domain.format.RuText.DASH
import com.akenory.personalka.domain.model.TimeRange
import kotlinx.datetime.LocalDate

/** Строки листа ожидания (4.16, 5.8, макеты ClNoBooking, ClWaitAdd). */
object WaitFormat {
    private const val DOT = " · "

    const val SECTION = "Ждут время"
    const val ADD = "В ожидание"
    const val EMPTY = "Никто не ждёт"

    /** Метка в шапке карточки клиента. */
    const val LABEL = "Ждёт"

    /** Пункт меню карточки клиента (5.9). */
    const val TO_WAITLIST = "В лист ожидания…"

    /**
     * Что ждёт: «постоянно · пн, ср · 07–12 · ПТ · ББ», «разово до 31.10 · любой день · 17–23 · ПТ»; любое место
     * не пишется. Промежутки — часами, если они на целых часах: «07–12», иначе «07:30–09:00».
     */
    fun want(want: WaitWant, typeShort: String, placeShort: String?): String {
        val kind = buildString {
            append(
                when {
                    want.series && want.oneOff -> "постоянно или разово"
                    want.series -> "постоянно"
                    else -> "разово"
                },
            )
            if (want.oneOff && want.until != null) append(" до ").append(DateFormat.dayMonth(want.until))
        }
        val days = if (want.days.isEmpty()) "любой день" else DateFormat.WEEK.filter { it in want.days }.joinToString(", ") { DateFormat.weekdayShort(it) }
        val time = if (want.ranges.isEmpty()) "любое время" else want.ranges.joinToString(", ") { range(it) }
        return listOfNotNull(kind, days, time, typeShort.ifBlank { null }, placeShort?.ifBlank { null }).joinToString(DOT)
    }

    /** Промежуток начала: «07–12», «07:30–09:00». */
    fun range(range: TimeRange): String {
        val whole = range.start.minute == 0 && range.end.minute == 0
        return if (whole) {
            two(range.start.hour) + DASH + two(range.end.hour)
        } else {
            DateFormat.timeRange(range.start, range.end)
        }
    }

    /** Чипы «Время начала»: «Утро 07–12». */
    fun preset(range: TimeRange): String = when (range.start.hour) {
        7 -> "Утро"
        12 -> "День"
        else -> "Вечер"
    } + " " + range(range)

    const val MORE_EXACT = "Точнее…"

    /** «ждёт 37 дней», в день записи — «ждёт с сегодня». */
    fun waiting(days: Int): String = if (days == 0) "ждёт с сегодня" else "ждёт " + RuText.count(days, "день", "дня", "дней")

    /** Заметка в строке — в кавычках: «пн, ср на 8 или на 10». */
    fun note(text: String): String = "«${text.trim()}»"

    // --- Форма «+ В ожидание» (макет ClWaitAdd) ---

    const val TITLE_NEW = "В ожидание"
    const val TITLE_EDIT = "Что ждёт"
    const val CLIENT = "Клиент"
    const val SEARCH = "Найти или добавить клиента"
    const val KIND = "Что ждёт"
    const val SERIES = "Постоянное время"
    const val ONE_OFF = "Разово"
    const val UNTIL = "Разово — до"
    const val NO_UNTIL = "Без срока"
    const val DAYS = "Дни"
    const val TIME = "Время начала"
    const val PLACE = "Место"
    const val ANY_PLACE = "Любое"
    const val TYPE = "Тип"
    const val NOTE = "Заметка"
    const val NOTE_HINT = "Своими словами: какие часы удобнее, с кем можно в сплит"
    const val SAVE = "Сохранить"
    const val EXACT_TITLE = "Время начала"
    const val EXACT_FROM = "С"
    const val EXACT_TO = "До"
    const val EXACT_ADD = "Добавить"
    const val NEW_LAST = "Фамилия"
    const val NEW_FIRST = "Имя"
    const val NEW_PHONE = "Телефон"

    /** «Новый клиент: Смирнова» под поиском, когда никого не нашлось. */
    fun newClient(query: String): String = "Новый клиент: ${query.trim()}"

    fun problem(problem: WaitProblem): String = when (problem) {
        WaitProblem.NO_CLIENT -> "Выберите клиента"
        WaitProblem.NO_KIND -> "Отметьте, что ждёт: постоянное время или разово"
        WaitProblem.NO_TYPE -> "Выберите тип"
        WaitProblem.UNTIL_PAST -> "Срок уже прошёл"
    }

    // --- Действия ---

    const val BOOK = "Записать"
    const val CONTACTED = "Написал"
    const val EDIT = "Изменить"
    const val CLOSE = "Больше не ждёт"
    const val SAVED = "Добавлен в лист ожидания"
    const val CHANGED = "Сохранено"

    /** После «Больше не ждёт»: «Петров К. больше не ждёт». */
    fun closed(name: String): String = "$name больше не ждёт"

    /** После записи через «Записать» (4.16): «Убран из листа ожидания» с «Вернуть». */
    const val BOOKED = "Убран из листа ожидания"
    const val RETURN = "Вернуть"

    /** Тренировок у клиента нет — предложить удалить и карточку (4.16). */
    const val ERASE_TITLE = "Удалить и карточку?"

    fun eraseText(name: String): String = "$name: тренировок нет ни одной. Карточка больше не нужна?"

    const val ERASE = "Удалить карточку"
    const val KEEP = "Оставить"

    /** Подпись над формой тренировки из «Записать» (макет ClBook). */
    const val FORM_BANNER = "Из листа ожидания. После сохранения клиент уйдёт из листа сам."

    private fun two(n: Int): String = n.toString().padStart(2, '0')
}
