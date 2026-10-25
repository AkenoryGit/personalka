package com.akenory.personalka.data.money

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.CategoryVersionEntity
import com.akenory.personalka.data.db.entity.PaySchemeVersionEntity
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.money.CategoryVersion
import com.akenory.personalka.domain.money.PayScheme
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.domain.money.RateRules
import com.akenory.personalka.domain.money.SalarySetupDraft
import com.akenory.personalka.domain.money.SalarySetupRules
import com.akenory.personalka.domain.money.SavedScheme
import com.akenory.personalka.domain.money.VersionEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/**
 * Категория и схема выплат версиями (ТЗ 3.2) и мастер «Настроить зарплату» (5.18). Что писать, решает
 * [SalarySetupRules]; ставки и ступени пишет [RatesRepository].
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class SalarySetupRepository(private val db: PersonalkaDatabase, private val rates: RatesRepository, private val clock: () -> Long) {
    private val coaches = db.coachDao()

    /** Версии категории по дате начала. */
    val categories: Flow<List<CategoryVersion>> = coaches.observeCategoryVersions().map { list -> list.map { it.version() } }

    /** Версии схемы выплат по дате начала. */
    val schemes: Flow<List<SavedScheme>> = coaches.observePaySchemeVersions().map { list -> list.map { SavedScheme(it.id, it.payScheme()) } }

    /** «Совместное ведение групп» (3.2): включает раздел «Коллеги» и доли в группах. */
    val coCoaching: Flow<Boolean> = coaches.observeSettings().map { it?.coCoachingEnabled == true }

    /**
     * «Готово» в мастере (5.18): ставки (по правилам формы ставок — внесённое раньше не дублируется), ступени,
     * схема (с выбранного месяца, до него — «Вся сумма») и категория с 1 января ([SalarySetupRules.start]). Категория пишется последней: пока её нет, мастер
     * открывается снова, и прерванная запись повторяется без двойных версий.
     */
    suspend fun finish(draft: SalarySetupDraft, today: LocalDate) {
        val start = SalarySetupRules.start(today)
        draft.rates.forEach { (typeId, byPlace) ->
            if (byPlace.isEmpty()) return@forEach
            val hasRates = db.referenceDao().rateVersions(typeId).isNotEmpty()
            rates.saveRates(typeId, RateRules.defaultFrom(hasRates, today), LinkedHashMap<String?, RateAmounts?>(byPlace), LinkedHashMap<String?, String>(draft.services[typeId].orEmpty()))
        }
        val tiers = db.referenceDao().tierSchemeVersions()
        val month = SalarySetupRules.tierMonth(tiers.isNotEmpty(), today)
        val current = tiers.map { it.scheme() }.let { RateRules.tierDraft(it, month, draft.tiers.ptTypeIds) }
        if (tiers.isEmpty() || current != draft.tiers) rates.saveTiers(month, draft.tiers)
        SalarySetupRules.wizardSchemes(start, draft.scheme, draft.advance, draft.schemeFrom).forEach { (month, scheme) -> saveScheme(month, scheme) }
        saveCategory(start, draft.category)
    }

    /** Категория с даты [from] (5.16): та же, что действует, — ничего; версия той же даты — правится. */
    suspend fun saveCategory(from: LocalDate, category: CoachCategory) {
        val coach = coaches.currentCoach() ?: return
        val now = clock()
        val versions = coaches.categoryVersions()
        when (SalarySetupRules.edit(versions, from, { it.validFrom }) { it.category == category }) {
            VersionEdit.NONE -> Unit
            VersionEdit.REPLACE -> versions.last { it.validFrom == from }.let {
                coaches.upsertCategoryVersion(it.copy(sync = it.sync.touched(now), category = category))
            }
            VersionEdit.ADD -> coaches.upsertCategoryVersion(
                CategoryVersionEntity(Ids.newId(now), SyncFields.new(coach.id, now), from, category),
            )
        }
    }

    /**
     * Схема выплат с месяца [month] (4.11): версия с 1-го числа. Совпала с действующей (вид и аванс) — ничего;
     * версия того же месяца — правится.
     */
    suspend fun saveScheme(month: YearMonth, scheme: PayScheme) {
        val coach = coaches.currentCoach() ?: return
        val now = clock()
        val from = SalarySetupRules.schemeFrom(month)
        val versions = coaches.paySchemeVersions()
        val same: (PaySchemeVersionEntity) -> Boolean = { it.kind == scheme.kind && it.advanceDefault == scheme.advanceDefault }
        when (SalarySetupRules.edit(versions, from, { it.validFrom }, same)) {
            VersionEdit.NONE -> Unit
            VersionEdit.REPLACE -> versions.last { it.validFrom == from }.let {
                coaches.upsertPaySchemeVersion(it.copy(sync = it.sync.touched(now)).with(scheme))
            }
            VersionEdit.ADD -> coaches.upsertPaySchemeVersion(
                PaySchemeVersionEntity(id = Ids.newId(now), sync = SyncFields.new(coach.id, now), validFrom = from, kind = scheme.kind, officialShareBp = 0)
                    .with(scheme),
            )
        }
    }

    /** Выключатель «Совместное ведение групп» в «Профиле» — пишется сразу. */
    suspend fun saveCoCoaching(enabled: Boolean) {
        val coach = coaches.currentCoach() ?: return
        val settings = coaches.settings(coach.id) ?: return
        if (settings.coCoachingEnabled == enabled) return
        coaches.upsertSettings(settings.copy(sync = settings.sync.touched(clock()), coCoachingEnabled = enabled))
    }
}

private fun PaySchemeVersionEntity.with(s: PayScheme) = copy(
    kind = s.kind,
    officialShareBp = s.officialShareBp,
    ndflRateBp = s.ndflBp,
    advanceDay = s.advanceDay,
    advanceDefault = s.advanceDefault,
    salaryDay = s.salaryDay,
)

private fun CategoryVersionEntity.version(): CategoryVersion = CategoryVersion(id, validFrom, category)

/** Версия схемы из базы — во вход выплат (4.11). */
fun PaySchemeVersionEntity.payScheme(): PayScheme =
    PayScheme(validFrom, kind, officialShareBp, ndflRateBp, advanceDay, advanceDefault, salaryDay)
