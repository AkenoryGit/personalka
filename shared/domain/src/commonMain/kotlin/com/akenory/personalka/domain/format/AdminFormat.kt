package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus

/**
 * Строки админки на сайте сервера (решение Дмитрия 27.10.2026, шаг 5.2б; ТЗ 5.17, 1.5): вход почтой, паролем и кодом
 * из письма, список тренеров с приостановкой, журнал. Статус тренера — [ClubFormat.status], как было в приложении.
 */
object AdminFormat {
    private const val DOT = " · "

    const val TITLE = "Персоналка · админка"
    const val SIGN_IN = "Вход в админку"
    const val SIGN_IN_NOTE = "Только для администратора. После пароля придёт код на почту."
    const val NEXT = "Дальше"
    const val CODE_TITLE = "Код из письма"
    const val ENTER = "Войти"
    const val AGAIN = "Войти заново"
    const val SIGN_OUT = "Выйти"
    const val COACHES = "Тренеры"
    const val LOG = "Журнал"
    const val LOG_EMPTY = "Пока пусто"

    const val WRONG = "Неверная почта или пароль"
    const val CODE_EXPIRED = "Код устарел или попытки кончились — войдите заново"
    const val NO_MAIL = "Письма с сервера не отправляются — без кода войти нельзя"
    const val MAIL_FAILED = "Не удалось отправить письмо — попробуйте позже"
    const val FORM_EXPIRED = "Страница устарела — обновите её и повторите"

    const val ALL_COACHES = "Все тренеры"
    const val FEATURES = "Возможности"
    const val UNTIL = "Открыто до"
    const val UNTIL_HINT = "Пусто — без срока. За 7 дней до конца тренер увидит напоминание; по окончании данные не удаляются."
    const val SAVE = "Сохранить"
    const val DEFAULTS = "Новым тренерам по умолчанию"
    const val DEFAULTS_NOTE = "Зарплата «Вся сумма» есть у всех всегда. Уже зарегистрированных это не меняет."
    const val NOTHING_OPEN = "Ничего не открыто"
    const val APPLIES = "Телефон тренера увидит изменения при следующем обмене с сервером. Без сети всё работает, как раньше."
    const val BAD_DATE = "Проверьте дату — например, 31.12.2026"
    const val NO_COACH = "Такого тренера на сервере нет"

    const val SIGNED_IN = "Вход в админку"
    const val SIGNED_OUT = "Выход из админки"
    const val SUSPENDED = "приостановлен"
    const val RESUMED = "возобновлён"
    const val CLOSED_ALL = "всё закрыто"

    /** Пояснение под возможностью на странице тренера. */
    fun note(feature: Feature): String = when (feature) {
        Feature.SERVER_COPY -> "Синхронизация, второй телефон, восстановление"
        Feature.CABINETS -> "Ссылки для родителей; открывают и копию на сервере"
        Feature.HALF_OFFICIAL -> "Виды оплаты: выбор «с налогом 6,5\u00A0%» у себя и у коллег. Выключено — о них ни слова, всё 100\u00A0% 10-го"
    }

    /**
     * Что открыто — для журнала и строки тренера: «Копия на сервере, Кабинеты родителей до 31.12.2026»;
     * ничего — [CLOSED_ALL].
     */
    fun features(open: Map<Feature, LocalDate?>): String =
        if (open.isEmpty()) {
            CLOSED_ALL
        } else {
            open.entries.joinToString(", ") { (f, until) -> if (until == null) f.title else f.title + " до " + DateFormat.full(until) }
        }

    /** Журнал: «Новым тренерам: Копия на сервере». */
    fun defaults(features: Set<Feature>): String = "Новым тренерам: " + features(features.associateWith { null })

    /** «Отправили код на d…@gmail.com — действует 15 минут». Почта скрыта: страницу могут увидеть через плечо. */
    fun codeSent(email: String, minutes: Int): String =
        "Отправили код на ${hide(email)} — действует ${RuText.count(minutes, "минуту", "минуты", "минут")}"

    /** «d…@gmail.com»: первая буква и домен. */
    fun hide(email: String): String {
        val at = email.indexOf('@')
        return if (at <= 0) email else email.first() + "…" + email.substring(at)
    }

    /** «Тренеры · 4». */
    fun coaches(count: Int): String = COACHES + DOT + count

    /** Строка журнала: «27.10 09:14 · Елена Соколова · приостановлен»; прошлый год — «22.10.2025 09:14 · …». */
    fun log(at: LocalDateTime, thisYear: Int, parts: List<String>): String {
        val day = if (at.year == thisYear) DateFormat.dayMonth(at.date) else DateFormat.full(at.date)
        return (listOf(day + " " + DateFormat.time(at.time)) + parts.filter { it.isNotBlank() }).joinToString(DOT)
    }

    /** Копия старше этого — строка «Сервер» красная (ночная копия идёт раз в сутки, запас на сбой одной ночи). */
    const val BACKUP_STALE_HOURS = 36L

    /** Диск занят на столько процентов и больше — строка «Сервер» красная. */
    const val DISK_ALARM_PERCENT = 85L

    /**
     * Строка «Сервер» над тренерами (шаг 5.3, ТЗ 5.17): «Сервер 0.1.377 · диск 7,4 из 19,6 ГБ (38 %) · копия базы
     * сегодня 03:30». Копия с ошибкой — «копия базы не сделана 28.10 03:31 — шифрование», старше суток с половиной —
     * «… — новой нет больше суток», не было ни одной — «копий базы ещё не было».
     */
    fun server(s: ServerStatus, now: LocalDateTime): String {
        val parts = mutableListOf("Сервер ${s.version}")
        if (s.diskTotal > 0) parts += "диск ${gb(s.diskUsed)} из ${gb(s.diskTotal)}${NBSP}ГБ (${diskPercent(s)}${NBSP}%)"
        val b = s.backup
        parts += when {
            b == null -> "копий базы ещё не было"
            !b.ok -> "копия базы не сделана ${moment(b.at, now)}" + (b.detail.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
            stale(b, now) -> "копия базы ${moment(b.at, now)} — новой нет больше суток"
            else -> "копия базы ${moment(b.at, now)}"
        }
        return parts.joinToString(DOT)
    }

    /** Красная ли строка «Сервер»: копии нет, она с ошибкой или старая, диск занят на [DISK_ALARM_PERCENT] %. */
    fun serverAlarm(s: ServerStatus, now: LocalDateTime): Boolean {
        val b = s.backup
        return b == null || !b.ok || stale(b, now) || (s.diskTotal > 0 && diskPercent(s) >= DISK_ALARM_PERCENT)
    }

    private fun stale(b: BackupMark, now: LocalDateTime): Boolean =
        ClubTime.minutesBetween(b.at, now) >= BACKUP_STALE_HOURS * 60

    private fun diskPercent(s: ServerStatus): Long = (s.diskUsed * 100 + s.diskTotal / 2) / s.diskTotal

    /** Гигабайты (1024³) с одним знаком: «7,4». */
    private fun gb(bytes: Long): String {
        val tenths = (bytes * 10 + GB / 2) / GB
        return "${tenths / 10},${tenths % 10}"
    }

    /** «сегодня 03:30», «вчера 03:30», «26.10 03:30», прошлый год — «30.12.2025 03:30». */
    private fun moment(at: LocalDateTime, now: LocalDateTime): String {
        val day = when (at.date) {
            now.date -> "сегодня"
            now.date.minus(DatePeriod(days = 1)) -> "вчера"
            else -> if (at.year == now.year) DateFormat.dayMonth(at.date) else DateFormat.full(at.date)
        }
        return day + " " + DateFormat.time(at.time)
    }

    private const val GB = 1024L * 1024 * 1024
}

/** Что показывает строка «Сервер» в админке: [backup] — последняя ночная копия базы из `backup-status` (ТЗ 7.5). */
data class ServerStatus(val version: String, val diskUsed: Long, val diskTotal: Long, val backup: BackupMark?)

/** Отметка ночной копии: время по клубу, удалась ли, подробности (у ошибки — этап, на котором сорвалась). */
data class BackupMark(val at: LocalDateTime, val ok: Boolean, val detail: String)
