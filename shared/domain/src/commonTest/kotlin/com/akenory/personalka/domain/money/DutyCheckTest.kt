package com.akenory.personalka.domain.money

import com.akenory.personalka.domain.format.MoneyFormat
import com.akenory.personalka.domain.format.ReconcileFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.SalaryFormat
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Сверка дежурств месяца (ТЗ 5.14, шаг 3.9а): часы и деньги проведённых — те же, что в «Зарплате». */
class DutyCheckTest {
    private val duty = PayType("duty", TrainingKind.DUTY, PayUnit.PER_HOUR, countsTowardPt = false, tierDependent = false)
    private val pt = PayType("pt", TrainingKind.PERSONAL, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true)
    private val from = LocalDate(2026, 1, 1)
    private val october = YearMonth(2026, 10)
    private val today = LocalDate(2026, 10, 20)
    private val n = RuText.NBSP

    private fun t(id: String, type: String, day: Int, hour: Int, minutes: Int, status: TrainingStatus, coverFor: String? = null, month: Int = 10) =
        PayTraining(id, type, "db", LocalDateTime(2026, month, day, hour, 0), minutes, status, emptyList(), coverFor = coverFor)

    private fun input(vararg trainings: PayTraining) = MonthInput(
        october, trainings.toList(), listOf(duty, pt),
        listOf(PayRate("duty", null, from, 20_000), PayRate("pt", "db", from, 92_000)), emptyList(),
    )

    private fun check(vararg trainings: PayTraining, checked: Int? = null, on: LocalDate = today): DutyCheck {
        val input = input(*trainings)
        return DutyCheckRules.build(input, input.pay(), on, checked)
    }

    @Test
    fun rowsHoursAndMoney() {
        val c = check(
            t("d2", "duty", 13, 15, 240, TrainingStatus.DONE),
            t("d1", "duty", 6, 8, 240, TrainingStatus.DONE, coverFor = "Александра"),
            t("d3", "duty", 27, 15, 240, TrainingStatus.PLANNED),
            t("d4", "duty", 8, 15, 240, TrainingStatus.CANCELLED),
            t("p1", "pt", 6, 9, 50, TrainingStatus.DONE),
            t("d5", "duty", 30, 15, 240, TrainingStatus.DONE, month = 9),
        )
        assertEquals(listOf("d1", "d2", "d3"), c.rows.map { it.trainingId }, "По порядку, без отменённых, ПТ и чужого месяца")
        assertEquals(480, c.doneMinutes)
        assertEquals(160_000, c.doneAmount, "2 × 4 ч × 200 ₽ — как в «Зарплате»")
        assertEquals(240, c.plannedMinutes)
        assertEquals(DutyCheckState.NONE, c.state)
        assertTrue(c.canCheck && c.visible)
        assertEquals("Проведено ${SalaryFormat.hours(480)} · ${MoneyFormat.rub(160_000)}", ReconcileFormat.doneTotal(c))
        assertEquals("Дежурства за октябрь 2026\nвт${n}06.10, 08:00–12:00, ДБ — 4${n}ч (за кого: Александра)\nвт${n}13.10, 15:00–19:00, ДБ — 4${n}ч\nИтого: 8${n}ч", ReconcileFormat.shareText(october, c) { "ДБ" })
    }

    @Test
    fun checkedAndChanged() {
        val done = t("d1", "duty", 6, 8, 240, TrainingStatus.DONE)
        assertEquals(DutyCheckState.CHECKED, check(done, checked = 240).state)
        val changed = check(done, t("d2", "duty", 7, 8, 120, TrainingStatus.DONE), checked = 240)
        assertEquals(DutyCheckState.CHANGED, changed.state)
        assertEquals("Сверены на 4${n}ч, потом часы изменились — теперь 6${n}ч", ReconcileFormat.changed(240, changed.doneMinutes))
        // Дежурства удалили, а отметка осталась — сверка видна, чтобы было видно изменение.
        assertTrue(check(checked = 240).visible)
        assertFalse(check().visible)
    }

    @Test
    fun futureMonthIsNotChecked() {
        assertFalse(check(t("d1", "duty", 27, 15, 240, TrainingStatus.PLANNED), on = LocalDate(2026, 9, 30)).canCheck)
        assertTrue(check(t("d1", "duty", 6, 15, 240, TrainingStatus.DONE), on = LocalDate(2026, 11, 2)).canCheck)
    }
}
