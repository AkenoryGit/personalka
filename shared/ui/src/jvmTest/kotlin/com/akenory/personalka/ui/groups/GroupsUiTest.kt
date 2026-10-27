package com.akenory.personalka.ui.groups

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.home.homeTodos
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** «Ещё → Группы» (шаг 1.8, ТЗ 5.10): группа, состав — просто список (шаг 3.9т). «Сейчас» — вт 20.10.2026. */
@OptIn(ExperimentalTestApi::class)
class GroupsUiTest {
    private val folded = 412.dp
    private val unfolded = 760.dp
    private val height = 900.dp
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val groups = TestData.groupsOf(settings)
    private val today = TestData.now.date

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun kid(last: String, first: String, birth: LocalDate? = LocalDate(2016, 5, 14)): String =
        runBlocking { clients.create(ClientDraft(last, first, sex = Sex.FEMALE, birthDate = birth)) }

    private fun group(name: String = "Сб 9:00 ББ"): String = runBlocking {
        val type = settings.types.first().first { it.shortName == "Группа" }
        val place = settings.places.first().first { it.shortName == "ББ" }
        groups.create(GroupDraft(name, type.id, place.id))
    }

    private fun ComposeUiTest.openGroups(width: () -> Dp = { folded }) {
        setContent { Box(Modifier.size(width(), height)) { App("0.1.0", settings, clients, groups, TestData.calendarOf(settings), clock = TestData.clock) } }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.GROUPS)).performClick()
    }

    @Test
    fun newGroupAndFirstChild() = runComposeUiTest {
        kid("Белова", "Ева")
        openGroups()
        awaitNode(hasText("Групп пока нет"))
        onNodeWithText("Добавить группу").performClick()
        // Форма во весь экран: нижней панели нет.
        onNodeWithTag(navTag(Section.HOME)).assertDoesNotExist()
        onNodeWithText("Сохранить").performClick()
        onNodeWithText("Введите название").assertIsDisplayed()
        // Тип и место выбраны сами: первый тип-группа и первое место.
        onNodeWithText("Мини-группа").assertIsSelected()
        onNodeWithText("Большой бассейн").assertIsSelected()
        onNodeWithText("Группа").performClick()
        onNodeWithTag(GROUP_NAME_TAG).performTextInput("Сб 9:00 ББ")
        onNodeWithText("Сохранить").performClick()

        // Открылась карточка группы.
        awaitNode(hasText("В группе пока никого"))
        onNodeWithText("Сб 9:00 ББ").assertIsDisplayed()
        onNodeWithText("Группа · Большой бассейн · без расписания").assertIsDisplayed()

        onNodeWithTag(MEMBER_ADD_TAG).performClick()
        // Даты нет (шаг 3.9т): ребёнок просто в группе.
        onNodeWithText("С какого дня в группе").assertDoesNotExist()
        onNodeWithTag(memberPickTag("Белова Ева")).performClick()
        onNodeWithText("Сохранить").performClick()

        awaitNode(hasTestTag(memberRowTag("Белова Ева")))
        assertEquals(today, runBlocking { groups.rows(today).first().single().members.single().fromDate })
        onNodeWithText("Состав · 1").assertIsDisplayed()

        onNodeWithContentDescription("Назад").performClick()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).assertTextContains(s("без расписания · ББ · 1_ребёнок"), substring = true)
    }

    @Test
    fun removedFromGroupWithUndo() = runComposeUiTest {
        val eva = kid("Белова", "Ева")
        val id = group()
        val member = runBlocking { groups.addMember(id, eva, LocalDate(2026, 9, 1)) }
        openGroups()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).performClick()
        awaitNode(hasTestTag(memberRowTag("Белова Ева")))
        // В строке — без дат.
        onNodeWithText("с 01.09.2026", substring = true).assertDoesNotExist()
        onNodeWithTag(memberRowTag("Белова Ева")).performClick()

        onNodeWithText("Убрать из группы").performClick()
        awaitNode(hasText("Белова Ева убрана из группы"))
        // «Ушли» больше нет: ребёнка просто нет в списке; прошлое помнит последний день — вчера.
        awaitNode(hasText("В группе пока никого"))
        onNodeWithText("Ушли", substring = true).assertDoesNotExist()
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { groups.rows(today).first().single().members.single { it.id == member }.toDate } == LocalDate(2026, 10, 19)
        }
        onNodeWithText("Отменить").performClick()
        awaitNode(hasTestTag(memberRowTag("Белова Ева")))
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { groups.rows(today).first().single().members.single { it.id == member }.toDate } == null
        }
    }

    @Test
    fun newClientStraightIntoGroup() = runComposeUiTest {
        group()
        openGroups()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).performClick()
        awaitNode(hasTestTag(MEMBER_ADD_TAG))
        onNodeWithTag(MEMBER_ADD_TAG).performClick()

        onNodeWithTag(MEMBER_SEARCH_TAG).performTextInput("Смирнова Аня")
        onNodeWithTag(NEW_MEMBER_TAG).performClick()
        onNodeWithTag(NEW_MEMBER_LAST_TAG).assertTextContains("Смирнова")
        onNodeWithTag(NEW_MEMBER_FIRST_TAG).assertTextContains("Аня")
        onNodeWithText("Сохранить").performClick()

        awaitNode(hasTestTag(memberRowTag("Смирнова Аня")))
        val anya = runBlocking { clients.rows(TestData.now).first().single().client }
        assertEquals(Sex.FEMALE, anya.sex)
    }

    @Test
    fun similarClientInsteadOfDuplicate() = runComposeUiTest {
        val anna = kid("Смирнова", "Анна")
        runBlocking { clients.archive(anna) }
        group()
        openGroups()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).performClick()
        awaitNode(hasTestTag(MEMBER_ADD_TAG))
        onNodeWithTag(MEMBER_ADD_TAG).performClick()

        // Архивной в списке нет — тренер набирает её заново; приложение находит её и в архиве (шаг 3.9о).
        onNodeWithTag(MEMBER_SEARCH_TAG).performTextInput("Смирнова Анна")
        onNodeWithTag(NEW_MEMBER_TAG).performClick()
        onNodeWithText("Сохранить").performClick()
        awaitNode(hasText("Похожий клиент уже есть"))
        onNodeWithText("в архиве", substring = true).assertIsDisplayed()
        onNodeWithText("Да, выбрать").performClick()

        awaitNode(hasTestTag(memberRowTag("Смирнова Анна")))
        val all = runBlocking { clients.rows(TestData.now).first().map { it.client } }
        assertEquals(listOf(anna), all.map { it.id }, "Дубля нет")
        assertEquals(ClientStatus.ACTIVE, all.single().status, "Вернулась из архива")
    }

    @Test
    fun addedTodayRemovedLeavesNoRecord() = runComposeUiTest {
        val eva = kid("Белова", "Ева")
        val id = group()
        runBlocking { groups.addMember(id, eva, today) }
        openGroups()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).performClick()
        awaitNode(hasTestTag(memberRowTag("Белова Ева")))
        onNodeWithTag(memberRowTag("Белова Ева")).performClick()

        onNodeWithText("Убрать из группы").performClick()
        awaitNode(hasText("В группе пока никого"))
        waitUntil(timeoutMillis = 5_000) { runBlocking { groups.rows(today).first().single().members.isEmpty() } }
        onNodeWithText("Отменить").performClick()
        awaitNode(hasTestTag(memberRowTag("Белова Ева")))
    }

    /** Ева в группе с 01.09, была на ней 05.09, а группа проходила и после — 30 дней назад и позже (шаг 3.9у). */
    private fun absentEva(): Pair<String, String> = runBlocking {
        val eva = kid("Белова", "Ева")
        val id = group()
        groups.addMember(id, eva, LocalDate(2026, 9, 1))
        val db = TestData.dbOf(settings)
        val coach = db.coachDao().currentCoach()!!.id
        val type = settings.types.first().first { it.shortName == "Группа" }.id
        val place = settings.places.first().first { it.shortName == "ББ" }.id
        listOf(LocalDate(2026, 9, 5), LocalDate(2026, 10, 10)).forEachIndexed { i, date ->
            val t = TrainingEntity("g$i", SyncFields.new(coach, 1), type, place, LocalDateTime(date, LocalTime(9, 0)), 45, groupId = id, status = TrainingStatus.DONE)
            db.trainingDao().upsertTraining(t)
            val status = if (i == 0) ParticipationStatus.ATTENDED else ParticipationStatus.CANCELLED
            db.trainingDao().upsertParticipation(ParticipationEntity("p$i", SyncFields.new(coach, 1), t.id, eva, status))
        }
        id to eva
    }

    private fun ComposeUiTest.openGroupCard() {
        openGroups()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).performClick()
    }

    @Test
    fun absentChildKeptTwiceThenNeverAsked() = runComposeUiTest {
        val (id, eva) = absentEva()
        // На «Главной» — пункт «Не ходят в группу», нажатие ведёт в эту группу.
        val todo = homeTodos(emptyList(), null, TestData.now, absent = runBlocking { groups.absent(today).first() }).single()
        assertEquals(Triple(TodoKind.GROUP_ABSENT, "Белова Ева", id), Triple(todo.kind, todo.note, todo.groupId))
        openGroupCard()
        awaitNode(hasText("Не ходят 30 дней · 1"))
        onNodeWithText("не была с 05.09").assertIsDisplayed()
        onNodeWithText("Оставить").performClick()
        awaitNode(hasText("Оставили. Спрошу ещё раз через 30 дней"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Не ходят 30 дней", substring = true)).fetchSemanticsNodes().isEmpty() }
        // Ева осталась в составе.
        onNodeWithTag(memberRowTag("Белова Ева")).assertIsDisplayed()
        val kept = runBlocking { groups.rows(today).first().single().members.single { it.clientId == eva } }
        assertEquals(today to 1, kept.absenceKeptOn to kept.absenceKeeps)
        // Через 30 дней спросит ещё раз, после второго «Оставить» — никогда.
        val later = LocalDate(2026, 12, 1)
        runBlocking {
            val db = TestData.dbOf(settings)
            val coach = db.coachDao().currentCoach()!!.id
            val t = db.trainingDao().training("g1")!!
            db.trainingDao().upsertTraining(t.copy(id = "g2", sync = SyncFields.new(coach, 2), start = LocalDateTime(2026, 11, 28, 9, 0)))
            assertEquals(1, groups.absent(later).first().size)
            db.clientDao().upsertGroupMember(kept.copy(absenceKeptOn = later, absenceKeeps = 2))
            assertTrue(groups.absent(LocalDate(2027, 6, 1)).first().isEmpty())
        }
        assertEquals(id, runBlocking { groups.rows(today).first().single().group.id })
    }

    @Test
    fun rareChildOfferedAndPercentInTrainingCard() = runComposeUiTest {
        // Шаг 3.14б: Ева ходит на 4 из 12 занятий (33 %) — «ходит редко» с тремя кнопками; Глеб — на все, его нет.
        val eva = kid("Белова", "Ева")
        val gleb = kid("Ильин", "Глеб")
        val id = group()
        groups.addMember(id, eva, LocalDate(2026, 9, 1))
        groups.addMember(id, gleb, LocalDate(2026, 9, 1))
        val db = TestData.dbOf(settings)
        runBlocking {
            val coach = db.coachDao().currentCoach()!!.id
            val type = settings.types.first().first { it.shortName == "Группа" }.id
            val place = settings.places.first().first { it.shortName == "ББ" }.id
            (1..12).forEach { day ->
                val t = TrainingEntity("r$day", SyncFields.new(coach, 1), type, place, LocalDateTime(2026, 10, day, 9, 0), 45, groupId = id, status = TrainingStatus.DONE)
                db.trainingDao().upsertTraining(t)
                val came = day % 3 == 1
                db.trainingDao().upsertParticipation(ParticipationEntity("re$day", SyncFields.new(coach, 1), t.id, eva, if (came) ParticipationStatus.ATTENDED else ParticipationStatus.CANCELLED))
                db.trainingDao().upsertParticipation(ParticipationEntity("rg$day", SyncFields.new(coach, 1), t.id, gleb, ParticipationStatus.ATTENDED))
            }
            // Сегодняшняя группа: в карточке у каждого — как он ходит в эту группу.
            val today = TrainingEntity("today", SyncFields.new(coach, 1), type, place, LocalDateTime(2026, 10, 20, 17, 0), 45, groupId = id)
            db.trainingDao().upsertTraining(today)
            db.trainingDao().upsertParticipation(ParticipationEntity("te", SyncFields.new(coach, 1), today.id, eva))
            db.trainingDao().upsertParticipation(ParticipationEntity("tg", SyncFields.new(coach, 1), today.id, gleb))
            val card = TestData.calendarOf(settings).cards.card("today").first()!!
            assertEquals(mapOf(eva to 33, gleb to 100), card.reliability.mapValues { it.value.percent })
        }
        openGroupCard()
        awaitNode(hasText("Не ходят или ходят редко · 1"))
        onNodeWithText(s("ходит на 33_% занятий из 12")).assertIsDisplayed()
        assertEquals(0, onAllNodes(hasTestTag(absentRowTag("Ильин Глеб"))).fetchSemanticsNodes().size)
        onNodeWithText("Оставить").performClick()
        awaitNode(hasText("Оставили. Спрошу ещё раз через 30 дней"))

        // Надёжность выключена — карточка тренировки без процентов.
        runBlocking {
            TestData.calendarOf(settings).reminders.saveReliability(false)
            assertTrue(TestData.calendarOf(settings).cards.card("today").first()!!.reliability.isEmpty())
        }
    }

    @Test
    fun absentChildRemovedOrArchived() = runComposeUiTest {
        val (_, eva) = absentEva()
        openGroupCard()
        awaitNode(hasTestTag(absentRowTag("Белова Ева")))
        onNodeWithText("В архив").performClick()
        awaitNode(hasText("Белова Ева в архиве"))
        waitUntil(timeoutMillis = 5_000) { runBlocking { clients.rows(TestData.now).first().none { it.client.id == eva && it.client.status == ClientStatus.ACTIVE } } }
        awaitNode(hasText("В группе пока никого"))
        onNodeWithText("Отменить").performClick()
        awaitNode(hasTestTag(absentRowTag("Белова Ева")))

        onAllNodes(hasText("Убрать из группы")).onFirst().performClick()
        awaitNode(hasText("Белова Ева убрана из группы"))
        awaitNode(hasText("В группе пока никого"))
        onNodeWithText("Не ходят 30 дней", substring = true).assertDoesNotExist()
    }

    @Test
    fun emptyGroupDeletedFromMenu() = runComposeUiTest {
        group()
        openGroups()
        awaitNode(hasTestTag(groupRowTag("Сб 9:00 ББ")))
        onNodeWithTag(groupRowTag("Сб 9:00 ББ")).performClick()
        awaitNode(hasTestTag(GROUP_MENU_TAG))
        onNodeWithTag(GROUP_MENU_TAG).performClick()
        awaitNode(hasText("Удалить группу"))
        onNodeWithText("Удалить группу").performClick()
        onNodeWithText("Удалить группу?").assertIsDisplayed()
        onNodeWithText("Удалить").performClick()

        onNodeWithText("Группа удалена").assertIsDisplayed()
        awaitNode(hasText("Групп пока нет"))
        waitUntil(timeoutMillis = 5_000) { runBlocking { groups.rows(today).first().isEmpty() } }
    }

    @Test
    fun groupFormSurvivesUnfolding() = runComposeUiTest {
        var width by mutableStateOf(folded)
        openGroups { width }
        awaitNode(hasText("Добавить группу"))
        onNodeWithText("Добавить группу").performClick()
        onNodeWithTag(GROUP_NAME_TAG).performTextInput("Вт 18:00 ДБ")
        onNodeWithText("Детский бассейн").performClick()

        width = unfolded
        waitForIdle()
        onNodeWithTag(GROUP_NAME_TAG).assertTextContains("Вт 18:00 ДБ")
        onNodeWithText("Детский бассейн").assertIsSelected()
        width = folded
        waitForIdle()
        onNodeWithTag(GROUP_NAME_TAG).assertTextContains("Вт 18:00 ДБ")
        onNodeWithText("Сохранить").performClick()
        awaitNode(hasText("В группе пока никого"))
        assertNull(runBlocking { groups.rows(today).first().single().schedule.firstOrNull() })
    }
}
