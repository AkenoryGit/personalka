package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.sync.SyncProblem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Строки синхронизации: «Настройки → Данные», первый запуск и «Нужно сделать» (ТЗ 7.2, 1.5, 5.16, 5.18, 5.2; шаг 2.10а). */
object SyncFormat {
    private const val DOT = " · "

    const val SECTION = "Сервер"
    const val COPY_OFF = "Копия на сервере ещё не включена — данные есть только в этом телефоне."
    const val TURN_ON = "Включить копию"
    const val WORKING = "Минуту…"
    const val SERVER_HAS_DATA = "На сервере уже есть ваши данные — с другого телефона. Данные этого телефона заменятся ими."
    const val SERVER_HAS_DATA_NOTE =
        "Если данные этого телефона нужны — не входите и сохраните их копией в «Данных»: «Создать копию сейчас»."
    const val TAKE = "Взять данные с сервера"
    const val DONT_SIGN_IN = "Не входить"
    const val CONNECTED = "Копия на сервере включена"

    const val REPLACE = "Убрать и загрузить мои"
    const val GOT_IT = "Понятно"

    const val SIGN_OUT_SENT = "Все правки — на сервере."
    const val WIPE = "Убрать данные с этого телефона"
    const val WIPE_NOTE = "Понадобятся — войдите снова, данные придут с сервера."
    const val SIGN_OUT_DO = "Выйти"
    const val WIPED = "Вы вышли, данные с телефона убраны"

    const val SYNC_NOW = "Синхронизировать"
    const val SYNCING = "Синхронизация…"
    const val SYNCED = "Синхронизировано"
    const val NEVER = "Ещё не синхронизировано"

    const val DISCONNECT = "Удалить копию с сервера"
    const val DISCONNECT_TEXT =
        "Ваши данные на сервере удалятся, кабинеты клиентов перестанут открываться, аккаунт выйдет на всех телефонах. " +
            "В телефоне всё останется; войдёте снова — копия соберётся заново."
    const val DISCONNECT_CONFIRM = "Удалить"
    const val DISCONNECTED = "Копия на сервере удалена, вы вышли из аккаунта"

    const val FIRST_SIGN_IN = "Войти"
    const val FIRST_NOTE = "Войдите в аккаунт — данные придут с сервера, с другого вашего телефона."
    const val LOAD = "Загрузить данные"
    const val LOADING = "Загружаем данные…"
    const val FIRST_CLOSED = "У аккаунта не открыта копия на сервере. Начните на телефоне — копия включится, когда её откроют."
    const val FIRST_EMPTY = "На сервере пока нет ваших данных. Начните на телефоне — всё, что внесёте, уйдёт на сервер само."
    const val START_ON_PHONE = "Начать на телефоне"

    const val LATE = "Изменения не отправлены больше суток"

    /**
     * Вход в аккаунт на телефоне с данными другого (шаг 5.1а): все они на сервере — «Убрать данные … и загрузить
     * ваши?»; не все — стирать нельзя, сначала войти в тот аккаунт и выйти.
     */
    fun otherAccount(email: String, complete: Boolean): String =
        if (complete) {
            "На телефоне данные аккаунта $email — все они на сервере. Убрать их с телефона и загрузить ваши?"
        } else {
            "На телефоне данные аккаунта $email, и не все они на сервере. Убрать их нельзя: войдите в $email и выйдите — " +
                "правки уйдут на сервер, потом входите в свой."
        }

    /** «Выйти», когда не всё ушло: «Не ушло на сервер правок: 3. Данные останутся в телефоне, убрать их пока нельзя». */
    fun notSent(pending: Int): String {
        val head = if (pending > 0) "Не ушло на сервер правок:$NBSP${RuText.digits(pending.toLong())}." else "Сервер не ответил."
        return "$head Выйти можно — данные останутся в телефоне, убрать их пока нельзя."
    }

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
        SyncProblem.OTHER_ACCOUNT -> "Сервер подключён для другого аккаунта — обмен стоит, данные не смешиваются. Войдите в свой"
        SyncProblem.FAILED -> "Обмен с сервером не удался — данные в телефоне целы. Пришлите разработчику текст ошибки ниже"
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
