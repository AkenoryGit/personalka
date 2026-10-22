package com.akenory.personalka.ui.clients

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.client.RelationRole
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.RelationKind
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Раздел «Клиенты» (шаг 1.7, ТЗ 5.8, 5.9, 4.17; макеты ClList, ClNew, ClCardInfo, ClRelAdd, ClRelBack). */
@OptIn(ExperimentalTestApi::class)
class ClientsUiTest {
    private val folded = 412.dp
    private val unfolded = 760.dp
    private val height = 900.dp
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private fun seed(last: String, first: String, sex: Sex? = null, birth: LocalDate? = null): String =
        runBlocking { clients.create(ClientDraft(last, first, sex = sex, birthDate = birth)) }

    private fun all() = runBlocking { clients.rows(TestData.now).first() }

    private fun ComposeUiTest.show(width: () -> androidx.compose.ui.unit.Dp = { folded }) {
        setContent { Box(Modifier.size(width(), height)) { App("0.1.0", settings, clients, TestData.groupsOf(settings), TestData.calendarOf(settings), clock = TestData.clock) } }
        awaitNode(hasTestTag(navTag(Section.CLIENTS)))
        onNodeWithTag(navTag(Section.CLIENTS)).performClick()
    }

    private fun ComposeUiTest.openCard(name: String) {
        awaitNode(hasTestTag(clientRowTag(name)))
        onNodeWithTag(clientRowTag(name)).performClick()
        // Тренировок у клиентов в этих тестах нет — карточка открывается сразу на «Инфо».
        awaitNode(hasTestTag(ADD_RELATIVE_TAG))
    }

    private fun ComposeUiTest.menu(item: String) {
        onNodeWithTag(CARD_MENU_TAG).performClick()
        onNodeWithText(item).performClick()
    }

    @Test
    fun firstClientFromEmptyList() = runComposeUiTest {
        show()
        awaitNode(hasText("Пока нет клиентов"))
        onNodeWithText("Добавить клиента").performClick()
        // Форма во весь экран: нижней панели нет.
        onNodeWithTag(navTag(Section.HOME)).assertDoesNotExist()
        onNodeWithText("Сохранить").performClick()
        onNodeWithText("Введите фамилию").assertIsDisplayed()

        onNodeWithTag(CLIENT_LAST_TAG).performTextInput("Ковалёв")
        onNodeWithTag(CLIENT_FIRST_TAG).performTextInput("Артём")
        onNodeWithText("Мужской").assertIsSelected()
        onNodeWithText("Предложен по фамилии").assertIsDisplayed()
        onNodeWithTag(CLIENT_BIRTH_TAG).performTextInput("14.02.2016")
        onNodeWithText(s("10_лет · ребёнок")).assertIsDisplayed()
        onNodeWithTag(CLIENT_PHONE_TAG).performTextInput("8 919 922-41-08")
        onNodeWithText("Сохранить").performClick()

        // Открылась карточка нового клиента: сразу «Инфо», тренировок ещё нет.
        awaitNode(hasText("Родственников нет"))
        onNodeWithText("Ковалёв Артём").assertIsDisplayed()
        onNodeWithText("Ребёнок").assertIsDisplayed()
        onNodeWithText("Мужской").assertIsDisplayed()
        onNodeWithText("+7 919 922-41-08").assertIsDisplayed()
        onNodeWithText("Родственников нет").assertIsDisplayed()
        onNodeWithTag(cardTabTag(CardTab.TRAININGS)).assertDoesNotExist()

        onNodeWithContentDescription("Назад").performClick()
        awaitNode(hasTestTag(clientRowTag("Ковалёв Артём")))
        onNodeWithTag(clientRowTag("Ковалёв Артём")).assertTextContains(s("10_лет · без расписания"))
        assertEquals("+79199224108", all().single().client.contactPhone)
    }

    @Test
    fun similarClientIsCaughtBeforeSaving() = runComposeUiTest {
        seed("Дельфенурова", "Аделина", Sex.FEMALE, LocalDate(2017, 5, 12))
        show()
        awaitNode(hasTestTag(clientRowTag("Дельфенурова Аделина")))
        onNodeWithTag(CLIENT_ADD_TAG).performClick()
        onNodeWithTag(CLIENT_LAST_TAG).performTextInput("Дельфинурова")
        onNodeWithTag(CLIENT_FIRST_TAG).performTextInput("Аделина")
        onNodeWithText("Сохранить").performClick()

        onNodeWithText("Похожий клиент уже есть").assertIsDisplayed()
        onNodeWithText("Дельфенурова Аделина").assertIsDisplayed()
        onNodeWithText("Это она?").assertIsDisplayed()
        onNodeWithText("Да, выбрать").performClick()

        // Открыта карточка той, что уже была, нового клиента нет.
        awaitNode(hasTestTag(CARD_MENU_TAG))
        onNodeWithText("Дельфенурова Аделина").assertIsDisplayed()
        assertEquals(1, all().size)
    }

    @Test
    fun similarClientCanStillBeCreated() = runComposeUiTest {
        seed("Дельфенурова", "Аделина", Sex.FEMALE)
        show()
        awaitNode(hasTestTag(clientRowTag("Дельфенурова Аделина")))
        onNodeWithTag(CLIENT_ADD_TAG).performClick()
        onNodeWithTag(CLIENT_LAST_TAG).performTextInput("Дельфинурова")
        onNodeWithTag(CLIENT_FIRST_TAG).performTextInput("Аделина")
        onNodeWithText("Сохранить").performClick()
        onNodeWithText("Нет, создать нового").performClick()

        awaitNode(hasText("Дельфинурова Аделина"))
        waitUntil(timeoutMillis = 5_000) { all().size == 2 }
    }

    @Test
    fun searchTreatsYoAsYeAndOffersNewClient() = runComposeUiTest {
        seed("Ковалёв", "Артём", Sex.MALE)
        seed("Ковалёва", "Наталья", Sex.FEMALE)
        seed("Орлова", "Ксения", Sex.FEMALE)
        show()
        awaitNode(hasTestTag(clientRowTag("Орлова Ксения")))
        onNodeWithText(s("3_клиента · по фамилии")).assertIsDisplayed()

        onNodeWithTag(CLIENT_SEARCH_TAG).performTextInput("ковалев")
        onNodeWithTag(clientRowTag("Ковалёв Артём")).assertIsDisplayed()
        onNodeWithTag(clientRowTag("Ковалёва Наталья")).assertIsDisplayed()
        onNodeWithTag(clientRowTag("Орлова Ксения")).assertDoesNotExist()
        onNodeWithText(s("2_клиента · по фамилии")).assertIsDisplayed()

        onNodeWithTag(CLIENT_SEARCH_TAG).performTextClearance()
        onNodeWithTag(CLIENT_SEARCH_TAG).performTextInput("Смирнова")
        onNodeWithText("Никого не нашлось").assertIsDisplayed()
        onNodeWithText("Новый клиент: Смирнова").performClick()
        onNodeWithTag(CLIENT_LAST_TAG).assertTextContains("Смирнова")
    }

    @Test
    fun rareFilterFromMenuStandsAsChip() = runComposeUiTest {
        seed("Ковалёв", "Артём", Sex.MALE, LocalDate(2016, 2, 14))
        seed("Ковалёва", "Наталья", Sex.FEMALE, LocalDate(1985, 6, 23))
        show()
        awaitNode(hasTestTag(clientRowTag("Ковалёв Артём")))

        onNodeWithTag(CLIENT_SORT_TAG).performClick()
        onNodeWithText("Взрослые").performClick()
        onNodeWithTag(clientRowTag("Ковалёв Артём")).assertDoesNotExist()
        onNodeWithTag(clientRowTag("Ковалёва Наталья")).assertIsDisplayed()

        onNodeWithContentDescription("Убрать фильтр «Взрослые»").performClick()
        onNodeWithTag(clientRowTag("Ковалёв Артём")).assertIsDisplayed()
    }

    @Test
    fun archiveWithUndoAndArchiveFilter() = runComposeUiTest {
        val id = seed("Захаров", "Никита", Sex.MALE)
        seed("Орлова", "Ксения", Sex.FEMALE)
        show()
        openCard("Захаров Никита")

        menu("В архив")
        awaitNode(hasText("Захаров Никита в архиве"))
        awaitNode(hasText("В архиве"))
        onNodeWithText("Отменить").performClick()
        waitUntil(timeoutMillis = 5_000) { all().single { it.client.id == id }.client.status == ClientStatus.ACTIVE }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("В архиве")).fetchSemanticsNodes().isEmpty() }

        menu("В архив")
        waitUntil(timeoutMillis = 5_000) { all().single { it.client.id == id }.client.status == ClientStatus.ARCHIVED }
        onNodeWithContentDescription("Назад").performClick()
        awaitNode(hasTestTag(filterChipTag(ClientFilter.ARCHIVE)))
        onNodeWithTag(clientRowTag("Захаров Никита")).assertDoesNotExist()
        onNodeWithText("Активные · 1").assertIsDisplayed()
        onNodeWithTag(filterChipTag(ClientFilter.ARCHIVE)).performClick()
        onNodeWithTag(clientRowTag("Захаров Никита")).assertIsDisplayed()
        onNodeWithTag(clientRowTag("Орлова Ксения")).assertDoesNotExist()
    }

    @Test
    fun motherInOneCardIsSonInTheOther() = runComposeUiTest {
        seed("Ковалёв", "Артём", Sex.MALE, LocalDate(2016, 2, 14))
        seed("Ковалёва", "Наталья", Sex.FEMALE, LocalDate(1985, 6, 23))
        show()
        openCard("Ковалёв Артём")
        awaitNode(hasText("Родственников нет"))

        // «+ Родственник» → Наталья → «Мать» → «Сохранить»: 4 нажатия (4.17).
        onNodeWithTag(ADD_RELATIVE_TAG).performScrollTo().performClick()
        onNodeWithText("Родственник для Артёма").assertIsDisplayed()
        onNodeWithTag(relativePickTag("Ковалёва Наталья")).performClick()
        // Наталья старше и женщина: «Мать» есть, «Дочь» и «Отец» — нет.
        onNodeWithText("Дочь").assertDoesNotExist()
        onNodeWithText("Отец").assertDoesNotExist()
        onNodeWithText("Мать").performClick()
        onNodeWithText("У Натальи появится: Сын · Ковалёв Артём").assertIsDisplayed()
        onNodeWithText("Сохранить").performClick()

        awaitNode(hasTestTag(relativeRowTag("Ковалёва Наталья")))
        onNodeWithTag(relativeRowTag("Ковалёва Наталья")).assertTextContains("Мать")

        // Карточка Натальи: Артём — сын; «Назад» — снова карточка Артёма.
        onNodeWithTag(relativeRowTag("Ковалёва Наталья")).performClick()
        awaitNode(hasTestTag(relativeRowTag("Ковалёв Артём")))
        onNodeWithTag(relativeRowTag("Ковалёв Артём")).assertTextContains("Сын")
        onNodeWithText("Взрослый").assertIsDisplayed()
        onNodeWithContentDescription("Назад").performClick()
        awaitNode(hasTestTag(relativeRowTag("Ковалёва Наталья")))
        onNodeWithText("Ребёнок").assertIsDisplayed()
    }

    @Test
    fun newRelativeWithUnknownSexAsksAboutClient() = runComposeUiTest {
        val artem = seed("Ковалёв", "Артём")
        show()
        openCard("Ковалёв Артём")
        onNodeWithTag(ADD_RELATIVE_TAG).performScrollTo().performClick()
        onNodeWithTag(NEW_RELATIVE_TAG).performClick()
        onNodeWithTag(NEW_RELATIVE_LAST_TAG).performTextClearance()
        onNodeWithTag(NEW_RELATIVE_LAST_TAG).performTextInput("Ковалёва")
        onNodeWithTag(NEW_RELATIVE_FIRST_TAG).performTextInput("Алиса")
        onNodeWithText("Сестра").performClick()
        // Пол Артёма не указан — вопрос в той же форме.
        onNodeWithText("Артём для Алисы").assertIsDisplayed()
        onNodeWithText("брат").performClick()
        onNodeWithText("У Алисы появится: Брат · Ковалёв Артём").assertIsDisplayed()
        onNodeWithText("Сохранить").performClick()

        awaitNode(hasTestTag(relativeRowTag("Ковалёва Алиса")))
        waitUntil(timeoutMillis = 5_000) { all().single { it.client.id == artem }.client.sex == Sex.MALE }
        val alisa = all().single { it.client.firstName == "Алиса" }.client
        assertEquals(Sex.FEMALE, alisa.sex)
    }

    @Test
    fun relationChangedFromLongPressAndRemovedWithUndo() = runComposeUiTest {
        val artem = seed("Ковалёв", "Артём", Sex.MALE, LocalDate(2016, 2, 14))
        val alisa = seed("Ковалёва", "Алиса", Sex.FEMALE, LocalDate(2014, 3, 1))
        runBlocking { clients.addRelation(artem, alisa, RelationRole(RelationKind.SIBLING)) }
        show()
        openCard("Ковалёв Артём")
        awaitNode(hasTestTag(relativeRowTag("Ковалёва Алиса")))
        onNodeWithTag(relativeRowTag("Ковалёва Алиса")).assertTextContains("Сестра")

        onNodeWithTag(relativeRowTag("Ковалёва Алиса")).performTouchInput { longClick() }
        onNodeWithText("Изменить связь").performClick()
        onNodeWithText("Двоюродная сестра").performClick()
        onNodeWithText("Сохранить").performClick()
        awaitNode(hasText("Двоюродная сестра"))

        // «Убрать связь» — действием строки, как у TalkBack; «Отменить» возвращает.
        runOnIdle {
            onNodeWithTag(relativeRowTag("Ковалёва Алиса")).fetchSemanticsNode().config[SemanticsActions.CustomActions]
                .single { it.label == "Убрать связь" }.action()
        }
        onNodeWithText("Связь убрана").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(relativeRowTag("Ковалёва Алиса"))).fetchSemanticsNodes().isEmpty() }
        onNodeWithText("Родственников нет").assertIsDisplayed()
        onNodeWithText("Отменить").performClick()
        awaitNode(hasTestTag(relativeRowTag("Ковалёва Алиса")))
    }

    @Test
    fun mergeDuplicatesKeepsChosenCard() = runComposeUiTest {
        seed("Дельфенурова", "Аделина", Sex.FEMALE, LocalDate(2017, 5, 12))
        val typo = seed("Дельфинурова", "Аделина")
        show()
        openCard("Дельфинурова Аделина")

        menu("Объединить с…")
        onNodeWithText("Похожие").assertIsDisplayed()
        onNodeWithTag(mergePickTag("Дельфенурова Аделина")).performClick()
        onNodeWithText("Объединить карточки?").assertIsDisplayed()
        // Остаётся карточка без опечатки.
        onNodeWithTag(mergeKeepTag("Дельфинурова Аделина")).assertIsSelected()
        onNodeWithTag(mergeKeepTag("Дельфенурова Аделина")).performClick()
        onNodeWithTag(mergeKeepTag("Дельфинурова Аделина")).assertIsNotSelected()
        onNodeWithText("Объединить").performClick()

        onNodeWithText("Карточки объединены").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) { all().size == 1 }
        val left = all().single().client
        assertEquals("Дельфенурова", left.lastName)
        assertTrue(left.id != typo)
        awaitNode(hasText(s("9_лет")))
    }

    @Test
    fun deleteCompletelyNeedsSurname() = runComposeUiTest {
        seed("Захаров", "Никита", Sex.MALE)
        show()
        openCard("Захаров Никита")

        menu("Удалить полностью")
        onNodeWithText("Удалить клиента полностью?").assertIsDisplayed()
        onNodeWithText("Дальше").performClick()
        onNodeWithText("Введите фамилию клиента").assertIsDisplayed()
        onNodeWithTag(DELETE_SURNAME_TAG).performTextInput("Захар")
        onNodeWithText("Удалить навсегда").assertIsNotEnabled()
        onNodeWithTag(DELETE_SURNAME_TAG).performTextInput("ов")
        onNodeWithText("Удалить навсегда").assertIsEnabled().performClick()

        onNodeWithText("Захаров Никита удалён").assertIsDisplayed()
        awaitNode(hasText("Пока нет клиентов"))
        assertTrue(all().isEmpty())
    }

    @Test
    fun unfoldedShowsListAndCardSideBySide() = runComposeUiTest {
        var width by mutableStateOf(unfolded)
        seed("Орлова", "Ксения", Sex.FEMALE, LocalDate(2015, 3, 10))
        seed("Ковалёв", "Артём", Sex.MALE)
        show { width }
        awaitNode(hasText("Откройте клиента"))

        openCard("Орлова Ксения")
        // Разложено: список слева, карточка справа, её строка выделена, «Назад» не нужен.
        onNodeWithTag(clientRowTag("Орлова Ксения")).assertIsSelected()
        onNodeWithTag(clientRowTag("Ковалёв Артём")).assertIsDisplayed()
        onNodeWithContentDescription("Назад").assertDoesNotExist()

        // Сложили: карточка во весь экран с «Назад».
        width = folded
        waitForIdle()
        onNodeWithTag(clientRowTag("Ковалёв Артём")).assertDoesNotExist()
        onNodeWithTag(CARD_MENU_TAG).assertIsDisplayed()
        onNodeWithText(s("11_лет")).assertIsDisplayed()

        // Разложили снова: всё на месте.
        width = unfolded
        waitForIdle()
        onNodeWithTag(clientRowTag("Ковалёв Артём")).assertIsDisplayed()
        onNodeWithTag(clientRowTag("Орлова Ксения")).assertIsSelected()
    }

    @Test
    fun newClientFormSurvivesFolding() = runComposeUiTest {
        var width by mutableStateOf(folded)
        show { width }
        awaitNode(hasText("Добавить клиента"))
        onNodeWithText("Добавить клиента").performClick()
        onNodeWithTag(CLIENT_LAST_TAG).performTextInput("Орлова")

        width = unfolded
        waitForIdle()
        onNodeWithTag(CLIENT_LAST_TAG).assertTextContains("Орлова")
        width = folded
        waitForIdle()
        onNodeWithTag(CLIENT_LAST_TAG).assertTextContains("Орлова")
        onNodeWithText("Женский").assertIsSelected()
    }
}
