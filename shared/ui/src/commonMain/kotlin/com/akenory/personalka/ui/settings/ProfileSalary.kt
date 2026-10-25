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
import com.akenory.personalka.domain.model.CoachCategory
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

internal const val CATEGORY_ROW_TAG = "profile-category"
internal const val SCHEME_ROW_TAG = "profile-scheme"
internal const val CO_COACHING_TAG = "profile-co-coaching"
internal const val SALARY_FROM_TAG = "profile-salary-from"
internal const val PROFILE_ADVANCE_TAG = "profile-advance"
internal fun profileCategoryTag(c: CoachCategory) = "profile-category-${c.name}"
internal fun profileSchemeTag(k: PaySchemeKind) = "profile-scheme-${k.name}"

/**
 * Зарплатная часть «Профиля» (ТЗ 5.16): категория и схема выплат на сегодня строками (нажатие — форма с датой),
 * выключатель «Совместное ведение групп» — пишется сразу.
 */
@Composable
internal fun ProfileSalaryRows(settings: SettingsRepository, today: LocalDate, shell: ShellState, scope: CoroutineScope) {
    val categories by remember(settings) { settings.salary.categories }.collectAsState(initial = emptyList())
    val schemes by remember(settings) { settings.salary.schemes }.collectAsState(initial = emptyList())
    val coCoaching by remember(settings) { settings.salary.coCoaching }.collectAsState(initial = false)
    val category = Versions.activeOn(categories, today) { it.validFrom }
    val scheme = Versions.activeIn(schemes, today.yearMonth) { it.scheme.validFrom }?.scheme
    SectionLabel(SalarySetupFormat.SALARY)
    ListCard {
        NavRow(
            title = SalarySetupFormat.CATEGORY,
            subtitle = category?.let { SalarySetupFormat.since(SalarySetupFormat.category(it.category), it.validFrom) } ?: SalarySetupFormat.NOT_SET,
            onClick = { shell.open(SettingsRoute.CATEGORY) },
            modifier = Modifier.testTag(CATEGORY_ROW_TAG),
        )
        RowDivider()
        NavRow(
            title = SalarySetupFormat.SCHEME,
            subtitle = scheme?.let { SalarySetupFormat.schemeRow(it.kind, it.advanceDefault, it.validFrom) } ?: SalarySetupFormat.NOT_SET,
            onClick = { shell.open(SettingsRoute.SCHEME) },
            modifier = Modifier.testTag(SCHEME_ROW_TAG),
        )
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

/** Категория с даты (ТЗ 5.16, 3.2): по умолчанию с сегодняшнего дня; та же дата — правка версии. */
@Composable
internal fun CategoryEditor(settings: SettingsRepository, today: LocalDate, messages: MessageState, scope: CoroutineScope, onBack: () -> Unit) {
    val categories by remember(settings) { settings.salary.categories }.collectAsState(initial = null)
    val loaded = categories ?: return
    val current = Versions.activeOn(loaded, today) { it.validFrom }
    var picked by rememberSaveable { mutableStateOf(current?.category?.name) }
    var from by rememberSaveable { mutableStateOf((if (current == null) SalarySetupRules.start(today) else today).toString()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.parse(from)
    val category = picked?.let { CoachCategory.valueOf(it) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(SalarySetupFormat.CATEGORY, onBack = onBack)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
                SectionLabel(SalarySetupFormat.CATEGORY)
                ListCard {
                    SalarySetupRules.CATEGORIES.forEachIndexed { i, c ->
                        if (i > 0) RowDivider()
                        CheckRow(
                            SalarySetupFormat.category(c),
                            category == c,
                            { picked = c.name },
                            Modifier.padding(horizontal = Dimens.space4).testTag(profileCategoryTag(c)),
                        )
                    }
                }
                SectionLabel(SalarySetupFormat.FROM_DAY)
                PickBox(RateFormat.date(date), PIcons.CalendarMonth, { picking = true }, Modifier.fillMaxWidth().testTag(SALARY_FROM_TAG))
                Hint(SalarySetupFormat.CATEGORY_NOTE)
                Spacer(Modifier.height(Dimens.space6))
            }
            BottomAction(
                text = "Сохранить",
                enabled = category != null,
                onClick = {
                    val c = category ?: return@BottomAction
                    scope.launch { settings.salary.saveCategory(date, c) }
                    onBack()
                    messages.show("Сохранено")
                },
            )
        }
        if (picking) DatePickerPanel(date, { picking = false; from = it.toString() }, { picking = false })
    }
}

/**
 * Схема выплат с месяца (ТЗ 4.11): «Половина официально» — только если открыта у аккаунта; ожидаемый аванс — у схемы
 * с официальной частью. Версия того же месяца правится.
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
    var picking by rememberSaveable { mutableStateOf(false) }
    val month = YearMonth.parse(monthText)
    val kind = picked?.let { PaySchemeKind.valueOf(it) }
    val withAdvance = kind != null && SalarySetupRules.hasAdvance(kind)
    val advanceProblem = if (withAdvance) RateRules.moneyProblem(advance) else null

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
                enabled = kind != null && advanceProblem == null,
                onClick = {
                    val k = kind ?: return@BottomAction
                    val scheme = SalarySetupRules.scheme(month.firstDay, k, RateRules.money(advance))
                    scope.launch { settings.salary.saveScheme(month, scheme) }
                    onBack()
                    messages.show("Сохранено")
                },
            )
        }
        if (picking) DatePickerPanel(month.firstDay, { picking = false; monthText = it.yearMonth.toString() }, { picking = false })
    }
}
