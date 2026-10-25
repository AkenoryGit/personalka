package com.akenory.personalka.ui.reminders

import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.ReminderFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.domain.money.SalarySetupDraft
import com.akenory.personalka.domain.money.TierDraft
import com.akenory.personalka.ui.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверка по будильнику (шаг 2.5, ТЗ 5.19): что показать и когда следующая. День — вт 20.10.2026, начало — 15:40.
 * Имена придуманы.
 */
class RemindersTest {
    private val today = TestData.now.date
    private val tomorrow = today.plus(1, DateTimeUnit.DAY)
    private var now = TestData.now
    private val prefs = MemoryPrefs()

    private fun at(hour: Int, minute: Int = 0, day: LocalDate = today) = LocalDateTime(day, LocalTime(hour, minute))

    private fun remindersOf(settings: SettingsRepository) =
        Reminders(TestData.calendarOf(settings), TestData.clientsOf(settings), backup = null, prefs = prefs, clock = { ClubTime.toMillis(now) })

    private fun check(reminders: Reminders, time: LocalDateTime) = runBlocking {
        now = time
        reminders.check()
    }

    /** ПТ 14:00–14:50 (проведётся сама, дневника нет) и группа 13:00–14:00 без отметки. */
    private fun dayWithTodos(settings: SettingsRepository): String = runBlocking {
        val calendar = TestData.calendarOf(settings)
        val clients = TestData.clientsOf(settings)
        val groups = TestData.groupsOf(settings)
        val pt = settings.types.first().single { it.shortName == "ПТ" }.id
        val groupType = settings.types.first().single { it.shortName == "Группа" }.id
        val place = settings.places.first().single { it.shortName == "ББ" }.id
        val orlov = clients.create(ClientDraft("Орлов", "Максим"))
        calendar.forms.create(TrainingDraft(at(14), pt, listOf(orlov), null, place, 50))
        val group = groups.create(GroupDraft("Вт 13:00", groupType, place))
        groups.addMember(group, clients.create(ClientDraft("Белов", "Артём")), LocalDate(2026, 9, 1))
        calendar.forms.create(TrainingDraft(at(13), groupType, emptyList(), group, place, 60))
        TestData.dbOf(settings).trainingDao().trainingsBetween(at(14), at(14, 1)).single().id
    }

    @Test
    fun beforeFirstRunNothingAndNoAlarm() {
        val result = check(remindersOf(TestData.fresh()), at(21))
        assertEquals(emptyList(), result.notices)
        assertNull(result.next)
    }

    @Test
    fun noTodosNoDigest() {
        val reminders = remindersOf(TestData.ready())
        check(reminders, at(15, 40))
        // Проверка шага: без дел сводка не приходит, будильник — на завтрашнюю сводку.
        val evening = check(reminders, at(21, 2))
        assertEquals(emptyList(), evening.notices)
        assertEquals(at(21, 0, tomorrow), evening.next)
    }

    @Test
    fun eveningDigestWithTodosAndDiaryButton() {
        val settings = TestData.ready()
        dayWithTodos(settings)
        val reminders = remindersOf(settings)
        val day = check(reminders, at(15, 40))
        assertEquals(emptyList(), day.notices)
        assertEquals(at(21), day.next)

        val evening = check(reminders, at(21))
        val digest = evening.notices.single()
        assertEquals(NoticeChannel.DIGEST, digest.channel)
        assertTrue(digest.text.startsWith("Группа не отмечена · 1${RuText.NBSP}без дневника"), digest.text)
        assertTrue(digest.title.startsWith("Нужно сделать: "), digest.title)
        assertEquals(AppLink.Home, digest.open)
        assertEquals(AppLink.FirstDiary, digest.diary)

        // Повторная проверка в тот же вечер сводку не повторяет.
        assertEquals(emptyList(), check(reminders, at(21, 30)).notices)
    }

    @Test
    fun instantDiaryAndGroupWhenEnabled() {
        val settings = TestData.ready()
        val pt = dayWithTodos(settings)
        val calendar = TestData.calendarOf(settings)
        runBlocking {
            calendar.reminders.saveInstantDiary(true)
            calendar.reminders.saveInstantGroup(true)
        }
        val reminders = remindersOf(settings)
        // Группа закончилась в 14:00 — напоминание в 14:15; ПТ в 14:50 — в 15:05.
        check(reminders, at(14, 10))
        assertEquals(at(14, 15), runBlocking { reminders.nextCheck().first() })
        val group = check(reminders, at(14, 15)).notices.single()
        assertEquals("13:00–14:00 · отметьте, кто пришёл", group.text)
        assertTrue(group.open is AppLink.Card)
        val diary = check(reminders, at(15, 5))
        assertEquals(listOf(AppLink.Diary(pt)), diary.notices.map { it.open })
        assertEquals(ReminderFormat.instantDiary(at(14), at(14, 50)), diary.notices.single().text)
    }

    @Test
    fun dayPlanIsPhoneSettingAndOffByDefault() {
        val settings = TestData.ready()
        dayWithTodos(settings)
        val reminders = remindersOf(settings)
        assertEquals(false, reminders.dayPlan)
        reminders.dayPlan = true
        check(reminders, at(6, 50))
        val plan = check(reminders, at(7, 0)).notices.single()
        assertEquals(ReminderFormat.DAY_PLAN_TITLE, plan.title)
        assertEquals(ReminderFormat.dayPlan(2, at(13)), plan.text)
    }

    @Test
    fun advanceOnAdvanceDayAtSeven() {
        // Шаг 3.9: аванс 25-го в 19:00, пока сумма не введена; нажатие — окно аванса в «Зарплате».
        val settings = TestData.ready()
        runBlocking {
            val pt = settings.types.first().single { it.shortName == "ПТ" }.id
            val bb = settings.places.first().single { it.shortName == "ББ" }.id
            settings.salary.finish(
                SalarySetupDraft(
                    category = CoachCategory.MASTER,
                    scheme = PaySchemeKind.FULL_OFFICIAL,
                    advance = 1_000_000,
                    schemeFrom = YearMonth(2026, 1),
                    rates = mapOf(pt to mapOf(bb to RateAmounts(92_000, 96_000, 100_000))),
                    services = emptyMap(),
                    tiers = TierDraft(true, 101, 151, setOf(pt)),
                ),
                today,
            )
            TestData.calendarOf(settings).reminders.saveDigest(false)
        }
        val reminders = Reminders(TestData.calendarOf(settings), TestData.clientsOf(settings), null, prefs, { ClubTime.toMillis(now) }, settings.months)
        val day = LocalDate(2026, 10, 25)
        assertEquals(at(19, 0, day), check(reminders, at(10, 0, day)).next)
        val evening = check(reminders, at(19, 3, day))
        assertEquals(listOf(Notice(Reminders.ADVANCE_KEY, NoticeChannel.INSTANT, ReminderFormat.ADVANCE_TITLE, ReminderFormat.ADVANCE_TEXT, AppLink.Advance)), evening.notices)
        assertEquals(at(19, 0, LocalDate(2026, 11, 25)), evening.next)
        // Выключили «Аванс» — будильника нет (сводка тоже выключена).
        reminders.advance = false
        assertNull(check(reminders, at(19, 10, day)).next)
    }
}

