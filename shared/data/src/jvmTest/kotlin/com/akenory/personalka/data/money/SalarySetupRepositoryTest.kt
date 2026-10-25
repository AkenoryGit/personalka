package com.akenory.personalka.data.money

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.model.CoachCategory
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
        // ДБ тренер поправил — связи с сеткой у неё нет.
        val draft = SalarySetupDraft(
            category = CoachCategory.MASTER,
            scheme = PaySchemeKind.FULL_OFFICIAL,
            advance = 1_354_650,
            rates = mapOf(pt to mapOf(bb to RateAmounts(rub(920), rub(960), rub(1000)), dbPlace to RateAmounts(rub(850)))),
            services = mapOf(pt to mapOf(bb to "Персональная")),
            tiers = TierDraft(true, 101, 151, asPt),
        )
        assertTrue(SalarySetupRules.needed(salary.categories.first()))
        salary.finish(draft, today)

        val jan = LocalDate(2026, 1, 1)
        assertEquals(listOf(jan), salary.categories.first().map { it.validFrom })
        assertEquals(CoachCategory.MASTER, salary.categories.first().single().category)
        val scheme = salary.schemes.first().single().scheme
        assertEquals(jan, scheme.validFrom)
        assertEquals(10000, scheme.officialShareBp)
        assertEquals(25, scheme.advanceDay)
        assertEquals(1_354_650, scheme.advanceDefault)
        val rates = db.referenceDao().rateVersions(pt)
        assertEquals(listOf(jan, jan), rates.map { it.validFrom })
        assertEquals("Персональная", rates.single { it.locationId == bb }.clubService)
        assertNull(rates.single { it.locationId == dbPlace }.clubService)
        val tiers = settings.rates.tiers.first().single()
        assertEquals(jan, tiers.validFrom)
        assertEquals(asPt, tiers.ptTypeIds)

        // Повтор «Готово» (прерванная запись) не дублирует версий.
        salary.finish(draft, today)
        assertEquals(1, salary.categories.first().size)
        assertEquals(1, salary.schemes.first().size)
        assertEquals(2, db.referenceDao().rateVersions(pt).size)
        assertEquals(1, settings.rates.tiers.first().size)
    }

    @Test
    fun profileVersions(): Unit = runBlocking {
        val jan = LocalDate(2026, 1, 1)
        salary.saveCategory(jan, CoachCategory.TRAINER)
        salary.saveCategory(jan, CoachCategory.MASTER)
        salary.saveCategory(LocalDate(2026, 11, 1), CoachCategory.MASTER)
        salary.saveCategory(LocalDate(2026, 11, 1), CoachCategory.ELITE)
        assertEquals(listOf(CoachCategory.MASTER, CoachCategory.ELITE), salary.categories.first().map { it.category })

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

        assertEquals(false, salary.coCoaching.first())
        salary.saveCoCoaching(true)
        assertEquals(true, salary.coCoaching.first())
    }
}
