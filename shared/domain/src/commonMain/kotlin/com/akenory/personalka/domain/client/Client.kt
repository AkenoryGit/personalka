package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.training.ClientOutcome
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number

// ТЗ 3.5, 4.17, 5.8, 5.9. Клиент для правил и форм — без служебных полей базы.

/** ФИО клиента. В списке и строках — «Фамилия Имя», отчество — только в карточке и в поиске. */
data class ClientName(val lastName: String, val firstName: String, val middleName: String? = null) {
    /** «Дельфенурова Аделина». */
    val short: String get() = "${lastName.trim()} ${firstName.trim()}".trim()

    /** «Дельфенурова Аделина Сергеевна». */
    val full: String get() = listOfNotNull(lastName, firstName, middleName).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}

/** Всё, что тренер вводит в форме клиента (5.8). Пустая строка — «не указано». */
data class ClientDraft(
    val lastName: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val sex: Sex? = null,
    val birthDate: LocalDate? = null,
    /** Взрослый ли — вручную, только пока нет даты рождения: с ней считается сам, 18+ (3.5). */
    val isAdult: Boolean = false,
    /** «Для связи»: кто — «мама», «папа», «сама». */
    val contactName: String = "",
    val contactPhone: String = "",
    /** Ссылка на фото и видео: Яндекс Диск, Облако Mail.ru. */
    val mediaUrl: String = "",
    /** Заметка «только для себя» (5.9): видна только тренеру, в кабинет не передаётся (6.2). */
    val privateNote: String = "",
) {
    val name: ClientName get() = ClientName(lastName, firstName, middleName)
}

/** Поля формы клиента, под которыми показывается ошибка. */
enum class ClientField { LAST_NAME, FIRST_NAME, BIRTH_DATE, PHONE }

/** Предложенный пол и откуда он взят — подсказка под чипами: «Предложен по отчеству». */
data class SexGuess(val sex: Sex, val byMiddleName: Boolean)

/** Постоянное расписание одной строкой списка: «Вт, Чт 10:00 · ПТ ББ» (5.8). */
data class ScheduleLine(val weekdays: Set<DayOfWeek>, val start: LocalTime, val typeShort: String, val placeShort: String)

/** Правила клиентов: поиск, похожие, пол, возраст, проверка формы (5.8, 4.17). */
object ClientRules {
    /** С 18 лет — взрослый (3.5). */
    const val ADULT_AGE = 18

    /** До скольких букв разницы в фамилии клиент считается похожим (5.8). */
    const val SIMILAR_LETTERS = 2

    /** Старше — почти наверняка ошибка в годе рождения. */
    private const val MAX_AGE = 110

    /** Ключ для поиска и сравнения: без пробелов по краям, строчными, «ё» = «е». */
    fun key(text: String): String = text.trim().lowercase().replace('ё', 'е')

    /**
     * Порядок по фамилии (5.8): «Ё» стоит вместе с «Е», а не после «Я», как поставил бы SQLite.
     * Однофамильцы — по имени, потом по отчеству.
     */
    fun sortKey(name: ClientName): String = listOf(name.lastName, name.firstName, name.middleName.orEmpty()).joinToString("\u0000") { key(it) }

    /**
     * Поиск (5.8): каждое слово запроса — начало фамилии, имени или отчества; регистр не важен, «ё» = «е».
     * «Ков Ар» находит «Ковалёв Артём», пустой запрос — всех.
     */
    fun matches(query: String, name: ClientName): Boolean {
        val words = words(query)
        if (words.isEmpty()) return true
        val parts = listOfNotNull(name.lastName, name.firstName, name.middleName).flatMap { words(it) }
        return words.all { word -> parts.any { it.startsWith(word) } }
    }

    private fun words(text: String): List<String> = key(text).split(' ', '-').filter { it.isNotEmpty() }

    /**
     * Похожие клиенты перед созданием нового (5.8): то же имя и фамилия, отличающаяся не больше чем на
     * [SIMILAR_LETTERS] буквы, — «Дельфенурова» и «Дельфинурова». Регистр и «ё» не считаются. Ближайшие первыми.
     * Имя и фамилия, набранные наоборот («Аделина Дельфенурова»), — тоже похожий (шаг 3.9о): так их пишут в поиске.
     */
    fun <T> similar(name: ClientName, candidates: List<T>, nameOf: (T) -> ClientName): List<T> {
        val first = key(name.firstName)
        val last = key(name.lastName)
        if (first.isEmpty() || last.isEmpty()) return emptyList()
        return candidates
            .mapNotNull { candidate ->
                val other = nameOf(candidate)
                val otherFirst = key(other.firstName)
                val otherLast = key(other.lastName)
                val letters = when {
                    otherFirst == first -> distance(otherLast, last)
                    otherFirst == last && otherLast == first -> 0
                    else -> return@mapNotNull null
                }
                if (letters <= SIMILAR_LETTERS) candidate to letters else null
            }
            .sortedBy { it.second }
            .map { it.first }
    }

    /** Сколько букв заменить, вставить или убрать, чтобы из [a] получилось [b] (расстояние Левенштейна). */
    fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val replace = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + replace)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /**
     * Пол по отчеству, а без него — по фамилии (4.17). Остальное (Ли, Шевченко, Дюма) — `null`:
     * тренер выберет сам или оставит пустым.
     */
    fun guessSex(middleName: String?, lastName: String): SexGuess? {
        val middle = key(middleName.orEmpty())
        val byMiddle = when {
            middle.endsWith("вна") || middle.endsWith("ична") -> Sex.FEMALE
            middle.endsWith("ич") -> Sex.MALE
            else -> null
        }
        if (byMiddle != null) return SexGuess(byMiddle, byMiddleName = true)
        val last = key(lastName)
        val byLast = when {
            FEMALE_LAST.any { last.endsWith(it) } -> Sex.FEMALE
            MALE_LAST.any { last.endsWith(it) } -> Sex.MALE
            else -> null
        }
        return byLast?.let { SexGuess(it, byMiddleName = false) }
    }

    private val FEMALE_LAST = listOf("ова", "ева", "ина", "ына", "ая")
    private val MALE_LAST = listOf("ов", "ев", "ин", "ын", "ий", "ой")

    /** Полных лет на [today]. */
    fun age(birth: LocalDate, today: LocalDate): Int =
        today.year - birth.year - if (beforeBirthday(birth, today)) 1 else 0

    /** Полных месяцев на [today] — возраст до года: «8 мес». */
    fun ageMonths(birth: LocalDate, today: LocalDate): Int =
        (today.year - birth.year) * 12 + today.month.number - birth.month.number - if (today.day < birth.day) 1 else 0

    private fun beforeBirthday(birth: LocalDate, today: LocalDate): Boolean =
        today.month.number < birth.month.number || (today.month.number == birth.month.number && today.day < birth.day)

    /** Взрослый ли (3.5): с датой рождения — по возрасту, без неё — как отметил тренер. */
    fun isAdult(birth: LocalDate?, manual: Boolean, today: LocalDate): Boolean =
        if (birth != null) age(birth, today) >= ADULT_AGE else manual

    /**
     * Ошибки формы словами тренера. [birthText] — то, что набрано в поле даты: набранное, но не понятое
     * приложением, — тоже ошибка, а не молча пустая дата.
     */
    fun problems(draft: ClientDraft, birthText: String, today: LocalDate): Map<ClientField, String> = buildMap {
        if (draft.lastName.isBlank()) put(ClientField.LAST_NAME, "Введите фамилию")
        if (draft.firstName.isBlank()) put(ClientField.FIRST_NAME, "Введите имя")
        val birth = DateFormat.parseFull(birthText)
        when {
            birthText.isBlank() -> Unit
            birth == null -> put(ClientField.BIRTH_DATE, "Дата в виде 12.05.2017")
            birth > today -> put(ClientField.BIRTH_DATE, "Эта дата ещё не наступила")
            age(birth, today) > MAX_AGE -> put(ClientField.BIRTH_DATE, "Проверьте год")
        }
        val digits = draft.contactPhone.count { it.isDigit() }
        if (draft.contactPhone.isNotBlank() && digits !in 5..15) put(ClientField.PHONE, "Проверьте номер")
    }

    /**
     * Телефон для записи: российский мобильный — «+79123842017», как его ни набрали («8 912 384-20-17»,
     * «9123842017»); остальное — как набрано.
     */
    fun normalizePhone(raw: String): String {
        val text = raw.trim()
        val digits = text.filter { it.isDigit() }
        return when {
            digits.length == 11 && (digits[0] == '7' || digits[0] == '8') && digits[1] == '9' -> "+7" + digits.drop(1)
            digits.length == 10 && digits[0] == '9' && !text.startsWith("+") -> "+7$digits"
            else -> text
        }
    }
}

/**
 * Счётчики в шапке карточки (5.9): проведено · отменено · со списанием · в среднем в месяц.
 * «В месяц» — проведённые с первой проведённой до сегодня, не меньше чем за месяц; в десятых: 74 — «7,4».
 */
data class ClientStats(val done: Int, val cancelled: Int, val charged: Int, val perMonthTenths: Int) {
    /** Тренировок ещё не было — счётчики не показываются (10.7: пустое не показывается). */
    val isEmpty: Boolean get() = done == 0 && cancelled == 0 && charged == 0

    companion object {
        private const val DAYS_IN_MONTH = 30.4375

        /** [outcomes] — дата и итог клиента на каждой его тренировке; запланированные не считаются. */
        fun of(outcomes: List<Pair<LocalDate, ClientOutcome>>, today: LocalDate): ClientStats {
            val done = outcomes.filter { it.second == ClientOutcome.DONE }
            val first = done.minOfOrNull { it.first }
            val perMonth = if (first == null) {
                0
            } else {
                val days = (today.toEpochDays() - first.toEpochDays() + 1).coerceAtLeast(1)
                val months = maxOf(1.0, days / DAYS_IN_MONTH)
                kotlin.math.round(done.size * 10 / months).toInt()
            }
            return ClientStats(
                done = done.size,
                cancelled = outcomes.count { it.second == ClientOutcome.CANCELLED },
                charged = outcomes.count { it.second == ClientOutcome.CANCELLED_CHARGED },
                perMonthTenths = perMonth,
            )
        }
    }
}
