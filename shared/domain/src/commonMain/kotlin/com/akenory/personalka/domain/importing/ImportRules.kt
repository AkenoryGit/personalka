package com.akenory.personalka.domain.importing

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

// ТЗ 9.2. Правила разового импорта из таблиц: как текст ячейки становится статусом, временем, результатом,
// группой и серией. Чтение xlsx — в tools/import, здесь только правила, чтобы их можно было проверить примерами.

/** Статус тренировки и участия клиента из столбца «Статус». */
data class ImportedStatus(val training: TrainingStatus, val participation: ParticipationStatus)

/** Время результата в сотых и бассейн, в котором он показан. */
data class ResultTime(val cs: Int, val poolM: Int)

/** Кто владелец группы и кто провёл тренировку — по метке «Д», «Д+Л», «Л+Д». */
data class GroupCode(val owner: Owner, val conductedBy: ConductedBy)

/** Постоянный слот: день недели и время начала. */
data class Slot(val weekday: DayOfWeek, val start: LocalTime)

/** Серия, найденная по прошлым тренировкам: слот, сколько раз был за 4 недели и с какого дня начинается. */
data class FoundSeries(val slot: Slot, val times: Int, val from: LocalDate)

object ImportRules {
    /** Длительность импортированной тренировки клиента, минуты. */
    const val DURATION_MIN = 50

    /** Тип и место клиента, если в файле сопоставления ничего не сказано. */
    const val DEFAULT_TYPE = "ПТ"
    const val DEFAULT_PLACE = "ББ"

    /** Окно для состава групп и серий: последние 4 недели. */
    const val WINDOW_DAYS = 28

    /** Столько раз в одном слоте за 4 недели — уже постоянное расписание. */
    const val SERIES_MIN_TIMES = 3

    val DISTANCES = listOf(25, 50, 100, 200, 400, 800, 1500)

    private val MONTHS = listOf(
        "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
    )

    /** Ключ для сравнения текста ячеек: строчными, «ё» = «е», пробелы схлопнуты. */
    fun key(text: String): String = ClientRules.key(text).replace(Regex("\\s+"), " ")

    /**
     * «Запланировано» → PLANNED / EXPECTED, «Проведено» → DONE / ATTENDED, «Отменено» → CANCELLED / CANCELLED,
     * «Отмена со списанием» → CANCELLED / CANCELLED_CHARGED. Регистр и окончания не важны; пустое и прочее — `null`.
     */
    fun status(text: String): ImportedStatus? {
        val k = key(text)
        return when {
            k.isEmpty() -> null
            "списан" in k -> ImportedStatus(TrainingStatus.CANCELLED, ParticipationStatus.CANCELLED_CHARGED)
            k.startsWith("отмен") -> ImportedStatus(TrainingStatus.CANCELLED, ParticipationStatus.CANCELLED)
            k.startsWith("провед") -> ImportedStatus(TrainingStatus.DONE, ParticipationStatus.ATTENDED)
            k.startsWith("заплан") -> ImportedStatus(TrainingStatus.PLANNED, ParticipationStatus.EXPECTED)
            else -> null
        }
    }

    /**
     * Время тренировки: «10.00», «10:00», «9.30», «9,30», «9» — в чч:мм. Хвост после времени («10.00–10.50») не мешает.
     * Пустое и непонятное — `null`, такая строка уходит в отчёт.
     */
    fun time(text: String): LocalTime? {
        val match = Regex("^(\\d{1,2})(?:[.:,](\\d{2}))?(?![\\d.:,])").find(text.trim()) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        if (hour > 23 || minute > 59) return null
        return LocalTime(hour, minute)
    }

    /**
     * Дата ячейки, набранная текстом: «22.10.2026», «22.10.26», «2026-10-22». Без года — `null`:
     * год не угадывается, строка уходит в отчёт.
     */
    fun date(text: String): LocalDate? {
        val clean = text.trim()
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(clean)?.let { m ->
            val (y, mo, d) = m.destructured
            return safeDate(y.toInt(), mo.toInt(), d.toInt())
        }
        val m = Regex("^(\\d{1,2})[./](\\d{1,2})[./](\\d{2}|\\d{4})(?!\\d)").find(clean) ?: return null
        val (d, mo, y) = m.destructured
        val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
        return safeDate(year, mo.toInt(), d.toInt())
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        if (month in 1..12 && day in 1..31) runCatching { LocalDate(year, month, day) }.getOrNull() else null

    /** «в/ст» и «вольный», «н/сп» и «на спине», «брасс», «батт» и «дельфин», «к/п» и «комплекс». */
    fun style(text: String): SwimStyle? {
        val k = key(text).replace(" ", "")
        return when {
            "в/ст" in k || "вольн" in k || "кроль" in k -> SwimStyle.FREESTYLE
            "н/сп" in k || "спин" in k -> SwimStyle.BACKSTROKE
            "брасс" in k -> SwimStyle.BREASTSTROKE
            "батт" in k || "дельф" in k -> SwimStyle.BUTTERFLY
            "к/п" in k || "комплекс" in k -> SwimStyle.MEDLEY
            else -> null
        }
    }

    /** Дистанция — первое число в ячейке («50», «100 в/ст», «50м»), если такая бывает в плавании. */
    fun distance(text: String): Int? =
        Regex("\\d+").find(text)?.value?.toIntOrNull()?.takeIf { it in DISTANCES }

    /**
     * Время результата: «1.03,17», «1,14.75», «59.00», «52.16», «56,99 (50м бассейн)». Три группы цифр — минуты,
     * секунды, сотые; две — секунды, сотые; одна цифра сотых — десятые («59.5» = 59,50). Бассейн 50 м, если в скобках
     * написано «50м», иначе 25 м. Непонятное — `null`.
     */
    fun resultTime(text: String): ResultTime? {
        val brackets = Regex("\\(([^)]*)\\)").findAll(text).joinToString(" ") { it.groupValues[1] }
        val pool = if (Regex("50\\s*м").containsMatchIn(key(brackets))) 50 else 25
        val body = text.replace(Regex("\\([^)]*\\)"), "").trim()
        if (!Regex("^\\d+([.,:]\\d+){1,2}$").matches(body)) return null
        val groups = body.split('.', ',', ':')
        val hundredths = groups.last().let { if (it.length == 1) it.toInt() * 10 else if (it.length == 2) it.toInt() else return null }
        val cs = when (groups.size) {
            3 -> {
                val (min, sec) = groups[0].toInt() to groups[1].toInt()
                if (sec > 59 || groups[1].length != 2) return null
                (min * 60 + sec) * 100 + hundredths
            }
            else -> groups[0].toInt() * 100 + hundredths
        }
        return if (cs > 0) ResultTime(cs, pool) else null
    }

    /** «Д» — моя группа, провёл один; «Д+Л» — моя, вместе с Леной; «Л+Д» — группа Лены, вместе. */
    fun groupCode(text: String): GroupCode? = when (key(text).replace(" ", "")) {
        "д" -> GroupCode(Owner.SELF, ConductedBy.SELF)
        "д+л" -> GroupCode(Owner.SELF, ConductedBy.SELF_AND_COLLEAGUE)
        "л+д" -> GroupCode(Owner.COLLEAGUE, ConductedBy.SELF_AND_COLLEAGUE)
        else -> null
    }

    /** Месяц по имени листа: «Октябрь», «Октябрь 2026», «Октябрь группы». */
    fun sheetMonth(name: String): Month? {
        val first = key(name).split(' ').firstOrNull() ?: return null
        val index = MONTHS.indexOf(first)
        return if (index >= 0) Month.entries[index] else null
    }

    /** Лист «<Месяц> группы» — список групповых тренировок месяца. */
    fun isGroupsSheet(name: String): Boolean = sheetMonth(name) != null && key(name).endsWith("группы")

    /** Год, написанный в имени листа («Октябрь 2026»). */
    fun sheetYear(name: String): Int? = Regex("(?<!\\d)(20\\d{2})(?!\\d)").find(name)?.value?.toInt()

    /** Год листа без года в имени — тот, что чаще всего встречается в его датах. */
    fun usualYear(dates: List<LocalDate>): Int? = dates.groupingBy { it.year }.eachCount().maxByOrNull { it.value }?.key

    /** Дата с опечаткой года (2023 в листе 2026-го) — на год листа; без опечатки — та же. */
    fun fixYear(date: LocalDate, sheetYear: Int): LocalDate =
        if (date.year == sheetYear) date else safeDate(sheetYear, date.month.number, date.day) ?: date

    /**
     * ФИО из ячейки: «Фамилия Имя Отчество». Меньше двух слов — `null`: без фамилии клиента не сопоставить.
     * Лишние пробелы убираются, регистр сохраняется.
     */
    fun name(text: String): ClientName? {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return null
        return ClientName(words[0], words[1], words.drop(2).joinToString(" ").ifEmpty { null })
    }

    /** Ключ сопоставления имён (8.3): фамилия + имя, отчество не учитывается. */
    fun nameKey(name: ClientName): String = "${key(name.lastName)} ${key(name.firstName)}"

    /** Сумма денег из ячейки: «12 500», «12500,50», «-1 000 ₽» — в копейках. Непонятное — `null`. */
    fun money(text: String): Long? {
        val clean = key(text).replace(Regex("[\\s\u00A0\u2009\u202F₽]|руб"), "").removeSuffix(".").removeSuffix("р").replace(',', '.')
        val m = Regex("^(-?)(\\d+)(?:\\.(\\d{1,2}))?$").find(clean) ?: return null
        val (sign, rub, kop) = m.destructured
        val value = rub.toLong() * 100 + kop.padEnd(2, '0').toLong()
        return if (sign == "-") -value else value
    }

    /** Число из ячейки в копейки: 12500.5 → 1 250 050. */
    fun money(value: Double): Long = kotlin.math.round(value * 100).toLong()

    /** Первый день окна «последние 4 недели» до [last] включительно. */
    fun windowStart(last: LocalDate): LocalDate = last.minus(WINDOW_DAYS - 1, DateTimeUnit.DAY)

    /**
     * Постоянное расписание клиента: слоты, в которые он приходил не менее [SERIES_MIN_TIMES] раз за последние
     * 4 недели до [last] (последний день импорта). Серия начинается со следующего дня после его последней тренировки.
     */
    fun series(starts: List<LocalDateTime>, last: LocalDate): List<FoundSeries> {
        if (starts.isEmpty()) return emptyList()
        val from = starts.maxOf { it.date }.plus(1, DateTimeUnit.DAY)
        val window = windowStart(last)
        return starts
            .filter { it.date in window..last }
            .groupingBy { Slot(it.date.dayOfWeek, it.time) }
            .eachCount()
            .filter { it.value >= SERIES_MIN_TIMES }
            .map { (slot, times) -> FoundSeries(slot, times, from) }
            .sortedWith(compareBy({ it.slot.weekday }, { it.slot.start }))
    }

    /** Состав постоянной группы: дети, приходившие за последние 4 недели до [last]. */
    fun <T> members(visits: List<Pair<LocalDate, T>>, last: LocalDate): Set<T> {
        val window = windowStart(last)
        return visits.filter { it.first in window..last }.map { it.second }.toSet()
    }
}
