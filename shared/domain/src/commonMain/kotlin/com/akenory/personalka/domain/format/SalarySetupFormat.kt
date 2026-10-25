package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.model.PaySchemeKind
import kotlinx.datetime.LocalDate

/** Строки мастера «Настроить зарплату» и зарплатной части «Профиля» (ТЗ 5.18, 5.16, 4.11). */
object SalarySetupFormat {
    private const val DOT = " · "

    const val CATEGORY_TITLE = "Ваша категория"
    const val CATEGORY_TEXT = "От категории зависят ставки клуба."
    const val SCHEME_TITLE = "Как платит клуб"
    const val SCHEME_TEXT = "По схеме считаются налог, аванс и выплаты 10-го."
    const val RATES_TITLE = "Проверьте ставки"
    const val GRID_TEXT = "Заполнено по сетке клуба — сверьте со своим расчётным листком."
    const val NO_GRID_TEXT = "Сетки клуба для этой категории пока нет — впишите ставки сами."
    const val OTHER_RATES = "Остальные типы — потом в «Ставках и ступенях»: оттуда же правятся и эти."
    const val TIERS_TITLE = "Ступени"
    const val TIERS_TEXT = "Ставка растёт, когда за месяц набирается больше ПТ."
    const val NEXT = "Далее"
    const val DONE = "Готово"
    const val SETUP = "Настроить зарплату"
    const val SETUP_HEADING = "Зарплата ещё не настроена"
    const val ADVANCE = "Ожидаемый аванс, ₽"
    const val ADVANCE_NOTE = "Приходит 25-го. Фактическую сумму приложение спросит в этот день."
    const val SAVED = "Зарплата настроена"

    // «Профиль»
    const val SALARY = "Зарплата"
    const val CATEGORY = "Категория"
    const val SCHEME = "Схема выплат"
    const val CO_COACHING = "Совместное ведение групп"
    const val CO_COACHING_NOTE = "Группы с коллегами: доли и взаиморасчёт."
    const val NOT_SET = "Не выбрана"
    const val FROM_DAY = "С какого дня"
    const val FROM_MONTH = "С какого месяца"
    const val CATEGORY_NOTE = "Ставки по новой категории впишите в «Ставках и ступенях» с той же даты."
    const val SCHEME_NOTE = "Схема меняется с 1-го числа: прошлые месяцы считаются по прежней."
    const val SCHEME_FROM_NOTE = "С этого месяца выплаты считаются по новой схеме, до него — вся сумма без аванса."

    fun category(c: CoachCategory): String = when (c) {
        CoachCategory.TRAINER -> "Персональный тренер"
        CoachCategory.MASTER -> "Мастер тренер"
        CoachCategory.ELITE -> "Элит тренер"
    }

    fun scheme(k: PaySchemeKind): String = when (k) {
        PaySchemeKind.HALF_OFFICIAL -> "Официально с налогом 6,5${NBSP}%"
        PaySchemeKind.FULL_OFFICIAL -> "Полностью официально"
        PaySchemeKind.IP -> "ИП"
        PaySchemeKind.NONE -> "Вся сумма, без аванса"
    }

    /** Пояснение под схемой в выборе. */
    fun schemeNote(k: PaySchemeKind): String = when (k) {
        PaySchemeKind.HALF_OFFICIAL -> "Половина — на карту с НДФЛ 13${NBSP}% (6,5${NBSP}% всей суммы), половина — наличными, аванс 25-го"
        PaySchemeKind.FULL_OFFICIAL -> "Всё на карту с НДФЛ 13${NBSP}%, аванс 25-го"
        PaySchemeKind.IP -> "Вся сумма без удержаний, 10-го"
        PaySchemeKind.NONE -> "100${NBSP}% по ставкам одной выплатой 10-го"
    }

    /** «Шаг 2 из 4»: без выбора схемы шагов три. */
    fun step(n: Int, of: Int): String = "Шаг $n из $of"

    /** Строка «Профиля»: «Мастер тренер · с 01.01.2026». */
    fun since(value: String, from: LocalDate): String = "$value$DOT${RateFormat.since(from)}"

    /** Строка схемы: «Полностью официально · аванс 13 546,50 ₽ · с 01.10.2026». */
    fun schemeRow(k: PaySchemeKind, advance: Long?, from: LocalDate): String =
        since(scheme(k) + (advance?.let { "${DOT}аванс ${MoneyFormat.rub(it)}" } ?: ""), from)
}
