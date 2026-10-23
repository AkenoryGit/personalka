package com.akenory.personalka.domain.series

import com.akenory.personalka.domain.group.GroupMember
import com.akenory.personalka.domain.group.membersOn
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.training.ParticipantState
import com.akenory.personalka.domain.training.TrainingTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus

/**
 * Серии — постоянное расписание (ТЗ 4.4). Каждая функция получает серию и её тренировки и возвращает
 * [SeriesChanges] — что записать в базу одной транзакцией; сама ничего не сохраняет. «Сейчас» — время клуба
 * (`ClubTime.now`), «сегодня» — его дата. Недопустимое действие — `IllegalArgumentException`.
 *
 * Главные правила:
 * - **Прошлое серия не меняет.** Тренировку, которая уже началась, проведена, с записью о занятии
 *   или со списанием, серия не двигает и не удаляет ([SeriesTraining.changeableAt]); тренировок раньше
 *   «сейчас» серия при изменениях и продлении не создаёт.
 * - **Ключ — серия и дата.** На одну дату серия создаёт одну тренировку; повторный запуск ничего не дублирует.
 *   Тренировка, изменённая «Только эту», занимает свою дату через [Series.skipDates].
 * - **Удаление не теряет план.** План удалённой тренировки переходит на следующую тренировку серии
 *   (дописывается с новой строки, если там уже есть план), а если её нет — в [SeriesChanges.pendingPlans].
 */
object SeriesRules {
    /** `series_horizon_weeks` по умолчанию (ТЗ 3.2). */
    const val DEFAULT_HORIZON_WEEKS: Int = 8

    // --- Даты ---

    /**
     * До какого дня включительно серия создаёт тренировки: сегодня + горизонт, но не дальше конца серии.
     * Во вторник 20.10 при 8 неделях — по вторник 15.12.
     */
    fun generationEnd(series: Series, today: LocalDate, horizonWeeks: Int = DEFAULT_HORIZON_WEEKS): LocalDate {
        require(horizonWeeks >= 1) { "Горизонт серий — от 1 недели, сейчас $horizonWeeks" }
        val horizon = today.plusDays(horizonWeeks * 7)
        return series.validTo?.let { minOf(it, horizon) } ?: horizon
    }

    /** Есть ли у серии тренировка в этот день: в периоде, в её день недели и не пропущен. */
    fun occursOn(series: Series, date: LocalDate): Boolean =
        date >= series.validFrom &&
            (series.validTo == null || date <= series.validTo) &&
            date.dayOfWeek in series.pattern.weekdays &&
            date !in series.skipDates

    /** Дни серии с [from] по [to] включительно, по порядку. */
    fun occurrences(series: Series, from: LocalDate, to: LocalDate): List<LocalDate> {
        val last = series.validTo?.let { minOf(it, to) } ?: to
        val out = mutableListOf<LocalDate>()
        var d = maxOf(from, series.validFrom)
        while (d <= last) {
            if (occursOn(series, d)) out += d
            d = d.plusDays(1)
        }
        return out
    }

    // --- Создание и продление ---

    /**
     * Новая серия: тренировки от [validFrom] до меньшего из [validTo] и «сегодня + горизонт».
     * Дата начала — та, что выбрана в форме, даже если она уже прошла: так тренер вносит занятие задним числом.
     * У группы ожидаемые участники — состав [members] на каждую дату.
     */
    fun create(
        id: String,
        pattern: SeriesPattern,
        validFrom: LocalDate,
        validTo: LocalDate?,
        now: LocalDateTime,
        horizonWeeks: Int = DEFAULT_HORIZON_WEEKS,
        members: List<GroupMember> = emptyList(),
    ): SeriesChanges {
        checkPattern(pattern)
        require(validTo == null || validTo >= validFrom) { "Конец серии $validTo раньше начала $validFrom" }
        val series = Series(id, pattern, validFrom, validTo, generatedUntil = validFrom.plusDays(-1))
        val draft = Draft(emptyList())
        val generated = draft.generate(series, validFrom, generationEnd(series, now.date, horizonWeeks), members, notAfter = null)
        return draft.result(listOf(generated))
    }

    /**
     * Продление до горизонта — раз в сутки и при открытии приложения. Повторный запуск ничего не создаёт:
     * дата, на которой у серии уже есть тренировка, пропускается; прошедшие дни и время не создаются.
     * Ничего нового — [SeriesChanges.NONE], серия не перезаписывается.
     */
    fun extend(
        series: Series,
        trainings: List<SeriesTraining>,
        now: LocalDateTime,
        horizonWeeks: Int = DEFAULT_HORIZON_WEEKS,
        members: List<GroupMember> = emptyList(),
    ): SeriesChanges {
        val to = generationEnd(series, now.date, horizonWeeks)
        if (to <= series.generatedUntil) return SeriesChanges.NONE
        val from = maxOf(series.generatedUntil.plusDays(1), now.date)
        val draft = Draft(trainings.filter { it.seriesId == series.id })
        val generated = draft.generate(series, from, to, members, notAfter = now)
        val changes = draft.result(listOfNotNull(generated.takeIf { it != series }))
        return if (changes.isEmpty) SeriesChanges.NONE else changes
    }

    /** Продление всех серий тренера; состав групп — по `groupId`. */
    fun extendAll(
        series: List<Series>,
        trainings: List<SeriesTraining>,
        now: LocalDateTime,
        horizonWeeks: Int = DEFAULT_HORIZON_WEEKS,
        membersByGroup: Map<String, List<GroupMember>> = emptyMap(),
    ): SeriesChanges = series.fold(SeriesChanges.NONE) { acc, s ->
        acc + extend(s, trainings, now, horizonWeeks, s.pattern.groupId?.let { membersByGroup[it] }.orEmpty())
    }

    // --- Изменение ---

    /**
     * Спрашивать ли «Только эту / Эту и следующие»: изменились время, день, длительность, тип, место
     * или кто занимается. План и дневник — не расписание, вопроса нет.
     */
    fun asksScope(training: SeriesTraining, edit: TrainingEdit): Boolean =
        training.seriesId != null && (
            edit.start != training.start ||
                edit.durationMin != training.durationMin ||
                edit.typeId != training.typeId ||
                edit.locationId != training.locationId ||
                edit.groupId != training.groupId ||
                (edit.groupId == null && edit.clientIds.toSet() != training.clientIdSet())
            )

    /**
     * «Только эту»: тренировка меняется одна и становится отдельной (`series_detached`) — следующие правки
     * серии её не перезаписывают. Статус, план и отметки остаются: перенос — не отмена (4.2).
     * Её исходная дата уходит в [Series.skipDates], чтобы серия не создала на ней вторую тренировку.
     */
    fun changeOnlyThis(
        series: Series,
        training: SeriesTraining,
        edit: TrainingEdit,
        members: List<GroupMember> = emptyList(),
    ): SeriesChanges {
        require(training.seriesId == series.id) { "Тренировка ${training.id} не из серии ${series.id}" }
        checkTime(edit)
        val changed = training.applying(edit, members).copy(detached = true)
        val atOwnDate = !training.detached && training.date !in series.skipDates
        val next = if (atOwnDate) series.copy(skipDates = series.skipDates + training.date) else series
        return SeriesChanges(
            series = listOfNotNull(next.takeIf { it != series }),
            updated = listOfNotNull(changed.takeIf { it != training }),
        )
    }

    /**
     * «Эту и следующие». Серия получает только то, что изменилось в этой тренировке: другое время — у всех
     * дней серии, другой день — вместо дня этой тренировки (вт и чт, четверг → пятница: вт и пт).
     * Сама тренировка получает всё из [edit]. Дальше — как [changeFrom] с даты этой тренировки
     * (или новой даты, если она раньше). Сегодняшние и будущие — прошлое так не меняется.
     *
     * [newSeriesId] — id новой серии; не нужен, если серия ещё не началась, — тогда она меняется сама.
     */
    fun changeFollowing(
        series: Series,
        training: SeriesTraining,
        edit: TrainingEdit,
        trainings: List<SeriesTraining>,
        newSeriesId: String,
        now: LocalDateTime,
        horizonWeeks: Int = DEFAULT_HORIZON_WEEKS,
        members: List<GroupMember> = emptyList(),
    ): SeriesChanges {
        require(training.seriesId == series.id) { "Тренировка ${training.id} не из серии ${series.id}" }
        val today = now.date
        require(training.date >= today) { "«Эту и следующие» — с сегодняшней и будущих: прошлое серия не меняет" }
        require(edit.start.date >= today) { "Перенести «Эту и следующие» в прошлое нельзя: ${edit.start.date}" }
        checkTime(edit)

        val old = series.pattern
        val ownDate = training.date.dayOfWeek in old.weekdays &&
            (!training.detached || training.date in series.skipDates)
        val oldDay = training.date.dayOfWeek
        val newDay = edit.start.date.dayOfWeek
        val pattern = SeriesPattern(
            typeId = if (edit.typeId != training.typeId) edit.typeId else old.typeId,
            locationId = if (edit.locationId != training.locationId) edit.locationId else old.locationId,
            weekdays = if (oldDay != newDay && ownDate) old.weekdays - oldDay + newDay else old.weekdays,
            startTime = if (edit.start.time != training.start.time) edit.start.time else old.startTime,
            durationMin = if (edit.durationMin != training.durationMin) edit.durationMin else old.durationMin,
            clientIds = if (edit.groupId == null && edit.clientIds.toSet() != training.clientIdSet()) {
                edit.clientIds
            } else {
                old.clientIds
            },
            groupId = if (edit.groupId != training.groupId) edit.groupId else old.groupId,
        )
        val all = trainings.filter { it.id != training.id } + training
        return replaceFrom(
            old = series,
            split = minOf(training.date, edit.start.date),
            pattern = pattern,
            trainings = all,
            newSeriesId = newSeriesId,
            now = now,
            horizonWeeks = horizonWeeks,
            members = members,
            edited = Edited(training, edit, ownDate),
        )
    }

    /**
     * «Изменить с даты…» (5.9) и основа «Эту и следующие». Старая серия заканчивается накануне [from],
     * с [from] действует новая с [pattern]. Тренировки старой серии с этой даты:
     * - начатые, проведённые, с записью о занятии или со списанием — не трогаются (прошлое);
     * - изменённые «Только эту» — остаются как есть и переходят в новую серию;
     * - в дни новой серии — меняются на месте: новое время, тип, место, длительность; статус, план и
     *   отметки остаются (так план и отмена заранее не теряются);
     * - в остальные дни — удаляются, план переходит на следующую тренировку новой серии.
     * Недостающие дни новой серии создаются до горизонта.
     *
     * Если серия начинается не раньше [from], она меняется сама, без новой — [newSeriesId] не нужен.
     */
    fun changeFrom(
        series: Series,
        from: LocalDate,
        pattern: SeriesPattern,
        trainings: List<SeriesTraining>,
        newSeriesId: String,
        now: LocalDateTime,
        horizonWeeks: Int = DEFAULT_HORIZON_WEEKS,
        members: List<GroupMember> = emptyList(),
    ): SeriesChanges {
        require(from >= now.date) { "Изменить с даты — с сегодняшней или будущей: прошлое серия не меняет" }
        return replaceFrom(series, maxOf(from, series.validFrom), pattern, trainings, newSeriesId, now, horizonWeeks, members, null)
    }

    // --- Пропуск, отпуск, конец серии ---

    /**
     * «Пропустить дату» (праздник): тренировка удаляется, её дата — в [Series.skipDates], чтобы продление
     * её не вернуло. У тренировки, перенесённой «Только эту», дата уже занята — она просто удаляется.
     */
    fun skip(
        series: Series,
        training: SeriesTraining,
        trainings: List<SeriesTraining>,
        now: LocalDateTime,
    ): SeriesChanges {
        require(training.seriesId == series.id) { "Тренировка ${training.id} не из серии ${series.id}" }
        require(training.changeableAt(now)) {
            "Пропустить можно будущую тренировку без записи о занятии и без списания"
        }
        val draft = Draft(trainings.filter { it.seriesId == series.id && it.id != training.id } + training)
        draft.delete(training.id)
        draft.carryPlans(series.id)
        val next = if (training.detached) series else series.copy(skipDates = series.skipDates + training.date)
        return draft.result(listOfNotNull(next.takeIf { it != series }))
    }

    /**
     * «Отпуск с … по …» для всех серий тренера: будущие тренировки серий в эти дни удаляются, дни уходят
     * в [Series.skipDates] — и те, до которых серия ещё не создана. Прошлое и начатое не трогается.
     * Лист ожидания отпуск не задевает (4.16): тренер сам не работает.
     */
    fun vacation(
        series: List<Series>,
        trainings: List<SeriesTraining>,
        from: LocalDate,
        to: LocalDate,
        now: LocalDateTime,
    ): SeriesChanges {
        require(from <= to) { "Отпуск: $from позже $to" }
        return series.fold(SeriesChanges.NONE) { acc, s ->
            val mine = trainings.filter { it.seriesId == s.id }
            val draft = Draft(mine)
            mine.filter { it.date in from..to && it.changeableAt(now) }.forEach { draft.delete(it.id) }
            draft.carryPlans(s.id)
            val days = occurrences(s, maxOf(from, now.date), to)
            val next = if (days.isEmpty()) s else s.copy(skipDates = s.skipDates + days)
            acc + draft.result(listOfNotNull(next.takeIf { it != s }))
        }
    }

    /**
     * «Завершить» (5.9): [lastDate] — последний день серии. Будущие тренировки после него удаляются,
     * планы — в [SeriesChanges.pendingPlans]. Закончить в прошлом нельзя — самое раннее вчера.
     */
    fun end(
        series: Series,
        lastDate: LocalDate,
        trainings: List<SeriesTraining>,
        now: LocalDateTime,
    ): SeriesChanges {
        require(lastDate >= now.date.plusDays(-1)) { "Серию нельзя закончить в прошлом: $lastDate" }
        return endSeries(series, lastDate, deleteFrom = lastDate.plusDays(1), trainings, now)
    }

    /**
     * Клиент ушёл в архив (подтверждение — на экране). Его серии ПТ и СПТ заканчиваются сегодня, все будущие
     * тренировки этих серий удаляются, даже сегодняшние, если ещё не начались. В сплите с двумя клиентами
     * серия продолжается со вторым: с сегодняшнего дня — новая серия без ушедшего, его участие убирается
     * из будущих тренировок. Группы — через состав группы и [updateGroupMembers]; разовые тренировки — не здесь.
     */
    fun archiveClient(
        clientId: String,
        series: List<Series>,
        trainings: List<SeriesTraining>,
        now: LocalDateTime,
        newSeriesId: () -> String,
    ): SeriesChanges {
        val today = now.date
        return series
            .filter { clientId in it.pattern.clientIds && (it.validTo == null || it.validTo >= today) }
            .fold(SeriesChanges.NONE) { acc, s ->
                val mine = trainings.filter { it.seriesId == s.id }
                val rest = s.pattern.clientIds - clientId
                acc + if (rest.isEmpty()) {
                    endSeries(s, today, deleteFrom = today, mine, now)
                } else {
                    dropClient(s, clientId, rest, mine, now, newSeriesId)
                }
            }
    }

    // --- Группы ---

    /**
     * Состав группы изменился (5.10): в будущих неотмеченных тренировках группы — пришедшие в состав на эту
     * дату добавляются ожидаемыми, ушедшие убираются. Гости «на раз», отметки и прошлое не трогаются.
     * [before] и [after] — состав группы до и после правки.
     *
     * В группу, которая уже идёт или где детей начали отмечать, новый ребёнок тоже добавляется ожидаемым
     * (шаг 1.15): его записали, пока он стоит у бортика. Убирать из такой тренировки никого не нужно.
     */
    fun updateGroupMembers(
        trainings: List<SeriesTraining>,
        before: List<GroupMember>,
        after: List<GroupMember>,
        now: LocalDateTime,
    ): SeriesChanges {
        val updated = trainings.mapNotNull { t ->
            if (t.groupId == null || t.status != TrainingStatus.PLANNED) return@mapNotNull null
            val unmarked = t.changeableAt(now) && t.participants.none { it.status == ParticipationStatus.ATTENDED }
            val going = now < TrainingTime.end(t.start, t.durationMin)
            if (!unmarked && !going) return@mapNotNull null
            val was = before.membersOn(t.date).toSet()
            val will = after.membersOn(t.date)
            val gone = if (unmarked) was - will.toSet() else emptySet()
            val joined = will.filter { it !in was && t.participants.none { p -> p.clientId == it } }
            if (gone.isEmpty() && joined.isEmpty()) {
                null
            } else {
                t.copy(participants = t.participants.filterNot { it.clientId in gone } + joined.map { ParticipantState(it) })
            }
        }
        return SeriesChanges(updated = updated.filter { u -> trainings.first { it.id == u.id } != u })
    }

    // --- Внутреннее ---

    private class Edited(val training: SeriesTraining, val edit: TrainingEdit, val ownDate: Boolean)

    private fun replaceFrom(
        old: Series,
        split: LocalDate,
        pattern: SeriesPattern,
        trainings: List<SeriesTraining>,
        newSeriesId: String,
        now: LocalDateTime,
        horizonWeeks: Int,
        members: List<GroupMember>,
        edited: Edited?,
    ): SeriesChanges {
        checkPattern(pattern)
        require(old.validTo == null || split <= old.validTo) { "Серия закончилась ${old.validTo}" }
        val mine = trainings.filter { it.seriesId == old.id }
        val inPlace = notStarted(old, split, mine, now)
        val id = if (inPlace) old.id else newSeriesId
        val skip = old.skipDates.filter { it >= split }.toMutableSet()
        edited?.let { skip -= it.edit.start.date }
        var next = Series(id, pattern, validFrom = split, validTo = old.validTo, generatedUntil = split.plusDays(-1), skipDates = skip)

        val draft = Draft(mine)

        if (edited != null) {
            val e = edited.edit
            val origin = edited.training.date
            val target = e.start.date
            // Тренировка ушла со своего дня, а он остался в серии — его место занято переносом.
            if (edited.ownDate && origin != target && occursOn(next, origin)) next = next.copy(skipDates = next.skipDates + origin)
            val matches = e.start.time == pattern.startTime && e.durationMin == pattern.durationMin &&
                e.typeId == pattern.typeId && e.locationId == pattern.locationId && e.groupId == pattern.groupId &&
                (e.groupId != null || e.clientIds.toSet() == pattern.clientIds.toSet())
            val regular = matches && occursOn(next, target)
            // Отдельная тренировка на дне серии занимает его, как после «Только эту».
            if (!regular && occursOn(next, target)) next = next.copy(skipDates = next.skipDates + target)
            draft.update(edited.training.applying(e, members).copy(seriesId = id, detached = !regular))
            draft.processed += edited.training.id
            if (regular) draft.claim(id, target)
        }

        for (t in mine.sortedBy { it.start }) {
            if (t.id == edited?.training?.id || t.date < split) continue
            draft.processed += t.id
            when {
                !t.changeableAt(now) -> if (!t.detached) draft.claim(id, t.date)
                t.detached -> draft.update(t.copy(seriesId = id))
                occursOn(next, t.date) && !draft.isClaimed(id, t.date) -> {
                    draft.update(t.reshaped(next.pattern, id, members))
                    draft.claim(id, t.date)
                }
                else -> draft.delete(t.id)
            }
        }

        val generated = draft.generate(next, split, generationEnd(next, now.date, horizonWeeks), members, notAfter = now)
        draft.carryPlans(id)
        val series = if (inPlace) listOf(generated) else listOf(old.copy(validTo = split.plusDays(-1)), generated)
        return draft.result(series)
    }

    private fun endSeries(
        series: Series,
        validTo: LocalDate,
        deleteFrom: LocalDate,
        trainings: List<SeriesTraining>,
        now: LocalDateTime,
    ): SeriesChanges {
        val mine = trainings.filter { it.seriesId == series.id }
        val draft = Draft(mine)
        mine.filter { it.date >= deleteFrom && it.changeableAt(now) }.forEach { draft.delete(it.id) }
        draft.carryPlans(series.id)
        val next = if (series.validTo != null && series.validTo <= validTo) series else series.copy(validTo = validTo)
        return draft.result(listOfNotNull(next.takeIf { it != series }))
    }

    /** Сплит без ушедшего клиента: с сегодня — новая серия, будущие тренировки переходят в неё без него. */
    private fun dropClient(
        series: Series,
        clientId: String,
        rest: List<String>,
        trainings: List<SeriesTraining>,
        now: LocalDateTime,
        newSeriesId: () -> String,
    ): SeriesChanges {
        val split = maxOf(now.date, series.validFrom)
        val inPlace = notStarted(series, split, trainings, now)
        val id = if (inPlace) series.id else newSeriesId()
        val draft = Draft(trainings)
        for (t in trainings.filter { it.changeableAt(now) }) {
            val left = t.participants.filterNot { it.clientId == clientId }
            if (left.isEmpty()) draft.delete(t.id) else draft.update(t.copy(seriesId = id, participants = left))
        }
        draft.carryPlans(id)
        val pattern = series.pattern.copy(clientIds = rest)
        val result = if (inPlace) {
            listOf(series.copy(pattern = pattern))
        } else {
            listOf(
                series.copy(validTo = split.plusDays(-1)),
                Series(
                    id = id,
                    pattern = pattern,
                    validFrom = split,
                    validTo = series.validTo,
                    generatedUntil = series.generatedUntil,
                    skipDates = series.skipDates.filter { it >= split }.toSet(),
                ),
            )
        }
        return draft.result(result)
    }

    /**
     * Серия ещё не началась: её период — с [split] или позже и ни одна тренировка не прошла.
     * Такую серию правка меняет саму, без новой: истории, которую надо сохранить, у неё нет.
     */
    private fun notStarted(series: Series, split: LocalDate, trainings: List<SeriesTraining>, now: LocalDateTime): Boolean =
        series.validFrom >= split && trainings.all { it.changeableAt(now) }

    private fun checkPattern(pattern: SeriesPattern) {
        require(pattern.weekdays.isNotEmpty()) { "Не выбран ни один день недели" }
        require(pattern.groupId == null || pattern.clientIds.isEmpty()) { "У групповой серии клиенты — в составе группы" }
        require(pattern.clientIds.distinct().size == pattern.clientIds.size) { "Клиент указан дважды" }
        val problem = TrainingTime.problem(LocalDateTime(ANY_DAY, pattern.startTime), pattern.durationMin)
        require(problem == null) { "Время серии: $problem" }
    }

    private fun checkTime(edit: TrainingEdit) {
        val problem = TrainingTime.problem(edit.start, edit.durationMin)
        require(problem == null) { "Время тренировки: $problem" }
    }

    private val ANY_DAY = LocalDate(2026, 1, 1)
}

// --- Помощники ---

internal fun LocalDate.plusDays(days: Int): LocalDate = plus(days.toLong(), DateTimeUnit.DAY)

private fun SeriesTraining.clientIdSet(): Set<String> = participants.map { it.clientId }.toSet()

/** Участники после правки: у той же группы — как были (отметки, гости «на раз»); у клиентов — отметки остающихся. */
private fun SeriesTraining.participantsFor(
    clientIds: List<String>,
    groupId: String?,
    date: LocalDate,
    members: List<GroupMember>,
): List<ParticipantState> = when {
    groupId != null && groupId == this.groupId -> participants
    groupId != null -> members.membersOn(date).map { ParticipantState(it) }
    clientIds.toSet() == clientIdSet() -> participants
    else -> clientIds.map { c -> participants.firstOrNull { it.clientId == c } ?: ParticipantState(c) }
}

private fun SeriesTraining.applying(edit: TrainingEdit, members: List<GroupMember>): SeriesTraining = copy(
    start = edit.start,
    durationMin = edit.durationMin,
    typeId = edit.typeId,
    locationId = edit.locationId,
    groupId = edit.groupId,
    participants = participantsFor(edit.clientIds, edit.groupId, edit.start.date, members),
)

private fun SeriesTraining.reshaped(pattern: SeriesPattern, seriesId: String, members: List<GroupMember>): SeriesTraining =
    copy(
        seriesId = seriesId,
        start = LocalDateTime(date, pattern.startTime),
        durationMin = pattern.durationMin,
        typeId = pattern.typeId,
        locationId = pattern.locationId,
        groupId = pattern.groupId,
        participants = participantsFor(pattern.clientIds, pattern.groupId, date, members),
    )

/**
 * Черновик изменений: текущие тренировки после правок, созданные, удалённые, занятые даты.
 * Дата «занята» для серии, если на ней есть её обычная (не отдельная) тренировка.
 */
private class Draft(trainings: List<SeriesTraining>) {
    private val original: Map<String, SeriesTraining> = trainings.associateBy { it.id }
    private val current = LinkedHashMap(original)
    private val updatedIds = LinkedHashSet<String>()
    private val deleted = mutableListOf<SeriesTraining>()
    private val created = mutableListOf<NewTraining>()
    private val pending = mutableListOf<String>()
    private val claims = HashSet<Pair<String, LocalDate>>()

    /** Тренировки, уже разобранные правилом: их прежняя дата больше не занимает место. */
    val processed = HashSet<String>()

    fun update(t: SeriesTraining) {
        current[t.id] = t
        if (original[t.id] == t) updatedIds -= t.id else updatedIds += t.id
    }

    fun delete(id: String) {
        val t = current.remove(id) ?: return
        updatedIds -= id
        deleted += t
        processed += id
    }

    fun claim(seriesId: String, date: LocalDate) {
        claims += seriesId to date
    }

    fun isClaimed(seriesId: String, date: LocalDate): Boolean = (seriesId to date) in claims

    private fun occupied(seriesId: String, date: LocalDate): Boolean =
        isClaimed(seriesId, date) ||
            current.values.any { it.seriesId == seriesId && !it.detached && it.date == date && it.id !in processed }

    /**
     * Тренировки серии на её дни с [from] по [to], кроме занятых. [notAfter] — «сейчас»: тренировки,
     * которые начинались бы не позже него, не создаются. `generatedUntil` сдвигается, если в отрезке есть дни серии.
     */
    fun generate(series: Series, from: LocalDate, to: LocalDate, members: List<GroupMember>, notAfter: LocalDateTime?): Series {
        val days = SeriesRules.occurrences(series, from, to)
        for (d in days) {
            if (occupied(series.id, d)) continue
            val start = LocalDateTime(d, series.pattern.startTime)
            if (notAfter != null && start <= notAfter) continue
            val p = series.pattern
            created += NewTraining(
                seriesId = series.id,
                start = start,
                durationMin = p.durationMin,
                typeId = p.typeId,
                locationId = p.locationId,
                groupId = p.groupId,
                clientIds = if (p.groupId != null) members.membersOn(d) else p.clientIds,
            )
            claim(series.id, d)
        }
        return if (days.isEmpty() || to <= series.generatedUntil) series else series.copy(generatedUntil = to)
    }

    /**
     * План удалённой тренировки — на следующую `PLANNED` тренировку серии [seriesId] после неё
     * (с новой строки, если план там уже есть); нет следующей — в отложенные планы.
     */
    fun carryPlans(seriesId: String) {
        for (gone in deleted.filter { it.hasPlan }.sortedBy { it.start }) {
            val text = gone.plan.orEmpty().trim()
            val existing = current.values
                .filter { it.seriesId == seriesId && it.status == TrainingStatus.PLANNED && it.start > gone.start }
                .minByOrNull { it.start }
            val fresh = created.withIndex()
                .filter { it.value.seriesId == seriesId && it.value.start > gone.start }
                .minByOrNull { it.value.start }
            when {
                existing != null && (fresh == null || existing.start <= fresh.value.start) ->
                    update(existing.copy(plan = joinPlans(existing.plan, text)))
                fresh != null -> created[fresh.index] = fresh.value.copy(plan = joinPlans(fresh.value.plan, text))
                else -> pending += text
            }
        }
    }

    fun result(series: List<Series>): SeriesChanges = SeriesChanges(
        series = series,
        created = created.toList(),
        updated = updatedIds.map { current.getValue(it) },
        deleted = deleted.toList(),
        pendingPlans = pending.toList(),
    )

    private fun joinPlans(old: String?, add: String): String = if (old.isNullOrBlank()) add else "$old\n$add"
}
