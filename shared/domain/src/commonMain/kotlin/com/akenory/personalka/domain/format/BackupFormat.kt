package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.backup.BackupFrequency
import com.akenory.personalka.domain.backup.BackupRules
import com.akenory.personalka.domain.format.RuText.NBSP
import kotlinx.datetime.LocalDateTime

/** Строки раздела «Данные» в настройках: резервная копия и выгрузка в календарь (ТЗ 5.16, 7.6, шаг 1.15). */
object BackupFormat {
    private const val DOT = " · "

    const val TITLE = "Данные"
    const val BACKUP = "Резервная копия"
    const val FOLDER = "Папка для копий"
    const val FOLDER_NONE = "Не выбрана — автокопии нет"
    const val FOLDER_NOTE =
        "Можно выбрать папку облака (Яндекс Диск, Google Диск), если их приложение стоит на телефоне. В папке остаются последние ${BackupRules.KEEP} копий."
    const val FREQUENCY = "Автокопия"
    const val ENCRYPT = "Шифровать паролем"
    const val ENCRYPT_NOTE = "В копии — данные детей. Без пароля её прочтёт любой, у кого окажется файл."
    const val PASSWORD = "Пароль копии"
    const val PASSWORD_SET = "Задан"
    const val PASSWORD_NONE = "Не задан"
    const val PASSWORD_TITLE = "Пароль копии"
    const val PASSWORD_FIELD = "Пароль · не короче ${BackupRules.PASSWORD_MIN} знаков"
    const val PASSWORD_REPEAT = "Ещё раз"
    const val PASSWORD_WARNING =
        "Забудете пароль — копию не откроет никто, даже разработчик. Запишите его туда, где храните другие пароли."
    const val PASSWORD_SAVE = "Сохранить пароль"
    const val PASSWORD_MISMATCH = "Пароли не совпадают"
    const val CREATE_NOW = "Создать копию сейчас"
    const val SHARE = "Поделиться копией"
    const val RESTORE = "Восстановить из копии"
    const val UPDATE_COPY = "Вернуть данные до обновления"
    const val CALENDAR = "Выгрузить в календарь (.ics)"
    const val CALENDAR_NOTE = "Тренировки за 3 месяца назад и 3 вперёд — для просмотра в любом календаре."
    const val NO_COPIES = "Копий ещё не было"

    const val OPEN_PASSWORD_TITLE = "Пароль от копии"
    const val OPEN = "Открыть"
    const val WRONG_PASSWORD = "Неверный пароль"
    const val NOT_BACKUP = "Это не копия «Персоналки» или файл повреждён"
    const val NEWER = "Копия из более новой версии «Персоналки» — сначала обновите приложение"
    const val RESTORE_TITLE = "Восстановить из копии"
    const val RESTORE_WARNING = "Все текущие данные будут заменены данными копии."
    const val RESTORE_CONFIRM = "Заменить данные"
    const val RESTORE_FAILED = "Копию не удалось восстановить — данные не изменились"
    const val FOLDER_LOST = "Папка для копий недоступна — выберите её снова"
    const val SAVED = "Копия сохранена в папку"
    const val CALENDAR_SAVED = "Календарь выгружен"
    const val WORKING = "Минуту…"

    /** Чип частоты автокопии. */
    fun frequency(f: BackupFrequency): String = when (f) {
        BackupFrequency.DAILY -> "Каждый день"
        BackupFrequency.WEEKLY -> "Раз в неделю"
    }

    /** «Последняя копия — чт 22.10 21:00» или «Копий ещё не было». */
    fun last(time: LocalDateTime?): String =
        if (time == null) NO_COPIES else "Последняя копия — ${DateFormat.short(time.date)}$NBSP${DateFormat.time(time.time)}"

    /** Строка раздела в «Настройках»: «Копия чт 22.10 21:00 · каждый день» или «Папка для копий не выбрана». */
    fun summary(last: LocalDateTime?, folderChosen: Boolean, frequency: BackupFrequency): String = when {
        !folderChosen -> "Папка для копий не выбрана"
        last == null -> NO_COPIES + DOT + frequency(frequency).lowercase()
        else -> "Копия ${DateFormat.short(last.date)}$NBSP${DateFormat.time(last.time)}" + DOT + frequency(frequency).lowercase()
    }

    /** Предпросмотр копии: «Копия от 22.10.2026 21:00». */
    fun previewTitle(createdAt: LocalDateTime): String = "Копия от ${DateFormat.full(createdAt.date)}$NBSP${DateFormat.time(createdAt.time)}"

    /** «34 клиента · 512 тренировок». */
    fun previewCounts(clients: Int, trainings: Int): String =
        RuText.count(clients, "клиент", "клиента", "клиентов") + DOT + RuText.count(trainings, "тренировка", "тренировки", "тренировок")

    /**
     * Под кнопкой копии перед обновлением базы (2.7б): «Копию приложение сделало само сб 24.10 18:30, перед обновлением.
     * Нужна, только если после обновления данные стали неверными.»
     */
    fun updateCopyNote(at: LocalDateTime): String =
        "Копию приложение сделало само ${DateFormat.short(at.date)}$NBSP${DateFormat.time(at.time)}, перед обновлением. " +
            "Нужна, только если после обновления данные стали неверными."

    /** «Данные восстановлены из копии от 22.10.2026». */
    fun restored(createdAt: LocalDateTime): String = "Данные восстановлены из копии от ${DateFormat.full(createdAt.date)}"
}
