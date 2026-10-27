package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.account.Feature
import kotlinx.datetime.LocalDate
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
        Feature.HALF_OFFICIAL -> "Второй вариант оплаты Академии — выбор в «Зарплате»"
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
}
