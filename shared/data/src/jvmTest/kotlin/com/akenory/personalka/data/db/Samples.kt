package com.akenory.personalka.data.db

import com.akenory.personalka.data.db.entity.CategoryVersionEntity
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.ClientRelationEntity
import com.akenory.personalka.data.db.entity.ClubRateGridEntity
import com.akenory.personalka.data.db.entity.CoachEntity
import com.akenory.personalka.data.db.entity.CoachSettingsEntity
import com.akenory.personalka.data.db.entity.GroupEntity
import com.akenory.personalka.data.db.entity.GroupMemberEntity
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.PaySchemeVersionEntity
import com.akenory.personalka.data.db.entity.RateVersionEntity
import com.akenory.personalka.data.db.entity.ResultEntity
import com.akenory.personalka.data.db.entity.ScheduleBlockEntity
import com.akenory.personalka.data.db.entity.SeriesClientEntity
import com.akenory.personalka.data.db.entity.SeriesEntity
import com.akenory.personalka.data.db.entity.SptCaseEntity
import com.akenory.personalka.data.db.entity.TierSchemeVersionEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.db.entity.WaitEntryEntity
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.ConsentSource
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.RecordSource
import com.akenory.personalka.domain.model.RelationKind
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.Scope
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.model.TimeRange
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** Придуманные данные для тестов базы (реальных имён клиентов в репозитории нет). */
object Samples {
    /** 20.10.2026 10:40 UTC = 15:40 по клубу, «сейчас» из макетов. */
    const val NOW = 1_792_492_800_000L

    private var counter = 0L

    fun id(): String = Ids.newId(NOW + counter++)

    fun sync(): SyncFields = SyncFields.new(coachId = COACH_ID, now = NOW)

    val COACH_ID: String = Ids.newId(NOW - 86_400_000L)

    fun coach() = CoachEntity(
        id = COACH_ID,
        sync = sync(),
        lastName = "Тренеров",
        firstName = "Иван",
        middleName = "Петрович",
        contactForParents = "+7 900 000-00-00",
    )

    fun settings() = CoachSettingsEntity(id = id(), sync = sync())

    fun category(from: LocalDate) =
        CategoryVersionEntity(id = id(), sync = sync(), validFrom = from, category = CoachCategory.MASTER)

    fun payScheme(from: LocalDate, kind: PaySchemeKind) = PaySchemeVersionEntity(
        id = id(),
        sync = sync(),
        validFrom = from,
        kind = kind,
        officialShareBp = if (kind == PaySchemeKind.HALF_OFFICIAL) 5000 else 0,
        advanceDay = if (kind == PaySchemeKind.HALF_OFFICIAL) 25 else null,
        advanceDefault = if (kind == PaySchemeKind.HALF_OFFICIAL) 1_354_650L else null,
    )

    fun location(short: String, name: String, sort: Int) =
        LocationEntity(id = id(), sync = sync(), scope = Scope.CLUB, name = name, shortName = short, sort = sort)

    fun personalType() = TrainingTypeEntity(
        id = id(),
        sync = sync(),
        name = "Персональная",
        shortName = "ПТ",
        kind = TrainingKind.PERSONAL,
        color = "blue",
        maxClients = 1,
        payUnit = PayUnit.PER_SESSION,
        countsTowardPt = true,
        tierDependent = true,
    )

    fun groupType() = TrainingTypeEntity(
        id = id(),
        sync = sync(),
        name = "Группа",
        shortName = "Группа",
        kind = TrainingKind.GROUP,
        color = "green",
        maxClients = null,
        payUnit = PayUnit.PER_CLIENT,
        countsTowardPt = false,
        tierDependent = false,
        diaryBehavior = false,
    )

    /** ПТ в ББ у Мастер тренера: 920 / 960 / 1 000 ₽. */
    fun ptRate(typeId: String, locationId: String) = RateVersionEntity(
        id = id(),
        sync = sync(),
        typeId = typeId,
        locationId = locationId,
        validFrom = LocalDate(2026, 1, 1),
        amountT1 = 92_000L,
        amountT2 = 96_000L,
        amountT3 = 100_000L,
    )

    fun clubRate() = ClubRateGridEntity(
        id = id(),
        sync = sync(),
        validFrom = LocalDate(2026, 1, 1),
        category = CoachCategory.MASTER,
        service = "Персональная",
        locationShort = "ББ",
        unit = PayUnit.PER_SESSION,
        amountT1 = 92_000L,
        amountT2 = 96_000L,
        amountT3 = 100_000L,
    )

    fun tierScheme(ptTypeIds: List<String>) =
        TierSchemeVersionEntity(id = id(), sync = sync(), validFrom = LocalDate(2026, 10, 1), ptTypeIds = ptTypeIds)

    /** Запрет ДБ вт и чт 17:00–18:00, в одну дату группы нет. */
    fun block(locationId: String) = ScheduleBlockEntity(
        id = id(),
        sync = sync(),
        scope = Scope.CLUB,
        locationId = locationId,
        reason = "Групповая тренировка",
        weekdays = setOf(DayOfWeek.THURSDAY, DayOfWeek.TUESDAY),
        startTime = LocalTime(17, 0),
        endTime = LocalTime(18, 0),
        validFrom = LocalDate(2026, 9, 1),
        skipDates = listOf(LocalDate(2026, 10, 22), LocalDate(2026, 11, 5)),
    )

    /** Серия вт и чт 13:30, 50 минут, бессрочно. */
    fun series(typeId: String, locationId: String) = SeriesEntity(
        id = id(),
        sync = sync(),
        typeId = typeId,
        locationId = locationId,
        weekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
        startTime = LocalTime(13, 30),
        durationMin = 50,
        validFrom = LocalDate(2026, 9, 1),
        generatedUntil = LocalDate(2026, 12, 15),
        skipDates = listOf(LocalDate(2026, 11, 3)),
    )

    fun seriesClient(seriesId: String, clientId: String) =
        SeriesClientEntity(id = id(), sync = sync(), seriesId = seriesId, clientId = clientId)

    fun client(lastName: String, firstName: String) = ClientEntity(
        id = id(),
        sync = sync(),
        lastName = lastName,
        firstName = firstName,
        birthDate = LocalDate(2016, 5, 14),
        sex = Sex.FEMALE,
        contactName = "Мама",
        contactPhone = "+7 900 000-00-01",
        mediaUrl = "https://disk.yandex.ru/d/example",
        privateNote = "Не говорить про соревнования до весны",
        cabinetEnabled = true,
        consentAt = NOW - 3_600_000L,
        consentSource = ConsentSource.PAPER,
        followupPausedUntil = LocalDate(2026, 11, 1),
        pendingPlan = "Старт с тумбы",
        source = RecordSource.IMPORT,
    )

    fun group(typeId: String, locationId: String) = GroupEntity(
        id = id(),
        sync = sync(),
        name = "Сб 9:00 ББ",
        typeId = typeId,
        locationId = locationId,
        defaultConductedBy = ConductedBy.SELF_AND_COLLEAGUE,
        defaultColleagueId = id(),
    )

    fun member(groupId: String, clientId: String, from: LocalDate, to: LocalDate? = null) =
        GroupMemberEntity(id = id(), sync = sync(), groupId = groupId, clientId = clientId, fromDate = from, toDate = to)

    fun training(typeId: String, locationId: String, start: LocalDateTime) = TrainingEntity(
        id = id(),
        sync = sync(),
        typeId = typeId,
        locationId = locationId,
        start = start,
        durationMin = 50,
    )

    fun doneTraining(typeId: String, locationId: String, groupId: String) = TrainingEntity(
        id = id(),
        sync = sync(),
        typeId = typeId,
        locationId = locationId,
        start = LocalDateTime(2026, 10, 17, 9, 0),
        durationMin = 60,
        groupId = groupId,
        status = TrainingStatus.DONE,
        plan = "Кроль на груди 8 × 25 м",
        fact = "Проплыли 6 × 25 м, дыхание на три гребка",
        owner = Owner.COLLEAGUE,
        ownerColleagueId = id(),
        conductedBy = ConductedBy.SELF_AND_COLLEAGUE,
        colleagueId = id(),
        overRestriction = true,
        planCarriedFromId = id(),
        planCarriedText = "Повороты",
        doneAt = NOW - 1_000L,
    )

    fun participation(trainingId: String, clientId: String) = ParticipationEntity(
        id = id(),
        sync = sync(),
        trainingId = trainingId,
        clientId = clientId,
        status = ParticipationStatus.CANCELLED_CHARGED,
        behavior = "Внимательна, слушает задания",
        cancelReason = "Не сможет",
        notifiedAt = LocalDateTime(2026, 10, 20, 16, 20),
        chargeOverridden = true,
    )

    /** 50 м вольным стилем за 39,62 с в бассейне 25 м. */
    fun result(clientId: String) = ResultEntity(
        id = id(),
        sync = sync(),
        clientId = clientId,
        date = LocalDate(2026, 10, 11),
        style = SwimStyle.FREESTYLE,
        distanceM = 50,
        poolM = 25,
        timeCs = 3962,
        kind = ResultKind.COMPETITION,
        eventName = "Первенство города",
    )

    fun sptCase(clientId: String, trainingId: String) = SptCaseEntity(
        id = id(),
        sync = sync(),
        clientId = clientId,
        sptTrainingId = trainingId,
        sptDate = LocalDate(2026, 10, 8),
        windowUntil = LocalDate(2026, 11, 8),
        purchaseDate = LocalDate(2026, 10, 15),
        status = SptCaseStatus.CLOSED,
    )

    fun waitEntry(clientId: String, typeId: String) = WaitEntryEntity(
        id = id(),
        sync = sync(),
        clientId = clientId,
        wantsSeries = true,
        wantsOneOff = true,
        days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        timeRanges = listOf(
            TimeRange(LocalTime(7, 0), LocalTime(12, 0)),
            TimeRange(LocalTime(18, 0), LocalTime(21, 0)),
        ),
        typeId = typeId,
        until = LocalDate(2026, 11, 30),
        note = "Лучше утром",
        matchOneOff = true,
        matchSlot = LocalTime(8, 0),
        matchDate = LocalDate(2026, 10, 26),
    )

    fun relation(parentId: String, childId: String) = ClientRelationEntity(
        id = id(),
        sync = sync(),
        clientAId = parentId,
        clientBId = childId,
        kind = RelationKind.PARENT,
    )
}
