package com.akenory.personalka.ui.training

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.DiaryFormat
import com.akenory.personalka.domain.format.HomeFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TodoListFormat
import com.akenory.personalka.domain.group.GroupDraft
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.home.NEAREST_TAG
import com.akenory.personalka.ui.home.todoFillTag
import com.akenory.personalka.ui.home.todoPanelTag
import com.akenory.personalka.ui.home.todoTag
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Дневник (шаг 2.1, ТЗ 5.7, макеты «Дневник ПТ», «Дневник сплита», «Дневник группы»). «Сейчас» — вт 20.10.2026 15:40.
 * Имена придуманы.
 */
@OptIn(ExperimentalTestApi::class)
class DiaryUiTest {
    private val settings = TestData.ready(diary = true)
    private val clients = TestData.clientsOf(settings)
    private val groups = TestData.groupsOf(settings)
    private val calendar = TestData.calendarOf(settings)
    private val db = TestData.dbOf(settings)
    private val today = TestData.now.date

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun typeId(short: String) = runBlocking { settings.types.first().single { it.shortName == short }.id }

    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }

    private fun client(last: String, first: String) = runBlocking { clients.create(ClientDraft(last, first)) }

    private fun at(time: String): LocalDateTime =
        time.split(':').map { it.toInt() }.let { (h, m) -> LocalDateTime(today, LocalTime(h, m)) }

    private fun training(time: String, vararg clientIds: String, type: String = "ПТ", groupId: String? = null, plan: String = ""): String = runBlocking {
        calendar.forms.create(TrainingDraft(at(time), typeId(type), clientIds.toList(), groupId, placeId("ББ"), 50, plan = plan))
        db.trainingDao().trainingsBetween(at(time), at("23:59")).first { it.start == at(time) }.id
    }

    private fun factOf(id: String) = runBlocking { db.trainingDao().training(id)!!.fact }

    private fun inDiary(text: String) = hasText(text) and hasAnyAncestor(hasTestTag(DIARY_TAG))

    /** Дневник [id] сам по себе, с микрофоном, который «слышит» [heard]. */
    private fun ComposeUiTest.openDiary(id: String, heard: String = "добавить повороты") {
        setContent {
            PersonalkaTheme {
                Box(Modifier.size(412.dp, 960.dp)) {
                    DiaryScreen(
                        id = id,
                        diaries = calendar.diaries,
                        voice = VoiceInput { onText -> onText(heard) },
                        scope = rememberCoroutineScope(),
                        onOpen = {},
                        onClient = {},
                        onGroup = {},
                        onClose = {},
                    )
                }
            }
        }
        awaitNode(hasTestTag(DIARY_TITLE_TAG))
    }

    @Test
    fun dayIsFilledInARow() = runComposeUiTest {
        // Проверка шага: две ПТ утром проведены сами, дневников нет — «Без дневника · 2» на «Главной».
        val morning = training("09:00", client("Яковлева", "Таисия"))
        val noon = training("13:30", client("Орлова", "Ксения"), plan = "Кроль на груди: дыхание на 3 гребка")
        setContent {
            Box(Modifier.size(412.dp, 960.dp)) {
                App("0.1.0", settings, clients, groups, calendar, MemoryPrefs(), clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(NEAREST_TAG))
        awaitNode(hasTestTag(todoTag(TodoKind.NO_DIARY)))
        onNodeWithText(HomeFormat.NO_DIARY).assertIsDisplayed()
        onNodeWithText(s("Яковлева_Т. 09:00 · Орлова_К. 13:30")).assertIsDisplayed()

        // Пункт — окно со списком (шаг 3.16а), «Заполнить» у самой ранней — её дневник; «ещё 1 без дневника».
        onNodeWithTag(todoTag(TodoKind.NO_DIARY)).performClick()
        awaitNode(hasTestTag(todoPanelTag(TodoKind.NO_DIARY)))
        onNodeWithText(TodoListFormat.DIARY_WHY).assertIsDisplayed()
        onNodeWithTag(todoFillTag(morning)).performClick()
        awaitNode(inDiary(s("Яковлева_Т.")))
        onNode(inDiary("ещё 1 без дневника")).assertIsDisplayed()
        onNode(inDiary(DiaryFormat.NO_HEALTH)).assertIsDisplayed()
        onNodeWithTag(DIARY_FACT_TAG).performTextInput("Проплыли 4 × 50 м в ластах")
        waitUntil(timeoutMillis = 5_000) { factOf(morning) == "Проплыли 4 × 50 м в ластах" }
        awaitNode(hasTestTag(DIARY_SAVED_TAG))

        // «Следующая без дневника» — Орлова: «План был» для справки, других без дневника нет — кнопки нет.
        onNodeWithTag(DIARY_NEXT_TAG).performClick()
        awaitNode(inDiary(s("Орлова_К.")))
        awaitNode(inDiary("Кроль на груди: дыхание на 3 гребка"))
        onNode(inDiary(DiaryFormat.PLAN_WAS)).assertIsDisplayed()
        onNodeWithTag(DIARY_NEXT_TAG).assertDoesNotExist()
        onNodeWithTag(DIARY_FACT_TAG).performTextInput("Сделали 5 стартов с тумбочки")
        waitUntil(timeoutMillis = 5_000) { factOf(noon) == "Сделали 5 стартов с тумбочки" }

        // «Готово» — дневник закрыт, пункт ушёл сам.
        onNodeWithTag(DIARY_DONE_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(DIARY_TAG)).fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(todoTag(TodoKind.NO_DIARY))).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun splitHasBehaviorOfEachAndVoiceAppends() = runComposeUiTest {
        val kovalev = client("Ковалёв", "Тимур")
        val kovaleva = client("Ковалёва", "Алиса")
        val split = training("09:00", kovalev, kovaleva, type = "Сплит", plan = "Обоим: старт с тумбочки")
        runBlocking { calendar.cards.closeEnded() }
        openDiary(split)

        onNode(inDiary("Ковалёв / Ковалёва")).assertIsDisplayed()
        onNode(inDiary(s("вт_20.10 · 09:00–09:50 · Сплит · ББ"))).assertIsDisplayed()
        onNode(inDiary(s("Поведение · Ковалёв_Т."))).assertIsDisplayed()
        onNode(inDiary(s("Поведение · Ковалёва_А."))).assertIsDisplayed()
        onNodeWithTag(diaryBehaviorTag(kovaleva)).performTextInput("Внимательна")
        waitUntil(timeoutMillis = 5_000) { runBlocking { db.trainingDao().participationRow(split, kovaleva)!!.behavior } == "Внимательна" }

        // Карандаш у «План был» — план можно исправить.
        onNodeWithTag(DIARY_PLAN_EDIT_TAG).performClick()
        awaitNode(hasTestTag(DIARY_PLAN_TAG))

        // Микрофон у «Плана на следующую»: следующей нет — план ждёт у обоих детей (4.3).
        onNode(inDiary(DiaryFormat.nextPlan(null))).performScrollTo().assertIsDisplayed()
        onNodeWithTag(diaryVoiceTag(DIARY_NEXT_PLAN_TAG)).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            runBlocking { listOf(kovalev, kovaleva).map { db.clientDao().client(it)!!.pendingPlan } } == listOf("Добавить повороты", "Добавить повороты")
        }
    }

    @Test
    fun groupHasNoBehaviorAndNamesHint() = runComposeUiTest {
        val group = runBlocking { groups.create(GroupDraft("Вт 12:00 ББ", typeId("Группа"), placeId("ББ"))) }
        runBlocking { groups.addMember(group, client("Белов", "Артём"), LocalDate(2026, 9, 1)) }
        val id = training("12:00", type = "Группа", groupId = group)
        runBlocking { calendar.cards.markDone(id) }
        openDiary(id)

        onNode(inDiary(DiaryFormat.GROUP_HINT)).assertIsDisplayed()
        assertEquals(0, onAllNodes(hasText(DiaryFormat.BEHAVIOR, substring = true) and hasAnyAncestor(hasTestTag(DIARY_TAG))).fetchSemanticsNodes().size)
    }
}
