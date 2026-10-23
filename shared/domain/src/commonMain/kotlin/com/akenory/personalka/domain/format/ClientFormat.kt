package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.client.ScheduleLine
import com.akenory.personalka.domain.format.RuText.DASH
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.model.Sex
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** Строки о клиентах: список, карточка, форма (5.8, 5.9). */
object ClientFormat {
    private const val DOT = " · "

    /** «9 лет», «1 год», до года — «8 мес». */
    fun age(birth: LocalDate, today: LocalDate): String {
        val years = ClientRules.age(birth, today)
        return if (years >= 1) RuText.count(years, "год", "года", "лет") else "${ClientRules.ageMonths(birth, today).coerceAtLeast(0)}${NBSP}мес"
    }

    /** «12.05.2017 · 9 лет» — строка «Дата рождения» в карточке. */
    fun birth(birth: LocalDate, today: LocalDate): String = DateFormat.full(birth) + DOT + age(birth, today)

    /** Подсказка под датой рождения в форме: «9 лет · ребёнок». */
    fun ageHint(birth: LocalDate, today: LocalDate): String =
        age(birth, today) + DOT + if (ClientRules.isAdult(birth, false, today)) "взрослый" else "ребёнок"

    /** Метка в шапке карточки. */
    fun adult(isAdult: Boolean): String = if (isAdult) "Взрослый" else "Ребёнок"

    fun sex(sex: Sex): String = if (sex == Sex.MALE) "Мужской" else "Женский"

    /** «Вт, Чт 10:00 · ПТ ББ». */
    fun schedule(line: ScheduleLine): String = "${daysTime(line)}$DOT${line.typeShort}${NBSP}${line.placeShort}"

    /** Дни и время серии без типа и места: «Вт, Чт 10:00». */
    fun daysTime(line: ScheduleLine): String {
        val days = DateFormat.WEEK.filter { it in line.weekdays }
            .joinToString(", ") { DateFormat.weekdayShort(it).replaceFirstChar { c -> c.uppercaseChar() } }
        return "$days${NBSP}${DateFormat.time(line.start)}"
    }

    /** Все серии клиента через «;», по первому дню недели; нет серий — `null`. */
    fun schedule(lines: List<ScheduleLine>): String? =
        lines.sortedWith(compareBy({ line -> DateFormat.WEEK.indexOfFirst { it in line.weekdays } }, { it.start }))
            .joinToString("; ") { schedule(it) }
            .takeIf { it.isNotEmpty() }

    /** Строка под именем: «9 лет · Сб 09:00 · Группа ББ», «13 лет · без расписания». */
    fun subtitle(age: String?, schedule: String?): String =
        listOfNotNull(age, schedule ?: "без расписания").joinToString(DOT)

    /** Ближайшая тренировка справа в строке: «сегодня 16:00», иначе «чт 22.10». */
    fun next(start: LocalDateTime, today: LocalDate): String =
        if (start.date == today) "сегодня ${DateFormat.time(start.time)}" else DateFormat.short(start.date)

    /** «42 клиента». */
    fun clients(count: Int): String = RuText.count(count, "клиент", "клиента", "клиентов")

    // --- Вкладка «Расписание» (5.9, макет ClCardSchedule) ---

    const val SCHEDULE_TITLE = "Постоянное расписание"
    const val NO_SCHEDULE = "Постоянного расписания нет"
    const val ADD_TRAINING = "Тренировка"
    const val ADD_SERIES = "Серия"
    const val CHANGE_FROM = "Изменить с даты…"
    const val END_SERIES = "Завершить"
    const val END_TITLE = "Завершить серию?"
    const val LAST_DAY = "Последний день"
    const val END_NOTE = "Тренировки после него удалятся, их планы сохранятся у клиента."
    const val SERIES_ENDED = "Серия завершена"

    /** Дни и время серии: «Вт, Чт · 08:00–08:50». */
    fun seriesDays(weekdays: Set<DayOfWeek>, start: LocalTime, durationMin: Int): String {
        val days = DateFormat.WEEK.filter { it in weekdays }
            .joinToString(", ") { DateFormat.weekdayShort(it).replaceFirstChar { c -> c.uppercaseChar() } }
        val from = start.hour * 60 + start.minute
        return days + DOT + DateFormat.timeRange(from, from + durationMin)
    }

    /**
     * Тип, место и период серии: «ПТ · ББ · с 01.09.2026», с концом — «ПТ · ББ · 01.09–31.12.2026»,
     * прошедшая — «ПТ · ББ · 02.06–31.08.2026 · завершена».
     */
    fun seriesPeriod(typeShort: String, placeShort: String, from: LocalDate, to: LocalDate?, today: LocalDate): String {
        val period = when {
            to == null -> "с ${DateFormat.full(from)}"
            to.year == from.year -> "${DateFormat.dayMonth(from)}$DASH${DateFormat.full(to)}"
            else -> "${DateFormat.full(from)}$DASH${DateFormat.full(to)}"
        }
        return listOfNotNull(typeShort.ifEmpty { null }, placeShort.ifEmpty { null }, period, "завершена".takeIf { to != null && to < today })
            .joinToString(DOT)
    }

    /** Сообщение после «Изменить с даты…»: «Расписание изменено с вт 27.10». */
    fun changedFrom(date: LocalDate): String = "Расписание изменено с ${DateFormat.short(date)}"

    /** Десятые в счётчик «в месяц»: 74 → «7,4», 10 → «1,0». */
    fun tenths(value: Int): String = "${RuText.digits((value / 10).toLong())},${value % 10}"

    /**
     * Телефон для показа: российский — «+7 912 384-20-17», остальное — как записан.
     * Записывается он так, как вернул [ClientRules.normalizePhone].
     */
    fun phone(stored: String): String {
        val digits = stored.drop(2)
        if (!stored.startsWith("+7") || digits.length != 10 || !digits.all { it.isDigit() }) return stored
        return "+7 ${digits.substring(0, 3)} ${digits.substring(3, 6)}-${digits.substring(6, 8)}-${digits.substring(8)}"
    }
}

/**
 * Имена в падежах для фраз 4.17: «Родственник для Артёма», «Кем приходится Артёму», «У Натальи появится».
 * Только русские имена по окончанию; имя, которое так не склоняется (Ирэн, Нико, двойное), остаётся как есть —
 * лучше несклонённое, чем неправильно склонённое. Кабинет имена не склоняет (решение шага 0.7).
 */
object RuName {
    /** Кого? чего?: Артёма, Натальи, Марии, Игоря, Ольги. */
    fun genitive(name: String, sex: Sex?): String = decline(name, sex, dative = false)

    /** Кому? чему?: Артёму, Наталье, Марии, Игорю, Ольге. */
    fun dative(name: String, sex: Sex?): String = decline(name, sex, dative = true)

    private const val CONSONANTS = "бвгджзклмнпрстфхцчшщ"
    private const val HUSHING = "гкхжчшщ"

    /** Беглая гласная: Павел — Павла, Лев — Льва, Пётр — Петра. */
    private val FLEETING = mapOf("павел" to "Павл", "лев" to "Льв", "пётр" to "Петр", "петр" to "Петр")

    private fun decline(name: String, sex: Sex?, dative: Boolean): String {
        val word = name.trim()
        if (word.length < 2 || word.any { it == ' ' || it == '-' }) return word
        val lower = word.lowercase()
        val last = lower.last()
        val stem = word.dropLast(1)
        val male = sex != Sex.FEMALE
        return when {
            lower.endsWith("ия") -> stem + "и"
            last == 'я' -> stem + if (dative) "е" else "и"
            last == 'а' -> stem + when {
                dative -> "е"
                stem.last().lowercaseChar() in HUSHING -> "и"
                else -> "ы"
            }
            last == 'ь' && sex == Sex.FEMALE -> stem + "и"
            last == 'ь' && sex == Sex.MALE -> stem + if (dative) "ю" else "я"
            last == 'й' && male -> stem + if (dative) "ю" else "я"
            last in CONSONANTS && male -> (FLEETING[lower] ?: word) + if (dative) "у" else "а"
            else -> word
        }
    }
}
