package com.akenory.personalka.data.money

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.domain.money.SalarySetupDraft
import com.akenory.personalka.domain.money.SalarySetupRules
import com.akenory.personalka.domain.money.TierDraft
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Мастер «Настроить зарплату» и зарплатная часть «Профиля» в базе (шаг 3.6б). */
class SalarySetupRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private var now = 1_792_500_000_000L // 20.10.2026
    private val today = LocalDate(2026, 10, 20)
    private lateinit var settings: SettingsRepository
    private val salary get() = settings.salary

    @BeforeTest
    fun open() {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { now }
        runBlocking { settings.completeFirstRun(CoachName("Соколов", "Андрей", null), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes) }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private fun rub(r: Long) = r * 100
    private fun typeId(short: String) = runBlocking { settings.types.first().single { it.shortName == short }.id }
    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }

    @Test
    fun wizardWritesEverythingFromJanuary(): Unit = runBlocking {
        val pt = typeId("ПТ")
        val bb = placeId("ББ")
        val dbPlace = placeId("ДБ")
        val asPt = setOf(pt, typeId("Сплит"))
        val draft = SalarySetupDraft(
            scheme = PaySchemeKind.HALF_OFFICIAL,
            advance = 1_354_650,
            // Переход на «налог 6,5 %» с марта: январь–февраль — вся сумма.
            schemeFrom = YearMonth(2026, 3),
            // Свои дни выплат (5.3а): аванс 20-го, зарплата 5-го.
            advanceDay = 20,
            salaryDay = 5,
            rates = mapOf(pt to mapOf(bb to RateAmounts(rub(920), rub(960), rub(1000)), dbPlace to RateAmounts(rub(850)))),
            tiers = TierDraft(true, 101, 151, asPt),
        )
        assertTrue(SalarySetupRules.needed(salary.schemes.first()))
        salary.finish(draft, today)
        assertFalse(SalarySetupRules.needed(salary.schemes.first()))

        val jan = LocalDate(2026, 1, 1)
        val (before, scheme) = salary.schemes.first().map { it.scheme }.sortedBy { it.validFrom }
        assertEquals(jan, before.validFrom)
        assertEquals(PaySchemeKind.NONE, before.kind)
        assertNull(before.advanceDefault)
        assertEquals(5, before.salaryDay)
        assertEquals(LocalDate(2026, 3, 1), scheme.validFrom)
        assertEquals(5000, scheme.officialShareBp)
        assertEquals(20, scheme.advanceDay)
        assertEquals(5, scheme.salaryDay)
        assertEquals(1_354_650, scheme.advanceDefault)
        val rates = db.referenceDao().rateVersions(pt)
        assertEquals(listOf(jan, jan), rates.map { it.validFrom })
        // Сетки клуба нет — связей с ней мастер не пишет.
        assertTrue(rates.all { it.clubService == null })
        val tiers = settings.rates.tiers.first().single()
        assertEquals(jan, tiers.validFrom)
        assertEquals(asPt, tiers.ptTypeIds)

        // Повтор «Готово» (прерванная запись) не дублирует версий.
        salary.finish(draft, today)
        assertEquals(2, salary.schemes.first().size)
        assertEquals(2, db.referenceDao().rateVersions(pt).size)
        assertEquals(1, settings.rates.tiers.first().size)
    }

    @Test
    fun profileVersions(): Unit = runBlocking {
        val jan = LocalDate(2026, 1, 1)
        salary.saveScheme(YearMonth(2026, 1), SalarySetupRules.scheme(jan, PaySchemeKind.HALF_OFFICIAL, rub(13_000)))
        // Тот же вид и аванс с ноября — ничего; другой аванс — новая версия с 01.11; ИП в том же месяце — правка её.
        salary.saveScheme(YearMonth(2026, 11), SalarySetupRules.scheme(jan, PaySchemeKind.HALF_OFFICIAL, rub(13_000)))
        assertEquals(1, salary.schemes.first().size)
        salary.saveScheme(YearMonth(2026, 11), SalarySetupRules.scheme(jan, PaySchemeKind.HALF_OFFICIAL, rub(14_000)))
        salary.saveScheme(YearMonth(2026, 11), SalarySetupRules.scheme(jan, PaySchemeKind.IP, null))
        val schemes = salary.schemes.first().map { it.scheme }
        assertEquals(listOf(jan, LocalDate(2026, 11, 1)), schemes.map { it.validFrom })
        assertEquals(PaySchemeKind.IP, schemes[1].kind)
        assertEquals(0, schemes[1].officialShareBp)
        assertNull(schemes[1].advanceDay)

        // День зарплаты с декабря (5.3а): та же схема, другой день — новая версия; тот же — ничего.
        salary.saveScheme(YearMonth(2026, 12), schemes[1].copy(validFrom = LocalDate(2026, 12, 1), salaryDay = 7))
        salary.saveScheme(YearMonth(2026, 12), schemes[1].copy(validFrom = LocalDate(2026, 12, 1), salaryDay = 7))
        assertEquals(listOf(10, 10, 7), salary.schemes.first().map { it.scheme.salaryDay })

        assertEquals(false, salary.coCoaching.first())
        salary.saveCoCoaching(true)
        assertEquals(true, salary.coCoaching.first())
    }
}
