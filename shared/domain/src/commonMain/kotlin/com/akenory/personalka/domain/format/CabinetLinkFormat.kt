package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ClientName
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Строки кабинета в приложении: окно «Кабинет» в карточке и раздел «Кабинеты» в настройках (ТЗ 5.9, 5.16; шаг 2.13). */
object CabinetLinkFormat {
    const val BUTTON = "Кабинет"

    /** Заголовок окна: «Кабинет · Орлова К.». */
    fun title(lastName: String, firstName: String): String =
        "$BUTTON · $lastName" + (firstName.firstOrNull()?.let { " $it." } ?: "")

    const val OPEN = "Кабинет открыт"
    const val OPEN_NOTE = "Родитель видит расписание и итоги тренировок"
    const val OPEN_NOTE_ADULT = "Клиент видит своё расписание и итоги тренировок"
    const val LINK = "Ссылка"
    const val SEND = "Отправить"
    const val COPY = "Скопировать"
    const val CODE = "Код доступа"
    const val CODE_OFF = "Без кода — ссылка открывается сразу"
    const val ISSUE = "Выпустить ссылку"
    const val NEW_LINK = "Выпустить новую ссылку"
    const val NEW_LINK_NOTE = "Старая перестанет открываться"
    const val NEW_LINK_DONE = "Новая ссылка готова, старая больше не открывается"
    const val NEVER_OPENED = "Ещё не открывали"
    const val LOADING = "Связываемся с сервером…"
    const val OFFLINE = "Нет связи с сервером — код и новую ссылку можно поменять, когда связь появится"
    const val OTHER_PHONE = "Ссылка выпущена на другом телефоне. Чтобы отправить её отсюда, выпустите новую — старая перестанет открываться."
    const val NO_LINK = "Ссылки пока нет"
    const val CLOSED_NOTE = "Кабинет закрыт: ссылка не открывается, пока его не откроете снова"

    /** «Скопировать» кладёт тот же текст, что «Отправить»: с кодом — и его. */
    fun copied(withPin: Boolean): String = if (withPin) "Ссылка и код скопированы" else "Ссылка скопирована"

    /** «4719 · спрашивается при входе». */
    fun codeOn(pin: String?): String = if (pin == null) "Спрашивается при входе" else "$pin · спрашивается при входе"

    /** «Последний раз открывали: вчера, 21:14»; не открывали — «Ещё не открывали». */
    fun lastOpened(time: LocalDateTime?, today: LocalDate): String {
        if (time == null) return NEVER_OPENED
        val day = when {
            time.date == today -> "сегодня"
            time.date.toEpochDays() == today.toEpochDays() - 1 -> "вчера"
            time.date.year == today.year -> DateFormat.short(time.date)
            else -> DateFormat.full(time.date)
        }
        return "Последний раз открывали: $day, ${DateFormat.time(time.time)}"
    }

    // --- «Как видит родитель» (6.4, шаг 2.13б) ---

    /** Кнопка и заголовок предпросмотра; у взрослого кабинет видит сам клиент. */
    fun preview(adult: Boolean): String = if (adult) "Как видит клиент" else "Как видит родитель"

    const val PREVIEW_NOTE = "Тот же кабинет — время «Последний раз открывали» не меняется"
    const val PREVIEW_LOADING = "Отправляем изменения…"
    const val NEED_INTERNET = "Нужен интернет"

    /** «Родитель пока не видит 3 последних изменения» — часть правок не ушла на сервер. */
    fun notSeen(count: Int, adult: Boolean): String =
        (if (adult) "Клиент" else "Родитель") + " пока не видит " +
            RuText.count(count, "последнее изменение", "последних изменения", "последних изменений")

    // --- Текст «Отправить» (5.9) ---

    const val SHARE_CHILD = "Здравствуйте! Здесь расписание и итоги тренировок"
    const val SHARE_ADULT = "Здравствуйте! Здесь расписание и итоги ваших тренировок"

    fun sharePin(pin: String): String = "Код для входа: $pin"

    // --- Согласие (6.1) ---

    /** «Согласие получено 05.09, отмечено в кабинете»; без фото — «… · без фото и видео». */
    fun consentGiven(day: LocalDate, photo: Boolean): String =
        "Согласие получено ${DateFormat.dayMonth(day)}, отмечено в кабинете" + if (photo) "" else " · без фото и видео"

    fun consentNone(child: Boolean): String =
        if (child) "Согласия пока нет — родитель отметит его при первом открытии" else "Согласия пока нет — клиент отметит его при первом открытии"

    /** «Согласие отозвано 24.10 — удалите данные клиента до 23.11.2026». */
    fun consentRevoked(day: LocalDate, deleteBy: LocalDate): String =
        "Согласие отозвано ${DateFormat.dayMonth(day)} — удалите данные клиента до ${DateFormat.full(deleteBy)}"

    // --- Согласие в «Инфо» и «Нужно сделать» (6.1, 5.2; шаг 2.13а) ---

    /** Пометка у ссылки «Фото и видео» в «Инфо». */
    const val NO_PHOTO = "Нет согласия на фото"

    /** «Удалить данные клиента»; нескольких — «Удалить данные клиентов». */
    fun deleteData(count: Int): String = if (count == 1) "Удалить данные клиента" else "Удалить данные клиентов"

    /** Подпись пункта: «Орлова К. · осталось 12 дней»; срок — ближайший. */
    fun deleteNote(names: List<ClientName>, deleteBy: LocalDate, today: LocalDate): String =
        FollowupFormat.todoNote(names) + " · " + left(deleteBy, today)

    /** «остался 1 день», «осталось 12 дней», «сегодня последний день», «срок прошёл 23.11». */
    fun left(deleteBy: LocalDate, today: LocalDate): String {
        val days = (deleteBy.toEpochDays() - today.toEpochDays()).toLong()
        return when {
            days < 0 -> "срок прошёл ${DateFormat.dayMonth(deleteBy)}"
            days == 0L -> "сегодня последний день"
            else -> RuText.plural(days, "остался", "осталось", "осталось") + " " + RuText.count(days, "день", "дня", "дней")
        }
    }

    // --- Настройки «Кабинеты» (5.16) ---

    const val SETTINGS_TITLE = "Кабинеты"
    const val SETTINGS_SWITCH = "Кабинеты родителей"
    const val SETTINGS_SWITCH_NOTE = "В карточке клиента появится «Кабинет» — ссылка для родителя"
    const val CONTACT = "Контакт для родителей"
    const val CONTACT_HINT = "Телефон или почта"
    const val CONTACT_NOTE = "Попадёт в согласие и политику обработки данных: по нему родители обращаются к вам"
    const val CONTACT_NEEDED = "Без контакта кабинеты не включаются"
    const val OFF_NOTE = "Выключены — ни одна ссылка не открывается. Ссылки сохраняются: включите снова, и они заработают."
    const val ON = "Включены"
    const val OFF = "Выключены"
}
