package com.akenory.personalka.domain.about

/**
 * Тема приложения (ТЗ 5.16 «О приложении», 10.5; шаг 5.33): как в телефоне (по умолчанию), всегда светлая
 * или всегда тёмная. Хранится в настройках этого телефона ([code]), в копию и на сервер не попадает.
 */
enum class AppTheme(val code: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    /** Тёмная ли тема сейчас; [systemDark] — тёмная ли тема телефона. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** Сохранённый выбор; нет его или он незнакомый — как в телефоне. */
        fun of(code: String?): AppTheme = entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}

/** Итог «Проверить обновление» (шаг 5.33). */
sealed interface UpdateCheck {
    /** Стоит последняя сборка. */
    data object Fresh : UpdateCheck

    /** Есть сборка новее — [build], её версия «0.1.[build]». */
    data class Available(val build: Int) : UpdateCheck

    /** Не удалось узнать: нет связи или ответ без номера сборки. */
    data object Failed : UpdateCheck
}

/**
 * Обновление Android без сервера и магазина (ТЗ 7.7, решение шага 5.33): последняя сборка из `main` лежит в релизе
 * «Последняя сборка» на GitHub (репозиторий открытый). Приложение читает описание релиза — «Сборка 567 (версия
 * 0.1.567) из коммита …» (`.github/workflows/build.yml`) — и сравнивает номер со своим (`versionCode` = номер
 * сборки). APK скачивается в браузере по постоянной ссылке и ставится поверх, данные сохраняются.
 */
object UpdateRules {
    /** Описание релиза «Последняя сборка»: JSON GitHub API, в поле `body` — «Сборка N». */
    const val RELEASE_URL = "https://api.github.com/repos/AkenoryGit/personalka/releases/tags/latest-build"

    /** Постоянная ссылка на APK последней сборки. */
    const val APK_URL = "https://github.com/AkenoryGit/personalka/releases/download/latest-build/personalka.apk"

    private val BUILD = Regex("""Сборка (\d+)""")

    /** Номер сборки из ответа о релизе; нет «Сборка N» — `null`. */
    fun latestBuild(release: String?): Int? = release?.let { BUILD.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** Сравнение своей сборки [current] с ответом о релизе [release] (`null` — ответа нет). */
    fun check(current: Int, release: String?): UpdateCheck {
        val latest = latestBuild(release) ?: return UpdateCheck.Failed
        return if (latest > current) UpdateCheck.Available(latest) else UpdateCheck.Fresh
    }
}

/**
 * Журнал ошибок (ТЗ 5.16, шаг 5.33): тексты ошибок, на которых закрывалось приложение, — последние [MAX_ENTRIES],
 * новые в конце. Каждая запись начинается строкой «Версия 0.1.567 · дата» (так её пишет приложение платформы).
 * Журнал — только в телефоне; тренер отправляет его разработчику сам.
 */
object CrashLogRules {
    const val MAX_ENTRIES = 5

    /** Не больше этого числа знаков на весь журнал: старые записи уходят первыми. */
    const val MAX_LENGTH = 60_000

    /** Разделитель записей в файле журнала. */
    const val SEPARATOR = "\n\n——————————\n\n"

    private val VERSION = Regex("""^Версия (\S+)""")

    /** Записи журнала по порядку, пустые пропускаются. */
    fun entries(log: String?): List<String> =
        log.orEmpty().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /** Журнал с новой записью [report] в конце: не больше [MAX_ENTRIES] записей и [MAX_LENGTH] знаков. */
    fun add(log: String?, report: String): String {
        val all = (entries(log) + report.trim()).filter { it.isNotEmpty() }.takeLast(MAX_ENTRIES).toMutableList()
        while (all.size > 1 && all.joinToString(SEPARATOR).length > MAX_LENGTH) all.removeAt(0)
        return all.joinToString(SEPARATOR).take(MAX_LENGTH)
    }

    /** Версия приложения из первой строки записи, если она там есть. */
    fun versionOf(entry: String): String? = VERSION.find(entry.trim())?.groupValues?.get(1)
}
