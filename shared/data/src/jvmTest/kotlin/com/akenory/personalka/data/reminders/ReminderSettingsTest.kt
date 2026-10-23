package com.akenory.personalka.data.reminders

import com.akenory.personalka.data.calendar.CalendarRepository
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** «Настройки → Дневник и напоминания» (шаг 2.5, ТЗ 5.16, 5.19). Имена придуманы. */
class ReminderSettingsTest {
    private lateinit var db: PersonalkaDatabase
    private var millis = ClubTime.toMillis(LocalDateTime(2026, 10, 20, 15, 40))
    private lateinit var repo: ReminderSettingsRepository

    @BeforeTest
    fun open() = runBlocking {
        db = inMemoryPersonalkaDatabase()
        val settings = SettingsRepository(db) { millis }
        settings.completeFirstRun(CoachName("Соколов", "Андрей"), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes)
        repo = CalendarRepository(db) { millis }.reminders
    }

    @AfterTest
    fun close() {
        db.close()
    }

    @Test
    fun defaultsFromTz() = runBlocking {
        // Дневник, автоотметка, сводка в 21:00 и «без записи» 14 дней — включены; мгновенные — выключены (5.19).
        assertEquals(DiaryPrefs(), repo.prefs.first())
        assertEquals(LocalTime(21, 0), repo.prefs.first().digestTime)
    }

    @Test
    fun everyValueSavedAtOnce() = runBlocking {
        val before = db.coachDao().observeSettings().first()!!.sync.version
        millis += 60_000
        repo.saveDiary(false)
        repo.saveAutoDone(false)
        repo.saveDigest(false)
        repo.saveDigestTime(LocalTime(22, 30))
        repo.saveInstantDiary(true)
        repo.saveInstantGroup(true)
        repo.saveFollowup(false)
        repo.saveFollowupDays(21)
        val prefs = repo.prefs.first()
        assertEquals(DiaryPrefs(false, false, false, LocalTime(22, 30), true, true, false, 21), prefs)
        val row = db.coachDao().observeSettings().first()!!
        assertTrue(row.sync.version > before)
        assertEquals(millis, row.sync.updatedAt)
    }

    @Test
    fun followupDaysOnlyFromChips() {
        assertFailsWith<IllegalArgumentException> { runBlocking { repo.saveFollowupDays(10) } }
    }
}
