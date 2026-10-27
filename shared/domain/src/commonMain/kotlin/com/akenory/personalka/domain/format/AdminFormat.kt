package com.akenory.personalka.domain.format

import kotlinx.datetime.LocalDateTime

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

    const val SIGNED_IN = "Вход в админку"
    const val SIGNED_OUT = "Выход из админки"
    const val SUSPENDED = "приостановлен"
    const val RESUMED = "возобновлён"

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
}
