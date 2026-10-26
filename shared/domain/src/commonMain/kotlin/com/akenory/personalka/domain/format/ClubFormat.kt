package com.akenory.personalka.domain.format

import kotlinx.datetime.LocalDate

/** Строки «Ещё → Клуб» у администратора (ТЗ 5.17, 1.5; шаг 5.2): тренеры сервера и приостановка доступа. */
object ClubFormat {
    private const val DOT = " · "

    const val TITLE = "Клуб"
    const val COACHES = "Тренеры"
    const val YOU = "вы"
    const val LOADING = "Загружаю список…"
    const val EMPTY = "Коллег пока нет. Коллега сам ставит «Персоналку» и регистрируется — тогда он появится здесь."
    const val PRIVATE = "Здесь только аккаунты: клиентов и тренировок коллег вы не видите."

    const val SUSPEND = "Приостановить"
    const val RESUME = "Возобновить"
    const val SUSPEND_NOTE =
        "Синхронизация остановится, кабинеты его клиентов закроются, войти с нового телефона не выйдет. " +
            "Данные в телефоне тренера и на сервере останутся."
    const val RESUME_NOTE = "Синхронизация и кабинеты заработают снова."
    const val SUSPENDED_DONE = "Доступ приостановлен"
    const val RESUMED_DONE = "Доступ возобновлён"

    /** Приостановленный аккаунт видит это в «Аккаунте». */
    const val YOU_SUSPENDED = "Доступ к серверу приостановлен администратором — данные в телефоне целы"

    /** Имя в списке: своя строка — «Дмитрий · вы». */
    fun name(name: String, self: Boolean): String = if (self) name + DOT + YOU else name

    /**
     * Строка под почтой: «Приостановлен», «Почта не подтверждена» или последний обмен — «Обмен сегодня»,
     * «Обмен вт 22.10», «Обмен 22.10.2025», «Обмена не было»; открытые кабинеты — «· 2 кабинета».
     */
    fun status(confirmed: Boolean, suspended: Boolean, synced: LocalDate?, cabinets: Int, today: LocalDate): String {
        val state = when {
            suspended -> "Доступ приостановлен"
            !confirmed -> "Почта не подтверждена"
            synced == null -> "Обмена не было"
            synced == today -> "Обмен сегодня"
            synced.year == today.year -> "Обмен " + DateFormat.short(synced)
            else -> "Обмен " + DateFormat.full(synced)
        }
        return if (cabinets > 0) state + DOT + RuText.count(cabinets, "кабинет", "кабинета", "кабинетов") else state
    }
}
