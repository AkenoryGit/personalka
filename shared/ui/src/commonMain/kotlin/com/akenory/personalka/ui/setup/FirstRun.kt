package com.akenory.personalka.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.format.TypeFormat
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.PlaceDraft
import com.akenory.personalka.domain.reference.ReferenceRules
import com.akenory.personalka.domain.reference.TypeDraft
import com.akenory.personalka.ui.components.BottomAction
import com.akenory.personalka.ui.components.CheckBox
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.ListCard
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.components.RowDivider
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.TypeColor
import kotlinx.coroutines.launch

const val LAST_NAME_TAG = "last-name"
const val FIRST_NAME_TAG = "first-name"
const val MIDDLE_NAME_TAG = "middle-name"
const val NEW_TYPE_ROW_TAG = "new-type"
internal fun setupTypeTag(name: String) = "setup-type-$name"

/** Экраны первого запуска (ТЗ 5.18); «Новое место» и «Новый тип» открываются поверх «Мест и типов». */
enum class FirstRunPage { WELCOME, NAME, SETUP, NEW_PLACE, NEW_TYPE }

/** Пункт списка с отметкой: «Лишнее снимите». */
data class Picked<T>(val item: T, val on: Boolean = true)

/**
 * Что тренер успел ввести в первом запуске. В базу ничего не пишется до «Готово»: снятые места и типы
 * не создаются вовсе, а прерванный первый запуск начинается заново без следов. Состояние сохраняется
 * [Saver]-ом — сложение Fold, смена темы и выгрузка из памяти его не теряют.
 */
@Stable
class FirstRunState(
    page: FirstRunPage = FirstRunPage.WELCOME,
    name: CoachName = CoachName("", "", ""),
    places: List<Picked<PlaceDraft>> = ReferenceRules.defaultPlaces.map { Picked(it) },
    types: List<Picked<TypeDraft>> = ReferenceRules.defaultTypes.map { Picked(it) },
) {
    var page: FirstRunPage by mutableStateOf(page)
    var name: CoachName by mutableStateOf(name)
    var places: List<Picked<PlaceDraft>> by mutableStateOf(places)
        private set
    var types: List<Picked<TypeDraft>> by mutableStateOf(types)
        private set

    /** «Готово» доступно, когда отмечено хотя бы одно место и один тип. */
    val canFinish: Boolean get() = places.any { it.on } && types.any { it.on }

    val canGoBack: Boolean get() = page != FirstRunPage.WELCOME

    fun back() {
        page = when (page) {
            FirstRunPage.WELCOME, FirstRunPage.NAME -> FirstRunPage.WELCOME
            FirstRunPage.SETUP -> FirstRunPage.NAME
            FirstRunPage.NEW_PLACE, FirstRunPage.NEW_TYPE -> FirstRunPage.SETUP
        }
    }

    fun togglePlace(index: Int) {
        places = places.mapIndexed { i, p -> if (i == index) p.copy(on = !p.on) else p }
    }

    fun toggleType(index: Int) {
        types = types.mapIndexed { i, t -> if (i == index) t.copy(on = !t.on) else t }
    }

    /** Новое место — отмеченным в конце списка. */
    fun addPlace(place: PlaceDraft) {
        places = places + Picked(PlaceDraft(place.name.trim(), place.shortName.trim()))
        page = FirstRunPage.SETUP
    }

    fun addType(type: TypeDraft) {
        types = types + Picked(type.copy(name = type.name.trim(), shortName = type.shortName.trim()))
        page = FirstRunPage.SETUP
    }

    companion object {
        private const val ITEM = "\u001E"
        private const val FIELD = "\u001F"

        val Saver: Saver<FirstRunState, Any> = Saver(
            save = { s ->
                arrayListOf(
                    s.page.name,
                    s.name.lastName,
                    s.name.firstName,
                    s.name.middleName.orEmpty(),
                    s.places.joinToString(ITEM) { (if (it.on) "1" else "0") + it.item.name + FIELD + it.item.shortName },
                    s.types.joinToString(ITEM) { (if (it.on) "1" else "0") + encodeType(it.item) },
                )
            },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                val v = saved as List<String>
                FirstRunState(
                    page = FirstRunPage.valueOf(v[0]),
                    name = CoachName(v[1], v[2], v[3]),
                    places = v[4].split(ITEM).filter { it.isNotEmpty() }.map {
                        val (name, short) = it.drop(1).split(FIELD)
                        Picked(PlaceDraft(name, short), on = it.startsWith("1"))
                    },
                    types = v[5].split(ITEM).filter { it.isNotEmpty() }.map {
                        Picked(decodeType(it.drop(1)), on = it.startsWith("1"))
                    },
                )
            },
        )
    }
}

/**
 * Первый запуск (ТЗ 5.18): приветствие → ФИО → места и типы → календарь. Меньше минуты; про зарплату
 * и папку копии не спрашивается.
 *
 * @param onDone вызывается перед записью в базу: приложение откроется на «Календаре».
 */
@Composable
fun FirstRun(
    settings: SettingsRepository,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit,
    onDone: () -> Unit,
) {
    val state = rememberSaveable(saver = FirstRunState.Saver) { FirstRunState() }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    backHandler(state.canGoBack) { state.back() }

    // Отступы под системные панели — без «поглощения»: формы внутри сами поднимаются над клавиатурой.
    val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    Box(
        Modifier.fillMaxSize().background(PersonalkaTheme.colors.bg).padding(safe),
        contentAlignment = Alignment.TopCenter,
    ) {
        // На разложенном Fold — колонка по центру: строки во всю ширину 840 dp читать неудобно.
        Box(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
            when (state.page) {
                FirstRunPage.WELCOME -> WelcomePage(onStart = { state.page = FirstRunPage.NAME })
                FirstRunPage.NAME -> NamePage(state)
                FirstRunPage.SETUP -> SetupPage(
                    state = state,
                    saving = saving,
                    failed = failed,
                    onFinish = {
                        if (!saving) {
                            saving = true
                            failed = false
                            onDone()
                            scope.launch {
                                try {
                                    settings.completeFirstRun(
                                        name = state.name,
                                        places = state.places.filter { it.on }.map { it.item },
                                        types = state.types.filter { it.on }.map { it.item },
                                    )
                                } catch (e: Exception) {
                                    saving = false
                                    failed = true
                                }
                            }
                        }
                    },
                )
                FirstRunPage.NEW_PLACE -> PlaceEditor(
                    title = "Новое место",
                    initial = PlaceDraft("", ""),
                    others = state.places.map { it.item },
                    onSave = state::addPlace,
                    onClose = state::back,
                )
                FirstRunPage.NEW_TYPE -> TypeEditor(
                    title = "Новый тип",
                    initial = ReferenceRules.newType(state.types.map { it.item.color }),
                    others = state.types.map { it.item },
                    placeShort = state.places.firstOrNull { it.on }?.item?.shortName,
                    onSave = state::addType,
                    onClose = state::back,
                )
            }
        }
    }
}

@Composable
private fun WelcomePage(onStart: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Dimens.space8), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppMark()
                Spacer(Modifier.height(Dimens.space6))
                Text(
                    "Персоналка",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.text,
                )
                Spacer(Modifier.height(Dimens.space3))
                Text(
                    "Расписание, клиенты и зарплата тренера в одном телефоне. Работает и без интернета.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
        // «Войти» (сервер) и «Восстановить из копии» появятся вместе с сервером (этап 2) и копией (1.15):
        // выключенное не показывается (10.7).
        PrimaryButton("Начать на телефоне", onStart, Modifier.fillMaxWidth().padding(Dimens.space4))
    }
}

/** Знак приложения — монограмма «П» с оранжевой точкой, как иконка на рабочем столе (шаг 0.1). */
@Composable
private fun AppMark() {
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.size(96.dp).shadow(6.dp, shape).background(IconCream, shape)) {
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / 72f
            val path = Path().apply {
                moveTo(23 * k, 53 * k)
                lineTo(23 * k, 21 * k)
                lineTo(49 * k, 21 * k)
                lineTo(49 * k, 53 * k)
            }
            drawPath(path, IconGraphite, style = Stroke(width = 8 * k))
            drawCircle(IconOrange, radius = 5 * k, center = Offset(36 * k, 47 * k))
        }
    }
}

// Цвета иконки 0.1 — постоянные, в обеих темах знак тот же, что на рабочем столе.
private val IconCream = Color(0xFFF3EFE6)
private val IconGraphite = Color(0xFF15171A)
private val IconOrange = Color(0xFFFF5A1F)

/** Шапка шага: «Назад» и полоска из двух частей. */
@Composable
private fun StepHeader(step: Int, onBack: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier.fillMaxWidth().height(Dimens.topBar).padding(start = 4.dp, end = Dimens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(PIcons.ArrowBack, contentDescription = "Назад", tint = colors.text)
        }
        Spacer(Modifier.width(Dimens.space2))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(STEPS) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(if (i < step) colors.primary else colors.surfaceAlt, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

private const val STEPS = 2

@Composable
private fun StepTitle(step: Int, title: String, text: String? = null) {
    val colors = PersonalkaTheme.colors
    Spacer(Modifier.height(Dimens.space4))
    Text("Шаг $step из $STEPS", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    Spacer(Modifier.height(Dimens.space2))
    Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = colors.text)
    if (text != null) {
        Spacer(Modifier.height(Dimens.space2))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.textMuted)
    }
}

@Composable
private fun NamePage(state: FirstRunState) {
    val name = state.name
    val next = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.systemBars))) {
        StepHeader(step = 1, onBack = state::back)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            StepTitle(1, "Как вас зовут?")
            Field("Фамилия", name.lastName, { state.name = name.copy(lastName = it) }, keyboardOptions = next, tag = LAST_NAME_TAG)
            Field("Имя", name.firstName, { state.name = name.copy(firstName = it) }, keyboardOptions = next, tag = FIRST_NAME_TAG)
            Field(
                label = "Отчество · если есть",
                value = name.middleName.orEmpty(),
                onValueChange = { state.name = name.copy(middleName = it) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                tag = MIDDLE_NAME_TAG,
            )
            Text(
                "Имя и фамилию родители увидят в кабинете, полное ФИО войдёт в согласие на обработку данных. " +
                    "Поменять можно в настройках.",
                style = MaterialTheme.typography.bodySmall,
                color = PersonalkaTheme.colors.textMuted,
            )
        }
        BottomAction(
            text = "Далее",
            enabled = name.isComplete,
            onClick = {
                state.name = name.trimmed()
                state.page = FirstRunPage.SETUP
            },
        )
    }
}

@Composable
private fun SetupPage(state: FirstRunState, saving: Boolean, failed: Boolean, onFinish: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Column(Modifier.fillMaxSize()) {
        StepHeader(step = 2, onBack = state::back)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4),
        ) {
            StepTitle(
                step = 2,
                title = "Места и типы",
                text = "Обычный набор клуба уже заполнен. Лишнее снимите, остальное поправите потом в настройках.",
            )
            SectionLabel("Места")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                state.places.forEachIndexed { index, place ->
                    ChoiceChip(place.item.shortName, selected = place.on, toggle = true, onClick = { state.togglePlace(index) })
                }
                ChoiceChip("Место", selected = false, icon = PIcons.Add, onClick = { state.page = FirstRunPage.NEW_PLACE })
            }
            SectionLabel("Типы тренировок")
            ListCard {
                state.types.forEachIndexed { index, type ->
                    if (index > 0) RowDivider(start = 52.dp)
                    SetupTypeRow(type, onToggle = { state.toggleType(index) })
                }
                RowDivider(start = 52.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(role = Role.Button) { state.page = FirstRunPage.NEW_TYPE }
                        .testTag(NEW_TYPE_ROW_TAG)
                        .padding(horizontal = Dimens.space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PIcons.Add, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Dimens.space4))
                    Text("Новый тип", style = MaterialTheme.typography.bodyLarge, color = colors.text)
                }
            }
            Spacer(Modifier.height(Dimens.space4))
        }
        BottomAction(
            text = "Готово",
            onClick = onFinish,
            enabled = state.canFinish && !saving,
            hint = when {
                failed -> "Не получилось сохранить. Попробуйте ещё раз."
                !state.canFinish -> "Отметьте хотя бы одно место и один тип."
                else -> null
            },
        )
    }
}

@Composable
private fun SetupTypeRow(type: Picked<TypeDraft>, onToggle: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = type.on, role = Role.Checkbox, onValueChange = { onToggle() })
            .testTag(setupTypeTag(type.item.name))
            .padding(horizontal = Dimens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(type.on)
        Spacer(Modifier.width(Dimens.space4))
        Box(Modifier.size(10.dp).background(TypeColor.of(type.item.color).tone(colors.isDark).stripe, CircleShape))
        Spacer(Modifier.width(Dimens.space3))
        Text(type.item.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.text)
        Spacer(Modifier.width(Dimens.space2))
        Text(TypeFormat.setupLine(type.item), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    }
}
