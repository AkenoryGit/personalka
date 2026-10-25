package com.akenory.personalka.domain.backup

import com.akenory.personalka.domain.ClubTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

/** Как часто автокопия (5.16): [code] — значение в настройках телефона. */
enum class BackupFrequency(val code: String, val days: Int) {
    DAILY("day", 1),
    WEEKLY("week", 7),
    ;

    companion object {
        fun of(code: String?): BackupFrequency = entries.firstOrNull { it.code == code } ?: DAILY
    }
}

/**
 * Резервная копия на телефоне (ТЗ 7.6, 1.5): имя файла, сколько копий держать в папке, когда пора автокопии
 * и когда напомнить, что копии давно нет.
 */
object BackupRules {
    /** В папке остаются последние 10 копий. */
    const val KEEP = 10

    /** Копий перед обновлением базы в папке приложения остаётся 3 последних (2.7б). */
    const val KEEP_UPDATES = 3

    /** Кнопка копии перед обновлением видна 30 дней: если обновление что-то испортило, это заметят раньше. */
    const val UPDATE_COPY_DAYS = 30

    /** Напоминание «Нет копии больше 7 дней» (1.5, 5.2). */
    const val REMIND_DAYS = 7

    /** Пароль копии — не короче 6 знаков. */
    const val PASSWORD_MIN = 6

    private val NAME = Regex("""trener-backup-\d{8}-\d{4}\.zip""")

    /** `trener-backup-20261023-1540.zip` — по часам клуба. */
    fun fileName(now: LocalDateTime): String = "trener-backup-${stamp(now.date)}-${two(now.hour)}${two(now.minute)}.zip"

    /** Выгрузка в календарь: `trener-calendar-20261023.ics`. */
    fun calendarFileName(today: LocalDate): String = "trener-calendar-${stamp(today)}.ics"

    /** Выгрузка в Excel (шаг 3.8в): `trener-data-20261025.xlsx`. */
    fun excelFileName(today: LocalDate): String = "trener-data-${stamp(today)}.xlsx"

    fun isBackupName(name: String): Boolean = NAME.matches(name)

    /**
     * Какие копии в папке удалить, чтобы осталось [keep] последних. Чужие файлы не трогаются; время — в имени,
     * поэтому порядок имён и есть порядок копий.
     */
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter { isBackupName(it) }.distinct().sortedDescending().drop(keep)

    /** Пора ли автокопии: последней в папке нет или она старше дня (недели) по календарю клуба. */
    fun autoDue(last: LocalDateTime?, now: LocalDateTime, frequency: BackupFrequency): Boolean =
        last == null || now.date >= last.date.plus(frequency.days, DateTimeUnit.DAY)

    /** Напомнить на «Главной» (1.16): копии нет или последней больше 7 дней. */
    fun remind(last: LocalDateTime?, now: LocalDateTime): Boolean =
        last == null || ClubTime.minutesBetween(last, now) >= REMIND_DAYS * ClubTime.MINUTES_PER_DAY.toLong()

    /** Показывать ли в «Данных» копию перед обновлением базы, снятую в [at]: первые [UPDATE_COPY_DAYS] дней. */
    fun showUpdateCopy(at: LocalDateTime?, now: LocalDateTime): Boolean =
        at != null && ClubTime.minutesBetween(at, now) < UPDATE_COPY_DAYS * ClubTime.MINUTES_PER_DAY.toLong()

    /** Пароль годится: не короче [PASSWORD_MIN] и совпадает с повтором. */
    fun passwordOk(password: String, repeat: String): Boolean = password.length >= PASSWORD_MIN && password == repeat

    /** Из выгрузки в календарь (7.6) — прошлые 3 и будущие 3 месяца от [today]. */
    fun calendarRange(today: LocalDate): Pair<LocalDate, LocalDate> =
        today.plus(-3, DateTimeUnit.MONTH) to today.plus(3, DateTimeUnit.MONTH)

    private fun stamp(date: LocalDate): String = "${date.year}${two(date.month.ordinal + 1)}${two(date.day)}"

    private fun two(n: Int): String = n.toString().padStart(2, '0')
}
