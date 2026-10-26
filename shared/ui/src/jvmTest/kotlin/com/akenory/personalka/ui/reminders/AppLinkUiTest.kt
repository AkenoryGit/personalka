package com.akenory.personalka.ui.reminders

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.training.TrainingDraft
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.training.DIARY_TAG
import com.akenory.personalka.ui.training.TRAINING_CARD_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertNull

/** Нажатие на уведомление (шаг 2.5, ТЗ 5.19): нужный экран сразу. «Сейчас» — вт 20.10.2026 15:40. Имена придуманы. */
@OptIn(ExperimentalTestApi::class)
class AppLinkUiTest {
    private val settings = TestData.ready(diary = true)
    private val calendar = TestData.calendarOf(settings)
    private val clients = TestData.clientsOf(settings)

    /** ПТ 14:00–14:50 у Орлова: закончилась, приложение закроет её «Проведено» — дневника нет. */
    private fun pastTraining(): String = runBlocking {
        val pt = settings.types.first().single { it.shortName == "ПТ" }.id
        val place = settings.places.first().single { it.shortName == "ББ" }.id
        val start = LocalDateTime(TestData.now.date, LocalTime(14, 0))
        calendar.forms.create(TrainingDraft(start, pt, listOf(clients.create(ClientDraft("Орлов", "Максим"))), null, place, 50))
        TestData.dbOf(settings).trainingDao().trainingsBetween(start, LocalDateTime(TestData.now.date, LocalTime(14, 1))).single().id
    }

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    @Test
    fun diaryButtonOpensFirstWithoutDiary() = runComposeUiTest {
        pastTraining()
        var link by mutableStateOf<AppLink?>(AppLink.FirstDiary)
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App("0.1.0", settings, clients, TestData.groupsOf(settings), calendar, link = link, onLink = { link = null }, clock = TestData.clock)
            }
        }
        awaitNode(hasText(s("Орлов_М.")) and hasAnyAncestor(hasTestTag(DIARY_TAG)))
        waitUntil(timeoutMillis = 5_000) { link == null }
        assertNull(link)
    }

    @Test
    fun groupReminderOpensCardOnHome() = runComposeUiTest {
        val id = pastTraining()
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App("0.1.0", settings, clients, TestData.groupsOf(settings), calendar, link = AppLink.Card(id), clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(TRAINING_CARD_TAG))
    }

    @Test
    fun codesRoundTrip() {
        listOf(AppLink.Home, AppLink.FirstDiary, AppLink.Diary("a1"), AppLink.Card("b2")).forEach {
            kotlin.test.assertEquals(it, AppLink.parse(it.code))
        }
        assertNull(AppLink.parse("что-то"))
    }
}
