package com.akenory.personalka.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akenory.personalka.data.training.Diary
import com.akenory.personalka.data.training.DiaryRepository
import com.akenory.personalka.domain.format.DiaryFormat
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.training.DiaryRules
import com.akenory.personalka.ui.components.Field
import com.akenory.personalka.ui.components.Note
import com.akenory.personalka.ui.components.PrimaryButton
import com.akenory.personalka.ui.components.SecondaryButton
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.shell.LocalWide
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import com.akenory.personalka.ui.theme.tabular
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

const val DIARY_TAG = "diary"
const val DIARY_PLAN_TAG = "diary-plan"
const val DIARY_PLAN_EDIT_TAG = "diary-plan-edit"
const val DIARY_FACT_TAG = "diary-fact"
const val DIARY_NEXT_PLAN_TAG = "diary-next-plan"
const val DIARY_SAVED_TAG = "diary-saved"
const val DIARY_NEXT_TAG = "diary-next"
const val DIARY_DONE_TAG = "diary-done"
const val DIARY_TITLE_TAG = "diary-title"
fun diaryBehaviorTag(clientId: String) = "diary-behavior-$clientId"
fun diaryVoiceTag(field: String) = "$field-voice"

/**
 * Голосовой ввод (5.7): системное распознавание речи телефона (на Android — `RecognizerIntent`), [onText] — что
 * услышано. Нет у платформы (тесты, телефон без распознавания) — `null`, микрофонов в полях нет: выключенное не
 * показывается (10.7); голосовой ввод клавиатуры остаётся.
 */
fun interface VoiceInput {
    fun listen(onText: (String) -> Unit)
}

/**
 * Дневник тренировки (ТЗ 5.7, макеты «Дневник ПТ», «Дневник сплита», «Дневник группы»): поля по типу (3.3), подсказка
 * «Без диагнозов…» (10.3), у группы под фактом — «Видят родители всех детей…». Каждая правка сразу пишется в базу —
 * это черновик: закрыли посреди слова — текст на месте. «Следующая без дневника» открывает следующую проведённую
 * с пустым фактом ([DiaryRules.next]), «Готово» закрывает.
 *
 * Поля читаются из базы один раз при открытии тренировки [id], дальше их ведёт экран: запись не двигает курсор.
 * Экран один для сложенного и разложенного Fold — сложение ничего не сбрасывает.
 *
 * @param scope запись живёт дольше экрана: последняя буква дописывается и после «Готово».
 * @param onOpen открыть дневник другой тренировки — «Следующая без дневника».
 */
@Composable
fun DiaryScreen(
    id: String,
    diaries: DiaryRepository,
    voice: VoiceInput?,
    scope: CoroutineScope,
    onOpen: (String) -> Unit,
    onClient: (String) -> Unit,
    onGroup: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = PersonalkaTheme.colors
    val wide = LocalWide.current
    var diary by remember(id) { mutableStateOf<Diary?>(null) }
    var plan by remember(id) { mutableStateOf("") }
    var fact by remember(id) { mutableStateOf("") }
    var nextPlan by remember(id) { mutableStateOf("") }
    val behavior = remember(id) { mutableStateMapOf<String, String>() }
    var editingPlan by remember(id) { mutableStateOf(false) }
    var saved by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        val loaded = diaries.diary(id)
        if (loaded == null) {
            onClose()
            return@LaunchedEffect
        }
        plan = loaded.plan
        fact = loaded.fact
        nextPlan = loaded.nextPlan
        loaded.behavior.forEach { behavior[it.clientId] = it.text }
        diary = loaded
    }
    val missing by remember(diaries) { diaries.missing }.collectAsState(initial = emptyList())
    val shown = diary?.takeIf { it.id == id }
    val slots = missing.map { it.slot }
    val next = shown?.let { DiaryRules.next(it.slot, slots) }

    fun save(write: suspend () -> Unit) {
        scope.launch {
            write()
            saved = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag(DIARY_TAG),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .imePadding()
                .then(if (wide) Modifier.widthIn(max = 600.dp) else Modifier)
                .fillMaxSize(),
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = Dimens.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(PIcons.ArrowBack, contentDescription = "Назад", tint = colors.text) }
                Spacer(Modifier.width(4.dp))
                Text(
                    DiaryFormat.TITLE,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.text,
                    maxLines = 1,
                )
                DiaryFormat.left(DiaryRules.left(id, slots))?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.textMuted)
                }
            }
            if (shown == null) {
                Spacer(Modifier.weight(1f))
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.space4),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space3),
                ) {
                    DiaryTitle(shown, onClient, onGroup)
                    Note(DiaryFormat.NO_HEALTH)
                    if (shown.fields.plan && (plan.isNotBlank() || editingPlan)) {
                        if (editingPlan) {
                            DiaryField(DiaryFormat.PLAN_WAS, plan, DIARY_PLAN_TAG, voice) { text ->
                                plan = text
                                save { diaries.savePlan(id, text) }
                            }
                        } else {
                            PlanWas(plan) { editingPlan = true }
                        }
                    }
                    if (shown.fields.fact) {
                        Column {
                            DiaryField(DiaryFormat.FACT, fact, DIARY_FACT_TAG, voice) { text ->
                                fact = text
                                save { diaries.saveFact(id, text) }
                            }
                            if (shown.kind == TrainingKind.GROUP) GroupHint()
                        }
                    }
                    shown.behavior.forEach { b ->
                        val label = if (shown.behavior.size > 1) DiaryFormat.behaviorOf(b.name) else DiaryFormat.BEHAVIOR
                        DiaryField(label, behavior[b.clientId].orEmpty(), diaryBehaviorTag(b.clientId), voice) { text ->
                            behavior[b.clientId] = text
                            save { diaries.saveBehavior(id, b.clientId, text) }
                        }
                    }
                    if (shown.fields.plan) {
                        DiaryField(DiaryFormat.nextPlan(shown.next), nextPlan, DIARY_NEXT_PLAN_TAG, voice) { text ->
                            nextPlan = text
                            save { diaries.saveNextPlan(id, text) }
                        }
                    }
                    if (saved) {
                        Row(Modifier.testTag(DIARY_SAVED_TAG), verticalAlignment = Alignment.CenterVertically) {
                            Icon(PIcons.CheckCircle, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Dimens.space2))
                            Text(DiaryFormat.SAVED, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                        }
                    }
                    Spacer(Modifier.height(Dimens.space4))
                }
            }
            HorizontalDivider(thickness = 1.dp, color = colors.divider)
            Row(Modifier.fillMaxWidth().padding(Dimens.space4), horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                if (next != null) {
                    SecondaryButton(DiaryFormat.NEXT, { onOpen(next.id) }, Modifier.weight(1f).testTag(DIARY_NEXT_TAG))
                }
                PrimaryButton(DiaryFormat.DONE, onClose, Modifier.weight(1f).testTag(DIARY_DONE_TAG))
            }
        }
    }
}

/** «Орлова К. ›» и «вт 20.10 · 13:30–14:20 · ПТ · ББ»: имя ведёт в карточку клиента или группы, у сплита — никуда. */
@Composable
private fun DiaryTitle(diary: Diary, onClient: (String) -> Unit, onGroup: (String) -> Unit) {
    val colors = PersonalkaTheme.colors
    val link: (() -> Unit)? = diary.clientId?.let { c -> { onClient(c) } } ?: diary.groupId?.let { g -> { onGroup(g) } }
    Column {
        Row(
            Modifier
                .heightIn(min = Dimens.touch)
                .then(if (link != null) Modifier.clickable(role = Role.Button, onClick = link) else Modifier)
                .testTag(DIARY_TITLE_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                diary.title,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleLarge,
                color = colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (link != null) Icon(PIcons.ChevronRight, contentDescription = null, tint = colors.textMuted)
        }
        Text(
            DiaryFormat.whenLine(diary.start, diary.end, diary.typeShort, diary.placeShort),
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = colors.textMuted,
        )
    }
}

/** «План был» для справки — на подложке, карандаш открывает его правку. */
@Composable
private fun PlanWas(plan: String, onEdit: () -> Unit) {
    val colors = PersonalkaTheme.colors
    Column {
        Text(
            DiaryFormat.PLAN_WAS,
            modifier = Modifier.padding(bottom = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textMuted,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceAlt, MaterialTheme.shapes.medium)
                .padding(start = Dimens.space4, top = Dimens.space1, bottom = Dimens.space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(plan, modifier = Modifier.weight(1f).padding(vertical = Dimens.space3), style = MaterialTheme.typography.bodyLarge, color = colors.text)
            IconButton(onClick = onEdit, modifier = Modifier.testTag(DIARY_PLAN_EDIT_TAG)) {
                Icon(PIcons.Edit, contentDescription = DiaryFormat.EDIT_PLAN, tint = colors.textMuted)
            }
        }
    }
}

/** «Видят родители всех детей — не пишите имён» под фактом группы. */
@Composable
private fun GroupHint() {
    val colors = PersonalkaTheme.colors
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(PIcons.Visibility, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(DiaryFormat.GROUP_HINT, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    }
}

/**
 * Поле дневника: многострочное, с заглавной буквы предложения; микрофон справа дописывает услышанное в конец
 * через пробел ([VoiceInput]); нет распознавания — микрофона нет, голосовой ввод клавиатуры остаётся.
 */
@Composable
private fun DiaryField(label: String, value: String, tag: String, voice: VoiceInput?, onChange: (String) -> Unit) {
    val colors = PersonalkaTheme.colors
    val mic: (@Composable () -> Unit)? = voice?.let { v ->
        @Composable {
            IconButton(
                onClick = { v.listen { heard -> onChange(appendHeard(value, heard)) } },
                modifier = Modifier.testTag(diaryVoiceTag(tag)),
            ) {
                Icon(PIcons.Mic, contentDescription = DiaryFormat.VOICE, tint = colors.textMuted)
            }
        }
    }
    Field(
        label = label,
        value = value,
        onValueChange = onChange,
        tag = tag,
        singleLine = false,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        trailing = mic,
    )
}

/** Услышанное — в конец поля: пусто — как есть, иначе через пробел. */
internal fun appendHeard(current: String, heard: String): String {
    val text = heard.trim()
    return when {
        text.isEmpty() -> current
        current.isBlank() -> text.replaceFirstChar { it.uppercaseChar() }
        current.endsWith(' ') || current.endsWith('\n') -> current + text
        else -> "$current $text"
    }
}
