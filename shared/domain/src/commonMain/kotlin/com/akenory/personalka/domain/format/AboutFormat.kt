package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.about.AppTheme
import com.akenory.personalka.domain.about.CrashLogRules

/** «Ещё → О приложении» (ТЗ 5.16, шаг 5.33): тема, версия, «Проверить обновление», журнал ошибок. */
object AboutFormat {
    const val TITLE = "О приложении"

    /** Пояснение пункта «Ещё» — подпись для экранного диктора. */
    const val NOTE = "Тема, версия, обновление"

    const val THEME = "Тема"

    fun theme(theme: AppTheme): String = when (theme) {
        AppTheme.SYSTEM -> "Как в телефоне"
        AppTheme.LIGHT -> "Светлая"
        AppTheme.DARK -> "Тёмная"
    }

    const val VERSION = "Версия"

    const val CHECK = "Проверить обновление"
    const val CHECKING = "Проверяю…"
    const val FRESH = "У вас последняя версия"
    const val FAILED = "Не удалось проверить — нет связи с интернетом"

    fun available(build: Int): String = "Есть новая версия 0.1.$build"

    const val DOWNLOAD = "Скачать"
    const val DOWNLOAD_NOTE = "Файл скачается в браузере — откройте его, чтобы установить. Данные сохранятся."

    const val CRASHES = "Журнал ошибок"
    const val SEND = "Отправить разработчику"
    const val CLEAR = "Очистить"
    const val CLEARED = "Журнал ошибок очищен"

    /** «2 ошибки · последняя в версии 0.1.567»; без версии — только число. */
    fun crashes(log: String?): String {
        val entries = CrashLogRules.entries(log)
        val count = RuText.count(entries.size, "ошибка", "ошибки", "ошибок")
        val version = entries.lastOrNull()?.let(CrashLogRules::versionOf) ?: return count
        return "$count · последняя в версии $version"
    }

    /** Текст для «Отправить разработчику»: заголовок и записи журнала. */
    fun crashReport(appVersion: String, log: String): String = "Персоналка $appVersion — журнал ошибок\n\n$log"
}
