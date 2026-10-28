package com.akenory.personalka.domain.format

/**
 * Меню «Ещё» (ТЗ 5.1, 5.16, шаг 3.15а): один экран, три группы, под каждым пунктом — строка о том, что внутри.
 * Пишется для тренера, который открыл приложение впервые.
 */
object MoreFormat {
    const val TITLE = "Ещё"

    const val WORK = "Работа"
    const val SETUP = "Как я работаю"
    const val DATA = "Данные"

    const val GROUPS = "Группы"
    const val GROUPS_NOTE = "Кто в какой группе и кто перестал ходить"
    const val SLOTS = "Свободные слоты"
    const val SLOTS_NOTE = "Свободное время, чтобы предложить клиенту"
    const val RECONCILE = "Сверки"
    const val RECONCILE_NOTE = "Проверить месяц: зарплата, дежурства, отчёт 1С"
    const val VACATION = "Отпуск"
    const val VACATION_NOTE = "Снять тренировки на дни отпуска и предупредить клиентов"

    const val PROFILE = "Профиль"
    const val PROFILE_NOTE = "ФИО, категория тренера, совместное ведение групп"
    const val PLACES = "Места и типы"
    const val PLACES_NOTE = "Бассейны и залы, виды тренировок и их цвета"
    const val RATES = "Ставки и ступени"
    const val RATES_NOTE = "Сколько платят за каждый тип тренировки"
    const val COLLEAGUES = "Коллеги"
    const val COLLEAGUES_NOTE = "С кем ведёте группы вместе"
    const val CALENDAR = "Календарь"
    const val CALENDAR_NOTE = "Часы работы, расписание вперёд, отмены, запреты"
    const val REMINDERS = "Дневник и напоминания"
    const val REMINDERS_NOTE = "Дневник, вечерняя сводка и другие уведомления"
    const val CABINETS = "Кабинеты родителей"
    const val CABINETS_NOTE = "Ссылки для родителей и что им видно"

    const val ACCOUNT = "Аккаунт"
    const val ACCOUNT_NOTE = "Вход и копия на сервере"
    const val BACKUP = "Резервная копия"
    const val BACKUP_NOTE = "Файл со всеми данными в папке телефона, восстановление"
    const val EXPORTS = "Выгрузки"
    const val EXPORTS_NOTE = "Excel и календарь, чтобы поделиться или посмотреть"

    fun version(name: String): String = "Версия $name"
}
