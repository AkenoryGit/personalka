package com.akenory.personalka.data.settings

import kotlinx.coroutines.flow.flowOf
import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.CoachEntity
import com.akenory.personalka.data.db.entity.CoachSettingsEntity
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.money.ColleagueRepository
import com.akenory.personalka.data.money.RatesRepository
import com.akenory.personalka.data.money.SalaryRepository
import com.akenory.personalka.data.report.ReportRepository
import com.akenory.personalka.data.money.SalarySetupRepository
import com.akenory.personalka.data.reconcile.Report1cRepository
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.cabinet.CabinetLinkRules
import com.akenory.personalka.domain.model.Scope
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.PlaceDraft
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.reference.TypeDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Первый запуск (ТЗ 5.18) и настройки «Профиль», «Места и типы» (5.16): тренер, его места и типы в базе.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class SettingsRepository(
    private val db: PersonalkaDatabase,
    // Открыты ли виды оплаты у аккаунта (решение Дмитрия 27.10.2026): без них зарплата — «100 % 10-го».
    payTypes: Flow<Boolean> = flowOf(true),
    private val clock: () -> Long,
) {
    private val coaches = db.coachDao()
    private val reference = db.referenceDao()

    /** «Ставки и ступени» (5.16, шаг 3.6) — той же базы. */
    val rates: RatesRepository = RatesRepository(db, clock)

    /** Категория, схема выплат и мастер «Настроить зарплату» (5.16, 5.18, шаг 3.6б). */
    val salary: SalarySetupRepository = SalarySetupRepository(db, rates, clock)

    /** «Коллеги» совместного ведения групп (5.16, шаг 3.7г). */
    val colleagues: ColleagueRepository = ColleagueRepository(db, clock)

    /** Месяц и выплаты экрана «Зарплата» (5.12, шаг 3.7). */
    val months: SalaryRepository = SalaryRepository(db, clock, payTypes)

    /** «Отчёты» (5.15, шаг 5.3и): тем же расчётом, что «Зарплата». */
    val reports: ReportRepository = ReportRepository(db, months)

    /** Сверка с отчётом 1С (5.14, ТЗ 8, шаг 4.2а). */
    val report1c: Report1cRepository = Report1cRepository(db, clock)

    /** Тренер телефона; `null` — первый запуск ещё не пройден. */
    val coach: Flow<CoachEntity?> = coaches.observeCurrentCoach()

    /** Места, включая архивные, в порядке тренера. */
    val places: Flow<List<LocationEntity>> = reference.observeLocations()

    /** Типы, включая архивные, в порядке создания. */
    val types: Flow<List<TrainingTypeEntity>> = reference.observeTrainingTypes()

    /**
     * «Готово» первого запуска: тренер, его настройки по умолчанию (3.2), отмеченные места и типы — одной
     * транзакцией. Тренер записывается вместе со всем остальным, поэтому прерванный первый запуск
     * не оставит половину данных: он просто начнётся заново.
     *
     * Ставки, категория, схема выплат и ступени здесь не спрашиваются: они — при первом открытии «Зарплаты».
     */
    suspend fun completeFirstRun(name: CoachName, places: List<PlaceDraft>, types: List<TypeDraft>) {
        val now = clock()
        val coachId = Ids.newId(now)
        val clean = name.trimmed()
        db.withWriteTransaction {
            coaches.upsertCoach(
                CoachEntity(
                    id = coachId,
                    sync = SyncFields.new(coachId, now),
                    lastName = clean.lastName,
                    firstName = clean.firstName,
                    middleName = clean.middleName,
                ),
            )
            coaches.upsertSettings(CoachSettingsEntity(id = Ids.newId(now), sync = SyncFields.new(coachId, now)))
            places.forEachIndexed { index, place ->
                reference.upsertLocation(newPlace(coachId, now, place, sort = index))
            }
            // Типы создаются «по очереди» с разницей в миллисекунду: список типов идёт в порядке создания.
            val ids = types.map { Ids.newId(now) }
            val fallback = ReferenceRules.splitFallback(types)?.let { ids[it] }
            types.forEachIndexed { index, type ->
                reference.upsertTrainingType(newType(ids[index], coachId, now + index, type, fallback))
            }
        }
    }

    /** ФИО в «Профиле». */
    suspend fun saveName(name: CoachName) {
        val coach = coaches.currentCoach() ?: return
        val clean = name.trimmed()
        coaches.upsertCoach(
            coach.copy(
                sync = coach.sync.touched(clock()),
                lastName = clean.lastName,
                firstName = clean.firstName,
                middleName = clean.middleName,
            ),
        )
    }

    /** Общий выключатель кабинетов (3.2, только «С сервером»). */
    val cabinetsEnabled: Flow<Boolean> = coaches.observeSettings().map { it?.cabinetsEnabled == true }

    /**
     * Раздел «Кабинеты» (5.16, шаг 2.13): контакт для родителей и общий выключатель. Без контакта кабинеты
     * не включаются (3.2): пустой контакт выключает их. На сервер настройки уходят синхронизацией.
     */
    suspend fun saveCabinets(enabled: Boolean, contact: String) {
        val coach = coaches.currentCoach() ?: return
        val settings = coaches.settings(coach.id) ?: return
        val clean = CabinetLinkRules.contact(contact)
        val now = clock()
        db.withWriteTransaction {
            if (clean != coach.contactForParents) {
                coaches.upsertCoach(coach.copy(sync = coach.sync.touched(now), contactForParents = clean))
            }
            val on = enabled && clean != null
            if (on != settings.cabinetsEnabled) {
                coaches.upsertSettings(settings.copy(sync = settings.sync.touched(now), cabinetsEnabled = on))
            }
        }
    }

    /** Новое место — последним в списке. Возвращает id. */
    suspend fun addPlace(draft: PlaceDraft): String {
        val coach = coaches.currentCoach() ?: error("Место добавляется после первого запуска")
        val now = clock()
        val sort = (reference.locations().maxOfOrNull { it.sort } ?: -1) + 1
        val place = newPlace(coach.id, now, draft, sort)
        reference.upsertLocation(place)
        return place.id
    }

    /** Название, короткое имя и цвет места, архив. */
    suspend fun updatePlace(id: String, draft: PlaceDraft, archived: Boolean) {
        val place = reference.location(id) ?: return
        reference.upsertLocation(
            place.copy(
                sync = place.sync.touched(clock()),
                name = draft.name.trim(),
                shortName = draft.shortName.trim(),
                color = draft.color,
                archived = archived,
            ),
        )
    }

    /** Новый порядок мест после перетаскивания: пишутся только места, у которых он поменялся. */
    suspend fun reorderPlaces(ids: List<String>) {
        val now = clock()
        val byId = reference.locations().associateBy { it.id }
        db.withWriteTransaction {
            ids.forEachIndexed { index, id ->
                val place = byId[id] ?: return@forEachIndexed
                if (place.sort != index) reference.upsertLocation(place.copy(sync = place.sync.touched(now), sort = index))
            }
        }
    }

    /** Новый тип из конструктора. Сплиту сразу назначается, во что он превращается при одном клиенте. Возвращает id. */
    suspend fun addType(draft: TypeDraft): String {
        val coach = coaches.currentCoach() ?: error("Тип добавляется после первого запуска")
        val now = clock()
        val id = Ids.newId(now)
        val fallback = if (draft.kind == TrainingKind.SPLIT) fallbackFor(draft) else null
        reference.upsertTrainingType(newType(id, coach.id, now, draft, fallback))
        return id
    }

    /** Правка типа и архив. История не пересчитывается: прошлые тренировки хранят свои суммы (3.3, 3.5). */
    suspend fun updateType(id: String, draft: TypeDraft, archived: Boolean) {
        val type = reference.trainingType(id) ?: return
        val fallback = when {
            draft.kind != TrainingKind.SPLIT -> null
            type.splitFallbackTypeId != null -> type.splitFallbackTypeId
            else -> fallbackFor(draft)
        }
        reference.upsertTrainingType(
            draft.toEntity(id, type.sync.touched(clock()), fallback).copy(archived = archived),
        )
    }

    private suspend fun fallbackFor(split: TypeDraft): String? {
        val active = reference.trainingTypes().filter { !it.archived }
        return ReferenceRules.splitFallback(active.map { it.draft() } + split)?.let { active.getOrNull(it)?.id }
    }

    private fun newPlace(coachId: String, now: Long, draft: PlaceDraft, sort: Int) = LocationEntity(
        id = Ids.newId(now),
        sync = SyncFields.new(coachId, now),
        scope = Scope.COACH,
        name = draft.name.trim(),
        shortName = draft.shortName.trim(),
        sort = sort,
        color = draft.color,
    )

    private fun newType(id: String, coachId: String, now: Long, draft: TypeDraft, fallback: String?) =
        draft.toEntity(id, SyncFields.new(coachId, now), if (draft.kind == TrainingKind.SPLIT) fallback else null)
}

/** Тип из базы — в форму конструктора. */
fun TrainingTypeEntity.draft(): TypeDraft = TypeDraft(
    name = name,
    shortName = shortName,
    kind = kind,
    shade = shade,
    durationMin = defaultDurationMin,
    maxClients = maxClients,
    payUnit = payUnit,
    countsTowardPt = countsTowardPt,
    tierDependent = tierDependent,
    diaryPlan = diaryPlan,
    diaryFact = diaryFact,
    diaryBehavior = diaryBehavior,
    dutyColor = dutyColor,
)

/** Место из базы — в форму места. */
fun LocationEntity.draft(): PlaceDraft = PlaceDraft(name = name, shortName = shortName, color = color)

private fun TypeDraft.toEntity(id: String, sync: SyncFields, fallback: String?) = TrainingTypeEntity(
    id = id,
    sync = sync,
    name = name.trim(),
    shortName = shortName.trim(),
    kind = kind,
    shade = shade,
    defaultDurationMin = durationMin,
    maxClients = maxClients,
    payUnit = payUnit,
    countsTowardPt = countsTowardPt,
    tierDependent = tierDependent,
    diaryPlan = diaryPlan,
    diaryFact = diaryFact,
    diaryBehavior = diaryBehavior,
    splitFallbackTypeId = fallback,
    dutyColor = dutyColor?.takeIf { kind == TrainingKind.DUTY },
)
