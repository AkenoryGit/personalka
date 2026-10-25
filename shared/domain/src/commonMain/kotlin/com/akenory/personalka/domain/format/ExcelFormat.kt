package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.TrainingStatus

/** Строки выгрузки в Excel (ТЗ 7.6, шаг 3.8в): кнопка в «Данных», листы и столбцы. */
object ExcelFormat {
    const val BUTTON = "Выгрузить в Excel"
    const val NOTE = "Клиенты, тренировки, результаты и зарплата по месяцам — откроется в Excel и Google Таблицах."

    const val CLIENTS = "Клиенты"
    const val TRAININGS = "Тренировки"
    const val RESULTS = "Результаты"
    const val SALARY = "Зарплата по месяцам"

    val CLIENT_HEADERS = listOf(
        "Фамилия", "Имя", "Отчество", "Дата рождения", "Для связи", "Телефон", "Группа", "Статус",
        "Был на тренировках", "Последняя тренировка", "Фото и видео", "Заметка",
    )
    val TRAINING_HEADERS = listOf(
        "Дата", "День", "Начало", "Минут", "Тип", "Место", "Группа", "Клиенты", "Статус", "Провёл", "Причина отмены", "План", "Факт",
    )
    val RESULT_HEADERS = listOf("Дата", "Клиент", "Дистанция, м", "Стиль", "Бассейн, м", "Время", "Секунды", "Где", "Старт", "Заметка")
    val SALARY_HEADERS = listOf(
        "Год", "Месяц", "Заработано", "Прогноз", "План", "% плана", "Налог", "На руки", "ПТ", "Дети в группах", "Дежурства, ч",
    )

    const val ACTIVE = "занимается"
    const val ARCHIVED = "в архиве"

    fun status(status: TrainingStatus): String = when (status) {
        TrainingStatus.PLANNED -> "запланирована"
        TrainingStatus.DONE -> "проведена"
        TrainingStatus.CANCELLED -> "отменена"
    }

    /** «Орлов Миша — отмена»: у пришедшего и ожидаемого — только имя. */
    fun person(name: String, status: ParticipationStatus): String = when (status) {
        ParticipationStatus.EXPECTED, ParticipationStatus.ATTENDED -> name
        ParticipationStatus.CANCELLED -> "$name — отмена"
        ParticipationStatus.CANCELLED_CHARGED -> "$name — отмена со списанием"
    }

    /** «я», «я и Лена», «Лена»; без имени коллеги — «коллега». */
    fun conducted(by: ConductedBy, colleague: String?): String = when (by) {
        ConductedBy.SELF -> "я"
        ConductedBy.SELF_AND_COLLEAGUE -> "я и ${colleague ?: "коллега"}"
        ConductedBy.COLLEAGUE -> colleague ?: "коллега"
    }

    fun where(kind: ResultKind): String = when (kind) {
        ResultKind.TRAINING -> "тренировка"
        ResultKind.COMPETITION -> "соревнования"
    }

    /** «Итого 2026» — строка суммы года на листе зарплаты. */
    fun total(year: Int): String = "Итого $year"
}
