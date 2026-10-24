package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.sync.SyncProblem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Строки синхронизации: «Настройки → Данные», первый запуск и «Нужно сделать» (ТЗ 7.2, 1.5, 5.16, 5.18, 5.2; шаг 2.10а). */
object SyncFormat {
    private const val DOT = " · "

    const val SECTION = "Сервер"
    const val CONNECT = "Подключить сервер"
    const val CONNECT_NOTE =
        "Данные этого телефона уйдут на сервер, дальше изменения сами появятся на других ваших телефонах. В телефоне всё остаётся."
    const val TERMS_NO_WARRANTY =
        "Сервер работает без гарантий: если он недоступен, приложение работает как обычно, данные остаются в телефоне."
    const val TERMS_CONSENTS = "Согласия родителей на обработку данных детей собираете вы — сервер хранит то, что есть в телефоне."
    const val AGREE = "Понимаю и согласен"
    const val DO_CONNECT = "Подключить"
    const val CHECKING = "Проверяем сервер…"
    const val SERVER_HAS_DATA = "На сервере уже есть ваши данные — с другого телефона. Данные этого телефона заменятся ими."
    const val SERVER_HAS_DATA_NOTE =
        "Чтобы на сервер ушли данные этого телефона, сначала нажмите «Отключиться от сервера» на том телефоне."
    const val TAKE = "Взять данные с сервера"
    const val CONNECTED = "Сервер подключён"

    const val SYNC_NOW = "Синхронизировать"
    const val SYNCING = "Синхронизация…"
    const val SYNCED = "Синхронизировано"
    const val NEVER = "Ещё не синхронизировано"

    const val DISCONNECT = "Отключиться от сервера"
    const val DISCONNECT_TEXT =
        "Кабинеты ваших клиентов перестанут открываться, ваши данные на сервере удалятся. В телефоне всё останется."
    const val DISCONNECT_CONFIRM = "Отключиться"
    const val DISCONNECTED = "Сервер отключён, данные на сервере удалены"

    const val FIRST_SIGN_IN = "Войти"
    const val FIRST_NOTE = "Войдите в аккаунт — данные придут с сервера, с другого вашего телефона."
    const val LOAD = "Загрузить данные"
    const val LOADING = "Загружаем данные…"
    const val FIRST_CLOSED = "У аккаунта не открыта копия на сервере. Начните на телефоне — сервер можно подключить потом."
    const val FIRST_EMPTY = "На сервере пока нет ваших данных. Начните на телефоне — всё, что внесёте, уйдёт на сервер само."
    const val START_ON_PHONE = "Начать на телефоне"

    const val LATE = "Изменения не отправлены больше суток"

    /** Полоса первой выгрузки: «Выгружено 120 из 1 200». */
    fun progress(sent: Int, total: Int): String = "Выгружено ${RuText.digits(sent.toLong())} из ${RuText.digits(total.toLong())}"

    /**
     * Строка состояния в «Данных»: «Синхронизировано сегодня в 14:05 · ждут отправки 3»; обмена ещё не было —
     * «Ещё не синхронизировано».
     */
    fun status(last: LocalDateTime?, pending: Int, today: LocalDate): String {
        val main = if (last == null) NEVER else "$SYNCED ${moment(last, today)}"
        return if (pending > 0) main + DOT + "ждут отправки$NBSP${RuText.digits(pending.toLong())}" else main
    }

    /** Почему обмен не идёт — строкой под состоянием. */
    fun problem(problem: SyncProblem): String = when (problem) {
        SyncProblem.OFFLINE -> "Нет связи с сервером — обмен повторится сам"
        SyncProblem.SERVER_OUTDATED -> "Сервер обновляется — обмен повторится сам"
        SyncProblem.UPDATE_REQUIRED -> "Обновите приложение — до тех пор сервер не принимает изменения. В телефоне всё работает"
        SyncProblem.CLOSED -> "Копия на сервере не открыта у аккаунта — данные в телефоне целы"
        SyncProblem.SUSPENDED -> "Доступ к серверу приостановлен — данные в телефоне целы"
        SyncProblem.SIGNED_OUT -> "Вход устарел — войдите в «Аккаунте» заново"
    }

    /** Подпись пункта «Нужно сделать»: «последняя синхронизация — 22.10.2026» или «синхронизации ещё не было». */
    fun lateNote(last: LocalDateTime?): String =
        if (last == null) "синхронизации ещё не было" else "последняя синхронизация — " + DateFormat.full(last.date)

    /** «сегодня в 14:05», «вчера в 14:05», «вт 22.10 в 14:05». */
    private fun moment(time: LocalDateTime, today: LocalDate): String {
        val day = when {
            time.date == today -> "сегодня"
            time.date.toEpochDays() == today.toEpochDays() - 1 -> "вчера"
            else -> DateFormat.short(time.date)
        }
        return "$day в ${DateFormat.time(time.time)}"
    }
}
