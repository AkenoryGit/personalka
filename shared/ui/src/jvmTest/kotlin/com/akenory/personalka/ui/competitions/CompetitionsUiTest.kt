package com.akenory.personalka.ui.competitions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.results.ResultDraft
import com.akenory.personalka.domain.client.ClientDraft
import com.akenory.personalka.domain.competition.CollectItem
import com.akenory.personalka.domain.competition.CompetitionDraft
import com.akenory.personalka.domain.competition.EntryRule
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.result.SwimEvent
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/**
 * «Ещё → Соревнования» (шаги 5.39–5.41, ТЗ 5.21): форма с условиями, подбор по полу и годам, «Год и пол»,
 * отметки «сдал», старт с заявочным временем и итогом, «Поделиться». «Сейчас» — вт 20.10.2026. Имена придуманы.
 */
@OptIn(ExperimentalTestApi::class)
class CompetitionsUiTest {
    private val settings = TestData.ready()
    private val clients = TestData.clientsOf(settings)
    private val repo = clients.competitions
    private val today = TestData.now.date
    private var shared: String? = null

    private fun s(text: String) = text.replace('_', RuText.NBSP).replace('^', RuText.THIN)

    private fun kid(last: String, first: String, sex: Sex?, year: Int?): String =
        runBlocking { clients.create(ClientDraft(last, first, sex = sex, birthDate = year?.let { LocalDate(it, 4, 10) })) }

    private val fee = CollectItem("fee", "Взнос", 150_000)
    private val paper = CollectItem("paper", "Справка")

    private fun cup(date: LocalDate = LocalDate(2026, 12, 12), pool: Int = 50): String = runBlocking {
        repo.create(
            CompetitionDraft(
                "Кубок города", date, pool, null,
                listOf(EntryRule(Sex.MALE, 2010, 2015), EntryRule(Sex.FEMALE, 2008, 2013)), listOf(fee, paper),
            ),
        )
    }

    private fun ComposeUiTest.openCompetitions() {
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App("0.1.0", settings, clients, TestData.groupsOf(settings), TestData.calendarOf(settings), shareText = { shared = it }, clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        onNodeWithTag(moreRowTag(MoreItem.COMPETITIONS)).performClick()
    }

    @Test
    fun newCompetitionWithRulesAndItems() = runComposeUiTest {
        openCompetitions()
        awaitNode(hasText("Соревнований пока нет"))
        onNodeWithText("Добавить соревнование").performClick()
        // Форма во весь экран: нижней панели нет.
        onNodeWithTag(navTag(Section.HOME)).assertDoesNotExist()
        onNodeWithText("Сохранить").performClick()
        onNodeWithText("Введите название").assertIsDisplayed()
        onNodeWithTag(COMPETITION_NAME_TAG).performTextInput("Кубок города")
        onNodeWithText(s("50_м")).performClick()
        onNodeWithText("Без условий подбор покажет всех клиентов.").assertIsDisplayed()
        // Первое условие — мальчики, второе — девочки.
        onNodeWithTag(COMPETITION_RULE_ADD_TAG).performScrollTo().performClick()
        onNodeWithTag(ruleFromTag(0)).performTextInput("2010")
        onNodeWithTag(ruleToTag(0)).performTextInput("2015")
        onNodeWithTag(COMPETITION_RULE_ADD_TAG).performScrollTo().performClick()
        onNode(hasAnyAncestor(hasTestTag(ruleSexTag(1, Sex.FEMALE))) and isSelected()).assertExists()
        onNodeWithTag(ruleFromTag(1)).performTextInput("2013")
        onNodeWithTag(ruleToTag(1)).performTextInput("2008")
        onNodeWithTag(itemPresetTag("Взнос")).performScrollTo().performClick()
        onNodeWithTag(itemAmountTag(0)).performTextInput("1500")
        onNodeWithTag(itemPresetTag("Справка")).performScrollTo().performClick()
        // «Взнос» уже есть — его чипа больше нет.
        onNodeWithTag(itemPresetTag("Взнос")).assertDoesNotExist()
        onNodeWithText("Сохранить").performClick()
        onNodeWithText("Годы — четырьмя цифрами, «с» не позже «по»: 2010–2015").performScrollTo().assertIsDisplayed()
        // Второе условие убрали — остались только мальчики.
        onNodeWithTag(ruleRemoveTag(1)).performScrollTo().performClick()
        onNodeWithText("Сохранить").performClick()

        // Открылась карточка.
        awaitNode(hasTestTag(COMPETITION_PICK_TAG))
        onNodeWithText(s("вт_20.10.2026 · бассейн 50_м")).assertIsDisplayed()
        onNodeWithText("Мальчики 2010–2015").assertIsDisplayed()
        onNodeWithText(CompetitionFormatNoParticipants).assertIsDisplayed()
        val saved = runBlocking { repo.rows.first().single().competition }
        assertEquals("Кубок города" to 50, saved.name to saved.poolM)
        assertEquals(listOf(EntryRule(Sex.MALE, 2010, 2015)), saved.rules)
        assertEquals(listOf("Взнос" to 150_000L, "Справка" to null), saved.items.map { it.title to it.amount })
    }

    @Test
    fun pickByYearAndSexThenMarkHanded() = runComposeUiTest {
        kid("Петров", "Лев", Sex.MALE, 2014)
        kid("Соколова", "Мира", Sex.FEMALE, 2012)
        kid("Ковалёв", "Иван", Sex.MALE, 2009) // старше мальчиков
        val kim = kid("Ким", "Дан", null, 2011) // без пола
        cup()
        openCompetitions()
        awaitNode(hasTestTag(competitionRowTag("Кубок города")))
        onNodeWithText(s("сб_12.12 · участников пока нет")).assertIsDisplayed()
        onNodeWithTag(competitionRowTag("Кубок города")).performClick()
        onNodeWithTag(COMPETITION_PICK_TAG).performClick()

        awaitNode(hasText("Мальчики 2010–2015 · 1"))
        onNodeWithText("Девочки 2008–2013 · 1").assertIsDisplayed()
        onNodeWithTag(pickRowTag("Ковалёв Иван")).assertDoesNotExist()
        onNodeWithText("Добавить").assertIsNotEnabled()
        onNodeWithTag(PICK_ARCHIVED_TAG).assertIsOff()
        // Ким без пола — внизу; «Год и пол» пишет в карточку, и он в разделе мальчиков.
        onNodeWithTag(PICK_UNKNOWN_TAG).performScrollTo().assertTextContains("Не указан год или пол · 1").performClick()
        onNodeWithTag(pickUnknownRowTag("Ким Дан")).performScrollTo().performClick()
        onNodeWithTag(BIRTH_GUESS_TAG).assertDoesNotExist()
        onNodeWithTag(birthSexTag(Sex.MALE)).performClick()
        onNodeWithText("Сохранить").performClick()
        awaitNode(hasText("Мальчики 2010–2015 · 2"))
        assertEquals(Sex.MALE, runBlocking { clients.client(kim).first()?.sex })

        onNodeWithTag(pickRowTag("Петров Лев")).performClick()
        onNodeWithTag(pickRowTag("Ким Дан")).performClick()
        onNodeWithTag(pickRowTag("Ким Дан")).performClick()
        onNodeWithTag(pickRowTag("Ким Дан")).performClick()
        onNodeWithText("Добавить 2").performClick()

        // Карточка: участники и отметки «сдал».
        awaitNode(hasText("Участники · 2"))
        onNodeWithText(s("Добавлено 2_участника")).assertIsDisplayed()
        onNodeWithText("Взнос · не сдали 2").assertIsDisplayed()
        onNodeWithTag(handTag("Петров Лев", "Взнос"), useUnmergedTree = true).performClick()
        awaitNode(hasText("Взнос · не сдали 1"))
        onNodeWithTag(COMPETITION_COLLECTED_TAG).assertTextContains(s("Собрано 1^500 из 3^000_₽"))
        // Отбор «не сдали взнос» — остаётся Ким.
        onNodeWithTag(missingTag("Взнос")).performClick()
        onNodeWithTag(participantTag("Петров Лев")).assertDoesNotExist()
        onNodeWithTag(participantTag("Ким Дан")).assertIsDisplayed()

        // «Кто не сдал» — текстом.
        onNodeWithTag(COMPETITION_SHARE_TAG).performClick()
        onNodeWithTag(SHARE_RESULTS_TAG).assertDoesNotExist() // соревнования ещё впереди
        onNodeWithTag(SHARE_DEBT_TAG).performClick()
        assertEquals(
            s("Кубок города · сб_12.12.2026\n\nНе сдали:\nВзнос 1^500_₽ — Ким Дан\nСправка — Ким Дан, Петров Лев"),
            shared,
        )
    }

    @Test
    fun startTakesBestInSamePoolAndResultGoesToCard() = runComposeUiTest {
        val lev = kid("Петров", "Лев", Sex.MALE, 2014)
        val free50 = SwimEvent(SwimStyle.FREESTYLE, 50, 50)
        runBlocking {
            clients.results.save(ResultDraft(lev, LocalDate(2025, 3, 1), free50, 3812, ResultKind.TRAINING))
            clients.results.save(ResultDraft(lev, LocalDate(2026, 10, 1), free50.copy(poolM = 25), 3500, ResultKind.TRAINING))
        }
        // Соревнования сегодня — можно вносить результат и место.
        val id = cup(date = today)
        runBlocking { repo.add(id, listOf(lev)) }
        openCompetitions()
        awaitNode(hasTestTag(competitionRowTag("Кубок города")))
        onNodeWithTag(competitionRowTag("Кубок города")).performClick()
        onNodeWithTag(participantTag("Петров Лев")).performClick()
        onNodeWithTag(START_ADD_TAG).performClick()
        // Лучшее в 50-метровом — 38,12; 25-метровое (35,00) не подставляется.
        onNodeWithTag(START_HINT_TAG).assertTextContains("Лучшее: 38,12 · 01.03.2025")
        onNodeWithTag(START_ENTRY_TAG).assertTextContains("38,12")
        onNodeWithTag(START_RESULT_TAG).performTextInput("3705")
        onNodeWithTag(START_PLACE_TAG).performTextInput("2")
        onNodeWithText("Сохранить").performClick()
        awaitNode(hasText(s("Личный рекорд, −_1,07")))
        // После «Сохранить» снова окно участника — со стартом; «Готово» закрывает его.
        onNodeWithText(s("заявка 38,12 · результат 37,05 · 2 место")).assertIsDisplayed()
        onNodeWithText("Готово").performClick()

        val card = runBlocking { clients.results.results(lev).first().single { it.date == today } }
        assertEquals(ResultKind.COMPETITION to "Кубок города", card.kind to card.eventName)
        assertEquals(3705, card.timeCs)
        val start = assertNotNull(runBlocking { repo.rows.first().single().participants.single().starts.singleOrNull() })
        assertEquals(null to 2, start.entryCs to start.place)

        // Строка участника — с заявочным временем; «Итоги» — с рекордом и местом.
        onNodeWithTag(startsLineTag("Петров Лев"), useUnmergedTree = true).assertTextContains(s("50_м вольный 38,12"))
        onNodeWithTag(COMPETITION_SHARE_TAG).performClick()
        onNodeWithTag(SHARE_RESULTS_TAG).performClick()
        assertEquals(
            s("Итоги · Кубок города · 20.10.2026\n\nПетров Лев\n50_м вольный стиль — 37,05, личный рекорд −_1,07, 2 место"),
            shared,
        )
        onNodeWithTag(COMPETITION_SHARE_TAG).performClick()
        onNodeWithTag(SHARE_ENTRY_TAG).performClick()
        assertTrue(shared!!.contains("Петров Лев, 2014 — 38,12"), shared)
    }

    private companion object {
        const val CompetitionFormatNoParticipants = "Участников пока нет"
    }
}
