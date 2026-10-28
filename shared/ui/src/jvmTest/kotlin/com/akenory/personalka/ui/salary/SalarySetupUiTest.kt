package com.akenory.personalka.ui.salary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.account.FakeServer
import com.akenory.personalka.data.account.MemoryTokens
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.SalarySetupFormat
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.settings.CATEGORY_ROW_TAG
import com.akenory.personalka.ui.settings.CO_COACHING_TAG
import com.akenory.personalka.ui.settings.PROFILE_ROW_TAG
import com.akenory.personalka.ui.settings.SCHEME_ROW_TAG
import com.akenory.personalka.ui.settings.profileSchemeTag
import com.akenory.personalka.ui.settings.PROFILE_ADVANCE_TAG
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Мастер «Настроить зарплату» и зарплата в «Профиле» (шаг 3.6б, ТЗ 5.18, 5.16, 4.11). «Сейчас» — вт 20.10.2026. */
@OptIn(ExperimentalTestApi::class)
class SalarySetupUiTest {
    private val settings = TestData.ready()

    private fun s(text: String) = text.replace('_', RuText.NBSP).replace('^', RuText.THIN)
    private fun rub(r: Long) = r * 100
    private fun typeId(name: String) = runBlocking { settings.types.first().single { it.name == name }.id }
    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }

    private val server = FakeServer()
    private val account = AccountRepository(server.api(), MemoryTokens(), MemoryPrefs(), "Samsung SM-F976B") { "coach-1" }

    private fun ComposeUiTest.start(signedIn: Boolean = false) {
        if (signedIn) {
            // Администратор: у него открыто всё, в том числе «Официально с налогом 6,5 %» (1.5).
            server.signedUp("coach@example.ru", "длинный-пароль", "Андрей")
            server.admin = "coach@example.ru"
            runBlocking { account.login("coach@example.ru", "длинный-пароль") }
        }
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App(
                    "0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings),
                    account = if (signedIn) account else null, clock = TestData.clock,
                )
            }
        }
        awaitNode(hasTestTag(navTag(Section.SALARY)))
    }

    @Test
    fun wizardInThreeStepsWithoutScheme() = runComposeUiTest {
        start()
        onNodeWithTag(navTag(Section.SALARY)).performClick()
        awaitNode(hasText(SalarySetupFormat.CATEGORY_TITLE))
        onNodeWithText("Шаг 1 из 3").assertExists()
        onNodeWithText(SalarySetupFormat.NEXT).assertIsNotEnabled()
        onNodeWithTag(categoryTag(CoachCategory.MASTER)).performClick()
        onNodeWithText(SalarySetupFormat.NEXT).performClick()

        // Без открытой «налог 6,5 %» выбирать нечего: шага схемы нет, сразу ставки.

        // Ставки из сетки Мастер тренера: ДБ — «малый басс», зал — как ББ.
        awaitNode(hasText(SalarySetupFormat.RATES_TITLE))
        onNodeWithTag(setupRateTag("ПТ", "ББ", 0)).assertTextContains("920")
        onNodeWithTag(setupRateTag("ПТ", "ДБ", 2)).assertTextContains("900")
        onNodeWithTag(setupRateTag("Сплит", "Зал", 1)).performScrollTo().assertTextContains("1600")
        onNodeWithTag(setupRateTag("Груд", "ББ", 0)).performScrollTo().assertTextContains("670")
        // Поправили ДБ у ПТ — у неё не будет связи с сеткой.
        onNodeWithTag(setupRateTag("ПТ", "ДБ", 0)).performScrollTo().performTextReplacement("850")
        onNodeWithText(SalarySetupFormat.NEXT).performClick()

        awaitNode(hasText(SalarySetupFormat.TIERS_TITLE))
        onNodeWithTag(setupAsPtTag("Кинезио")).performScrollTo().performClick()
        onNodeWithText(SalarySetupFormat.DONE).performClick()

        awaitNode(hasTestTag(SALARY_EARNED_TAG))
        val jan = LocalDate(2026, 1, 1)
        val category = runBlocking { settings.salary.categories.first() }.single()
        assertEquals(CoachCategory.MASTER, category.category)
        assertEquals(jan, category.validFrom)
        val scheme = runBlocking { settings.salary.schemes.first() }.single().scheme
        assertEquals(PaySchemeKind.NONE, scheme.kind)
        assertEquals(jan, scheme.validFrom)
        assertEquals(null, scheme.advanceDefault)
        val rates = runBlocking { settings.rates.rates.first() }.map { it.rate }
        val pt = rates.filter { it.typeId == typeId("ПТ") }
        assertEquals(3, pt.size)
        assertEquals(rub(850), pt.single { it.locationId == placeId("ДБ") }.amountT1)
        assertEquals(setOf(jan), rates.map { it.validFrom }.toSet())
        assertEquals(3, rates.count { it.typeId == typeId("Грудничковое") })
        val tiers = runBlocking { settings.rates.tiers.first() }.single()
        assertEquals(jan, tiers.validFrom)
        assertEquals(setOf("ПТ", "Сплит", "Грудничковое", "Гидрореабилитация").map(::typeId).toSet(), tiers.ptTypeIds)
    }

    @Test
    fun wizardWithHiddenScheme() = runComposeUiTest {
        start(signedIn = true)
        onNodeWithTag(navTag(Section.SALARY)).performClick()
        awaitNode(hasText("Шаг 1 из 4"))
        onNodeWithTag(categoryTag(CoachCategory.MASTER)).performClick()
        onNodeWithText(SalarySetupFormat.NEXT).performClick()

        // Открыта «налог 6,5 %»: выбор из двух, «Вся сумма» отмечена сразу; ИП и «Полностью официально» нет.
        awaitNode(hasText(SalarySetupFormat.SCHEME_TITLE))
        onNodeWithTag(schemeTag(PaySchemeKind.FULL_OFFICIAL)).assertDoesNotExist()
        onNodeWithTag(schemeTag(PaySchemeKind.IP)).assertDoesNotExist()
        onNodeWithTag(SETUP_SCHEME_FROM_TAG).assertDoesNotExist()
        onNodeWithTag(schemeTag(PaySchemeKind.HALF_OFFICIAL)).performClick()
        onNodeWithTag(SETUP_ADVANCE_TAG).performTextReplacement("13 546,50")
        onNodeWithTag(SETUP_SCHEME_FROM_TAG).performScrollTo().assertTextContains("январь", substring = true, ignoreCase = true)
        onNodeWithText(SalarySetupFormat.NEXT).performClick()
        awaitNode(hasText(SalarySetupFormat.RATES_TITLE))
        onNodeWithText(SalarySetupFormat.NEXT).performClick()
        awaitNode(hasText(SalarySetupFormat.TIERS_TITLE))
        onNodeWithText(SalarySetupFormat.DONE).performClick()

        awaitNode(hasTestTag(SALARY_EARNED_TAG))
        val scheme = runBlocking { settings.salary.schemes.first() }.single().scheme
        assertEquals(PaySchemeKind.HALF_OFFICIAL, scheme.kind)
        assertEquals(LocalDate(2026, 1, 1), scheme.validFrom)
        assertEquals(1_354_650L, scheme.advanceDefault)
    }

    @Test
    fun profileHidesSchemeWithoutChoice() = runComposeUiTest {
        start()
        onNodeWithTag(navTag(Section.MORE)).performClick()
        awaitNode(hasTestTag(PROFILE_ROW_TAG))
        onNodeWithTag(PROFILE_ROW_TAG).performClick()
        awaitNode(hasTestTag(CATEGORY_ROW_TAG))
        onNodeWithTag(CATEGORY_ROW_TAG).assertTextContains(SalarySetupFormat.NOT_SET, substring = true)
        // Выбирать не из чего — строки схемы нет.
        onNodeWithTag(SCHEME_ROW_TAG).assertDoesNotExist()

        onNodeWithTag(CO_COACHING_TAG).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { settings.salary.coCoaching.first() } }
    }

    @Test
    fun profileSchemeWithHiddenScheme() = runComposeUiTest {
        start(signedIn = true)
        onNodeWithTag(navTag(Section.MORE)).performClick()
        awaitNode(hasTestTag(PROFILE_ROW_TAG))
        onNodeWithTag(PROFILE_ROW_TAG).performClick()
        awaitNode(hasTestTag(SCHEME_ROW_TAG))
        onNodeWithTag(SCHEME_ROW_TAG).performScrollTo().performClick()
        awaitNode(hasTestTag(profileSchemeTag(PaySchemeKind.HALF_OFFICIAL)))
        onNodeWithTag(profileSchemeTag(PaySchemeKind.IP)).assertDoesNotExist()
        onNodeWithTag(profileSchemeTag(PaySchemeKind.HALF_OFFICIAL)).performClick()
        onNodeWithTag(PROFILE_ADVANCE_TAG).performTextReplacement("13000")
        onNodeWithText("Сохранить").performClick()
        awaitNode(hasText(s("Официально с налогом 6,5_% · аванс 13^000_₽ · с_01.01.2026")))
    }
}
