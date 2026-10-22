package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.format.RuText.MINUS
import com.akenory.personalka.domain.format.RuText.NBSP

/**
 * Деньги (ТЗ 5.20, 10.6): «124 800 ₽» — разряды узким пробелом, копейки только там, где они есть.
 * На входе — целые копейки, как деньги хранятся везде (ТЗ 3.1).
 */
object MoneyFormat {
    private const val KOPECKS_IN_10_RUB = 1_000L

    /** «126 645 ₽», «8 170,50 ₽», «−4 165,43 ₽», «0 ₽». */
    fun rub(kopecks: Long): String = "${amount(kopecks)}$NBSP₽"

    /** Со знаком и у положительной суммы — для долгов, доплат и удержаний: «+883,58 ₽», «−8 170,50 ₽». Ноль — без знака. */
    fun signedRub(kopecks: Long): String = if (kopecks > 0) "+${rub(kopecks)}" else rub(kopecks)

    /** Сумма без «₽» — в таблицах, где рубль стоит в заголовке: «62 850», «−8 170,50». */
    fun amount(kopecks: Long): String {
        val negative = kopecks < 0
        val abs = if (negative) -kopecks else kopecks
        val rubles = RuText.digits(abs / 100)
        val rest = abs % 100
        val text = if (rest == 0L) rubles else "$rubles,${rest.toString().padStart(2, '0')}"
        return if (negative) "$MINUS$text" else text
    }

    /**
     * Прогноз (решение шага 0.6): «≈ 203 490 ₽» — до 10 ₽, без копеек. Факт всегда до копейки — через [rub].
     */
    fun approxRub(kopecks: Long): String = "≈$NBSP${rub(roundTo10Rub(kopecks))}"

    /** До 10 ₽, половина — от нуля: 5 815 → 5 820, 203 492,50 → 203 490, −5 815 → −5 820. */
    fun roundTo10Rub(kopecks: Long): Long {
        val negative = kopecks < 0
        val abs = if (negative) -kopecks else kopecks
        val rest = abs % KOPECKS_IN_10_RUB
        val rounded = abs - rest + if (rest * 2 >= KOPECKS_IN_10_RUB) KOPECKS_IN_10_RUB else 0
        return if (negative) -rounded else rounded
    }
}
