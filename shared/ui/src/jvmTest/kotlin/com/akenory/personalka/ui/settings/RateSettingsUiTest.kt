package com.akenory.personalka.ui.settings

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
import com.akenory.personalka.domain.format.RateFormat
import com.akenory.personalka.domain.format.RuText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.ui.App
import com.akenory.personalka.ui.TestData
import com.akenory.personalka.ui.awaitNode
import com.akenory.personalka.ui.screens.MoreItem
import com.akenory.personalka.ui.screens.moreRowTag
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.navTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Ещё → Настройки → Ставки и ступени» (шаг 3.6, ТЗ 5.16, 3.3). «Сейчас» — вт 20.10.2026 15:40. */
@OptIn(ExperimentalTestApi::class)
class RateSettingsUiTest {
    private val settings = TestData.ready()

    private fun s(text: String) = text.replace('_', RuText.NBSP).replace('^', RuText.THIN)
    private fun rub(r: Long) = r * 100
    private fun typeId(name: String) = runBlocking { settings.types.first().single { it.name == name }.id }
    private fun placeId(short: String) = runBlocking { settings.places.first().single { it.shortName == short }.id }
    private fun rates() = runBlocking { settings.rates.rates.first().map { it.rate } }

    private fun ComposeUiTest.openRates() {
        setContent {
            Box(Modifier.size(412.dp, 900.dp)) {
                App("0.1.0", settings, TestData.clientsOf(settings), TestData.groupsOf(settings), TestData.calendarOf(settings), clock = TestData.clock)
            }
        }
        awaitNode(hasTestTag(navTag(Section.MORE)))
        onNodeWithTag(navTag(Section.MORE)).performClick()
        awaitNode(hasTestTag(RATES_ROW_TAG))
        onNodeWithTag(RATES_ROW_TAG).performClick()
        awaitNode(hasTestTag(rateTypeTag("ПТ")))
    }

    @Test
    fun firstPtRatesFromJanuary() = runComposeUiTest {
        openRates()
        onNodeWithText(s("Нет ставки у 9_типов")).assertDoesNotExist()
        onNodeWithTag(rateTypeTag("ПТ")).assertTextContains("Нет ставки")
        onNodeWithTag(rateTypeTag("ПТ")).performClick()
        awaitNode(hasTestTag(RATE_FROM_TAG))
        // Первая ставка — с 1 января: её получат и уже записанные тренировки.
        onNodeWithText("01.01.2026").assertExists()
        onNodeWithTag(rateFieldTag("ББ", 0)).performTextReplacement("920")
        onNodeWithTag(rateFieldTag("ББ", 1)).performTextReplacement("960")
        onNodeWithTag(rateFieldTag("ББ", 2)).performTextReplacement("1 000")
        onNodeWithTag(rateFieldTag("ДБ", 1)).performTextReplacement("860")
        // Ступень 2 без ступени 1 — ошибка, «Сохранить» недоступна.
        onNodeWithText("Впишите ступень 1").performScrollTo()
        onNodeWithText("Сохранить").assertIsNotEnabled()
        onNodeWithTag(rateFieldTag("ДБ", 0)).performTextReplacement("830")
        onNodeWithText("Сохранить").performClick()

        awaitNode(hasText(s("Ставки «ПТ» с_01.01.2026 сохранены")))
        waitUntil(timeoutMillis = 5_000) { rates().size == 2 }
        val bb = rates().single { it.locationId == placeId("ББ") }
        assertEquals(LocalDate(2026, 1, 1), bb.validFrom)
        assertEquals(listOf(rub(920), rub(960), rub(1000)), listOf(bb.amountT1, bb.amountT2, bb.amountT3))
        awaitNode(hasText(s("ББ_920 / 960 / 1^000 · ДБ_830 / 860 · Зал_—")))
    }

    @Test
    fun babyBonusesLikePt() = runComposeUiTest {
        runBlocking {
            settings.rates.saveRates(typeId("ПТ"), LocalDate(2026, 1, 1), mapOf(placeId("ББ") to RateAmounts(rub(920), rub(960), rub(1000))))
        }
        openRates()
        onNodeWithTag(rateTypeTag("Грудничковое")).performScrollTo().performClick()
        awaitNode(hasTestTag(LIKE_PT_TAG))
        onNodeWithTag(rateFieldTag("ББ", 0)).performTextReplacement("750")
        onNodeWithTag(LIKE_PT_TAG).performScrollTo().performClick()
        onNodeWithTag(rateFieldTag("ББ", 1)).assertTextContains("790")
        onNodeWithTag(rateFieldTag("ББ", 2)).assertTextContains("830")
        onNodeWithText("Сохранить").performClick()
        waitUntil(timeoutMillis = 5_000) { rates().any { it.typeId == typeId("Грудничковое") } }
        assertEquals(rub(830), rates().single { it.typeId == typeId("Грудничковое") }.amountT3)
    }

    @Test
    fun dutyHasOneRateAndSptHasBonus() = runComposeUiTest {
        openRates()
        onNodeWithTag(rateTypeTag("Дежурство")).performScrollTo().performClick()
        awaitNode(hasTestTag(rateFieldTag(null, 0)))
        onNodeWithTag(rateFieldTag(null, 0)).performTextReplacement("200")
        onNodeWithText("Сохранить").performClick()
        waitUntil(timeoutMillis = 5_000) { rates().isNotEmpty() }
        awaitNode(hasText(s("200_₽ за_час")))

        onNodeWithTag(rateTypeTag("СПТ")).performScrollTo().performClick()
        awaitNode(hasTestTag(rateFieldTag("ББ", 3)))
        onNodeWithTag(rateFieldTag("ББ", 0)).performTextReplacement("200")
        onNodeWithTag(rateFieldTag("ББ", 3)).performTextReplacement("140")
        // Сообщение о дежурстве ещё висит над кнопкой — нажатие через действие, а не по точке.
        onNodeWithText("Сохранить").performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 5_000) { rates().any { it.typeId == typeId("СПТ") } }
        assertEquals(rub(140), rates().single { it.typeId == typeId("СПТ") }.sptCloseBonus)
        assertEquals(null, rates().single { it.typeId == typeId("Дежурство") }.locationId)
    }

    @Test
    fun tiersWithPtTypes() = runComposeUiTest {
        openRates()
        // Пока ступеней нет — «Без ступеней», пороги пустые: 101 и 151 только примером в подсказке (5.3а).
        onNodeWithTag(TIERS_ROW_TAG).assertTextContains("Без ступеней", substring = true)
        onNodeWithTag(TIERS_ROW_TAG).performClick()
        awaitNode(hasTestTag(TIERS_MONTH_TAG))
        onNodeWithText(s("Октябрь_2026")).assertExists()
        onNodeWithTag(TIERS_ON_TAG).performClick()
        onNodeWithText("Сохранить").assertIsNotEnabled()
        onNodeWithTag(T2_FROM_TAG).performTextReplacement("101")
        onNodeWithTag(T3_FROM_TAG).performTextReplacement("90")
        onNodeWithText("Ступень 3 начинается позже ступени 2").performScrollTo()
        onNodeWithText("Сохранить").assertIsNotEnabled()
        onNodeWithTag(T3_FROM_TAG).performTextReplacement("150")
        onNodeWithTag(asPtTag("Кинезио")).performScrollTo().performClick()
        onNodeWithText("Сохранить").performClick()

        waitUntil(timeoutMillis = 5_000) { runBlocking { settings.rates.tiers.first() }.isNotEmpty() }
        val tier = runBlocking { settings.rates.tiers.first() }.single()
        assertEquals(LocalDate(2026, 10, 1), tier.validFrom)
        assertEquals(150, tier.t3From)
        assertTrue(tier.enabled)
        // По умолчанию как ПТ — ПТ, сплит, грудничковое, гидро и кинезио; кинезио сняли.
        assertEquals(setOf("ПТ", "Сплит", "Грудничковое", "Гидрореабилитация").map(::typeId).toSet(), tier.ptTypeIds)
        awaitNode(hasText(s("с_01.10.2026")))
    }
}
