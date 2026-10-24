package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.LocalDate

/** Строки аккаунта в «Настройки → Данные» (ТЗ 7.3, 5.16, 1.5; шаг 2.8а). */
object AccountFormat {
    private const val DOT = " · "

    const val TITLE = "Аккаунт"
    const val SIGNED_OUT = "Не выполнен вход"
    const val WHY =
        "Аккаунт нужен для копии на сервере, второго телефона и кабинетов родителей. Без него всё остальное работает как раньше."

    const val SIGN_IN = "Вход"
    const val REGISTER = "Регистрация"
    const val NAME = "Имя"
    const val NAME_HINT = "Так вас увидят родители в кабинете"
    const val EMAIL = "Почта"
    const val PASSWORD = "Пароль"
    const val PASSWORD_HINT = "Не короче 10 знаков"
    const val NEW_PASSWORD = "Новый пароль"
    const val CODE = "Код из письма"
    const val DO_SIGN_IN = "Войти"
    const val DO_REGISTER = "Зарегистрироваться"
    const val FORGOT = "Забыли пароль?"
    const val RESET_TITLE = "Новый пароль"
    const val RESET_NOTE = "Пришлём на почту код — с ним можно задать новый пароль."
    const val SEND_CODE = "Получить код"
    const val CONFIRM = "Подтвердить"
    const val SAVE_PASSWORD = "Сохранить пароль"
    const val RESEND = "Отправить код ещё раз"
    const val BACK_TO_SIGN_IN = "Вернуться ко входу"
    const val WORKING = "Минуту…"

    const val DEVICES = "Устройства"
    const val THIS_DEVICE = "это устройство"
    const val SIGN_OUT_DEVICE = "Выйти на этом устройстве"
    const val SIGN_OUT = "Выйти"
    const val SIGN_OUT_NOTE = "Данные в телефоне останутся — выход их не трогает."
    const val SIGNED_OUT_DEVICE = "Устройство вышло из аккаунта"
    const val SIGNED_OUT_HERE = "Вы вышли из аккаунта"
    const val PASSWORD_SAVED = "Пароль сменён, другие устройства вышли"

    const val NAME_EMPTY = "Напишите имя"
    const val EMAIL_WRONG = "Проверьте адрес почты"
    const val PASSWORD_EMPTY = "Напишите пароль"
    const val OFFLINE = "Нет связи с сервером — проверьте интернет"
    const val EXPIRED = "Вход устарел — войдите заново"
    const val SERVER_ERROR = "Сервер ответил ошибкой — попробуйте позже"

    /** Строка «Аккаунт» в «Данных»: «Дмитрий · coach@example.ru» или «Не выполнен вход». */
    fun summary(name: String?, email: String?): String =
        if (email == null) SIGNED_OUT else listOfNotNull(name?.takeIf { it.isNotBlank() }, email).joinToString(DOT)

    /** «Код ушёл на coach@example.ru — действует 15 минут. Нет письма — загляните в «Спам»». */
    fun codeSent(email: String, minutes: Int): String =
        "Код ушёл на $email — действует ${RuText.count(minutes, "минуту", "минуты", "минут")}. Нет письма — загляните в «Спам»."

    /** Строка устройства: «Galaxy Z Fold8 · это устройство», «Pixel 8 · был вт 22.10», сегодня — «был сегодня». */
    fun device(name: String, current: Boolean, lastUsed: LocalDate, today: LocalDate): String = when {
        current -> name + DOT + THIS_DEVICE
        lastUsed == today -> name + DOT + "был сегодня"
        lastUsed.year == today.year -> name + DOT + "был " + DateFormat.short(lastUsed)
        else -> name + DOT + "был " + DateFormat.full(lastUsed)
    }

    /**
     * Что открыто у аккаунта: «Открыто: Копия на сервере — до 31.12.2026». Ничего не открыто — `null`, строки нет
     * (закрытое не показывается, 10.7). Срок кончается в ближайшие 7 дней — «… — до 31.10.2026, скоро конец».
     */
    fun open(features: Map<Feature, LocalDate?>, ending: Set<Feature>): String? {
        if (features.isEmpty()) return null
        val parts = features.map { (feature, until) ->
            when {
                until == null -> feature.title
                feature in ending -> "${feature.title} — до${NBSP}${DateFormat.full(until)}, скоро конец"
                else -> "${feature.title} — до${NBSP}${DateFormat.full(until)}"
            }
        }
        return "Открыто: " + parts.joinToString(", ")
    }
}
