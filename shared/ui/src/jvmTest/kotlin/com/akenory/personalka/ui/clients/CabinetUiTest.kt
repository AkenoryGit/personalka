package com.akenory.personalka.ui.clients

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.account.FakeServer
import com.akenory.personalka.data.account.MemoryTokens
import com.akenory.personalka.data.cabinet.CabinetApi
import com.akenory.personalka.data.cabinet.CabinetRepository
import com.akenory.personalka.data.cabinet.MemorySecrets
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.data.sync.SyncApi
import com.akenory.personalka.data.sync.SyncRepository
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.format.CabinetLinkFormat
import com.akenory.personalka.domain.home.TodoKind
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.home.todoTag
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.settings.SyncTools
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Кабинет в приложении (шаг 2.13, ТЗ 5.9, 5.16): «Кабинеты» в настройках с контактом, кнопка «Кабинет» в карточке,
 * окно с выпуском ссылки, «Отправить», «Скопировать», «Код доступа» и «Выпустить новую ссылку». Сервер — в памяти.
 */
@OptIn(ExperimentalTestApi::class)
class CabinetUiTest {
    private val server = FakeServer()
    private val url = "https://trener.example.ru"
    private val settings: SettingsRepository = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val shared = mutableListOf<String>()
    private val copied = mutableListOf<String>()
    private lateinit var sync: SyncRepository

    private fun ComposeUiTest.show(cabinetsOpen: Boolean = true) {
        server.signedUp(EMAIL, PASSWORD)
        server.serverCopy += EMAIL
        if (cabinetsOpen) server.cabinetAccess += EMAIL
        val tokens = MemoryTokens()
        val prefs = MemoryPrefs().apply { putBoolean(SyncRepository.CONNECTED, true) }
        val account = AccountRepository(server.api(url), tokens, prefs, "Samsung SM-F976B") { settings.coach.first()?.id }
        runBlocking { account.login(EMAIL, PASSWORD) }
        val db = TestData.dbOf(settings)
        sync = SyncRepository(db, SyncApi(server.client) { url }, tokens, prefs, clock = TestData.clock)
        val cabinets = CabinetRepository(db, CabinetApi(server.client) { url }, tokens, MemorySecrets(), TestData.clock)
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App(
                    "0.1.0", settings, clients, TestData.groupsOf(settings), TestData.calendarOf(settings),
                    account = account,
                    sync = SyncTools(sync) {},
                    cabinets = CabinetTools(cabinets, { shared += it }, { copied += it }, sync) { address, _, modifier ->
                        Text(address, modifier.testTag(BROWSER_TAG))
                    },
                    clock = TestData.clock,
                )
            }
        }
    }

    private fun ComposeUiTest.openSettings() {
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.SETTINGS)).performClick()
    }

    private fun ComposeUiTest.openCard(name: String) {
        // Оболочка с нижним меню появляется после ответа базы — в Actions раз не успела к нажатию (сборка 178).
        awaitNode(hasTestTag(navTag(Section.CLIENTS)))
        onNodeWithTag(navTag(Section.CLIENTS)).performClick()
        awaitNode(hasTestTag(clientRowTag(name)))
        onNodeWithTag(clientRowTag(name)).performClick()
    }

    @Test
    fun enableInSettingsThenIssueSendAndChangeCode() = runComposeUiTest {
        val id = runBlocking { clients.create(ClientDraft("Орлова", "Ксения")) }
        show()

        // «Настройки → Кабинеты»: без контакта не включаются.
        openSettings()
        awaitNode(hasTestTag("settings-cabinets"))
        onNodeWithTag("settings-cabinets").performClick()
        awaitNode(hasTestTag("cabinets-switch"))
        onNodeWithTag("cabinets-switch").performClick()
        onNodeWithText(CabinetLinkFormat.CONTACT_NEEDED).assertExists()
        onNodeWithText("Сохранить").assertIsNotEnabled()
        onNodeWithTag("cabinets-contact").performTextInput("+7 912 000-00-00")
        onNodeWithText("Сохранить").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { settings.cabinetsEnabled.first() } }
        assertEquals("+7 912 000-00-00", runBlocking { settings.coach.first()!!.contactForParents })

        // Карточка: «Кабинет» → открыть → ссылка с кодом выпущена.
        openCard("Орлова Ксения")
        awaitNode(hasTestTag(CARD_CABINET_TAG))
        onNodeWithTag(CARD_CABINET_TAG).performClick()
        onNodeWithText("Кабинет · Орлова К.").assertExists()
        onNodeWithTag(CABINET_OPEN_TAG).performClick()
        awaitNode(hasTestTag(CABINET_LINK_TAG))
        val pin = server.cabinets[id]!!.pin!!
        onNodeWithTag(CABINET_LINK_TAG).assertTextContains("$url/c/", substring = true)
        onNodeWithTag(CABINET_CONSENT_TAG).assertTextContains("Согласия пока нет", substring = true)
        onNodeWithText(CabinetLinkFormat.codeOn(pin)).assertExists()
        onNodeWithText(CabinetLinkFormat.NEVER_OPENED).assertExists()
        assertTrue(runBlocking { TestData.dbOf(settings).clientDao().client(id)!!.cabinetEnabled })

        onNodeWithTag(CABINET_SEND_TAG).performClick()
        val text = shared.single()
        assertTrue(text.startsWith("Здравствуйте! Здесь расписание и итоги тренировок: $url/c/"), text)
        assertTrue(text.endsWith("Код для входа: $pin"), text)
        onNodeWithTag(CABINET_COPY_TAG).performClick()
        assertEquals(text, copied.single())

        // Код выключен — ссылка та же, без кода.
        val token = server.cabinets[id]!!.tokenHash
        onNodeWithTag(CABINET_CODE_TAG).performClick()
        awaitNode(hasText(CabinetLinkFormat.CODE_OFF))
        assertEquals(null, server.cabinets[id]!!.pin)
        assertEquals(token, server.cabinets[id]!!.tokenHash)

        // Новая ссылка: на сервере — другая, старая не откроется.
        onNodeWithTag(CABINET_NEW_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { server.cabinets[id]!!.tokenHash != token }
        awaitNode(hasText(CabinetLinkFormat.NEW_LINK_DONE))
        onNodeWithTag(CABINET_SEND_TAG).performClick()
        assertFalse(shared.last() == text)
        assertFalse("Код для входа" in shared.last())
    }

    @Test
    fun revokedConsentIsATodoAndNoPhotoIsMarkedInInfo() = runComposeUiTest {
        val noPhoto = runBlocking { clients.create(ClientDraft("Орлова", "Ксения", mediaUrl = "https://disk.yandex.ru/d/abc")) }
        val revoked = runBlocking { clients.create(ClientDraft("Соколов", "Пётр")) }
        server.consents[noPhoto] = consent(noPhoto, photo = false, revokedAt = null)
        server.consents[revoked] = consent(revoked, photo = true, revokedAt = TestData.clock())
        runBlocking { settings.saveCabinets(true, "+7 912 000-00-00") }
        show()

        // «Нужно сделать»: «Удалить данные клиента» со сроком 30 дней, нажатие — карточка клиента.
        awaitNode(hasTestTag(todoTag(TodoKind.CONSENT_REVOKED)))
        onNodeWithText("Удалить данные клиента").assertExists()
        onNodeWithText("Соколов\u00A0П. · осталось 30\u00A0дней").assertExists()
        onNodeWithTag(todoTag(TodoKind.CONSENT_REVOKED)).performClick()
        awaitNode(hasText("Соколов Пётр"))

        // «Инфо»: у ссылки на фото — «Нет согласия на фото»; у клиента с согласием на фото пометки бы не было.
        openCard("Орлова Ксения")
        awaitNode(hasTestTag(CLIENT_NO_PHOTO_TAG))
        onNodeWithText(CabinetLinkFormat.NO_PHOTO).assertExists()
    }

    @Test
    fun previewOpensTheCabinetAndTellsWhatParentDoesNotSee() = runComposeUiTest {
        val id = runBlocking { clients.create(ClientDraft("Орлова", "Ксения")) }
        runBlocking { settings.saveCabinets(true, "+7 912 000-00-00") }
        show()
        openCard("Орлова Ксения")
        awaitNode(hasTestTag(CARD_CABINET_TAG))
        onNodeWithTag(CARD_CABINET_TAG).performClick()
        onNodeWithTag(CABINET_OPEN_TAG).performClick()
        awaitNode(hasTestTag(CABINET_LINK_TAG))

        // Сервер обновляется — правки не уходят, но кабинет показывается: с плашкой, сколько правок родитель не видит.
        server.schemaVersion -= 1
        onNodeWithTag(CABINET_PREVIEW_TAG).assertTextContains(CabinetLinkFormat.preview(adult = false))
        onNodeWithTag(CABINET_PREVIEW_TAG).performClick()
        awaitNode(hasTestTag(PREVIEW_SCREEN_TAG))
        onNodeWithTag(BROWSER_TAG).assertTextContains("$url/p/preview-$id")
        val pending = runBlocking { sync.pendingNow() }
        assertTrue(pending > 0)
        onNodeWithTag(PREVIEW_NOT_SEEN_TAG).assertTextContains(CabinetLinkFormat.notSeen(pending, adult = false))
        assertEquals(listOf(id), server.previews)

        // Крестик — обратно к окну «Кабинет».
        onNodeWithTag(PREVIEW_CLOSE_TAG).performClick()
        awaitNode(hasTestTag(CABINET_LINK_TAG))

        // Правки ушли — плашки нет.
        server.schemaVersion += 1
        onNodeWithTag(CABINET_PREVIEW_TAG).performClick()
        awaitNode(hasTestTag(PREVIEW_SCREEN_TAG))
        assertEquals(0, runBlocking { sync.pendingNow() })
        assertTrue(onAllNodes(hasTestTag(PREVIEW_NOT_SEEN_TAG)).fetchSemanticsNodes().isEmpty())
        onNodeWithTag(PREVIEW_CLOSE_TAG).performClick()
        awaitNode(hasTestTag(CABINET_PREVIEW_TAG))

        // Без связи — «Нужен интернет», кабинет не открывается.
        server.offline = true
        onNodeWithTag(CABINET_PREVIEW_TAG).performClick()
        awaitNode(hasText(CabinetLinkFormat.NEED_INTERNET))
        assertTrue(onAllNodes(hasTestTag(PREVIEW_SCREEN_TAG)).fetchSemanticsNodes().isEmpty())
        assertEquals(2, server.previews.size)
    }

    private fun consent(id: String, photo: Boolean, revokedAt: Long?) = buildJsonObject {
        put("client_id", id)
        put("accepted_at", TestData.clock() - 86_400_000L)
        put("photo", photo)
        put("given_by_name", "Родитель Тестовый")
        put("revoked_at", revokedAt)
    }

    @Test
    fun noCabinetsFeatureNoSectionNoButton() = runComposeUiTest {
        runBlocking { clients.create(ClientDraft("Орлова", "Ксения")) }
        show(cabinetsOpen = false)
        openSettings()
        awaitNode(hasTestTag("settings-profile"))
        assertTrue(onAllNodes(hasTestTag("settings-cabinets")).fetchSemanticsNodes().isEmpty())
        openCard("Орлова Ксения")
        awaitNode(hasTestTag(CARD_ADD_TRAINING_TAG))
        assertTrue(onAllNodes(hasTestTag(CARD_CABINET_TAG)).fetchSemanticsNodes().isEmpty())
    }

    private companion object {
        const val EMAIL = "coach@example.ru"
        const val BROWSER_TAG = "test-browser"
        const val PASSWORD = "пароль-тренера"
    }
}
