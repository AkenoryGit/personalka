package com.akenory.personalka.ui.reconcile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.domain.format.ReconcileFormat
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.PANEL_TAG
import com.akenory.personalka.ui.settings.PlatformFiles
import com.akenory.personalka.ui.settings.SharedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * «Отчёт 1С» в «Сверках» (шаг 4.2б, ТЗ 5.14, 8.2, 8.3): загрузка тестового отчёта за октябрь, шесть услуг и похожее имя
 * спрашиваются один раз, повторная загрузка сверяет сразу. «Сейчас» — вт 20.10.2026.
 */
@OptIn(ExperimentalTestApi::class)
class Report1cUiTest {
    private val settings = TestData.ready()
    private val db = TestData.dbOf(settings)
    private val pdf = File("../domain/src/jvmTest/resources/report1c/otchet-oktyabr-2026.pdf").readBytes()
    private val name = "Отчет по тренировкам report-20261018T104841.pdf"
    private val messages = MessageState()

    private class Files(private val file: SharedFile) : PlatformFiles {
        val sent = MutableStateFlow<SharedFile?>(null)
        override fun pickFolder(onPicked: (uri: String, name: String) -> Unit) = Unit
        override fun openFile(onOpened: (ByteArray) -> Unit) = Unit
        override fun share(name: String, mime: String, bytes: ByteArray) = Unit
        override fun openReport(onOpened: (SharedFile) -> Unit) = onOpened(file)
        override val incoming: StateFlow<SharedFile?> = sent
        override fun takeIncoming() {
            sent.value = null
        }
    }

    private fun client(id: String, last: String, first: String) = runBlocking {
        val coach = db.coachDao().currentCoach()!!.id
        db.clientDao().upsertClient(ClientEntity(id, SyncFields.new(coach, TestData.clock()), last, first))
    }

    private fun ComposeUiTest.show(files: Files) {
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                ReconcileScreen(settings, TestData.now.date, messages, rememberCoroutineScope(), onBack = null, files = files)
            }
        }
        awaitNode(hasTestTag(REPORT1C_LOAD_TAG))
    }

    private fun SemanticsNodeInteraction.press() = performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    private fun ComposeUiTest.next() {
        onNodeWithText(ReconcileFormat.NEXT).performClick()
    }

    @Test
    fun loadAsksServicesAndNamesOnce() = runComposeUiTest {
        client("egor", "Пестов", "Егор")
        // «Ганиева Кира» — на букву отличается от «Ганеевой Киры» в 1С: спросит, она ли это.
        client("kira", "Ганиева", "Кира")
        val files = Files(SharedFile(name, pdf))
        show(files)
        onNodeWithTag(REPORT1C_LOAD_TAG).press()

        // Шесть услуг, подсказка уже выбрана; у «Стартовой тренировки бассейн» места нет — выбираем ДБ.
        awaitNode(hasText(ReconcileFormat.step(1, 7)))
        onNodeWithText(ReconcileFormat.serviceQuestion("Персональная тренировка. ББ")).assertExists()
        val db1 = runBlocking { settings.places.first() }.single { it.shortName == "ДБ" }.id
        repeat(6) { i ->
            awaitNode(hasText(ReconcileFormat.step(i + 1, 7)))
            if (runCatching { onNodeWithText(ReconcileFormat.serviceQuestion("Стартовая тренировка бассейн")).assertExists() }.isSuccess) {
                onNodeWithTag(report1cPlaceTag(db1)).performClick()
            }
            next()
        }

        // Имя: похожая «Ганиева Кира» — это она.
        awaitNode(hasText(ReconcileFormat.step(7, 7)))
        onNodeWithText(ReconcileFormat.nameQuestion("Ганеева Кира Олеговна")).assertExists()
        onNodeWithTag(report1cClientTag("kira")).performClick()

        waitUntil(timeoutMillis = 5_000) { runBlocking { settings.report1c.history().first() }.isNotEmpty() }
        val rec = runBlocking { settings.report1c.history().first() }.single()
        awaitNode(hasTestTag(report1cRowTag(rec.id)))
        onNodeWithTag(report1cRowTag(rec.id)).assertTextContains(ReconcileFormat.period(rec.periodFrom, rec.periodTo, rec.generatedAt))
        assertEquals(6, runBlocking { db.report1cDao().services() }.size)
        val spt = runBlocking { db.report1cDao().services() }.single { it.name1c == "Стартовая тренировка бассейн" }
        assertEquals("ДБ", runBlocking { settings.places.first() }.single { it.id == spt.locationId }.shortName)
        assertEquals("kira", runBlocking { db.report1cDao().aliases() }.single().clientId)
        assertEquals("Тренеров Иван Петрович", runBlocking { db.coachDao().currentCoach() }!!.name1c, "ФИО в 1С — из первой сверки")

        // Второй раз — без вопросов, та же сверка периода.
        onNodeWithTag(REPORT1C_LOAD_TAG).press()
        waitUntil(timeoutMillis = 5_000) { runBlocking { settings.report1c.history().first() }.single().sync.version > rec.sync.version }
        assertEquals(1, runBlocking { settings.report1c.history().first() }.size)
        onNodeWithTag(PANEL_TAG).assertDoesNotExist()
    }

    @Test
    fun otherCoachAndBadFile() = runComposeUiTest {
        runBlocking {
            val coach = db.coachDao().currentCoach()!!
            db.coachDao().upsertCoach(coach.copy(name1c = "Соколов Андрей Петрович"))
        }
        val files = Files(SharedFile(name, pdf))
        show(files)
        onNodeWithTag(REPORT1C_LOAD_TAG).press()
        awaitNode(hasText(ReconcileFormat.otherCoach("Тренеров Иван Петрович")))
        onNodeWithText(ReconcileFormat.NOT_MINE).performClick()
        waitForIdle()
        onNodeWithTag(PANEL_TAG).assertDoesNotExist()
        assertNull(runBlocking { settings.report1c.history().first() }.firstOrNull())

        // Присланный не тот файл — «Это не похоже на отчёт 1С».
        files.sent.value = SharedFile("photo.pdf", "%PDF-1.4 пусто".encodeToByteArray())
        waitUntil(timeoutMillis = 5_000) { messages.current?.text == ReconcileFormat.NOT_A_REPORT }
        assertNull(files.sent.value, "Присланный файл забран")
    }
}
