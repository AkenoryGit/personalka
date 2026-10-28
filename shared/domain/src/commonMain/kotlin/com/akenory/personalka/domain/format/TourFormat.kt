package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.tour.TourStop

/**
 * Тексты знакомства с приложением (ТЗ 5.18, шаг 5.3б, макет `docs/mockups/tour`). Пишутся для тренера,
 * который открыл приложение впервые: без слов «серия», «синхронизация», «облако».
 */
object TourFormat {
    fun step(stop: TourStop): String = "Знакомство · ${stop.number} из ${TourStop.COUNT}"

    const val NEXT = "Дальше"
    const val SKIP = "Пропустить"
    const val LATER = "Позже"
    const val SETUP = "Настроить"
    const val PICK_FOLDER = "Выбрать папку"

    const val CALENDAR_TITLE = "Добавьте тренировку"
    const val CALENDAR_TEXT = "Удерживайте пустое место в календаре. Внесите настоящую тренировку, например завтрашнюю. " +
        "Клиента можно завести прямо в форме — впишите фамилию."
    const val CALENDAR_GESTURE = "Удерживайте выделенное место или нажмите «+»"

    const val CARD_TITLE = "Проведено — само"
    const val CARD_TEXT = "В конце тренировки она отметится проведённой, нажимать ничего не нужно."

    /** Правило списания словами (4.2): [minutes] — из настройки тренера, «1 час», «1 час 30 минут». */
    fun cardCancel(minutes: Int): String =
        "Клиент отменил — «Отменить». Списание считается само: предупредили меньше чем за " +
            "${DurationFormat.long(minutes.toLong())} — со списанием, раньше — без."

    const val CLIENTS_TITLE = "Все клиенты здесь"
    const val CLIENTS_TEXT = "Клиент из тренировки уже в списке. Откройте его карточку."
    const val CLIENTS_GESTURE = "Нажмите на клиента"
    const val CLIENTS_OPEN_TEXT = "В карточке — тренировки, расписание, результаты и кабинет для родителей."
    const val CLIENTS_EMPTY_TITLE = "Добавьте клиента"
    const val CLIENTS_EMPTY_TEXT = "Фамилия и имя — остальное по желанию. Карточка клиента появится в этом списке."
    const val CLIENTS_EMPTY_GESTURE = "Нажмите «Добавить клиента»"

    const val HOME_TITLE = "Главная — с неё начинается день"
    const val HOME_TEXT = "«Нужно сделать» — что ждёт вас: дневник, кого записать, кто ждёт время. Нажмите на пункт — " +
        "там пояснение и список. «Деньги» — заработок за месяц."

    const val SALARY_TITLE = "Настройте зарплату"
    const val SALARY_TEXT = "День зарплаты и ставки. Можно взять ставки-пример и поправить по расчётному листку — около минуты."

    const val BACKUP_TITLE = "Сохраните данные"
    const val BACKUP_TEXT = "Всё хранится только в этом телефоне. Выберите папку — копия будет ложиться в неё сама. " +
        "Есть аккаунт — включите и копию на сервере: «Ещё → Аккаунт»."

    const val NO_BACKUP_TITLE = "Без копии?"
    const val NO_BACKUP_TEXT = "Если телефон потеряется или сломается, клиенты, расписание и дневник пропадут насовсем."
    const val NO_BACKUP_NOTE = "Выбрать папку можно потом — пункт останется в «Нужно сделать»."
    const val NO_BACKUP = "Без копии"

    const val DONE = "Готово. Повторить — «Ещё → Знакомство с приложением»."

    const val HELP = "Помощь"
    const val MORE_ITEM = "Знакомство с приложением"
    const val MORE_NOTE = "Шесть шагов, около двух минут"
}
