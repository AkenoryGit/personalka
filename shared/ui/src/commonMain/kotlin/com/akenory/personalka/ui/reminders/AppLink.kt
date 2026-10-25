package com.akenory.personalka.ui.reminders

/**
 * Куда ведёт нажатие на уведомление (ТЗ 5.19). Платформа кладёт [code] в уведомление и при нажатии отдаёт ссылку
 * приложению ([com.akenory.personalka.ui.App]).
 */
sealed interface AppLink {
    val code: String

    /** «Главная» с блоком «Нужно сделать»: сводка и «План на день». */
    data object Home : AppLink {
        override val code = "home"
    }

    /** «Дневник» в сводке: первая тренировка без дневника на момент нажатия; её нет — «Главная». */
    data object FirstDiary : AppLink {
        override val code = "diary"
    }

    /** Дневник тренировки [id]: «Дневник сразу». */
    data class Diary(val id: String) : AppLink {
        override val code = "diary/$id"
    }

    /** Карточка тренировки [id] на «Главной»: «Группа сразу» — отметить детей. */
    data class Card(val id: String) : AppLink {
        override val code = "card/$id"
    }

    /** «Зарплата» → «Выплаты» с открытым окном аванса этого месяца: уведомление «Аванс» (шаг 3.9). */
    data object Advance : AppLink {
        override val code = "advance"
    }

    companion object {
        fun parse(code: String?): AppLink? = when {
            code == null -> null
            code == Home.code -> Home
            code == FirstDiary.code -> FirstDiary
            code == Advance.code -> Advance
            code.startsWith("diary/") -> Diary(code.removePrefix("diary/"))
            code.startsWith("card/") -> Card(code.removePrefix("card/"))
            else -> null
        }
    }
}

/**
 * Уведомления на платформе (на Android — `NotificationManager` и будильник). В тестах её нет — строки
 * «Уведомления выключены» в настройках тоже нет.
 */
interface NotificationAccess {
    /** Можно ли приложению показывать уведомления: разрешение Android 13+ и выключатель в настройках телефона. */
    fun enabled(): Boolean

    /** «Включить»: спросить разрешение или открыть настройки уведомлений приложения в телефоне. */
    fun enable()

    /** Настройки напоминаний изменились — переставить будильник. */
    fun changed()
}
