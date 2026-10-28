package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.model.PaySchemeKind
import kotlinx.datetime.LocalDate

/** Строки мастера «Настроить зарплату» и зарплатной части «Профиля» (ТЗ 5.18, 5.16, 4.11). */
object SalarySetupFormat {
    private const val DOT = " · "

    // Шаг 1 мастера (5.3а): ставки-пример или свои и день зарплаты — без категорий и сетки одного клуба.
    const val PAY_TITLE = "Как вам платят"
    const val PAY_TEXT = "Ставки и день зарплаты — по вашему расчётному листку."
    const val EXAMPLE = "Взять ставки-пример и поправить"
    const val EXAMPLE_NOTE = "Поля заполнятся примером, его останется сверить"
    const val OWN = "Вписать свои"
    const val OWN_NOTE = "Поля будут пустыми"
    const val SALARY_DAY = "День зарплаты"
    const val SALARY_DAY_NOTE = "Какого числа следующего месяца приходит зарплата за месяц."
    const val ADVANCE_DAY = "День аванса"
    const val ADVANCE_DAY_NOTE = "Какого числа месяца приходит аванс."
    const val SCHEME_TITLE = "Как платит клуб"
    const val SCHEME_TEXT = "По схеме считаются налог и аванс."
    const val RATES_TITLE = "Ставки"
    const val EXAMPLE_TEXT = "Это пример — сверьте со своим расчётным листком и поправьте."
    const val OWN_TEXT = "Впишите, сколько вы получаете за тренировку."
    const val OTHER_RATES = "Остальные типы — потом в «Ставках и ступенях»: оттуда же правятся и эти."
    const val TIERS_TITLE = "Ступени"
    const val TIERS_TEXT = "Ставка растёт, когда за месяц набирается больше ПТ."
    const val NEXT = "Далее"
    const val DONE = "Готово"
    const val SETUP = "Настроить зарплату"
    const val SETUP_HEADING = "Зарплата ещё не настроена"
    const val ADVANCE = "Ожидаемый аванс, ₽"
    const val ADVANCE_NOTE = "Фактическую сумму приложение спросит в день аванса."
    const val SAVED = "Зарплата настроена"

    // «Профиль»
    const val SALARY = "Зарплата"
    const val PAY_DAY = "День зарплаты"
    const val PAY_DAY_NOT_SET = "Настройте зарплату"
    const val SCHEME = "Схема выплат"
    const val CO_COACHING = "Совместное ведение групп"
    const val CO_COACHING_NOTE = "Группы с коллегами: доли и взаиморасчёт."
    const val NOT_SET = "Не выбрана"
    const val FROM_DAY = "С какого дня"
    const val FROM_MONTH = "С какого месяца"
    const val PAY_DAY_NOTE = "Новый день действует с 1-го числа выбранного месяца: прошлые месяцы не меняются."
    const val SCHEME_NOTE = "Схема меняется с 1-го числа: прошлые месяцы считаются по прежней."
    const val SCHEME_FROM_NOTE = "С этого месяца выплаты считаются по новой схеме, до него — вся сумма без аванса."

    /** Строка «Профиля»: «10-го числа следующего месяца». */
    fun salaryDay(day: Int): String = "$day-го числа следующего месяца"

    fun scheme(k: PaySchemeKind): String = when (k) {
        PaySchemeKind.HALF_OFFICIAL -> "Официально с налогом 6,5${NBSP}%"
        PaySchemeKind.FULL_OFFICIAL -> "Полностью официально"
        PaySchemeKind.IP -> "ИП"
        PaySchemeKind.NONE -> "Вся сумма, без аванса"
    }

    /** Пояснение под схемой в выборе. */
    fun schemeNote(k: PaySchemeKind): String = when (k) {
        PaySchemeKind.HALF_OFFICIAL -> "Половина — на карту с НДФЛ 13${NBSP}% (6,5${NBSP}% всей суммы), половина — наличными, есть аванс"
        PaySchemeKind.FULL_OFFICIAL -> "Всё на карту с НДФЛ 13${NBSP}%, есть аванс"
        PaySchemeKind.IP -> "Вся сумма без удержаний"
        PaySchemeKind.NONE -> "100${NBSP}% по ставкам одной выплатой"
    }

    /** «Шаг 2 из 4»: без выбора схемы шагов три. */
    fun step(n: Int, of: Int): String = "Шаг $n из $of"

    /** Строка «Профиля»: «… · с 01.01.2026». */
    fun since(value: String, from: LocalDate): String = "$value$DOT${RateFormat.since(from)}"

    /** Строка схемы: «Полностью официально · аванс 13 546,50 ₽ · с 01.10.2026». */
    fun schemeRow(k: PaySchemeKind, advance: Long?, from: LocalDate): String =
        since(scheme(k) + (advance?.let { "${DOT}аванс ${MoneyFormat.rub(it)}" } ?: ""), from)
}
