package com.akenory.personalka.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.format.RateFormat
import com.akenory.personalka.domain.format.SalarySetupFormat
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.money.RateRules
import com.akenory.personalka.domain.money.SalarySetupRules
import com.akenory.personalka.domain.money.Versions
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.CheckRow
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.NavRow
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.SwitchRow
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.salary.Hint
import com.akenory.personalka.ui.shell.Section
import com.akenory.personalka.ui.shell.ShellState
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.training.DatePickerPanel
import com.akenory.personalka.ui.training.PickBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

internal const val PAY_DAY_ROW_TAG = "profile-pay-day"
internal const val PROFILE_PAY_DAY_TAG = "profile-pay-day-field"
internal const val PROFILE_ADVANCE_DAY_TAG = "profile-advance-day"
internal const val SCHEME_ROW_TAG = "profile-scheme"
internal const val CO_COACHING_TAG = "profile-co-coaching"
internal const val SALARY_FROM_TAG = "profile-salary-from"
internal const val PROFILE_ADVANCE_TAG = "profile-advance"
internal fun profileSchemeTag(k: PaySchemeKind) = "profile-scheme-${k.name}"

/**
 * Зарплатная часть «Профиля» (ТЗ 5.16): день зарплаты и схема выплат на сегодня строками (нажатие — форма с месяцем),
 * выключатель «Совместное ведение групп» — пишется сразу. Схема — только когда есть из чего выбирать или действует
 * не «Вся сумма» (4.11).
 */
@Composable
internal fun ProfileSalaryRows(settings: SettingsRepository, open: Set<Feature>, today: LocalDate, shell: ShellState, scope: CoroutineScope) {
    val schemes by remember(settings) { settings.salary.schemes }.collectAsState(initial = emptyList())
    val coCoaching by remember(settings) { settings.salary.coCoaching }.collectAsState(initial = false)
    val scheme = Versions.activeIn(schemes, today.yearMonth) { it.scheme.validFrom }?.scheme
    SectionLabel(SalarySetupFormat.SALARY)
    ListCard {
        // Категории тренера нет (шаг 5.3а): ставки меняются в «Ставках» с даты; здесь — день зарплаты.
        NavRow(
            title = SalarySetupFormat.PAY_DAY,
            subtitle = scheme?.let { SalarySetupFormat.salaryDay(it.salaryDay) } ?: SalarySetupFormat.PAY_DAY_NOT_SET,
            // Зарплата не настроена — день спросит мастер «Зарплаты»: своя версия схемы здесь пропустила бы мастер.
            onClick = { if (schemes.isEmpty()) shell.select(Section.SALARY) else shell.open(SettingsRoute.PAY_DAY) },
            modifier = Modifier.testTag(PAY_DAY_ROW_TAG),
        )
        if (SalarySetupRules.schemeRow(open)) {
            RowDivider()
            NavRow(
                title = SalarySetupFormat.SCHEME,
                subtitle = scheme?.let { SalarySetupFormat.schemeRow(it.kind, it.advanceDefault, it.validFrom) } ?: SalarySetupFormat.NOT_SET,
                onClick = { shell.open(SettingsRoute.SCHEME) },
                modifier = Modifier.testTag(SCHEME_ROW_TAG),
            )
        }
        RowDivider()
        SwitchRow(
            SalarySetupFormat.CO_COACHING,
            coCoaching,
            { on -> scope.launch { settings.salary.saveCoCoaching(on) } },
            Modifier.padding(horizontal = Dimens.space4).testTag(CO_COACHING_TAG),
            subtitle = SalarySetupFormat.CO_COACHING_NOTE,
        )
    }
}

/**
 * День зарплаты с месяца (ТЗ 4.11, шаг 5.3а): новая версия схемы выплат с 1-го числа выбранного месяца с тем же видом
 * и авансом; версия того же месяца правится. Примера в поле нет как значения — только в подсказке.
 */
@Composable
internal fun PayDayEditor(settings: SettingsRepository, today: LocalDate, messages: MessageState, scope: CoroutineScope, onBack: () -> Unit) {
    val schemes by remember(settings) { settings.salary.schemes }.collectAsState(initial = null)
    val loaded = schemes ?: return
    val current = Versions.activeIn(loaded, today.yearMonth) { it.scheme.validFrom }?.scheme
    var dayText by rememberSaveable { mutableStateOf(current?.salaryDay?.toString() ?: "") }
    var monthText by rememberSaveable { mutableStateOf((if (current == null) YearMonth(today.year, 1) else today.yearMonth).toString()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val month = YearMonth.parse(monthText)
    val day = SalarySetupRules.day(dayText)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(SalarySetupFormat.PAY_DAY, onBack = onBack)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
                Spacer(Modifier.height(Dimens.space4))
                Field(
                    SalarySetupFormat.SALARY_DAY,
                    dayText,
                    { dayText = it },
                    error = if (dayText.isNotBlank()) SalarySetupRules.dayProblem(dayText) else null,
                    hint = SalarySetupFormat.SALARY_DAY_NOTE,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    tag = PROFILE_PAY_DAY_TAG,
                    placeholder = RateFormat.example(SalarySetupRules.SALARY_DAY_EXAMPLE),
                )
                SectionLabel(SalarySetupFormat.FROM_MONTH)
                PickBox(RateFormat.month(month.firstDay), PIcons.CalendarMonth, { picking = true }, Modifier.fillMaxWidth().testTag(SALARY_FROM_TAG))
                Hint(SalarySetupFormat.PAY_DAY_NOTE)
                Spacer(Modifier.height(Dimens.space6))
            }
            BottomAction(
                text = "Сохранить",
                enabled = day != null,
                onClick = {
                    val d = day ?: return@BottomAction
                    val base = Versions.activeIn(loaded, month) { it.scheme.validFrom }?.scheme
                    val scheme = base?.copy(validFrom = month.firstDay, salaryDay = d)
                        ?: SalarySetupRules.scheme(month.firstDay, PaySchemeKind.NONE, null, salaryDay = d)
                    scope.launch { settings.salary.saveScheme(month, scheme) }
                    onBack()
                    messages.show("Сохранено")
                },
            )
        }
        if (picking) DatePickerPanel(month.firstDay, { picking = false; monthText = it.yearMonth.toString() }, { picking = false })
    }
}

/**
 * Схема выплат с месяца (ТЗ 4.11): «Вся сумма, без аванса» у всех, «Официально с налогом 6,5 %» — только если
 * открыта у аккаунта; ожидаемый аванс — у схемы с официальной частью. Месяц — переход на новую схему; версия того же
 * месяца правится.
 */
@Composable
internal fun SchemeEditor(settings: SettingsRepository, open: Set<Feature>, today: LocalDate, messages: MessageState, scope: CoroutineScope, onBack: () -> Unit) {
    val schemes by remember(settings) { settings.salary.schemes }.collectAsState(initial = null)
    val loaded = schemes ?: return
    val current = Versions.activeIn(loaded, today.yearMonth) { it.scheme.validFrom }?.scheme
    val choices = SalarySetupRules.schemes(open)
    var picked by rememberSaveable { mutableStateOf(current?.kind?.takeIf { it in choices }?.name) }
    var monthText by rememberSaveable { mutableStateOf((if (current == null) YearMonth(today.year, 1) else today.yearMonth).toString()) }
    var advance by rememberSaveable { mutableStateOf(RateRules.moneyText(current?.advanceDefault)) }
    var advanceDay by rememberSaveable { mutableStateOf(current?.advanceDay?.toString() ?: "") }
    var picking by rememberSaveable { mutableStateOf(false) }
    val month = YearMonth.parse(monthText)
    val kind = picked?.let { PaySchemeKind.valueOf(it) }
    val withAdvance = kind != null && SalarySetupRules.hasAdvance(kind)
    val advanceProblem = if (withAdvance) RateRules.moneyProblem(advance) else null
    val advanceDayValue = SalarySetupRules.day(advanceDay)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(SalarySetupFormat.SCHEME, onBack = onBack)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
                SectionLabel(SalarySetupFormat.SCHEME)
                ListCard {
                    choices.forEachIndexed { i, k ->
                        if (i > 0) RowDivider()
                        CheckRow(
                            SalarySetupFormat.scheme(k),
                            kind == k,
                            { picked = k.name },
                            Modifier.padding(horizontal = Dimens.space4).testTag(profileSchemeTag(k)),
                            subtitle = SalarySetupFormat.schemeNote(k),
                        )
                    }
                }
                if (withAdvance) {
                    Spacer(Modifier.height(Dimens.space4))
                    Field(
                        SalarySetupFormat.ADVANCE_DAY,
                        advanceDay,
                        { advanceDay = it },
                        error = if (advanceDay.isNotBlank()) SalarySetupRules.dayProblem(advanceDay) else null,
                        hint = SalarySetupFormat.ADVANCE_DAY_NOTE,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        tag = PROFILE_ADVANCE_DAY_TAG,
                        placeholder = RateFormat.example(SalarySetupRules.ADVANCE_DAY_EXAMPLE),
                    )
                    Spacer(Modifier.height(Dimens.space4))
                    Field(
                        SalarySetupFormat.ADVANCE,
                        advance,
                        { advance = it },
                        error = advanceProblem,
                        hint = SalarySetupFormat.ADVANCE_NOTE,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        tag = PROFILE_ADVANCE_TAG,
                        placeholder = "Необязательно",
                    )
                }
                SectionLabel(SalarySetupFormat.FROM_MONTH)
                PickBox(
                    RateFormat.month(month.firstDay),
                    PIcons.CalendarMonth,
                    { picking = true },
                    Modifier.fillMaxWidth().testTag(SALARY_FROM_TAG),
                )
                Hint(SalarySetupFormat.SCHEME_NOTE)
                Spacer(Modifier.height(Dimens.space6))
            }
            BottomAction(
                text = "Сохранить",
                enabled = kind != null && advanceProblem == null && (!withAdvance || advanceDayValue != null),
                onClick = {
                    val k = kind ?: return@BottomAction
                    // День зарплаты остаётся прежним: его меняет своя строка «Профиля».
                    val salaryDay = current?.salaryDay ?: SalarySetupRules.SALARY_DAY_EXAMPLE
                    val scheme = SalarySetupRules.scheme(month.firstDay, k, RateRules.money(advance), advanceDayValue, salaryDay)
                    scope.launch { settings.salary.saveScheme(month, scheme) }
                    onBack()
                    messages.show("Сохранено")
                },
            )
        }
        if (picking) DatePickerPanel(month.firstDay, { picking = false; monthText = it.yearMonth.toString() }, { picking = false })
    }
}
