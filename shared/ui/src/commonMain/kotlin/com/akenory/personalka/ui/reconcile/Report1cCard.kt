package com.akenory.personalka.ui.reconcile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.akenory.personalka.data.db.entity.LocationEntity
import com.akenory.personalka.data.db.entity.TrainingTypeEntity
import com.akenory.personalka.data.reconcile.Name1cQuestion
import com.akenory.personalka.data.reconcile.Report1cRepository
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.format.ClientFormat
import com.akenory.personalka.domain.format.ReconcileFormat
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.report1c.NameMatch
import com.akenory.personalka.domain.report1c.Reconcile1cRules
import com.akenory.personalka.domain.report1c.Report1c
import com.akenory.personalka.domain.report1c.Report1cParser
import com.akenory.personalka.domain.report1c.Report1cRead
import com.akenory.personalka.ui.components.ChoiceChip
import com.akenory.personalka.ui.components.MessageState
import com.akenory.personalka.ui.components.OutlineButton
import com.akenory.personalka.ui.components.Panel
import com.akenory.personalka.ui.components.PanelActions
import com.akenory.personalka.ui.components.SectionLabel
import com.akenory.personalka.ui.icons.PIcons
import com.akenory.personalka.ui.salary.CardHeader
import com.akenory.personalka.ui.salary.MoneyRow
import com.akenory.personalka.ui.salary.Muted
import com.akenory.personalka.ui.salary.SalaryCard
import com.akenory.personalka.ui.settings.PlatformFiles
import com.akenory.personalka.ui.settings.SharedFile
import com.akenory.personalka.ui.theme.Dimens
import com.akenory.personalka.ui.theme.PersonalkaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

internal const val REPORT1C_TAG = "reconcile-1c"
internal const val REPORT1C_LOAD_TAG = "reconcile-1c-load"
internal const val REPORT1C_NEXT_TAG = "reconcile-1c-next"
internal const val REPORT1C_SKIP_TAG = "reconcile-1c-skip"
internal const val REPORT1C_MINE_TAG = "reconcile-1c-mine"
internal fun report1cRowTag(id: String) = "reconcile-1c-row-$id"
internal fun report1cTypeTag(id: String) = "reconcile-1c-type-$id"
internal fun report1cPlaceTag(id: String) = "reconcile-1c-place-$id"
internal fun report1cClientTag(id: String) = "reconcile-1c-client-$id"

/**
 * Загрузка отчёта 1С (ТЗ 5.14, 8.2, 8.3; шаг 4.2б): файл → разбор → «Это отчёт другого тренера?» → вопросы по одному
 * (тип и место для каждой новой услуги, кто это — для имён с похожими) → сверка. Держится над разметкой экрана: окно
 * переживает сложение Fold.
 */
internal class Report1cLoad(
    private val repo: Report1cRepository,
    private val messages: MessageState,
    private val scope: CoroutineScope,
    private val onChecked: (String) -> Unit,
) {
    /** Прочитанный отчёт, который сейчас готовится к сверке; `null` — окна нет. */
    var report by mutableStateOf<Report1c?>(null)
        private set
    var fileName by mutableStateOf("")
        private set

    /** Шапка отчёта с другим ФИО — сначала вопрос «Это отчёт другого тренера?». */
    var otherCoach by mutableStateOf(false)
        private set
    var services by mutableStateOf<List<String>>(emptyList())
        private set
    var names by mutableStateOf<List<Name1cQuestion>>(emptyList())
        private set
    var step by mutableStateOf(0)
        private set

    val total: Int get() = services.size + names.size

    fun start(file: SharedFile) {
        scope.launch {
            when (val read = withContext(Dispatchers.Default) { Report1cParser.parse(file.bytes) }) {
                Report1cRead.NotAReport -> messages.show(ReconcileFormat.NOT_A_REPORT)
                is Report1cRead.Incomplete -> messages.show(ReconcileFormat.incomplete(read.report.rows.size, read.expected))
                is Report1cRead.Read -> {
                    fileName = file.name
                    report = read.report
                    otherCoach = Report1cParser.otherCoach(read.report, repo.coachName1c())
                    if (!otherCoach) prepare()
                }
            }
        }
    }

    /** «Да, это мой отчёт» — ФИО в 1С не меняется: оно задаётся в профиле. */
    fun mine() {
        otherCoach = false
        scope.launch { prepare() }
    }

    fun cancel() {
        report = null
        otherCoach = false
        step = 0
    }

    fun saveService(name: String, typeId: String, placeId: String) {
        scope.launch {
            repo.saveService(name, typeId, placeId)
            next()
        }
    }

    /** Выбран клиент — пара запоминается; `null` — «Нет такого клиента», строки пойдут в «Только в 1С». */
    fun chooseClient(name: String, clientId: String?) {
        scope.launch {
            if (clientId != null) repo.saveAlias(name, clientId)
            next()
        }
    }

    suspend fun clients(ids: List<String>) = repo.clients(ids)

    private suspend fun prepare() {
        val r = report ?: return
        repo.rememberCoach(r.coach)
        val q = repo.questions(r)
        services = q.services
        names = q.names
        step = 0
        if (q.none) check()
    }

    private suspend fun next() {
        step++
        if (step >= total) check()
    }

    private suspend fun check() {
        val r = report ?: return
        val (id, result) = repo.check(r, fileName)
        cancel()
        messages.show(ReconcileFormat.summary(result))
        onChecked(id)
    }
}

/**
 * Карточка «Отчёт 1С» (5.14): сверки, чей период задевает месяц, — период, выгрузка и «Совпало · Расхождений» (нажатие —
 * экран сверки), и кнопка «Загрузить отчёт». Без выбора файлов и без сверок карточки нет (10.7).
 */
@Composable
internal fun Report1cCard(repo: Report1cRepository, month: YearMonth, files: PlatformFiles?, onLoad: (SharedFile) -> Unit, onOpen: (String) -> Unit = {}) {
    val history by remember(repo) { repo.history() }.collectAsState(initial = emptyList())
    val open by remember(repo) { repo.unresolved() }.collectAsState(initial = emptyList())
    val rows = history.filter { it.periodFrom <= month.lastDay && it.periodTo >= month.firstDay }
    if (files == null && rows.isEmpty()) return
    SalaryCard(Modifier.testTag(REPORT1C_TAG)) {
        CardHeader(ReconcileFormat.REPORT_TITLE)
        rows.forEach { rec ->
            MoneyRow(
                title = ReconcileFormat.period(rec.periodFrom, rec.periodTo, rec.generatedAt),
                amount = "",
                modifier = Modifier.clickable { onOpen(rec.id) }.heightIn(min = Dimens.touch).testTag(report1cRowTag(rec.id)),
                note = ReconcileFormat.summary(rec.matched, open.count { it.reconciliationId == rec.id }),
            )
        }
        if (files != null) {
            OutlineButton(ReconcileFormat.LOAD, { files.openReport(onLoad) }, Modifier.fillMaxWidth().testTag(REPORT1C_LOAD_TAG), icon = PIcons.FactCheck)
            Muted(ReconcileFormat.REPORT_HINT)
        }
    }
    // Отчёт, присланный через «Поделиться», — сразу в загрузку.
    val incoming by remember(files) { files?.incoming ?: MutableStateFlow(null) }.collectAsState()
    LaunchedEffect(incoming) {
        val file = incoming ?: return@LaunchedEffect
        files?.takeIncoming()
        onLoad(file)
    }
}

/** Окна загрузки: другой тренер, затем вопросы по одному. */
@Composable
internal fun Report1cSteps(load: Report1cLoad, settings: SettingsRepository, today: LocalDate) {
    val report = load.report ?: return
    if (load.otherCoach) {
        Panel(ReconcileFormat.OTHER_COACH, onDismiss = load::cancel) {
            Text(ReconcileFormat.otherCoach(report.coach.orEmpty()), style = MaterialTheme.typography.bodyMedium, color = PersonalkaTheme.colors.text)
            PanelActions(ReconcileFormat.MINE, load::mine, ReconcileFormat.NOT_MINE, load::cancel)
        }
        return
    }
    val step = load.step
    if (step >= load.total) return
    val title = ReconcileFormat.step(step + 1, load.total)
    if (step < load.services.size) {
        val name = load.services[step]
        val types by remember(settings) { settings.types }.collectAsState(initial = emptyList())
        val places by remember(settings) { settings.places }.collectAsState(initial = emptyList())
        ServiceStep(title, name, report, types, places, load)
    } else {
        NameStep(title, load.names[step - load.services.size], load, today)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceStep(
    title: String,
    name: String,
    report: Report1c,
    allTypes: List<TrainingTypeEntity>,
    allPlaces: List<LocationEntity>,
    load: Report1cLoad,
) {
    val types = allTypes.filter { !it.archived && it.kind != TrainingKind.DUTY }
    val places = allPlaces.filter { !it.archived }
    if (types.isEmpty() || places.isEmpty()) return
    val service = report.rows.first { it.serviceName == name }.service
    // Подсказка — по названию услуги; тренер меняет, если не так.
    var typeId by remember(name, types.size) {
        mutableStateOf(Reconcile1cRules.guessType(name, service, types, { it.kind }, { it.name })?.id)
    }
    var placeId by remember(name, places.size) {
        mutableStateOf(Reconcile1cRules.guessPlace(name, places, { it.shortName }, { it.name })?.id)
    }
    Panel(title, onDismiss = load::cancel) {
        Text(ReconcileFormat.serviceQuestion(name), style = MaterialTheme.typography.titleMedium, color = PersonalkaTheme.colors.text)
        SectionLabel(ReconcileFormat.TYPE)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            types.forEach { t ->
                ChoiceChip(t.name, t.id == typeId, { typeId = t.id }, Modifier.testTag(report1cTypeTag(t.id)))
            }
        }
        SectionLabel(ReconcileFormat.PLACE)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            places.forEach { p ->
                ChoiceChip(p.shortName, p.id == placeId, { placeId = p.id }, Modifier.testTag(report1cPlaceTag(p.id)))
            }
        }
        Muted(ReconcileFormat.SERVICE_NOTE)
        Column(Modifier.testTag(REPORT1C_NEXT_TAG)) {
            PanelActions(
                ReconcileFormat.NEXT,
                { load.saveService(name, typeId ?: return@PanelActions, placeId ?: return@PanelActions) },
                ReconcileFormat.CANCEL,
                load::cancel,
                primaryEnabled = typeId != null && placeId != null,
            )
        }
    }
}

@Composable
private fun NameStep(title: String, question: Name1cQuestion, load: Report1cLoad, today: LocalDate) {
    val ids = (question.match as? NameMatch.Ask)?.clientIds.orEmpty()
    val candidates by produceState(emptyList(), ids) { value = load.clients(ids) }
    val colors = PersonalkaTheme.colors
    Panel(title, onDismiss = load::cancel) {
        Text(ReconcileFormat.nameQuestion(question.name1c), style = MaterialTheme.typography.titleMedium, color = colors.text)
        candidates.forEach { c ->
            TextButton(
                onClick = { load.chooseClient(question.name1c, c.id) },
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button).testTag(report1cClientTag(c.id)),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                    Text(ClientName(c.lastName, c.firstName, c.middleName).full, style = MaterialTheme.typography.bodyLarge, color = colors.text)
                    ClientFormat.similarDetail(c.birthDate, c.contactPhone, c.status == ClientStatus.ARCHIVED, today)
                        ?.let { Muted(it) }
                }
            }
        }
        Muted(ReconcileFormat.NAME_NOTE)
        TextButton(
            onClick = { load.chooseClient(question.name1c, null) },
            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.button).testTag(REPORT1C_SKIP_TAG),
        ) {
            Text(ReconcileFormat.NO_SUCH_CLIENT, style = MaterialTheme.typography.labelLarge, color = colors.text)
        }
    }
}
