package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.model.Sex

/**
 * Русский текст (ТЗ 10.6): склонение числительных, слова по полу клиента, пробелы и знаки.
 *
 * Форматы лежат в `shared/domain`, а не в экранах: одни и те же строки нужны приложению и кабинету
 * родителя на сервере, а проверяются они обычными тестами на компьютере.
 */
object RuText {
    /** Неразрывный пробел — между числом и единицей («50 мин», «830 ₽») и в «вт 22.10». Строка не рвётся переносом. */
    const val NBSP: Char = ' '

    /** Узкий неразрывный пробел — между разрядами: «126 645». Тонкий, как требует 10.6, и тоже не рвётся. */
    const val THIN: Char = ' '

    /** Минус для сумм: «−8 170,50». Дефис короче и выглядит как тире в тексте. */
    const val MINUS: Char = '−'

    /** Короткое тире для промежутков: «16:00–16:50». */
    const val DASH: Char = '–'

    /**
     * Форма слова для числа: 1 тренировка, 2 тренировки, 5 тренировок; 11–14 — всегда «тренировок»,
     * 21 — снова «тренировка». Отрицательные числа — как положительные.
     */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val lastTwo = (n % 100).let { if (it < 0) -it else it }
        val last = lastTwo % 10
        return when {
            lastTwo in 11L..14L -> many
            last == 1L -> one
            last in 2L..4L -> few
            else -> many
        }
    }

    fun plural(n: Int, one: String, few: String, many: String): String = plural(n.toLong(), one, few, many)

    /** Число со словом: «1 тренировка», «12 тренировок», «1 350 детей». */
    fun count(n: Long, one: String, few: String, many: String): String = "${digits(n)}$NBSP${plural(n, one, few, many)}"

    fun count(n: Int, one: String, few: String, many: String): String = count(n.toLong(), one, few, many)

    /** Число с разрядами через узкий пробел: 830 → «830», 1350 → «1 350», −8170 → «−8 170». */
    fun digits(n: Long): String {
        val plain = n.toString().removePrefix("-")
        val grouped = buildString(plain.length + plain.length / 3) {
            plain.forEachIndexed { i, c ->
                if (i > 0 && (plain.length - i) % 3 == 0) append(THIN)
                append(c)
            }
        }
        return if (n < 0) "$MINUS$grouped" else grouped
    }

    /**
     * Слово или фраза по полу клиента (10.6, 4.17): «был» / «была». Пол неизвестен — фраза без рода,
     * её передают третьей: «последняя вт 15.10», «Это тот же клиент?».
     */
    fun bySex(sex: Sex?, male: String, female: String, unknown: String): String = when (sex) {
        Sex.MALE -> male
        Sex.FEMALE -> female
        null -> unknown
    }

    /**
     * Имя в дательном падеже — «Вы отдаёте Лене» (4.8): склоняется первое слово, остальное как есть. Лена → Лене,
     * Мария → Марии, Илья → Илье, Иван → Ивану, Андрей → Андрею, Игорь → Игорю; на о, е, и, у, ю, ы, э — не
     * склоняется. Женские имена на «ь» (Любовь) склоняются неверно, как мужские, — редкий случай.
     */
    fun dative(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return trimmed
        val end = trimmed.indexOf(' ').let { if (it < 0) trimmed.length else it }
        val word = trimmed.substring(0, end)
        val lower = word.lowercase()
        val last = lower.last()
        val declined = when {
            word.length < 2 -> word
            lower.endsWith("ия") -> word.dropLast(1) + "и"
            last == 'а' || last == 'я' -> word.dropLast(1) + "е"
            last == 'й' || last == 'ь' -> word.dropLast(1) + "ю"
            last in "оеиуюыэё" -> word
            last in "бвгджзклмнпрстфхцчшщ" -> word + "у"
            else -> word
        }
        return declined + trimmed.substring(end)
    }

    /**
     * Имя коллеги в родительном падеже — «дети Лены» (шаг 5.3м): склоняется первое слово. Лена → Лены, Ольга → Ольги,
     * Аня → Ани, Мария → Марии, Иван → Ивана, Андрей → Андрея, Игорь → Игоря; на о, е, и, у, ю, ы, э — не склоняется.
     */
    fun genitive(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return trimmed
        val end = trimmed.indexOf(' ').let { if (it < 0) trimmed.length else it }
        val word = trimmed.substring(0, end)
        val lower = word.lowercase()
        val last = lower.last()
        val declined = when {
            word.length < 2 -> word
            last == 'а' && lower[lower.length - 2] in "гкхжшчщ" -> word.dropLast(1) + "и"
            last == 'а' -> word.dropLast(1) + "ы"
            last == 'я' -> word.dropLast(1) + "и"
            last == 'й' || last == 'ь' -> word.dropLast(1) + "я"
            last in "оеиуюыэё" -> word
            last in "бвгджзклмнпрстфхцчшщ" -> word + "а"
            else -> word
        }
        return declined + trimmed.substring(end)
    }
}
