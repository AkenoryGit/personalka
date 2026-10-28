package com.akenory.personalka.ui.salary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.account.AccountRepository
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.money.RatesRepository
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.account.AccountRules
import com.akenory.personalka.domain.account.Feature
import com.akenory.personalka.domain.format.RateFormat
import com.akenory.personalka.domain.format.SalarySetupFormat
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.money.ExampleRates
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.domain.money.RateRules
import com.akenory.personalka.domain.money.SalarySetupDraft
import com.akenory.personalka.domain.money.SalarySetupRules
import com.akenory.personalka.domain.money.TierDraft
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.CheckRow
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.SwitchRow
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.training.DatePickerPanel
import com.akenory.personalka.ui.training.PickBox
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.ErrorText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

internal const val SETUP_ADVANCE_TAG = "salary-setup-advance"
internal const val SETUP_SCHEME_FROM_TAG = "salary-setup-scheme-from"
internal const val SALARY_RATES_TAG = "salary-rates"
internal const val SETUP_T2_TAG = "salary-setup-t2"
internal const val SETUP_T3_TAG = "salary-setup-t3"
internal const val SETUP_SALARY_DAY_TAG = "salary-setup-salary-day"
internal const val SETUP_ADVANCE_DAY_TAG = "salary-setup-advance-day"
internal const val SETUP_TIERS_ON_TAG = "salary-setup-tiers-on"
internal const val SOURCE_EXAMPLE = "example"
internal const val SOURCE_OWN = "own"
internal fun sourceTag(key: String) = "salary-source-$key"
internal fun schemeTag(k: PaySchemeKind) = "scheme-${k.name}"
internal fun setupRateTag(type: String, place: String, tier: Int) = "setup-rate-$type-$place-$tier"
internal fun setupAsPtTag(name: String) = "setup-as-pt-$name"

/** Ступени 1–3 одной ставки в полях мастера. */
private const val TIERS = 3

/** Возможности аккаунта, открытые сегодня: «Официально с налогом 6,5 %» видна только с ней (1.5, 4.11). */
@Composable
internal fun openFeatures(account: AccountRepository?, today: LocalDate): Set<Feature> {
    val signedIn by remember(account) { account?.account ?: flowOf(null) }.collectAsState(initial = null)
    return signedIn?.let { AccountRules.open(it.features, today) } ?: emptySet()
}

/**
 * Раздел «Зарплата»: при первом открытии — мастер «Настроить зарплату» (ТЗ 5.18), после него — месяц и выплаты
 * (5.12). [advanceAsk] — открыть «Выплаты» этого месяца с окном аванса (пункт «Главной» и уведомление, шаг 3.9);
 * [onAdvanceShown] — окно открыто, просьба выполнена.
 */
@Composable
fun SalaryScreen(
    settings: SettingsRepository,
    account: AccountRepository?,
    today: LocalDate,
    messages: MessageState,
    scope: CoroutineScope,
    shareText: ((String) -> Unit)? = null,
    advanceAsk: Boolean = false,
    onAdvanceShown: () -> Unit = {},
    onSlots: (() -> Unit)? = null,
    onRates: () -> Unit,
) {
    val schemes by remember(settings) { settings.salary.schemes }.collectAsState(initial = null)
    val places by remember(settings) { settings.places }.collectAsState(initial = null)
    val types by remember(settings) { settings.types }.collectAsState(initial = null)
    val loaded = schemes ?: return
    val loadedPlaces = places ?: return
    val loadedTypes = types ?: return
    if (SalarySetupRules.needed(loaded)) {
        SalarySetupWizard(settings, loadedPlaces.filter { !it.archived }, loadedTypes.filter { !it.archived }, openFeatures(account, today), today, messages, scope)
        return
    }
    SalaryMonthScreen(settings, today, messages, scope, onRates, shareText, advanceAsk, onAdvanceShown, onSlots)
}

/**
 * Мастер «Настроить зарплату» (ТЗ 5.18, шаг 5.3а): как платят (ставки-пример или свои, день зарплаты) → схема выплат
 * (только с открытой «налог 6,5 %») → ступени → ставки. Категорий и сетки одного клуба нет: пример помечен «сверьте
 * со своим расчётным листком», пороги ступеней и дни выплат без примера не подставляются.
 * До «Готово» в базу ничего не пишется; выбранное переживает сложение Fold и выгрузку из памяти.
 */
@Composable
private fun SalarySetupWizard(
    settings: SettingsRepository,
    places: List<LocationEntity>,
    types: List<TrainingTypeEntity>,
    open: Set<Feature>,
    today: LocalDate,
    messages: MessageState,
    scope: CoroutineScope,
) {
    val savedRates by remember(settings) { settings.rates.rates }.collectAsState(initial = null)
    val savedTiers by remember(settings) { settings.rates.tiers }.collectAsState(initial = null)
    val rates = savedRates ?: return
    val tierVersions = savedTiers ?: return

    var step by rememberSaveable { mutableStateOf(1) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var salaryDay by rememberSaveable { mutableStateOf("") }
    var scheme by rememberSaveable { mutableStateOf<String?>(null) }
    var advance by rememberSaveable { mutableStateOf("") }
    var advanceDay by rememberSaveable { mutableStateOf("") }
    var schemeFrom by rememberSaveable { mutableStateOf(YearMonth(today.year, 1).toString()) }
    var pickingMonth by rememberSaveable { mutableStateOf(false) }
    // Поля ставок и ступеней заполняются по выбору «пример / свои»; смена выбора заполняет их заново.
    var fieldsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var fields by rememberSaveable { mutableStateOf(listOf<String>()) }
    val tierMonth = SalarySetupRules.tierMonth(tierVersions.isNotEmpty(), today)
    val initialTiers = remember(tierVersions, types) {
        RateRules.tierDraft(tierVersions, tierMonth, types.filter { it.countsTowardPt }.map { it.id }.toSet())
    }
    val hasTiers = tierVersions.isNotEmpty()
    var tiersOn by rememberSaveable { mutableStateOf(initialTiers.enabled) }
    var t2 by rememberSaveable { mutableStateOf(RateRules.thresholdText(tierVersions, tierMonth, initialTiers.t2From)) }
    var t3 by rememberSaveable { mutableStateOf(RateRules.thresholdText(tierVersions, tierMonth, initialTiers.t3From)) }
    var asPt by rememberSaveable { mutableStateOf(initialTiers.ptTypeIds.toList()) }

    val schemes = SalarySetupRules.schemes(open)
    // Шаг схемы — только с открытой «налог 6,5 %»: шаги — по номерам 1–4, без выбора схемы второго нет.
    val pages = listOfNotNull(1, 2.takeIf { SalarySetupRules.schemeStep(open) }, 3, 4)
    val page = if (step in pages) step else pages.first { it > step }
    val example = source == SOURCE_EXAMPLE
    val pickedScheme = scheme?.let { PaySchemeKind.valueOf(it) }?.takeIf { it in schemes } ?: PaySchemeKind.NONE
    val withAdvance = SalarySetupRules.hasAdvance(pickedScheme)
    val fromMonth = YearMonth.parse(schemeFrom)
    val ptId = RatesRepository.ptTypeId(types)
    // Типы, для которых есть пример: ПТ, сплит, грудничковое; остальные — потом в «Ставках и ступенях».
    val rateTypes = types.mapNotNull { t -> ExampleRates.service(t.id, t.name, t.kind == TrainingKind.SPLIT, ptId)?.let { t to it } }
        .distinctBy { it.second }

    fun index(type: Int, place: Int, tier: Int) = (type * places.size + place) * TIERS + tier
    fun fill(withExample: Boolean): List<String> = rateTypes.indices.flatMap { ti ->
        places.indices.flatMap { pi ->
            val own = RateRules.activeAt(rates.filter { it.rate.typeId == rateTypes[ti].first.id }, places[pi].id, today)?.amounts
            val a = SalarySetupRules.prefill(own, if (withExample) ExampleRates.amounts(rateTypes[ti].second) else null)
            listOf(RateRules.moneyText(a?.t1), RateRules.moneyText(a?.t2), RateRules.moneyText(a?.t3))
        }
    }
    fun text(i: Int) = fields.getOrElse(i) { "" }
    // Ступени без ступеней — одно поле «Ставка»: ступени 2–3 не пишутся.
    val tierCount = if (tiersOn) TIERS else 1

    val salaryDayValue = SalarySetupRules.day(salaryDay)
    val advanceDayValue = SalarySetupRules.day(advanceDay)
    val advanceValue = RateRules.money(advance)
    val advanceProblem = RateRules.moneyProblem(advance)
    val t2From = RateRules.threshold(t2)
    val t3From = RateRules.threshold(t3)
    val tierProblem = if (tiersOn) RateRules.tierProblem(t2From, t3From) else null

    // Введённые ставки: тип → место → суммы; пустые места не пишутся; ступень 2–3 без ступени 1 — ошибка.
    val entered = mutableMapOf<String, Map<String, RateAmounts>>()
    val rateProblems = mutableSetOf<Int>()
    rateTypes.forEachIndexed { ti, (type, _) ->
        val byPlace = linkedMapOf<String, RateAmounts>()
        places.forEachIndexed { pi, place ->
            val values = (0 until TIERS).map { if (it < tierCount) RateRules.money(text(index(ti, pi, it))) else null }
            (0 until tierCount).forEach { if (RateRules.moneyProblem(text(index(ti, pi, it))) != null) rateProblems += index(ti, pi, it) }
            val (a1, a2, a3) = values
            if (RateRules.needsT1(a1, a2, a3, null)) rateProblems += index(ti, pi, 0)
            if (a1 != null) byPlace[place.id] = RateAmounts(a1, a2, a3)
        }
        entered[type.id] = byPlace
    }

    fun number(p: Int) = pages.indexOf(p) + 1
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(SalarySetupFormat.SETUP, onBack = if (page > 1) ({ step = pages.last { it < page } }) else null)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
                when (page) {
                    1 -> {
                        StepTitle(number(1), pages.size, SalarySetupFormat.PAY_TITLE, SalarySetupFormat.PAY_TEXT)
                        ListCard {
                            listOf(
                                Triple(SOURCE_EXAMPLE, SalarySetupFormat.EXAMPLE, SalarySetupFormat.EXAMPLE_NOTE),
                                Triple(SOURCE_OWN, SalarySetupFormat.OWN, SalarySetupFormat.OWN_NOTE),
                            ).forEachIndexed { i, (key, title, note) ->
                                if (i > 0) RowDivider()
                                CheckRow(
                                    title = title,
                                    subtitle = note,
                                    checked = source == key,
                                    onToggle = { source = key },
                                    modifier = Modifier.padding(horizontal = Dimens.space4).testTag(sourceTag(key)),
                                )
                            }
                        }
                        Spacer(Modifier.height(Dimens.space4))
                        Field(
                            SalarySetupFormat.SALARY_DAY,
                            salaryDay,
                            { salaryDay = it },
                            error = if (salaryDay.isNotBlank()) SalarySetupRules.dayProblem(salaryDay) else null,
                            hint = SalarySetupFormat.SALARY_DAY_NOTE,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            tag = SETUP_SALARY_DAY_TAG,
                            placeholder = RateFormat.example(SalarySetupRules.SALARY_DAY_EXAMPLE),
                        )
                    }
                    2 -> {
                        StepTitle(number(2), pages.size, SalarySetupFormat.SCHEME_TITLE, SalarySetupFormat.SCHEME_TEXT)
                        ListCard {
                            schemes.forEachIndexed { i, k ->
                                if (i > 0) RowDivider()
                                CheckRow(
                                    title = SalarySetupFormat.scheme(k),
                                    subtitle = SalarySetupFormat.schemeNote(k),
                                    checked = pickedScheme == k,
                                    onToggle = { scheme = k.name },
                                    modifier = Modifier.padding(horizontal = Dimens.space4).testTag(schemeTag(k)),
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
                                tag = SETUP_ADVANCE_DAY_TAG,
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
                                tag = SETUP_ADVANCE_TAG,
                                placeholder = "Необязательно",
                            )
                        }
                        if (pickedScheme != PaySchemeKind.NONE) {
                            SectionLabel(SalarySetupFormat.FROM_MONTH)
                            PickBox(RateFormat.month(fromMonth.firstDay), PIcons.CalendarMonth, { pickingMonth = true }, Modifier.fillMaxWidth().testTag(SETUP_SCHEME_FROM_TAG))
                            Hint(SalarySetupFormat.SCHEME_FROM_NOTE)
                        }
                    }
                    3 -> {
                        StepTitle(number(3), pages.size, SalarySetupFormat.TIERS_TITLE, SalarySetupFormat.TIERS_TEXT)
                        SwitchRow(RateFormat.TIERS_ON, tiersOn, { tiersOn = it }, Modifier.testTag(SETUP_TIERS_ON_TAG), subtitle = if (tiersOn) null else RateFormat.TIERS_ON_NOTE)
                        if (tiersOn) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                                val number = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                                Field(
                                    RateFormat.T2_FROM, t2, { t2 = it }, Modifier.weight(1f),
                                    error = if (t2.isNotBlank() && t2From == null) "" else null, keyboardOptions = number, tag = SETUP_T2_TAG,
                                    placeholder = RateFormat.example(RateRules.T2_FROM),
                                )
                                Field(
                                    RateFormat.T3_FROM, t3, { t3 = it }, Modifier.weight(1f),
                                    error = if (tierProblem != null && t2From != null && t3.isNotBlank()) "" else null, keyboardOptions = number, tag = SETUP_T3_TAG,
                                    placeholder = RateFormat.example(RateRules.T3_FROM),
                                )
                            }
                            if (t2.isNotBlank() && t3.isNotBlank()) tierProblem?.let { ErrorText(it) }
                        }
                        SectionLabel(RateFormat.AS_PT)
                        ListCard {
                            types.filter { it.kind != TrainingKind.DUTY }.forEachIndexed { i, type ->
                                if (i > 0) RowDivider()
                                CheckRow(
                                    title = type.name,
                                    checked = type.id in asPt,
                                    onToggle = { on -> asPt = if (on) asPt + type.id else asPt - type.id },
                                    modifier = Modifier.padding(horizontal = Dimens.space4).testTag(setupAsPtTag(type.name)),
                                )
                            }
                        }
                        Hint(RateFormat.AS_PT_NOTE)
                    }
                    else -> {
                        StepTitle(number(4), pages.size, SalarySetupFormat.RATES_TITLE, if (example) SalarySetupFormat.EXAMPLE_TEXT else SalarySetupFormat.OWN_TEXT)
                        rateTypes.forEachIndexed { ti, (type, _) ->
                            places.forEachIndexed { pi, place ->
                                SectionLabel("${type.name} · ${place.shortName}")
                                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                                    val labels = if (tiersOn) listOf(RateFormat.TIER_1, RateFormat.TIER_2, RateFormat.TIER_3) else listOf(RateFormat.ONLY)
                                    labels.forEachIndexed { tier, label ->
                                        val i = index(ti, pi, tier)
                                        Field(
                                            label,
                                            text(i),
                                            { value -> if (i < fields.size) fields = fields.toMutableList().also { it[i] = value } },
                                            Modifier.weight(1f),
                                            error = if (i in rateProblems) "" else null,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                                            tag = setupRateTag(type.shortName, place.shortName, tier),
                                        )
                                    }
                                }
                                if (index(ti, pi, 0) in rateProblems && RateRules.money(text(index(ti, pi, 0))) == null && RateRules.moneyProblem(text(index(ti, pi, 0))) == null) {
                                    ErrorText("Впишите ступень 1")
                                } else if ((0 until tierCount).any { index(ti, pi, it) in rateProblems }) {
                                    ErrorText("Сумма — числом: 920 или 13 546,50")
                                }
                            }
                        }
                        Hint(SalarySetupFormat.OTHER_RATES)
                    }
                }
                Spacer(Modifier.height(Dimens.space6))
            }
            val enabled = when (page) {
                1 -> source != null && salaryDayValue != null
                2 -> !withAdvance || (advanceDayValue != null && advanceProblem == null)
                3 -> tierProblem == null
                else -> rateProblems.isEmpty()
            }
            BottomAction(
                text = if (page < pages.last()) SalarySetupFormat.NEXT else SalarySetupFormat.DONE,
                enabled = enabled,
                onClick = {
                    when {
                        page == 1 -> {
                            val key = source ?: return@BottomAction
                            if (fieldsFor != key) {
                                fields = fill(key == SOURCE_EXAMPLE)
                                // Пример — со ступенями 101 и 151; свои — без ступеней, пока тренер их не включит.
                                if (!hasTiers) {
                                    val on = key == SOURCE_EXAMPLE
                                    tiersOn = on
                                    t2 = if (on) RateRules.T2_FROM.toString() else ""
                                    t3 = if (on) RateRules.T3_FROM.toString() else ""
                                }
                                fieldsFor = key
                            }
                            step = pages[1]
                        }
                        page < pages.last() -> step = pages.first { it > page }
                        else -> {
                            val draft = SalarySetupDraft(
                                scheme = pickedScheme,
                                advance = advanceValue,
                                schemeFrom = fromMonth,
                                advanceDay = advanceDayValue,
                                salaryDay = salaryDayValue ?: return@BottomAction,
                                rates = entered,
                                tiers = TierDraft(tiersOn, t2From ?: initialTiers.t2From, t3From ?: initialTiers.t3From, asPt.toSet()),
                            )
                            scope.launch { settings.salary.finish(draft, today) }
                            messages.show(SalarySetupFormat.SAVED)
                        }
                    }
                },
            )
        }
        if (pickingMonth) DatePickerPanel(fromMonth.firstDay, { pickingMonth = false; schemeFrom = it.yearMonth.toString() }, { pickingMonth = false })
    }
}

@Composable
private fun StepTitle(step: Int, of: Int, title: String, text: String) {
    val colors = PersonalkaTheme.colors
    Spacer(Modifier.height(Dimens.space2))
    Text(SalarySetupFormat.step(step, of), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    Spacer(Modifier.height(Dimens.space2))
    Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = colors.text)
    Spacer(Modifier.height(Dimens.space2))
    Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
    Spacer(Modifier.height(Dimens.space4))
}

@Composable
internal fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = Dimens.space2),
        style = MaterialTheme.typography.bodySmall,
        color = PersonalkaTheme.colors.textMuted,
    )
}
