package com.akenory.personalka.domain.competition

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.money.RateRules
import com.akenory.personalka.domain.result.ResultItem
import com.akenory.personalka.domain.result.ResultRules
import com.akenory.personalka.domain.result.SwimEvent
import kotlinx.datetime.LocalDate

// ТЗ 5.21. Соревнования: кто допускается, подбор участников, что собрать, заявочное время, итоги.

/** Условие «кто допускается»: пол ([sex] = `null` — любой) и годы рождения с [yearFrom] по [yearTo] включительно. */
data class EntryRule(val sex: Sex?, val yearFrom: Int, val yearTo: Int)

/** Пункт «Что собрать»: «Взнос» с суммой в копейках [amount] или «Справка» без суммы. */
data class CollectItem(val id: String, val title: String, val amount: Long? = null)

/** Что вносит форма соревнования (5.21). [place] — где проходит, необязательно. */
data class CompetitionDraft(
    val name: String,
    val date: LocalDate,
    val poolM: Int,
    val place: String? = null,
    val rules: List<EntryRule> = emptyList(),
    val items: List<CollectItem> = emptyList(),
)

/** Соревнование для правил и экранов. */
data class Competition(
    val id: String,
    val name: String,
    val date: LocalDate,
    val poolM: Int,
    val place: String? = null,
    val rules: List<EntryRule> = emptyList(),
    val items: List<CollectItem> = emptyList(),
)

/** Клиент для подбора участников: имя, дата рождения, пол и в архиве ли. */
data class Candidate(
    val id: String,
    val name: ClientName,
    val birthDate: LocalDate? = null,
    val sex: Sex? = null,
    val archived: Boolean = false,
)

/**
 * Старт участника: дистанция в бассейне соревнования, [entryCs] — заявочное время, введённое тренером (`null` —
 * лучшее до дня соревнования), [resultCs] и [place] — итог, [resultId] — результат в карточке клиента.
 */
data class CompetitionStart(
    val id: String,
    val event: SwimEvent,
    val entryCs: Int? = null,
    val resultCs: Int? = null,
    val place: Int? = null,
    val resultId: String? = null,
)

/** Участник: клиент, год рождения, отметки «сдал» (id пунктов) и старты. */
data class Participant(
    val id: String,
    val clientId: String,
    val name: ClientName,
    val birthYear: Int? = null,
    val handedIn: Set<String> = emptySet(),
    val starts: List<CompetitionStart> = emptyList(),
)

/** Заявочное время старта: [cs] — время или `null`; [manual] — введено тренером; [best] — лучшее до дня соревнования. */
data class EntryTime(val cs: Int?, val manual: Boolean, val best: ResultItem?)

/** Подходит ли клиент под условие: да, нет или неизвестно — нет года рождения или пола. */
enum class Fit { YES, NO, UNKNOWN }

/** Раздел подбора: условие ([rule] = `null` — «Все клиенты» или найденные поиском) и кто под него подходит. */
data class PickSection(val rule: EntryRule?, val people: List<Candidate>)

/** Подбор (5.21): разделы по условиям и «Не указан год или пол». */
data class Pick(val sections: List<PickSection>, val unknown: List<Candidate>)

/** Строка условия в форме, как её набрал тренер: годы — текстом. */
data class RuleInput(val sex: Sex?, val from: String, val to: String)

/** Строка «Что собрать» в форме: сумма — текстом в рублях. */
data class ItemInput(val id: String, val title: String, val amount: String)

/** Поля формы, под которыми показывается ошибка. */
enum class CompetitionField { NAME, RULES, ITEMS }

/** Сколько собрано по пунктам с суммой: [got] из [total], копейки. */
data class Collected(val got: Long, val total: Long)

/** Строка заявки: участник и заявочное время (`null` — без времени). */
data class EntryLine(val name: ClientName, val birthYear: Int?, val cs: Int?)

/** Итог старта для «Итогов»: время, на сколько быстрее лучшего до соревнования ([gain]), место. */
data class StartResult(val event: SwimEvent, val resultCs: Int?, val gain: Int?, val place: Int?)

object CompetitionRules {
    /** Бассейн нового соревнования. */
    const val DEFAULT_POOL = 25

    /** Годы рождения в условиях: четыре цифры в этих пределах. */
    const val MIN_YEAR = 1900
    const val MAX_YEAR = 2100

    /** Место — число, не больше. */
    const val MAX_PLACE = 999

    /** Дистанция нового старта: 50 м вольным в бассейне соревнования. */
    fun newEvent(poolM: Int): SwimEvent = SwimEvent(SwimStyle.FREESTYLE, 50, poolM)

    // --- Хранение в базе (3.5) ---

    private const val ANY_SEX = "*"

    /** «M 2010 2015;F 2008 2013;* 2012 2014». */
    fun encodeRules(rules: List<EntryRule>): String =
        rules.joinToString(";") { "${sexCode(it.sex)} ${it.yearFrom} ${it.yearTo}" }

    /** Обратно из базы; непонятные части пропускаются. */
    fun decodeRules(text: String): List<EntryRule> = text.split(';').mapNotNull { part ->
        val bits = part.trim().split(' ').filter { it.isNotEmpty() }
        if (bits.size != 3) return@mapNotNull null
        val sex = when (bits[0]) {
            "M" -> Sex.MALE
            "F" -> Sex.FEMALE
            ANY_SEX -> null
            else -> return@mapNotNull null
        }
        val from = bits[1].toIntOrNull() ?: return@mapNotNull null
        val to = bits[2].toIntOrNull() ?: return@mapNotNull null
        EntryRule(sex, from, to)
    }

    private fun sexCode(sex: Sex?) = when (sex) {
        Sex.MALE -> "M"
        Sex.FEMALE -> "F"
        null -> ANY_SEX
    }

    /** Пункты строками «id\tназвание\tкопейки», суммы может не быть. Табуляция и перевод строки в названии — пробелом. */
    fun encodeItems(items: List<CollectItem>): String = items.joinToString("\n") { item ->
        listOfNotNull(item.id, clean(item.title), item.amount?.toString()).joinToString("\t")
    }

    fun decodeItems(text: String): List<CollectItem> = text.split('\n').mapNotNull { line ->
        val bits = line.split('\t')
        if (bits.size < 2 || bits[0].isEmpty()) return@mapNotNull null
        CollectItem(bits[0], bits[1], bits.getOrNull(2)?.toLongOrNull())
    }

    private fun clean(title: String) = title.replace('\t', ' ').replace('\n', ' ').trim()

    /** Отметки «сдал»: id пунктов через пробел. */
    fun encodeHanded(ids: Set<String>): String = ids.sorted().joinToString(" ")

    fun decodeHanded(text: String): Set<String> = text.split(' ').filter { it.isNotEmpty() }.toSet()

    // --- Форма ---

    /** Год из поля: четыре цифры в пределах [MIN_YEAR]–[MAX_YEAR]. */
    fun year(text: String): Int? = text.trim().takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in MIN_YEAR..MAX_YEAR }

    /** Условие из строки формы: годы читаются и «с» не позже «по». */
    fun rule(input: RuleInput): EntryRule? {
        val from = year(input.from) ?: return null
        val to = year(input.to) ?: return null
        return if (from <= to) EntryRule(input.sex, from, to) else null
    }

    /** Пустая строка «Что собрать» (ни названия, ни суммы) не считается — её просто нет. */
    private fun ItemInput.isBlank() = title.isBlank() && amount.isBlank()

    /** Ошибки формы: название, условия, пункты. Пусто — можно сохранять. */
    fun problems(name: String, rules: List<RuleInput>, items: List<ItemInput>): Map<CompetitionField, String> = buildMap {
        if (name.isBlank()) put(CompetitionField.NAME, NAME_EMPTY)
        if (rules.any { rule(it) == null }) put(CompetitionField.RULES, RULE_BAD)
        val filled = items.filterNot { it.isBlank() }
        when {
            filled.any { it.title.isBlank() } -> put(CompetitionField.ITEMS, ITEM_NO_TITLE)
            filled.any { it.amount.isNotBlank() && RateRules.money(it.amount) == null } -> put(CompetitionField.ITEMS, ITEM_BAD_AMOUNT)
        }
    }

    /** Соревнование из формы; ошибки — `null` ([problems] скажет какие). Пустые строки пунктов отбрасываются. */
    fun draft(name: String, date: LocalDate, poolM: Int, place: String, rules: List<RuleInput>, items: List<ItemInput>): CompetitionDraft? {
        if (problems(name, rules, items).isNotEmpty()) return null
        return CompetitionDraft(
            name = name.trim(),
            date = date,
            poolM = poolM,
            place = place.trim().takeIf { it.isNotEmpty() },
            rules = rules.mapNotNull(::rule),
            items = items.filterNot { it.isBlank() }.map { CollectItem(it.id, clean(it.title), it.amount.takeIf(String::isNotBlank)?.let(RateRules::money)) },
        )
    }

    const val NAME_EMPTY = "Введите название"
    const val RULE_BAD = "Годы — четырьмя цифрами, «с» не позже «по»: 2010–2015"
    const val ITEM_NO_TITLE = "У пункта нет названия"
    const val ITEM_BAD_AMOUNT = "Сумма — числом: 1500 или 1500,50"

    // --- Подбор (5.21) ---

    /**
     * Подходит ли клиент под условие: нет года рождения — неизвестно; год вне условия — нет; пол любой или совпал — да;
     * пола нет, а условие с полом — неизвестно.
     */
    fun fit(rule: EntryRule, birthDate: LocalDate?, sex: Sex?): Fit {
        val year = birthDate?.year ?: return Fit.UNKNOWN
        if (year !in rule.yearFrom..rule.yearTo) return Fit.NO
        return when {
            rule.sex == null -> Fit.YES
            sex == null -> Fit.UNKNOWN
            sex == rule.sex -> Fit.YES
            else -> Fit.NO
        }
    }

    /**
     * Подбор участников: без поиска — разделы по условиям (клиент — в первом, под которое подходит), внизу — о ком
     * сказать нельзя ([Fit.UNKNOWN] и ни одного «да»); условий нет — все одним разделом; с поиском [query] — все
     * совпавшие одним разделом. Архивные — только с [archived]. Внутри — по фамилии.
     */
    fun pick(rules: List<EntryRule>, people: List<Candidate>, archived: Boolean, query: String): Pick {
        val shown = people.filter { archived || !it.archived }.sortedBy { ClientRules.sortKey(it.name) }
        if (query.isNotBlank()) return Pick(listOf(PickSection(null, shown.filter { ClientRules.matches(query, it.name) })), emptyList())
        if (rules.isEmpty()) return Pick(listOf(PickSection(null, shown)), emptyList())
        val byRule = rules.associateWith { mutableListOf<Candidate>() }
        val unknown = mutableListOf<Candidate>()
        shown.forEach { person ->
            val fits = rules.map { fit(it, person.birthDate, person.sex) }
            val first = fits.indexOf(Fit.YES)
            when {
                first >= 0 -> byRule.getValue(rules[first]) += person
                Fit.UNKNOWN in fits -> unknown += person
            }
        }
        return Pick(rules.distinct().map { PickSection(it, byRule.getValue(it)) }, unknown)
    }

    // --- Сбор ---

    /** Сколько участников не сдали пункт [item]. */
    fun missing(item: CollectItem, participants: List<Participant>): Int = participants.count { item.id !in it.handedIn }

    /** Сколько участников сдали не всё; пунктов нет — 0. */
    fun owing(items: List<CollectItem>, participants: List<Participant>): Int =
        if (items.isEmpty()) 0 else participants.count { p -> items.any { it.id !in p.handedIn } }

    /** «Собрано … из …» по пунктам с суммой; таких пунктов нет — `null`. */
    fun collected(items: List<CollectItem>, participants: List<Participant>): Collected? {
        val paid = items.filter { it.amount != null }
        if (paid.isEmpty()) return null
        val total = paid.sumOf { it.amount!! } * participants.size
        val got = participants.sumOf { p -> paid.filter { it.id in p.handedIn }.sumOf { it.amount!! } }
        return Collected(got, total)
    }

    /** Отметки участника после изменения пунктов: остаются только существующие пункты. */
    fun keepHanded(handedIn: Set<String>, items: List<CollectItem>): Set<String> = handedIn.intersect(items.map { it.id }.toSet())

    // --- Заявочное время и итоги ---

    /**
     * Заявочное время старта (5.21): введённое тренером, иначе лучшее за всё время на этой дистанции в бассейне
     * соревнования до его дня ([ResultRules.best]).
     */
    fun entryTime(start: CompetitionStart, results: List<ResultItem>, date: LocalDate): EntryTime {
        val best = ResultRules.best(results, start.event, date)
        return EntryTime(start.entryCs ?: best?.timeCs, start.entryCs != null, best)
    }

    /**
     * Что хранить заявочным временем после правки: совпало с лучшим — `null` (дальше само обновляется), иначе
     * введённое; поле пустое — `null`.
     */
    fun storedEntry(typedCs: Int?, best: ResultItem?): Int? = typedCs?.takeIf { it != best?.timeCs }

    /** Итог и место вносятся с дня соревнования. */
    fun canFinish(date: LocalDate, today: LocalDate): Boolean = today >= date

    /** На сколько [resultCs] быстрее лучшего до дня соревнования; не быстрее или сравнивать не с чем — `null`. */
    fun gain(results: List<ResultItem>, event: SwimEvent, resultCs: Int, date: LocalDate): Int? {
        val best = ResultRules.best(results, event, date) ?: return null
        return (best.timeCs - resultCs).takeIf { it > 0 }
    }

    /** Место из поля: число от 1 до [MAX_PLACE]; пусто — `null`. */
    fun place(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..MAX_PLACE }

    // --- Списки ---

    /** «Впереди»: сегодня и позже, ближайшие сверху. */
    fun upcoming(list: List<Competition>, today: LocalDate): List<Competition> =
        list.filter { it.date >= today }.sortedWith(compareBy<Competition>({ it.date }, { ClientRules.key(it.name) }))

    /** «Прошли»: от новых к старым. */
    fun past(list: List<Competition>, today: LocalDate): List<Competition> =
        list.filter { it.date < today }.sortedWith(compareByDescending<Competition> { it.date }.thenBy { ClientRules.key(it.name) })

    /** Участники — по фамилии. */
    val ORDER: Comparator<Participant> = compareBy { ClientRules.sortKey(it.name) }

    /**
     * Заявка по дистанциям (5.21): дистанции по порядку рекордов, внутри — от быстрых к медленным, без времени —
     * в конце по фамилии. [resultsOf] — результаты клиента для заявочного времени.
     */
    fun entryList(participants: List<Participant>, resultsOf: (String) -> List<ResultItem>, date: LocalDate): List<Pair<SwimEvent, List<EntryLine>>> {
        val lines = mutableMapOf<SwimEvent, MutableList<Pair<EntryLine, String>>>()
        participants.forEach { p ->
            val results = resultsOf(p.clientId)
            p.starts.forEach { start ->
                val line = EntryLine(p.name, p.birthYear, entryTime(start, results, date).cs)
                lines.getOrPut(start.event) { mutableListOf() } += line to ClientRules.sortKey(p.name)
            }
        }
        return lines.keys.sortedWith(ResultRules.ORDER).map { event ->
            val list = lines.getValue(event)
            val timed = list.filter { it.first.cs != null }.sortedWith(compareBy({ it.first.cs }, { it.second }))
            val untimed = list.filter { it.first.cs == null }.sortedBy { it.second }
            event to (timed + untimed).map { it.first }
        }
    }

    /** Участники без стартов — по фамилии. */
    fun withoutStarts(participants: List<Participant>): List<Participant> = participants.filter { it.starts.isEmpty() }.sortedWith(ORDER)

    /** Итоги участника (5.21): старты по порядку дистанций, личный рекорд — против лучшего до соревнования. */
    fun results(participant: Participant, results: List<ResultItem>, date: LocalDate): List<StartResult> =
        participant.starts.sortedWith(compareBy(ResultRules.ORDER) { it.event }).map { start ->
            StartResult(start.event, start.resultCs, start.resultCs?.let { gain(results, start.event, it, date) }, start.place)
        }
}
