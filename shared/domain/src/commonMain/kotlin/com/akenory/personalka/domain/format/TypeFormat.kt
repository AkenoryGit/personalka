package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.reference.TypeDraft

/** Строки о типах тренировок для первого запуска, «Мест и типов» и конструктора (5.16, 5.18). */
object TypeFormat {
    private const val DOT = " · "

    /** Вид в конструкторе и в строке списка: «Персональная», «Стартовая»… */
    fun kind(kind: TrainingKind): String = when (kind) {
        TrainingKind.PERSONAL -> "Персональная"
        TrainingKind.SPLIT -> "Сплит"
        TrainingKind.GROUP -> "Группа"
        TrainingKind.SPT -> "Стартовая"
        TrainingKind.DUTY -> "Дежурство"
        TrainingKind.OTHER -> "Другое"
    }

    /** Пояснение под видом в конструкторе. */
    fun kindHint(kind: TrainingKind): String = when (kind) {
        TrainingKind.PERSONAL -> "Один клиент, оплата за тренировку"
        TrainingKind.SPLIT -> "Два клиента, оплата за тренировку"
        TrainingKind.GROUP -> "Несколько клиентов, оплата за каждого"
        TrainingKind.SPT -> "Пробная, бонус за покупку блока"
        TrainingKind.DUTY -> "Без клиентов, оплата за час"
        TrainingKind.OTHER -> "Всё настроите сами"
    }

    /** «Как у группы» — в подсказке после выбора вида. */
    private fun kindGenitive(kind: TrainingKind): String = when (kind) {
        TrainingKind.PERSONAL -> "персональной"
        TrainingKind.SPLIT -> "сплита"
        TrainingKind.GROUP -> "группы"
        TrainingKind.SPT -> "стартовой"
        TrainingKind.DUTY -> "дежурства"
        TrainingKind.OTHER -> "обычной тренировки"
    }

    /** Подсказка после выбора вида: что заполнено само. */
    fun presetNote(kind: TrainingKind): String =
        "Длительность, оплата и дневник заполнены как у ${kindGenitive(kind)} — ниже их можно поправить."

    fun payUnit(unit: PayUnit): String = when (unit) {
        PayUnit.PER_SESSION -> "За тренировку"
        PayUnit.PER_CLIENT -> "За клиента"
        PayUnit.PER_HOUR -> "За час"
    }

    /**
     * Строка под типом в «Местах и типах»: «Персональная · 50 мин · как ПТ», «Группа · 60 мин · за клиента»,
     * «Стартовая · 50 мин · бонус за блок», «Дежурство · за час».
     */
    fun summary(type: TypeDraft): String {
        val pay = when {
            type.kind == TrainingKind.SPT -> "бонус за блок"
            type.countsTowardPt -> "как ПТ"
            else -> payUnit(type.payUnit).lowercase()
        }
        val parts = if (type.kind == TrainingKind.DUTY) {
            listOf(kind(type.kind), pay)
        } else {
            listOf(kind(type.kind), DurationFormat.minutes(type.durationMin), pay)
        }
        return parts.joinToString(DOT)
    }

    /**
     * Строка справа в списке первого запуска: «50 мин · 1 клиент», «50 мин · 2 клиента», «60 мин · группа»,
     * «50 мин · пробная», у дежурства — «оплата за час».
     */
    fun setupLine(type: TypeDraft): String {
        if (type.kind == TrainingKind.DUTY) return "оплата за час"
        val max = type.maxClients
        val clients = when {
            type.kind == TrainingKind.SPT -> "пробная"
            max == null -> "группа"
            type.kind == TrainingKind.GROUP -> "до " + RuText.count(max, "клиента", "клиентов", "клиентов")
            else -> RuText.count(max, "клиент", "клиента", "клиентов")
        }
        return DurationFormat.minutes(type.durationMin) + DOT + clients
    }

    /** Строка «Мест и типов» в настройках: «3 места · 9 типов». */
    fun placesAndTypes(places: Int, types: Int): String =
        RuText.count(places, "место", "места", "мест") + DOT + RuText.count(types, "тип", "типа", "типов")
}
