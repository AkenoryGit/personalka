package com.akenory.personalka.domain.format

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.competition.CollectItem
import com.akenory.personalka.domain.competition.Collected
import com.akenory.personalka.domain.competition.Competition
import com.akenory.personalka.domain.competition.CompetitionRules
import com.akenory.personalka.domain.competition.EntryLine
import com.akenory.personalka.domain.competition.EntryRule
import com.akenory.personalka.domain.competition.EntryTime
import com.akenory.personalka.domain.competition.Participant
import com.akenory.personalka.domain.competition.StartResult
import com.akenory.personalka.domain.format.RuText.DASH
import com.akenory.personalka.domain.format.RuText.MINUS
import com.akenory.personalka.domain.format.RuText.NBSP
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.result.SwimEvent
import kotlinx.datetime.LocalDate

/** Строки соревнований (ТЗ 5.21): список, карточка, подбор, старты и тексты «Поделиться». */
object CompetitionFormat {
    private const val DOT = " · "

    /** «сб 14.12.2026». */
    fun dateLong(date: LocalDate): String = DateFormat.weekdayShort(date.dayOfWeek) + NBSP + DateFormat.full(date)

    /** «Бассейн 50 м» в шапке — строчными: «бассейн 50 м». */
    fun pool(poolM: Int): String = "бассейн ${ResultFormat.meters(poolM)}"

    /** Шапка карточки: «сб 14.12.2026 · бассейн 50 м · Бассейн «Олимп»». */
    fun header(c: Competition): String = listOfNotNull(dateLong(c.date), pool(c.poolM), c.place).joinToString(DOT)

    /**
     * Строка списка: «сб 14.12 · 12 участников · не всё сдали 4» (другой год — «сб 14.12.2026»; всё сдано — «всё сдали»;
     * пунктов нет — без этой части; участников нет — «участников пока нет»).
     */
    fun listLine(c: Competition, participants: List<Participant>, today: LocalDate): String {
        val date = if (c.date.year == today.year) DateFormat.short(c.date) else dateLong(c.date)
        if (participants.isEmpty()) return date + DOT + NO_PARTICIPANTS_SHORT
        val owing = CompetitionRules.owing(c.items, participants)
        return listOfNotNull(
            date,
            participants(participants.size),
            when {
                c.items.isEmpty() -> null
                owing == 0 -> ALL_HANDED_SHORT
                else -> "не всё сдали $owing"
            },
        ).joinToString(DOT)
    }

    /** «12 участников». */
    fun participants(n: Int): String = RuText.count(n, "участник", "участника", "участников")

    /** Чип пола в условии: «Мальчики», «Девочки», «Все». */
    fun ruleSex(sex: Sex?): String = when (sex) {
        Sex.MALE -> "Мальчики"
        Sex.FEMALE -> "Девочки"
        null -> "Все"
    }

    /** «Мальчики 2010–2015»; один год — «Мальчики 2012». */
    fun rule(rule: EntryRule): String =
        ruleSex(rule.sex) + " " + if (rule.yearFrom == rule.yearTo) "${rule.yearFrom}" else "${rule.yearFrom}$DASH${rule.yearTo}"

    /** Условия одной строкой: «Мальчики 2010–2015 · Девочки 2008–2013»; условий нет — `null`. */
    fun rules(rules: List<EntryRule>): String? = rules.takeIf { it.isNotEmpty() }?.joinToString(DOT, transform = ::rule)

    /** Пункт с суммой: «Взнос 1 500 ₽»; без суммы — «Справка». */
    fun item(item: CollectItem): String = item.title + (item.amount?.let { " " + MoneyFormat.rub(it) } ?: "")

    /** Чип «что не сдано»: «Взнос · не сдали 4» или «Взнос · все сдали». */
    fun missing(item: CollectItem, missing: Int): String = item.title + DOT + if (missing == 0) "все сдали" else "не сдали $missing"

    /** «Собрано 9 000 из 12 000 ₽». */
    fun collected(c: Collected): String = "Собрано ${MoneyFormat.amount(c.got)} из ${MoneyFormat.rub(c.total)}"

    /** «Петров Лев, 2014»; года нет — «Петров Лев». */
    fun person(name: ClientName, birthYear: Int?): String = name.short + (birthYear?.let { ", $it" } ?: "")

    /** Коротко о старте в строке участника: «50 м вольный 36,40»; без времени — «50 м вольный». */
    fun startShort(event: SwimEvent, cs: Int?): String =
        ResultFormat.meters(event.distanceM) + " " + ResultFormat.styleChip(event.style).lowercase() + (cs?.let { " " + ResultFormat.time(it) } ?: "")

    /** Старты участника одной строкой; стартов нет — «Дистанция не выбрана». */
    fun starts(starts: List<Pair<SwimEvent, Int?>>): String =
        if (starts.isEmpty()) NO_STARTS else starts.joinToString(DOT) { (event, cs) -> startShort(event, cs) }

    /**
     * Подсказка под заявочным временем: «Лучшее: 38,12 · 10.10.2026» или «В бассейне 50 м результатов на этой
     * дистанции нет — введите время вручную».
     */
    fun entryHint(time: EntryTime, poolM: Int): String {
        val best = time.best ?: return "В бассейне ${ResultFormat.meters(poolM)} результатов на этой дистанции нет — введите время вручную"
        return "Лучшее: ${ResultFormat.time(best.timeCs)}$DOT${DateFormat.full(best.date)}"
    }

    /** Строка старта в окне участника: «заявка 38,12 · результат 37,05 · 2 место»; без заявочного — «без заявочного времени». */
    fun startLine(entryCs: Int?, resultCs: Int?, place: Int?): String = listOfNotNull(
        entryCs?.let { "заявка ${ResultFormat.time(it)}" } ?: NO_ENTRY,
        resultCs?.let { "результат ${ResultFormat.time(it)}" },
        place?.let { "$it место" },
    ).joinToString(DOT)

    /** Чего не хватает клиенту в «Не указан год или пол»: «нет даты рождения» или «пол не указан». */
    fun lacks(hasBirth: Boolean): String = if (hasBirth) NO_SEX else NO_BIRTH

    /** «Добавлен 1 участник», «Добавлено 3 участника». */
    fun added(n: Int): String = RuText.plural(n, "Добавлен", "Добавлено", "Добавлено") + " " + participants(n)

    /** Главная кнопка подбора: «Добавить 3»; никого не отмечено — «Добавить». */
    fun addButton(n: Int): String = if (n > 0) "$ADD_SHORT $n" else ADD_SHORT

    /** Раздел подбора: «Мальчики 2010–2015 · 7»; без условия — «Все клиенты · 40» или «Найдено · 2». */
    fun section(rule: EntryRule?, n: Int, search: Boolean): String =
        (rule?.let(::rule) ?: if (search) FOUND else ALL_CLIENTS) + DOT + n

    /** «Не указан год или пол · 3». */
    fun unknown(n: Int): String = UNKNOWN + DOT + n

    // --- Тексты «Поделиться» ---

    private fun shareHead(c: Competition, withPool: Boolean): String =
        listOfNotNull(c.name, dateLong(c.date), if (withPool) pool(c.poolM) else null).joinToString(DOT)

    /** «Заявка» (5.21): по дистанциям «Петров Лев, 2014 — 36,40», без времени — «без времени»; в конце — «Без дистанции: …». */
    fun entryText(c: Competition, lines: List<Pair<SwimEvent, List<EntryLine>>>, without: List<Participant>): String = buildString {
        append(shareHead(c, withPool = true))
        if (lines.isEmpty() && without.isEmpty()) {
            append("\n\n").append(NO_PARTICIPANTS)
            return@buildString
        }
        lines.forEach { (event, list) ->
            append("\n\n").append(ResultFormat.event(event))
            list.forEach { line ->
                append('\n').append(person(line.name, line.birthYear)).append(" — ")
                append(line.cs?.let(ResultFormat::time) ?: NO_TIME)
            }
        }
        if (without.isNotEmpty()) {
            append("\n\n").append(WITHOUT_STARTS).append(": ")
            append(without.joinToString(", ") { person(it.name, it.birthYear) })
        }
    }

    /** «Кто не сдал»: по пунктам «Взнос 1 500 ₽ — Петров Лев, Орлова Ева»; всё сдано — «Все всё сдали». */
    fun debtText(c: Competition, participants: List<Participant>): String = buildString {
        append(shareHead(c, withPool = false))
        if (participants.isEmpty()) {
            append("\n\n").append(NO_PARTICIPANTS)
            return@buildString
        }
        val sorted = participants.sortedWith(CompetitionRules.ORDER)
        val lines = c.items.mapNotNull { item ->
            val who = sorted.filter { item.id !in it.handedIn }
            if (who.isEmpty()) null else item(item) + " — " + who.joinToString(", ") { it.name.short }
        }
        if (lines.isEmpty()) {
            append("\n\n").append(ALL_HANDED)
        } else {
            append("\n\n").append(NOT_HANDED).append(':')
            lines.forEach { append('\n').append(it) }
        }
    }

    /** Строка итога старта: «50 м вольный стиль — 36,10, личный рекорд − 0,30, 2 место»; ничего нет — «результата нет». */
    fun resultLine(r: StartResult): String {
        val parts = listOfNotNull(
            r.resultCs?.let(ResultFormat::time),
            r.gain?.let { "личный рекорд $MINUS$NBSP${ResultFormat.time(it)}" },
            r.place?.let { "$it место" },
        )
        return ResultFormat.event(r.event) + " — " + if (parts.isEmpty()) NO_RESULT else parts.joinToString(", ")
    }

    /** «Итоги»: «Итоги · Кубок города · 14.12.2026», по участникам — имя и строки стартов. */
    fun resultsText(c: Competition, rows: List<Pair<Participant, List<StartResult>>>): String = buildString {
        append("Итоги").append(DOT).append(c.name).append(DOT).append(DateFormat.full(c.date))
        val withStarts = rows.filter { it.second.isNotEmpty() }
        if (withStarts.isEmpty()) {
            append("\n\n").append(NO_RESULTS)
            return@buildString
        }
        withStarts.forEach { (p, results) ->
            append("\n\n").append(p.name.short)
            results.forEach { append('\n').append(resultLine(it)) }
        }
    }

    const val TITLE = "Соревнования"
    const val MORE_NOTE = "Кто едет, что собрать, заявка и итоги"
    const val EMPTY_TITLE = "Соревнований пока нет"
    const val EMPTY_TEXT = "Добавьте соревнование — участников подберёте по году рождения и полу."
    const val EMPTY_ACTION = "Добавить соревнование"
    const val ADD = "Соревнование"
    const val UPCOMING = "Впереди"
    const val PAST = "Прошли"
    const val NEW_TITLE = "Новое соревнование"
    const val EDIT_TITLE = "Изменить"
    const val NAME = "Название"
    const val NAME_HINT = "Например, Кубок города"
    const val DATE = "Дата"
    const val POOL = "Бассейн"
    const val PLACE = "Место"
    const val PLACE_HINT = "Необязательно"
    const val RULES_TITLE = "Кто допускается"
    const val RULE_ADD = "Условие"
    const val RULES_NOTE = "Без условий подбор покажет всех клиентов."
    const val YEAR_FROM = "Год с"
    const val YEAR_TO = "по"
    const val ITEMS_TITLE = "Что собрать"
    val ITEM_PRESETS = listOf("Взнос", "Справка", "Заявление", "Страховка")
    const val ITEM_OWN = "Свой пункт"
    const val ITEM_TITLE = "Пункт"
    const val ITEM_AMOUNT = "Сумма, ₽"
    const val SAVE = "Сохранить"
    const val DELETE = "Удалить соревнование"
    const val DELETE_TITLE = "Удалить соревнование?"
    const val DELETE_TEXT = "Участники и отметки удалятся. Результаты в карточках клиентов останутся."
    const val DELETE_YES = "Удалить"
    const val CANCEL = "Отмена"
    const val DELETED = "Соревнование удалено"
    const val PICK = "Подобрать участников"
    const val PICK_TITLE = "Подбор участников"
    const val ARCHIVED = "Архивные"
    const val SEARCH = "Поиск по фамилии или имени"
    const val UNKNOWN = "Не указан год или пол"
    const val UNKNOWN_NOTE = "Нажмите на клиента, чтобы указать дату рождения и пол — они сохранятся в карточке."
    const val ALL_CLIENTS = "Все клиенты"
    const val FOUND = "Найдено"
    const val NOBODY = "Под условия никто не подходит"
    const val IN_LIST = "уже в списке"
    const val IN_ARCHIVE = "в архиве"
    const val ADD_SHORT = "Добавить"
    const val BIRTH_TITLE = "Год и пол"
    const val BIRTH = "Дата рождения"
    const val BIRTH_HINT = "ДД.ММ.ГГГГ"
    const val BIRTH_BAD = "Дата — ДД.ММ.ГГГГ, например 05.03.2014"
    const val SEX = "Пол"
    const val BOY = "Мальчик"
    const val GIRL = "Девочка"
    const val GUESS_LAST = "Предложен по фамилии"
    const val GUESS_MIDDLE = "Предложен по отчеству"
    const val NO_STARTS = "Дистанция не выбрана"
    const val START_TITLE = "Старт"
    const val NEW_START = "Новый старт"
    const val ADD_START = "Добавить старт"
    const val STARTS = "Старты"
    const val ITEMS = "Сдал"
    const val STYLE = "Стиль"
    const val DISTANCE = "Дистанция"
    const val ENTRY_TIME = "Заявочное время"
    const val RESULT = "Результат"
    const val RANK = "Место"
    const val RANK_HINT = "Число, например 2"
    const val RANK_BAD = "Место — числом от 1"
    const val START_SAVED = "Старт сохранён"
    const val START_DELETE = "Удалить старт"
    const val START_DELETED = "Старт удалён"
    const val REMOVE = "Убрать из соревнования"
    const val REMOVED = "Убран из соревнования"
    const val CLOSE = "Готово"
    const val SHARE = "Поделиться"
    const val SHARE_ENTRY = "Заявка"
    const val SHARE_DEBT = "Кто не сдал"
    const val SHARE_RESULTS = "Итоги"
    const val SHARE_ENTRY_NOTE = "Дистанции и заявочное время"
    const val SHARE_DEBT_NOTE = "Кто что ещё не сдал"
    const val SHARE_RESULTS_NOTE = "Время, личные рекорды и места"
    const val NO_PARTICIPANTS = "Участников пока нет"
    private const val NO_PARTICIPANTS_SHORT = "участников пока нет"
    const val NO_TIME = "без времени"
    const val NO_ENTRY = "без заявочного времени"
    const val NO_BIRTH = "нет даты рождения"
    const val NO_SEX = "пол не указан"
    const val NO_RESULT = "результата нет"
    const val NO_RESULTS = "Результатов пока нет"
    const val WITHOUT_STARTS = "Без дистанции"
    const val NOT_HANDED = "Не сдали"
    const val ALL_HANDED = "Все всё сдали"
    private const val ALL_HANDED_SHORT = "всё сдали"
}
