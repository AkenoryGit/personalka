package com.akenory.personalka.data.db.entity

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.Scope
import com.akenory.personalka.domain.model.TrainingKind
import kotlinx.datetime.LocalDate

// ТЗ 3.3. Справочники и ставки. Ставки — копейки (920 ₽ = 92 000).

/** Место: «Большой бассейн», «ББ». */
@Entity(tableName = "location", indices = [Index("coachId")])
data class LocationEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val scope: Scope,
    val name: String,
    /** До 4 символов: «ББ», «ДБ», «Зал». */
    val shortName: String,
    val sort: Int = 0,
    val archived: Boolean = false,
)

/** Тип тренировки: «Персональная», «ПТ». */
@Entity(tableName = "training_type", indices = [Index("coachId")])
data class TrainingTypeEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val name: String,
    val shortName: String,
    val kind: TrainingKind,
    /** Ключ цвета из палитры 16 цветов (`blue`, `green`…), не ARGB: у каждой темы свои тона. */
    val color: String,
    val defaultDurationMin: Int = 50,
    /** 1 для PERSONAL и SPT, 2 для SPLIT, пусто для GROUP, 0 для DUTY. */
    val maxClients: Int?,
    val payUnit: PayUnit,
    /** Отметка «Считать как ПТ» при создании; действующий список — в [TierSchemeVersionEntity.ptTypeIds]. */
    val countsTowardPt: Boolean,
    val tierDependent: Boolean,
    val diaryPlan: Boolean = true,
    val diaryFact: Boolean = true,
    /** У групп поведение выключено. */
    val diaryBehavior: Boolean = true,
    /** Только SPLIT: в какой тип превращается при одном клиенте. */
    val splitFallbackTypeId: String? = null,
    val archived: Boolean = false,
)

/** Ставка тренера, версия с даты. Пустое место — для любого места (дежурство). */
@Entity(tableName = "rate_version", indices = [Index("typeId", "locationId", "validFrom")])
data class RateVersionEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val typeId: String,
    val locationId: String?,
    val validFrom: LocalDate,
    /** Ступень 1 или единственная ставка, копейки. */
    val amountT1: Long,
    /** Ступени 2 и 3; пусто, если тип не зависит от ступени. */
    val amountT2: Long? = null,
    val amountT3: Long? = null,
    /** Только SPT: доплата за закрытие (140 ₽). */
    val sptCloseBonus: Long? = null,
)

/** Строка сетки ставок клуба (данные клуба, scope = CLUB): шаблон для ставок тренера. */
@Entity(tableName = "club_rate_grid", indices = [Index("validFrom", "category")])
data class ClubRateGridEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val validFrom: LocalDate,
    val category: CoachCategory,
    /** Услуга: «Персональная», «Сплит», «Грудничковое плавание»… */
    val service: String,
    val locationShort: String,
    val unit: PayUnit,
    val amountT1: Long,
    val amountT2: Long? = null,
    val amountT3: Long? = null,
)

/** Ступени (бонусы), версия с 1-го числа месяца. Выключенная схема — всегда ступень 1. */
@Entity(tableName = "tier_scheme_version", indices = [Index("coachId", "validFrom")])
data class TierSchemeVersionEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val validFrom: LocalDate,
    val enabled: Boolean = true,
    val t2From: Int = 101,
    val t3From: Int = 151,
    /** Типы, которые считаются как ПТ, — по версиям, чтобы правка не пересчитывала прошлые месяцы. */
    val ptTypeIds: List<String>,
)
