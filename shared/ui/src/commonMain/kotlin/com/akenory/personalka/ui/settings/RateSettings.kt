package com.akenory.personalka.ui.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.money.RatesRepository
import com.akenory.personalka.domain.format.RateFormat
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.domain.money.RateRules
import com.akenory.personalka.domain.money.SavedRate
import com.akenory.personalka.domain.money.TierDraft
import com.akenory.personalka.domain.money.TierScheme
import com.akenory.personalka.domain.money.Versions
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.CheckRow
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.NavRow
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.components.SwitchRow
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.ShellState
import com.akenory.personalka.ui.shell.TopBar
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.training.DatePickerPanel
import com.akenory.personalka.ui.training.ErrorText
import com.akenory.personalka.ui.training.PickBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth

internal const val RATES_ROW_TAG = "settings-rates"
internal const val TIERS_ROW_TAG = "settings-tiers"
internal const val RATE_FROM_TAG = "rate-from"
internal const val LIKE_PT_TAG = "rate-like-pt"
internal const val TIERS_MONTH_TAG = "tiers-month"
internal const val TIERS_ON_TAG = "tiers-on"
internal const val T2_FROM_TAG = "tiers-t2"
internal const val T3_FROM_TAG = "tiers-t3"
internal fun rateTypeTag(name: String) = "rate-type-$name"
internal fun rateFieldTag(place: String?, field: Int) = "rate-${place ?: "any"}-$field"
internal fun rateHistoryTag(date: LocalDate) = "rate-history-$date"
internal fun asPtTag(name: String) = "as-pt-$name"

/** Поля ставки одного места: ступени 1–3 и доплата СПТ. */
private const val FIELDS = 4
private const val T1 = 0
private const val T2 = 1
private const val T3 = 2
private const val BONUS = 3

/** Места, по которым у типа ставки: дежурство — одна ставка на любое место (`null`), остальные — по местам. */
private fun slots(type: TrainingTypeEntity, places: List<LocationEntity>, saved: List<SavedRate>): List<String?> {
    if (!RateRules.perPlace(type.kind)) return listOf(null)
    val anyPlace = saved.any { it.rate.typeId == type.id && it.rate.locationId == null }
    return places.map { it.id } + if (anyPlace) listOf(null) else emptyList()
}

/** Ставки типа, действующие на [date], по местам — для строки списка и для «Надбавок как у ПТ». */
private fun cells(type: TrainingTypeEntity, places: List<LocationEntity>, saved: List<SavedRate>, date: LocalDate): List<Pair<String?, RateAmounts?>> {
    val own = saved.filter { it.rate.typeId == type.id }
    val names = places.associate { it.id to it.shortName }
    return slots(type, places, own).map { slot -> (slot?.let { names[it] }) to RateRules.activeAt(own, slot, date)?.amounts }
}

/** Ступени, действующие в этом месяце, или по умолчанию. */
internal fun currentTiers(types: List<TrainingTypeEntity>, tiers: List<TierScheme>, today: LocalDate): TierDraft =
    RateRules.tierDraft(tiers, today.yearMonth, defaultPtTypes(types))

private fun defaultPtTypes(types: List<TrainingTypeEntity>): Set<String> =
    types.filter { it.countsTowardPt && !it.archived }.map { it.id }.toSet()

/**
 * «Настройки → Ставки и ступени» (ТЗ 5.16, 3.3): типы со ставками по местам на сегодня и ступени этого месяца.
 * Строка типа открывает его ставки, строка ступеней — форму ступеней.
 */
@Composable
internal fun RatesScreen(
    rates: RatesRepository,
    places: List<LocationEntity>,
    types: List<TrainingTypeEntity>,
    today: LocalDate,
    shell: ShellState,
    onBack: (() -> Unit)?,
) {
    val saved by remember(rates) { rates.rates }.collectAsState(initial = null)
    val tiers by remember(rates) { rates.tiers }.collectAsState(initial = null)
    val loaded = saved ?: return
    val loadedTiers = tiers ?: return
    val active = Versions.activeIn(loadedTiers, today.yearMonth) { it.validFrom }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(RateFormat.TITLE, onBack = onBack)
        Column(Modifier.padding(horizontal = Dimens.space4)) {
            SectionLabel(RateFormat.RATES)
            ListCard {
                types.forEachIndexed { index, type ->
                    if (index > 0) RowDivider()
                    NavRow(
                        title = type.name,
                        subtitle = RateFormat.summary(cells(type, places, loaded, today), type.payUnit),
                        onClick = { shell.open(SettingsRoute.rate(type.id)) },
                        modifier = Modifier.testTag(rateTypeTag(type.name)),
                    )
                }
            }
            SectionLabel(RateFormat.TIERS)
            ListCard {
                NavRow(
                    title = RateFormat.tiers(currentTiers(types, loadedTiers, today), null),
                    subtitle = active?.let { RateFormat.since(it.validFrom) },
                    onClick = { shell.open(SettingsRoute.TIERS) },
                    modifier = Modifier.testTag(TIERS_ROW_TAG),
                )
            }
            Spacer(Modifier.height(Dimens.space6))
        }
    }
}

/**
 * Ставки одного типа (ТЗ 3.3): «Действуют с», суммы по местам (ступени 1–3, если ставка зависит от ступени;
 * у СПТ — доплата за покупку блока), «Надбавки как у ПТ» и прежние ставки. Нажатие на прежнюю ставку переносит
 * в форму её дату и суммы — так правится ошибка в прошлой версии.
 */
@Composable
internal fun RateEditor(
    rates: RatesRepository,
    type: TrainingTypeEntity,
    places: List<LocationEntity>,
    types: List<TrainingTypeEntity>,
    today: LocalDate,
    messages: MessageState,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    val all by remember(rates) { rates.rates }.collectAsState(initial = null)
    val loaded = all ?: return
    val own = loaded.filter { it.rate.typeId == type.id }
    val slots = slots(type, places, own)
    val names = places.associate { it.id to it.shortName }
    val tiered = type.tierDependent
    val spt = type.kind == TrainingKind.SPT
    val ptId = RatesRepository.ptTypeId(types)

    fun fieldsAt(date: LocalDate): List<String> = slots.flatMap { slot ->
        val a = RateRules.activeAt(own, slot, date)?.amounts
        listOf(RateRules.moneyText(a?.t1), RateRules.moneyText(a?.t2), RateRules.moneyText(a?.t3), RateRules.moneyText(a?.sptBonus))
    }

    var from by rememberSaveable(type.id) { mutableStateOf(RateRules.defaultFrom(own.isNotEmpty(), today).toString()) }
    var fields by rememberSaveable(type.id) { mutableStateOf(fieldsAt(LocalDate.parse(from))) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.parse(from)

    fun text(slot: Int, field: Int) = fields.getOrElse(slot * FIELDS + field) { "" }
    fun set(slot: Int, field: Int, value: String) {
        fields = fields.toMutableList().also { it[slot * FIELDS + field] = value }
    }

    // Введённое по местам: пустые поля — `null`; ошибки — под полями, «Сохранить» недоступна.
    val entered = linkedMapOf<String?, RateAmounts?>()
    val problems = mutableMapOf<Int, String>()
    slots.forEachIndexed { i, slot ->
        val used = listOfNotNull(T1, if (tiered) T2 else null, if (tiered) T3 else null, if (spt) BONUS else null)
        used.forEach { f -> RateRules.moneyProblem(text(i, f))?.let { problems[i * FIELDS + f] = it } }
        val t1 = RateRules.money(text(i, T1))
        val t2 = if (tiered) RateRules.money(text(i, T2)) else null
        val t3 = if (tiered) RateRules.money(text(i, T3)) else null
        val bonus = if (spt) RateRules.money(text(i, BONUS)) else null
        when {
            RateRules.needsT1(t1, t2, t3, bonus) -> problems[i * FIELDS + T1] = "Впишите ступень 1"
            t1 == null -> {
                if (used.all { text(i, it).isBlank() }) RateRules.emptyProblem(own, slot, date)?.let { problems[i * FIELDS + T1] = it }
                entered[slot] = null
            }
            else -> entered[slot] = RateAmounts(t1, t2, t3, bonus)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(type.name, onBack = onBack)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
                SectionLabel(RateFormat.FROM)
                PickBox(
                    text = RateFormat.date(date),
                    icon = PIcons.CalendarMonth,
                    onClick = { picking = true },
                    modifier = Modifier.fillMaxWidth().testTag(RATE_FROM_TAG),
                )
                Hint(if (own.isEmpty()) RateFormat.FIRST_NOTE else RateFormat.FROM_NOTE)

                slots.forEachIndexed { i, slot ->
                    SectionLabel((slot?.let { names[it] } ?: RateFormat.ANY_PLACE) + RateFormat.unit(type.payUnit))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        val tiers = if (tiered) listOf(T1 to RateFormat.TIER_1, T2 to RateFormat.TIER_2, T3 to RateFormat.TIER_3) else listOf(T1 to RateFormat.ONLY)
                        tiers.forEach { (f, label) ->
                            MoneyField(label, text(i, f), { set(i, f, it) }, problems[i * FIELDS + f] != null, Modifier.weight(1f), rateFieldTag(slot?.let { names[it] }, f))
                        }
                    }
                    if (spt) {
                        Spacer(Modifier.height(Dimens.space2))
                        MoneyField(RateFormat.SPT_BONUS, text(i, BONUS), { set(i, BONUS, it) }, problems[i * FIELDS + BONUS] != null, Modifier.fillMaxWidth(), rateFieldTag(slot?.let { names[it] }, BONUS))
                    }
                    (0 until FIELDS).firstNotNullOfOrNull { problems[i * FIELDS + it] }?.let { ErrorText(it) }
                }

                if (ptId != null && RateRules.canCopyPtBonus(type.kind, tiered, type.id == ptId)) {
                    Spacer(Modifier.height(Dimens.space4))
                    OutlineButton(
                        RateFormat.LIKE_PT,
                        {
                            val pt = loaded.filter { it.rate.typeId == ptId }
                            slots.forEachIndexed { i, slot ->
                                val t1 = RateRules.money(text(i, T1)) ?: return@forEachIndexed
                                val ptHere = (RateRules.activeAt(pt, slot, date) ?: RateRules.activeAt(pt, null, date))?.amounts
                                val (t2, t3) = RateRules.likePt(t1, ptHere) ?: return@forEachIndexed
                                set(i, T2, RateRules.moneyText(t2))
                                set(i, T3, RateRules.moneyText(t3))
                            }
                        },
                        modifier = Modifier.testTag(LIKE_PT_TAG),
                    )
                }

                val history = own.groupBy { it.rate.validFrom }.entries.sortedByDescending { it.key }
                if (history.isNotEmpty()) {
                    SectionLabel(RateFormat.HISTORY)
                    ListCard {
                        history.forEachIndexed { index, (since, versions) ->
                            if (index > 0) RowDivider()
                            val line = versions.sortedBy { v -> places.indexOfFirst { it.id == v.rate.locationId }.let { if (it < 0) Int.MAX_VALUE else it } }
                                .map { (it.rate.locationId?.let { id -> names[id] }) to it.amounts }
                            NavRow(
                                title = RateFormat.since(since),
                                subtitle = RateFormat.summary(line, type.payUnit),
                                onClick = {
                                    from = since.toString()
                                    fields = fieldsAt(since)
                                },
                                modifier = Modifier.testTag(rateHistoryTag(since)),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Dimens.space6))
            }
            BottomAction(
                text = "Сохранить",
                enabled = problems.isEmpty(),
                onClick = {
                    scope.launch { rates.saveRates(type.id, date, entered) }
                    onBack()
                    messages.show(RateFormat.saved(type.name, date))
                },
            )
        }
        if (picking) {
            DatePickerPanel(date, { picking = false; from = it.toString() }, { picking = false })
        }
    }
}

/**
 * Ступени (ТЗ 3.3, 4.6): с какого месяца, включены ли, пороги ступеней 2 и 3 и типы «Считать как ПТ».
 * Смена месяца подставляет ступени, действующие в нём.
 */
@Composable
internal fun TierEditor(
    rates: RatesRepository,
    types: List<TrainingTypeEntity>,
    today: LocalDate,
    messages: MessageState,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    val versions by remember(rates) { rates.tiers }.collectAsState(initial = null)
    val loaded = versions ?: return
    var monthText by rememberSaveable { mutableStateOf(RateRules.defaultTierMonth(today).toString()) }
    val month = YearMonth.parse(monthText)
    val initial = RateRules.tierDraft(loaded, month, defaultPtTypes(types))
    var enabled by rememberSaveable(monthText) { mutableStateOf(initial.enabled) }
    // Пока ступеней нет — поля пустые, 101 и 151 только в подсказке (шаг 5.3а).
    var t2 by rememberSaveable(monthText) { mutableStateOf(RateRules.thresholdText(loaded, month, initial.t2From)) }
    var t3 by rememberSaveable(monthText) { mutableStateOf(RateRules.thresholdText(loaded, month, initial.t3From)) }
    var pt by rememberSaveable(monthText) { mutableStateOf(initial.ptTypeIds.toList()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    // Архивный тип виден, только пока он в списке: снять отметку можно, поставить новую — нет.
    val countable = types.filter { it.kind != TrainingKind.DUTY && (!it.archived || it.id in pt) }
    val t2From = RateRules.threshold(t2)
    val t3From = RateRules.threshold(t3)
    val problem = if (enabled) RateRules.tierProblem(t2From, t3From) else null
    val from = RateRules.tierFrom(month)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(RateFormat.TIERS, onBack = onBack)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4)) {
                SectionLabel(RateFormat.TIERS_MONTH)
                PickBox(
                    text = RateFormat.month(from),
                    icon = PIcons.CalendarMonth,
                    onClick = { picking = true },
                    modifier = Modifier.fillMaxWidth().testTag(TIERS_MONTH_TAG),
                )
                Hint(RateFormat.TIERS_MONTH_NOTE)
                Spacer(Modifier.height(Dimens.space2))
                SwitchRow(RateFormat.TIERS_ON, enabled, { enabled = it }, Modifier.testTag(TIERS_ON_TAG), subtitle = if (enabled) null else RateFormat.TIERS_ON_NOTE)
                if (enabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        Field(RateFormat.T2_FROM, t2, { t2 = it }, Modifier.weight(1f), error = if (t2From == null) "" else null, keyboardOptions = NUMBER, tag = T2_FROM_TAG, placeholder = RateFormat.example(RateRules.T2_FROM))
                        Field(RateFormat.T3_FROM, t3, { t3 = it }, Modifier.weight(1f), error = if (problem != null && t2From != null) "" else null, keyboardOptions = NUMBER, tag = T3_FROM_TAG, placeholder = RateFormat.example(RateRules.T3_FROM))
                    }
                    problem?.let { ErrorText(it) }
                }
                SectionLabel(RateFormat.AS_PT)
                ListCard {
                    countable.forEachIndexed { index, type ->
                        if (index > 0) RowDivider()
                        CheckRow(
                            title = type.name + if (type.archived) " · в архиве" else "",
                            checked = type.id in pt,
                            onToggle = { on -> pt = if (on) pt + type.id else pt - type.id },
                            modifier = Modifier.padding(horizontal = Dimens.space4).testTag(asPtTag(type.name)),
                        )
                    }
                }
                Hint(RateFormat.AS_PT_NOTE)
                Spacer(Modifier.height(Dimens.space6))
            }
            BottomAction(
                text = "Сохранить",
                enabled = problem == null,
                onClick = {
                    val draft = TierDraft(enabled, t2From ?: initial.t2From, t3From ?: initial.t3From, pt.toSet())
                    scope.launch { rates.saveTiers(month, draft) }
                    onBack()
                    messages.show(RateFormat.tiersSaved(from))
                },
            )
        }
        if (picking) {
            DatePickerPanel(from, { picking = false; monthText = it.yearMonth.toString() }, { picking = false })
        }
    }
}

private val NUMBER = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)

/** Поле суммы: цифры с запятой, ошибка — рамкой (текст ошибки — один под строкой полей). */
@Composable
private fun MoneyField(label: String, value: String, onChange: (String) -> Unit, error: Boolean, modifier: Modifier, tag: String) {
    Field(
        label,
        value,
        onChange,
        modifier,
        error = if (error) "" else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        tag = tag,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = Dimens.space2),
        style = MaterialTheme.typography.bodySmall,
        color = PersonalkaTheme.colors.textMuted,
    )
}
