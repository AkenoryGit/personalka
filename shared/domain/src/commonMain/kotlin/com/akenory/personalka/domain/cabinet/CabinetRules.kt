package com.akenory.personalka.domain.cabinet

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.CabinetFormat
import com.akenory.personalka.domain.format.CalendarFormat
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.result.ResultItem
import com.akenory.personalka.domain.training.ClientOutcome
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Тренировка клиента для кабинета родителя (ТЗ 6.3): только то, что можно показать. Имён других участников, названия
 * группы, коллег и служебных отметок здесь нет совсем (6.2) — сервер их сюда не кладёт, и страница не может их вывести.
 * [typeName] и [placeName] — полные названия; [showPlan], [showFact], [showBehavior] — включены ли эти поля у типа (3.3).
 * [cancelReason] — причина отмены клиента, а если её нет — всей тренировки.
 */
data class CabinetTraining(
    val start: LocalDateTime,
    val typeName: String,
    val placeName: String,
    val trainingStatus: TrainingStatus,
    val participationStatus: ParticipationStatus,
    val plan: String? = null,
    val fact: String? = null,
    val behavior: String? = null,
    val showPlan: Boolean = true,
    val showFact: Boolean = true,
    val showBehavior: Boolean = false,
    val cancelReason: String? = null,
    val notifiedAt: LocalDateTime? = null,
) {
    val outcome: ClientOutcome get() = ClientOutcome.of(trainingStatus, participationStatus)
}

/**
 * Всё, из чего собирается кабинет: имя клиента и тренера (именительный падеж, без склонений — 6.3), момент последней
 * синхронизации тренера по часам клуба, «сейчас», настройки тренера (3.2), тренировки и результаты клиента.
 */
data class CabinetInput(
    val clientName: String,
    val coachName: String,
    val syncedAt: LocalDateTime?,
    val now: LocalDateTime,
    val upcomingCount: Int,
    val cancelNoticeMinutes: Int,
    val trainings: List<CabinetTraining>,
    val results: List<ResultItem> = emptyList(),
    /** Ссылка на фото и видео из карточки клиента (3.5). */
    val mediaUrl: String? = null,
    /** Согласие (6.1): когда дано (день по часам клуба) и отмечена ли галочка про фото. */
    val consentDate: LocalDate? = null,
    val photoConsent: Boolean = false,
)

/** Строка тренировки: «вт 20.10 · 13:30», «Персональная · Большой бассейн», метка статуса, поля дневника или причина. */
data class CabinetItem(
    val whenText: String,
    val typePlace: String,
    /** Метка статуса; `null` — без метки (запланированная). */
    val status: ClientOutcome?,
    /** «План», «Факт», «Поведение» — только заполненные. */
    val fields: List<Pair<String, String>>,
    /** Причина отмены и «предупредили за 40 мин». */
    val note: String?,
)

/** Месяц истории: «Октябрь 2026», «6 тренировок». */
data class CabinetMonth(val title: String, val count: String, val items: List<CabinetItem>)

/** Строка счётчиков: слово статуса, текущий месяц, всего. */
data class CabinetCounter(val outcome: ClientOutcome, val month: Int, val total: Int)

/** «Ранее · 47 тренировок»: прошлые месяцы, от новых к старым. */
data class CabinetEarlier(val count: String, val months: List<CabinetMonth>)

/** Страница кабинета сверху вниз (6.3); пустые разделы — `null` или пустой список, их нет на странице. */
data class CabinetPage(
    val clientName: String,
    val coachLine: String,
    val dataLine: String?,
    val upcoming: List<CabinetItem>,
    /** Заголовок столбца текущего месяца: «Октябрь». */
    val monthColumn: String,
    val counters: List<CabinetCounter>,
    val rule: String,
    val currentMonth: CabinetMonth?,
    val earlier: CabinetEarlier?,
    /** Результаты (6.3, п. 6); нет результатов — `null`. */
    val results: CabinetResults? = null,
    /** «Фото и видео» (6.3, п. 7): нет ссылки или согласия на фото — `null`. */
    val media: CabinetMedia? = null,
    /** Подвал: «Согласие на обработку данных дано 05.09.2026». */
    val consentLine: String? = null,
)

/**
 * Кабинет родителя (ТЗ 6.3): что и в каком порядке показать. Сервер только читает данные и рисует HTML — слова,
 * счёт и порядок здесь, в тех же правилах, что у приложения (`ClientOutcome`, форматы `shared/domain/format`).
 */
object CabinetRules {
    /**
     * Ближайшие (6.3, п. 2): [upcomingCount] запланированных с этого момента и отменённые заранее из того же промежутка —
     * они идут серой меткой и в число не входят. Запланированных меньше — отменённые показываются все будущие.
     */
    fun upcoming(trainings: List<CabinetTraining>, now: LocalDateTime, upcomingCount: Int): List<CabinetTraining> {
        val ahead = trainings.filter { it.start >= now }.sortedBy { it.start }
        val planned = ahead.filter { it.outcome == ClientOutcome.PLANNED }.take(upcomingCount.coerceAtLeast(0))
        val until = if (planned.size < upcomingCount) null else planned.lastOrNull()?.start
        val cancelled = ahead.filter { it.outcome != ClientOutcome.PLANNED && (until == null || it.start <= until) }
        return (planned + cancelled).sortedBy { it.start }
    }

    /** История (6.3, п. 5): тренировки, которые уже начались, от новых к старым. */
    fun history(trainings: List<CabinetTraining>, now: LocalDateTime): List<CabinetTraining> =
        trainings.filter { it.start < now }.sortedByDescending { it.start }

    /**
     * Счётчики (6.3, п. 3): «Проведено», «Отменено», «Отмена со списанием» — за месяц [today] и всего. Считается только
     * история: будущая отмена видна в «Ближайших», но в счётчик не входит, пока её время не наступило.
     */
    fun counters(history: List<CabinetTraining>, today: LocalDate): List<CabinetCounter> {
        val rows = COUNTED.map { outcome ->
            val all = history.filter { it.outcome == outcome }
            CabinetCounter(outcome, all.count { sameMonth(it.start.date, today) }, all.size)
        }
        return if (rows.all { it.total == 0 }) emptyList() else rows
    }

    /** Страница целиком; [chartKey] — дистанция графика из адреса (`?d=`), неизвестная — по умолчанию. */
    fun page(input: CabinetInput, chartKey: String? = null): CabinetPage {
        val today = input.now.date
        val history = history(input.trainings, input.now)
        val (current, past) = history.partition { sameMonth(it.start.date, today) }
        val earlierMonths = past.groupBy { LocalDate(it.start.year, it.start.month, 1) }
            .toList()
            .sortedByDescending { it.first }
            .map { (first, list) -> month(first, list) }
        return CabinetPage(
            clientName = input.clientName,
            coachLine = CabinetFormat.coach(input.coachName),
            dataLine = input.syncedAt?.let(CabinetFormat::dataAt),
            upcoming = upcoming(input.trainings, input.now, input.upcomingCount).mapIndexed { i, t -> item(t, upcomingIndex = i) },
            monthColumn = CabinetFormat.monthColumn(today),
            counters = counters(history, today),
            rule = CabinetFormat.rule(input.cancelNoticeMinutes),
            currentMonth = current.takeIf { it.isNotEmpty() }?.let { month(LocalDate(today.year, today.month, 1), it) },
            earlier = past.takeIf { it.isNotEmpty() }?.let { CabinetEarlier(CabinetFormat.trainings(it.size), earlierMonths) },
            results = CabinetResultRules.results(input.results, today, chartKey),
            media = CabinetMediaRules.media(input.mediaUrl, input.photoConsent),
            consentLine = input.consentDate?.let(CabinetFormat::consentGiven),
        )
    }

    private fun month(first: LocalDate, list: List<CabinetTraining>) =
        CabinetMonth(CabinetFormat.monthTitle(first), CabinetFormat.trainings(list.size), list.map { item(it) })

    /**
     * Строка тренировки. В «Ближайших» ([upcomingIndex] не `null`) у запланированной — только план и только у первой,
     * без метки; у отменённой — серая метка и причина. В истории — метка, дальше поля дневника или причина отмены.
     */
    fun item(training: CabinetTraining, upcomingIndex: Int? = null): CabinetItem {
        val outcome = training.outcome
        val cancelled = outcome == ClientOutcome.CANCELLED || outcome == ClientOutcome.CANCELLED_CHARGED
        val fields = when {
            cancelled -> emptyList()
            upcomingIndex != null -> listOfNotNull(
                filled(training.plan)?.takeIf { training.showPlan && upcomingIndex == 0 }?.let { CabinetFormat.PLAN to it },
            )
            else -> listOfNotNull(
                filled(training.plan)?.takeIf { training.showPlan }?.let { CabinetFormat.PLAN to it },
                filled(training.fact)?.takeIf { training.showFact }?.let { CabinetFormat.FACT to it },
                filled(training.behavior)?.takeIf { training.showBehavior }?.let { CabinetFormat.BEHAVIOR to it },
            )
        }
        val notice = training.notifiedAt
            ?.takeIf { outcome == ClientOutcome.CANCELLED_CHARGED }
            ?.let { ClubTime.minutesBetween(it, training.start) }
        return CabinetItem(
            whenText = CabinetFormat.whenText(training.start),
            typePlace = CalendarFormat.typePlace(training.typeName, training.placeName),
            status = outcome.takeIf { it != ClientOutcome.PLANNED },
            fields = fields,
            note = if (cancelled) CabinetFormat.cancelNote(filled(training.cancelReason), notice) else null,
        )
    }

    private val COUNTED = listOf(ClientOutcome.DONE, ClientOutcome.CANCELLED, ClientOutcome.CANCELLED_CHARGED)

    private fun sameMonth(a: LocalDate, b: LocalDate) = a.year == b.year && a.month == b.month

    private fun filled(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }
}
