package com.akenory.personalka.data.db.entity

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.model.BackupFrequency
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.model.CoachMode
import com.akenory.personalka.domain.model.CoachRole
import com.akenory.personalka.domain.model.PaySchemeKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// ТЗ 3.2. Тренер и настройки. Деньги — копейки, длительности — минуты (ТЗ 3.1).

/** Тренер. Его `coachId` в служебных полях равен [id]. */
@Entity(tableName = "coach")
data class CoachEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val lastName: String,
    val firstName: String,
    val middleName: String? = null,
    /** Телефон или почта для обращений родителей; без него кабинеты не включаются. */
    val contactForParents: String? = null,
    /** ФИО тренера в заголовке отчёта 1С. */
    val name1c: String? = null,
    val mode: CoachMode = CoachMode.LOCAL,
    val role: CoachRole = CoachRole.COACH,
    val serverUrl: String? = null,
    /** Логин, только в режиме «С сервером». */
    val email: String? = null,
    val timezone: String = ClubTime.DEFAULT_ZONE_ID,
)

/** Настройки тренера, одна запись на тренера. Значения по умолчанию — из ТЗ 3.2. */
@Entity(tableName = "coach_settings", indices = [Index("coachId", unique = true)])
data class CoachSettingsEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    /** Меньше этого до начала — отмена со списанием. */
    val cancelNoticeMinutes: Int = 60,
    /** Выключен у нового тренера: кому нужен, включит сам (решение Дмитрия 26.10.2026). */
    val diaryEnabled: Boolean = false,
    val digestEnabled: Boolean = true,
    val digestTime: LocalTime = LocalTime(21, 0),
    val instantDiary: Boolean = false,
    val instantGroup: Boolean = false,
    val followupEnabled: Boolean = true,
    val followupDays: Int = 14,
    val cabinetsEnabled: Boolean = false,
    val coCoachingEnabled: Boolean = false,
    /** Часы сетки календаря и рамки поиска слотов; по умолчанию — часы клуба. */
    val workDayStart: LocalTime = LocalTime(7, 0),
    val workDayEnd: LocalTime = LocalTime(23, 0),
    /** Шаг перетаскивания: 5, 10, 15 или 30 минут. */
    val dragStepMinutes: Int = 15,
    val seriesHorizonWeeks: Int = 8,
    val upcomingInCard: Int = 4,
    val autoDonePersonal: Boolean = true,
    /** Дней в разложенном виде: 3, 5 или 7. */
    val weekViewDays: Int = 3,
    val backupFolderUri: String? = null,
    val backupFrequency: BackupFrequency = BackupFrequency.DAILY,
)

/** Категория тренера, версия с даты. */
@Entity(tableName = "category_version", indices = [Index("coachId", "validFrom")])
data class CategoryVersionEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val validFrom: LocalDate,
    val category: CoachCategory,
)

/**
 * Схема выплат, версия с 1-го числа месяца. Доли — в десятитысячных, чтобы не терять точность:
 * 0,5 → 5000, 0,13 → 1300.
 */
@Entity(tableName = "pay_scheme_version", indices = [Index("coachId", "validFrom")])
data class PaySchemeVersionEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val validFrom: LocalDate,
    val kind: PaySchemeKind,
    /** Доля официальной части в десятитысячных: 5000 / 10000 / 0. */
    val officialShareBp: Int,
    /** НДФЛ в десятитысячных: 1300 = 13 %. */
    val ndflRateBp: Int = 1300,
    /** День аванса (25); нет для IP и NONE. */
    val advanceDay: Int? = null,
    /** Ожидаемый аванс, копейки. */
    val advanceDefault: Long? = null,
    /** День выплаты за прошлый месяц. */
    val salaryDay: Int = 10,
)
