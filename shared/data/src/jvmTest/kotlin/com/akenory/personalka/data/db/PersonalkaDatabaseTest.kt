package com.akenory.personalka.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.akenory.personalka.domain.model.BackupFrequency
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.WaitStatus
import com.akenory.personalka.domain.money.Versions
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Запись и чтение всех таблиц ТЗ 3.2–3.6 (шаги 1.1, 3.1). База в памяти, SQLite тот же, что в приложении. */
class PersonalkaDatabaseTest {

    private lateinit var db: PersonalkaDatabase

    @BeforeTest
    fun open() {
        db = inMemoryPersonalkaDatabase()
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private val bigPool = Samples.location("ББ", "Большой бассейн", 1)
    private val kidsPool = Samples.location("ДБ", "Детский бассейн", 2)
    private val pt = Samples.personalType()
    private val groupType = Samples.groupType()

    @Test
    fun coachAndSettingsAreReadBack(): Unit = runBlocking {
        val dao = db.coachDao()
        val coach = Samples.coach()
        val settings = Samples.settings()
        val none = Samples.payScheme(LocalDate(2026, 1, 1), PaySchemeKind.NONE)
        val half = Samples.payScheme(LocalDate(2026, 3, 1), PaySchemeKind.HALF_OFFICIAL)
        val category = Samples.category(LocalDate(2026, 1, 1))

        assertNull(dao.currentCoach())
        dao.upsertCoach(coach)
        dao.upsertSettings(settings)
        dao.upsertPaySchemeVersion(half)
        dao.upsertPaySchemeVersion(none)
        dao.upsertCategoryVersion(category)

        assertEquals(coach, dao.coach(coach.id))
        assertEquals(coach, dao.currentCoach())
        assertEquals(settings, dao.settings(Samples.COACH_ID))
        assertEquals(listOf(none, half), dao.paySchemeVersions())
        assertEquals(listOf(category), dao.categoryVersions())
    }

    @Test
    fun settingsDefaultsFollowTz(): Unit = runBlocking {
        db.coachDao().upsertSettings(Samples.settings())
        val s = db.coachDao().settings(Samples.COACH_ID)!!
        assertEquals(60, s.cancelNoticeMinutes)
        assertEquals(LocalTime(21, 0), s.digestTime)
        assertEquals(14, s.followupDays)
        assertEquals(LocalTime(7, 0), s.workDayStart)
        assertEquals(LocalTime(23, 0), s.workDayEnd)
        assertEquals(15, s.dragStepMinutes)
        assertEquals(8, s.seriesHorizonWeeks)
        assertEquals(4, s.upcomingInCard)
        assertEquals(3, s.weekViewDays)
        assertEquals(BackupFrequency.DAILY, s.backupFrequency)
        assertTrue(s.autoDonePersonal && s.diaryEnabled && !s.cabinetsEnabled && !s.instantDiary)
    }

    @Test
    fun referencesAreReadBack(): Unit = runBlocking {
        val dao = db.referenceDao()
        val rate = Samples.ptRate(pt.id, bigPool.id)
        val dutyRate = rate.copy(id = Samples.id(), locationId = null, validFrom = LocalDate(2025, 1, 1))
        val club = Samples.clubRate()
        val tiers = Samples.tierScheme(listOf(pt.id, groupType.id))

        dao.upsertLocation(kidsPool)
        dao.upsertLocation(bigPool)
        dao.upsertTrainingType(pt)
        dao.upsertTrainingType(groupType)
        dao.upsertRateVersion(rate)
        dao.upsertRateVersion(dutyRate)
        dao.upsertClubRate(club)
        dao.upsertTierSchemeVersion(tiers)

        assertEquals(listOf(bigPool, kidsPool), dao.observeLocations().first())
        assertEquals(pt, dao.trainingType(pt.id))
        assertEquals(setOf(pt, groupType), dao.trainingTypes().toSet())
        assertEquals(listOf(dutyRate, rate), dao.rateVersions(pt.id))
        assertEquals(listOf(club), dao.clubRates())
        assertEquals(listOf(tiers), dao.tierSchemeVersions())
        assertEquals(listOf(pt.id, groupType.id), dao.tierSchemeVersions().single().ptTypeIds)
    }

    @Test
    fun scheduleIsReadBack(): Unit = runBlocking {
        val dao = db.scheduleDao()
        val block = Samples.block(kidsPool.id)
        val oneOff = block.copy(
            id = Samples.id(),
            weekdays = emptySet(),
            date = LocalDate(2026, 10, 24),
            validFrom = LocalDate(2026, 10, 24),
            skipDates = emptyList(),
        )
        val client = Samples.client("Соколова", "Мия")
        val series = Samples.series(pt.id, bigPool.id)
        val link = Samples.seriesClient(series.id, client.id)

        dao.upsertBlock(block)
        dao.upsertBlock(oneOff)
        dao.upsertSeries(series)
        dao.upsertSeriesClient(link)

        assertEquals(block, dao.block(block.id))
        assertEquals(oneOff, dao.block(oneOff.id))
        assertEquals(setOf(block, oneOff), dao.blocksAt(kidsPool.id).toSet())
        assertEquals(emptyList(), dao.blocksAt(bigPool.id))
        assertEquals(series, dao.series(series.id))
        assertEquals(listOf(link), dao.seriesClients(series.id))
        assertEquals(listOf(series), dao.seriesOfClient(client.id))
    }

    @Test
    fun clientsGroupsWaitListAndRelationsAreReadBack(): Unit = runBlocking {
        val dao = db.clientDao()
        val child = Samples.client("Соколова", "Мия")
        val parent = Samples.client("Соколова", "Анна").copy(isAdult = true, birthDate = null, cabinetEnabled = false)
        val archived = Samples.client("Ветрова", "Лея").copy(status = ClientStatus.ARCHIVED)
        val group = Samples.group(groupType.id, bigPool.id)
        val member = Samples.member(group.id, child.id, LocalDate(2026, 9, 1))
        val wait = Samples.waitEntry(child.id, pt.id)
        val relation = Samples.relation(parent.id, child.id)

        listOf(child, parent, archived).forEach { dao.upsertClient(it) }
        dao.upsertGroup(group)
        dao.upsertGroupMember(member)
        dao.upsertWaitEntry(wait)
        dao.upsertRelation(relation)

        assertEquals(child, dao.client(child.id))
        assertEquals(setOf(child, parent), dao.observeClients(ClientStatus.ACTIVE).first().toSet())
        assertEquals(listOf(archived), dao.observeClients(ClientStatus.ARCHIVED).first())
        assertEquals(group, dao.group(group.id))
        assertEquals(listOf(member), dao.membersOn(group.id, LocalDate(2026, 10, 20)))
        assertEquals(wait, dao.waitEntry(wait.id))
        assertEquals(listOf(wait), dao.waitEntries(WaitStatus.WAITING))
        assertEquals(emptyList(), dao.waitEntries(WaitStatus.BOOKED))
        // Одна запись на пару — видна из обеих карточек.
        assertEquals(listOf(relation), dao.relationsOf(child.id))
        assertEquals(listOf(relation), dao.relationsOf(parent.id))
    }

    @Test
    fun trainingsParticipationResultsAndSptAreReadBack(): Unit = runBlocking {
        val dao = db.trainingDao()
        val client = Samples.client("Соколова", "Мия")
        val group = Samples.group(groupType.id, bigPool.id)
        val training = Samples.doneTraining(groupType.id, bigPool.id, group.id)
        val participation = Samples.participation(training.id, client.id)
        val result = Samples.result(client.id)
        val spt = Samples.sptCase(client.id, training.id)

        dao.upsertTraining(training)
        dao.upsertParticipation(participation)
        dao.upsertResult(result)
        dao.upsertSptCase(spt)

        assertEquals(training, dao.training(training.id))
        assertEquals(participation, dao.participation(participation.id))
        assertEquals(listOf(participation), dao.participationsOf(training.id))
        assertEquals(listOf(result), dao.resultsOf(client.id))
        assertEquals(listOf(spt), dao.sptCasesOf(client.id))
    }

    @Test
    fun moneyIsReadBack(): Unit = runBlocking {
        val dao = db.moneyDao()
        val october = YearMonth(2026, 10)
        val lena = Samples.colleague("Лена")
        val lenaHalf = Samples.colleagueScheme(lena.id, LocalDate(2026, 3, 1), PaySchemeKind.HALF_OFFICIAL)
        val lenaNone = Samples.colleagueScheme(lena.id, LocalDate(2026, 1, 1), PaySchemeKind.NONE)
        val record = Samples.monthRecord(october)
        val bonus = Samples.adjustment(october, 500_000L, "Премия за соревнования")
        val fine = Samples.adjustment(october, -100_000L, "Удержание за форму", inClubAccrual = false)
        val archived = Samples.archivedMonth(YearMonth(2026, 9))

        dao.upsertColleague(lena)
        dao.upsertColleagueScheme(lenaHalf)
        dao.upsertColleagueScheme(lenaNone)
        dao.upsertMonthRecord(record)
        dao.upsertAdjustment(bonus)
        dao.upsertAdjustment(fine)
        dao.upsertAdjustment(Samples.adjustment(YearMonth(2026, 11), 1L, "Другой месяц"))
        dao.upsertArchivedMonth(archived)

        assertEquals(lena, dao.colleague(lena.id))
        assertEquals(listOf(lena), dao.colleagues())
        assertEquals(listOf(lenaNone, lenaHalf), dao.colleagueSchemes(), "По дате начала")
        assertEquals(record, dao.monthRecord(october))
        assertNull(dao.monthRecord(YearMonth(2026, 11)))
        assertEquals(listOf(bonus, fine), dao.adjustments(october), "Удержание — отрицательной суммой, только своего месяца")
        assertEquals(listOf(archived), dao.archivedMonths())
        assertEquals(960, dao.archivedMonths().single().dutyMinutes)

        // Два телефона без связи завели данные октября каждый: читается последняя изменённая, база не падает.
        val other = record.copy(id = Samples.id(), planAmount = 13_000_000L, sync = record.sync.touched(Samples.NOW + 60_000))
        dao.upsertMonthRecord(other)
        assertEquals(13_000_000L, dao.monthRecord(october)?.planAmount)
        assertEquals(other, dao.observeMonthRecord(october).first())
    }

    @Test
    fun rateKeepsClubServiceAndAllRatesAreRead(): Unit = runBlocking {
        val dao = db.referenceDao()
        val bb = Samples.ptRate(pt.id, bigPool.id).copy(clubService = "Персональная")
        val db2 = Samples.ptRate(pt.id, kidsPool.id).copy(amountT1 = 83_000L, amountT2 = null, amountT3 = null)
        val later = bb.copy(id = Samples.id(), validFrom = LocalDate(2026, 11, 1), amountT1 = 96_000L)
        listOf(later, bb, db2).forEach { dao.upsertRateVersion(it) }

        assertEquals("Персональная", dao.rateVersions(pt.id).first { it.id == bb.id }.clubService)
        val all = dao.allRateVersions()
        assertEquals(LocalDate(2026, 11, 1), all.last().validFrom, "По дате начала")
        fun rate(place: String, date: LocalDate) =
            Versions.rateFor(all, pt.id, place, date, { it.typeId }, { it.locationId }, { it.validFrom })?.amountT1
        assertEquals(92_000L, rate(bigPool.id, LocalDate(2026, 10, 31)))
        assertEquals(96_000L, rate(bigPool.id, LocalDate(2026, 11, 1)))
        assertEquals(83_000L, rate(kidsPool.id, LocalDate(2026, 11, 1)))
    }

    @Test
    fun trainingsAreSelectedAndSortedByClubLocalTime(): Unit = runBlocking {
        val dao = db.trainingDao()
        fun at(month: Int, day: Int, hour: Int, minute: Int = 0) =
            Samples.training(pt.id, bigPool.id, LocalDateTime(2026, month, day, hour, minute))

        val lateBefore = at(10, 19, 23)
        val early = at(10, 20, 7)
        val nineFive = at(10, 20, 9, 5)
        val ten = at(10, 20, 10)
        val evening = at(10, 20, 22, 30)
        val nextDay = at(10, 21, 7)
        dao.upsertTrainings(listOf(ten, nextDay, evening, lateBefore, nineFive, early))

        val day = dao.trainingsBetween(LocalDateTime(2026, 10, 20, 0, 0), LocalDateTime(2026, 10, 21, 0, 0))
        assertEquals(listOf(early, nineFive, ten, evening), day)

        // Переход месяца: 31.10 раньше 01.11, хотя «31» больше «01».
        val endOfMonth = at(10, 31, 20)
        val firstOfNext = at(11, 1, 7)
        dao.upsertTrainings(listOf(firstOfNext, endOfMonth))
        val week = dao.trainingsBetween(LocalDateTime(2026, 10, 31, 0, 0), LocalDateTime(2026, 11, 2, 0, 0))
        assertEquals(listOf(endOfMonth, firstOfNext), week)
    }

    @Test
    fun observedDayUpdatesAfterChange(): Unit = runBlocking {
        val dao = db.trainingDao()
        val from = LocalDateTime(2026, 10, 20, 0, 0)
        val to = LocalDateTime(2026, 10, 21, 0, 0)
        assertEquals(emptyList(), dao.observeTrainingsBetween(from, to).first())

        val training = Samples.training(pt.id, bigPool.id, LocalDateTime(2026, 10, 20, 17, 0))
        dao.upsertTraining(training)
        assertEquals(listOf(training), dao.observeTrainingsBetween(from, to).first())
    }

    @Test
    fun softDeletedRecordIsHiddenFromListsButReadableById(): Unit = runBlocking {
        val dao = db.trainingDao()
        val training = Samples.training(pt.id, bigPool.id, LocalDateTime(2026, 10, 20, 17, 0))
        dao.upsertTraining(training)

        val later = Samples.NOW + 60_000L
        val deleted = training.copy(sync = training.sync.deleted(later))
        dao.upsertTraining(deleted)

        val read = dao.training(training.id)!!
        assertEquals(later, read.sync.deletedAt)
        assertEquals(later, read.sync.updatedAt)
        assertEquals(2, read.sync.version)
        assertEquals(
            emptyList(),
            dao.trainingsBetween(LocalDateTime(2026, 10, 20, 0, 0), LocalDateTime(2026, 10, 21, 0, 0)),
        )
    }

    @Test
    fun changeRaisesVersionAndKeepsCreatedAt(): Unit = runBlocking {
        val dao = db.clientDao()
        val client = Samples.client("Соколова", "Мия")
        dao.upsertClient(client)
        val later = Samples.NOW + 5_000L
        dao.upsertClient(client.copy(firstName = "Мирослава", sync = client.sync.touched(later)))

        val read = dao.client(client.id)!!
        assertEquals("Мирослава", read.firstName)
        assertEquals(Samples.NOW, read.sync.createdAt)
        assertEquals(later, read.sync.updatedAt)
        assertEquals(2, read.sync.version)
    }

    @Test
    fun groupMembersAreTakenOnDate(): Unit = runBlocking {
        val dao = db.clientDao()
        val group = Samples.group(groupType.id, bigPool.id)
        val left = Samples.member(group.id, Samples.id(), LocalDate(2026, 9, 1), LocalDate(2026, 10, 15))
        val joined = Samples.member(group.id, Samples.id(), LocalDate(2026, 10, 16))
        val always = Samples.member(group.id, Samples.id(), LocalDate(2026, 9, 1))
        listOf(left, joined, always).forEach { dao.upsertGroupMember(it) }

        suspend fun on(day: Int) = dao.membersOn(group.id, LocalDate(2026, 10, day)).map { it.id }.toSet()
        assertEquals(setOf(left.id, always.id), on(10))
        assertEquals(setOf(left.id, always.id), on(15))
        assertEquals(setOf(joined.id, always.id), on(16))
        assertEquals(setOf(joined.id, always.id), on(20))
    }

    @Test
    fun clientTakesPartInTrainingOnlyOnce(): Unit = runBlocking {
        val dao = db.trainingDao()
        val training = Samples.training(pt.id, bigPool.id, LocalDateTime(2026, 10, 20, 17, 0))
        val clientId = Samples.id()
        val first = Samples.participation(training.id, clientId)
        dao.upsertParticipation(first)

        // Вторая запись на ту же пару «тренировка — клиент» не появляется: пара уникальна (ТЗ 3.5).
        runCatching { dao.upsertParticipation(Samples.participation(training.id, clientId)) }
        assertEquals(listOf(first.id), dao.participationsOf(training.id).map { it.id })
    }

    @Test
    fun fileDatabaseReopensAndStoresReadableValues(): Unit = runBlocking {
        val file = File.createTempFile("personalka", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            val series = Samples.series(pt.id, bigPool.id)
            val rate = Samples.ptRate(pt.id, bigPool.id)
            val wait = Samples.waitEntry(Samples.id(), pt.id)
            val training = Samples.training(pt.id, kidsPool.id, LocalDateTime(2026, 10, 20, 17, 0))

            personalkaDatabase(path).let { first ->
                first.scheduleDao().upsertSeries(series)
                first.referenceDao().upsertRateVersion(rate)
                first.clientDao().upsertWaitEntry(wait)
                first.trainingDao().upsertTraining(training)
                first.close()
            }

            personalkaDatabase(path).let { again ->
                assertEquals(series, again.scheduleDao().series(series.id))
                assertEquals(training, again.trainingDao().training(training.id))
                again.close()
            }

            // Так данные видны в файле копии и утилите импорта: строки и целые числа, без двоичных форматов.
            BundledSQLiteDriver().open(path).useConnection { raw ->
                assertEquals("PLANNED", raw.text("SELECT status FROM training"))
                assertEquals("2026-10-20T17:00", raw.text("SELECT start FROM training"))
                assertEquals("SELF", raw.text("SELECT conductedBy FROM training"))
                assertEquals("2,4", raw.text("SELECT weekdays FROM series"))
                assertEquals("13:30", raw.text("SELECT startTime FROM series"))
                assertEquals("2026-11-03", raw.text("SELECT skipDates FROM series"))
                assertEquals(92_000L, raw.long("SELECT amountT1 FROM rate_version"))
                assertEquals("07:00-12:00,18:00-21:00", raw.text("SELECT timeRanges FROM wait_entry"))
                assertEquals("1,3", raw.text("SELECT days FROM wait_entry"))
                assertEquals(PersonalkaDatabase.VERSION.toLong(), raw.long("PRAGMA user_version"))
            }
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach { File(path + it).delete() }
        }
    }

    private inline fun <T> SQLiteConnection.useConnection(block: (SQLiteConnection) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private fun SQLiteConnection.text(sql: String): String {
        val statement = prepare(sql)
        try {
            check(statement.step()) { "Пусто: $sql" }
            return statement.getText(0)
        } finally {
            statement.close()
        }
    }

    private fun SQLiteConnection.long(sql: String): Long {
        val statement = prepare(sql)
        try {
            check(statement.step()) { "Пусто: $sql" }
            return statement.getLong(0)
        } finally {
            statement.close()
        }
    }
}
