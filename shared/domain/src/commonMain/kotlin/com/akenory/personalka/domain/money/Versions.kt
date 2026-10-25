package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.model.PaySchemeKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/**
 * Версии по датам (ТЗ 3.1): ставки, категория, схема выплат, ступени и схема коллеги хранятся версиями с `valid_from`.
 * Версия действует с этой даты до следующей; раньше первой версии не действует ничего.
 */
object Versions {

    /** Версия, действующая в день [date]: последняя с началом не позже его. Две с одной датой — берётся последняя в списке. */
    fun <T> activeOn(versions: List<T>, date: LocalDate, validFrom: (T) -> LocalDate): T? =
        versions.withIndex()
            .filter { validFrom(it.value) <= date }
            .maxWithOrNull(compareBy<IndexedValue<T>> { validFrom(it.value) }.thenBy { it.index })
            ?.value

    /** Версия, действующая в месяце [month]: схема выплат и ступени начинаются с 1-го числа и действуют на весь месяц. */
    fun <T> activeIn(versions: List<T>, month: YearMonth, validFrom: (T) -> LocalDate): T? =
        activeOn(versions, month.firstDay, validFrom)

    /**
     * Ставка тренировки (ТЗ 3.3): версия для её типа [typeId] и места [locationId], действующая в день [date];
     * для места версии нет — версия с пустым местом; нет и её — `null` («нет ставки», пункт «Нужно сделать»).
     */
    fun <T> rateFor(
        rates: List<T>,
        typeId: String,
        locationId: String?,
        date: LocalDate,
        type: (T) -> String,
        location: (T) -> String?,
        validFrom: (T) -> LocalDate,
    ): T? {
        val ofType = rates.filter { type(it) == typeId }
        val here = if (locationId == null) null else activeOn(ofType.filter { location(it) == locationId }, date, validFrom)
        return here ?: activeOn(ofType.filter { location(it) == null }, date, validFrom)
    }

    /**
     * Доля официальной части по схеме, в десятитысячных (ТЗ 3.2): половина — 5000, полностью — 10000, ИП и без
     * официальной части — 0. У коллеги хранится только вид схемы (ТЗ 3.6), доля берётся отсюда.
     */
    fun officialShareBp(kind: PaySchemeKind): Int = when (kind) {
        PaySchemeKind.HALF_OFFICIAL -> 5000
        PaySchemeKind.FULL_OFFICIAL -> 10000
        PaySchemeKind.IP, PaySchemeKind.NONE -> 0
    }

    /** НДФЛ официальной части по умолчанию: 13 % (ТЗ 3.2). */
    const val NDFL_BP = 1300
}
