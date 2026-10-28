package com.akenory.personalka.domain.tour

/**
 * Знакомство с приложением (ТЗ 5.18, 5.20; шаг 5.3б, решение Дмитрия 28.10.2026): шесть остановок по порядку,
 * на каждой тренер делает одно настоящее действие или нажимает «Дальше».
 */
enum class TourStop {
    /** Удержать пустое место → новая тренировка (клиента можно завести прямо в форме). */
    CALENDAR,

    /** Карточка этой тренировки: «Проведено» ставится само, «Отменить» и правило списания — только показываются. */
    CARD,

    /** Клиенты: открыть карточку клиента из тренировки; клиентов нет — добавить первого. */
    CLIENTS,

    /** «Главная»: «Нужно сделать» и «Деньги». */
    HOME,

    /** «Зарплата»: мастер «Настроить зарплату» или «Позже»; зарплата уже настроена — остановки нет. */
    SALARY,

    /** Резервная копия: папка в телефоне; без неё «Дальше» спрашивает «Без копии?»; выбрана раньше — только «Готово». */
    BACKUP,
    ;

    /** Номер для «N из 6». */
    val number: Int get() = ordinal + 1

    companion object {
        val COUNT = entries.size

        fun of(name: String?): TourStop? = entries.firstOrNull { it.name == name }
    }
}

object TourRules {
    /**
     * Следующая остановка или `null` — знакомство кончилось. «Дальше» есть на каждой остановке, поэтому её можно
     * пройти и без действия: тренировку не добавили ([hasTraining] = false) — карточки показывать нечего, сразу
     * «Клиенты»; зарплата уже настроена ([salaryNeeded] = false) — «Зарплата» пропускается.
     */
    fun next(stop: TourStop, salaryNeeded: Boolean, hasTraining: Boolean = true): TourStop? {
        val after = TourStop.entries.getOrNull(stop.ordinal + 1) ?: return null
        val skip = after == TourStop.SALARY && !salaryNeeded || after == TourStop.CARD && !hasTraining
        return if (skip) next(after, salaryNeeded, hasTraining) else after
    }

    /**
     * Пустое место, которое обводит первая остановка, — минута начала в сутках или `null`, если сегодня места нет.
     * Это первый целый час после [nowMinute] в часах сетки [startMinute]–[endMinute], свободный на [durationMin]
     * минут: тренировки [busy] (начало и конец в минутах суток) с ним не пересекаются.
     */
    fun slotMinute(nowMinute: Int, startMinute: Int, endMinute: Int, busy: List<IntRange>, durationMin: Int = SLOT_MINUTES): Int? {
        var minute = maxOf(startMinute, (nowMinute / 60 + 1) * 60)
        while (minute + durationMin <= endMinute) {
            val end = minute + durationMin
            if (busy.none { it.first < end && minute < it.last }) return minute
            minute += 60
        }
        return null
    }

    const val SLOT_MINUTES = 50

    /** «Дальше» на последней остановке без выбранной папки копии спрашивает «Без копии?» (5.18). */
    fun asksAboutBackup(folderChosen: Boolean): Boolean = !folderChosen
}
