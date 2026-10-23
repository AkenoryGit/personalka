package com.akenory.personalka.importer

import com.akenory.personalka.domain.client.ClientName
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.importing.FoundSeries
import com.akenory.personalka.domain.importing.GroupCode
import com.akenory.personalka.domain.importing.ImportRules
import com.akenory.personalka.domain.importing.Slot
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.reference.ReferenceRules
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import java.io.File

// ТЗ 9.2, 9.4. Режим проверки: все таблицы папки → клиенты, тренировки, результаты, группы, серии и месяцы
// в том виде, в каком их перенесёт шаг 1.18, плюс список проблем и обновлённый файл сопоставления.

/** Клиент после сопоставления имён: из своей таблицы или только из списков групп. */
data class ImportClient(
    val name: ClientName,
    val sex: Sex?,
    val table: ClientTable?,
    val typeShort: String,
    val placeShort: String,
    val groupVisits: Int,
) {
    val trainings: List<RawTraining> get() = table?.trainings.orEmpty()
    val results: List<RawResult> get() = table?.results.orEmpty()
}

/** Постоянная группа: день недели + время + тип, состав — дети за последние 4 недели. */
data class ImportGroup(val slot: Slot, val code: GroupCode, val codeText: String, val trainings: List<GroupTraining>, val members: List<ClientName>)

/** Групповая тренировка с детьми после сопоставления имён. */
data class GroupTraining(val date: LocalDate, val children: List<ClientName>)

data class SeriesLine(val client: ClientName, val found: FoundSeries, val confirmed: Boolean)

data class CheckResult(
    val files: List<File>,
    val clientFiles: Int,
    val salaryFiles: Int,
    val unknownFiles: List<String>,
    val clients: List<ImportClient>,
    val groups: List<ImportGroup>,
    val series: List<SeriesLine>,
    val months: List<MonthSheet>,
    val rates: List<Pair<String, String>>,
    /** Последний день импорта: от него отсчитываются 4 недели для составов и серий. */
    val last: LocalDate,
    val issues: Issues,
    val mapping: Mapping,
) {
    val trainings: List<RawTraining> get() = clients.flatMap { it.trainings }
    val results: List<RawResult> get() = clients.flatMap { it.results }
    val groupTrainings: Int get() = groups.sumOf { it.trainings.size }
    val groupParticipations: Int get() = groups.sumOf { g -> g.trainings.sumOf { it.children.size } }
    val withColleague: Boolean get() = groups.any { it.codeText.contains('Л', ignoreCase = true) }
}

object Check {
    /** Все xlsx папки без временных файлов Excel («~$…»), по имени. */
    fun tables(folder: File): List<File> = folder.listFiles().orEmpty()
        .filter { it.isFile && it.extension.equals("xlsx", ignoreCase = true) && !it.name.startsWith("~$") }
        .sortedBy { it.name.lowercase() }

    fun run(files: List<File>, mapping: Mapping, today: LocalDate): CheckResult {
        val issues = Issues()
        val tables = mutableListOf<ClientTable>()
        val salaries = mutableListOf<SalaryTable>()
        val unknown = mutableListOf<String>()
        var clientFiles = 0
        var salaryFiles = 0
        for (file in files) {
            val book = try {
                Workbooks.read(file)
            } catch (e: Exception) {
                issues.problem(file.name, "не открыть файл (${e.message ?: e.javaClass.simpleName})")
                continue
            }
            when {
                ClientReader.isClientBook(book) -> {
                    clientFiles++
                    ClientReader.read(book, issues)?.let { tables += it }
                }
                SalaryReader.isSalaryBook(book) -> {
                    salaryFiles++
                    salaries += SalaryReader.read(book, issues)
                }
                else -> unknown += "${file.name} (листы: ${book.sheets.joinToString { it.name }})"
            }
        }
        if (salaryFiles == 0) issues.problem("папка", "не найдена таблица зарплаты (листы «<Месяц> группы», «Настройки»)")
        val groupRows = salaries.flatMap { it.groups }
        return Assembly(tables, groupRows, mapping, today, issues).result(files, clientFiles, salaryFiles, unknown, salaries)
    }

    private class Assembly(
        val tables: List<ClientTable>,
        val groupRows: List<GroupRow>,
        val mapping: Mapping,
        val today: LocalDate,
        val issues: Issues,
    ) {
        /** Имя по ключу: из таблицы клиента, а у детей групп — как чаще всего написано. */
        val names = linkedMapOf<String, ClientName>()

        /** Сколько раз имя встречается: по нему решается, какое из двух похожих — опечатка. */
        val uses = mutableMapOf<String, Int>()

        /** Вариант → правильный ключ: решения «да» из файла сопоставления. */
        val merged = mutableMapOf<String, String>()
        val merges = mapping.merges.toMutableList()

        fun result(
            files: List<File>,
            clientFiles: Int,
            salaryFiles: Int,
            unknown: List<String>,
            salaries: List<SalaryTable>,
        ): CheckResult {
            val tableKeys = collectNames()
            applyMerges()
            suggestSimilar(tableKeys)

            val byKey = linkedMapOf<String, MutableList<ClientTable>>()
            for (table in tables) byKey.getOrPut(final(ImportRules.nameKey(table.name))) { mutableListOf() } += table
            for ((_, same) in byKey.filter { it.value.size > 1 }) {
                issues.problem(same.joinToString(" и ") { it.where }, "две таблицы на одного клиента «${same.first().name.short}» — объединить или переименовать")
            }

            val groupDates = groupRows.map { it.date }
            val trainingDates = tables.flatMap { t -> t.trainings.map { it.date } }
            val last = (groupDates + trainingDates).filter { it <= today }.maxOrNull() ?: today

            val groups = groups(last)
            val visits = groups.flatMap { g -> g.trainings.flatMap { it.children } }.groupingBy { ImportRules.nameKey(it) }.eachCount()
            val clientMapping = linkedMapOf<String, String>()
            val clients = (byKey.keys + visits.keys).distinct().map { key ->
                val table = byKey[key]?.let { combine(it) }
                val name = table?.name ?: names.getValue(key)
                val (type, place) = if (table != null) typePlace(name, clientMapping) else ImportRules.DEFAULT_TYPE to ImportRules.DEFAULT_PLACE
                ImportClient(name, ClientRules.guessSex(name.middleName, name.lastName)?.sex, table, type, place, visits[key] ?: 0)
            }.sortedBy { ClientRules.sortKey(it.name) }

            for (client in clients) duplicates(client)
            val seriesMapping = linkedMapOf<String, Decision>()
            val series = clients.filter { it.table != null }.flatMap { client -> series(client, last, seriesMapping) }

            val month = mapping.transitionMonth.trim()
            if (month.isNotEmpty() && !Regex("^(0?[1-9]|1[0-2])\\.20\\d{2}$").matches(month)) {
                issues.problem(Mapping.FILE_NAME, "месяц перехода «$month» — нужно ММ.ГГГГ, например 12.2026")
            }
            val updated = Mapping(month, clientMapping, merges, seriesMapping)
            return CheckResult(
                files, clientFiles, salaryFiles, unknown, clients, groups, series,
                salaries.flatMap { it.months }, salaries.flatMap { it.rates }, last, issues, updated,
            )
        }

        /** Все имена: из таблиц клиентов и из списков детей. Возвращает ключи клиентов с таблицами. */
        private fun collectNames(): Set<String> {
            val spellings = mutableMapOf<String, MutableMap<ClientName, Int>>()
            for (table in tables) {
                val key = ImportRules.nameKey(table.name)
                names[key] = table.name
                uses[key] = (uses[key] ?: 0) + table.trainings.size + 1
            }
            val unreadable = linkedMapOf<String, String>()
            for (row in groupRows) for (text in row.children) {
                val name = ImportRules.name(text)
                if (name == null) {
                    unreadable.putIfAbsent(text, row.where)
                    continue
                }
                val key = ImportRules.nameKey(name)
                spellings.getOrPut(key) { mutableMapOf() }.merge(name, 1, Int::plus)
                uses[key] = (uses[key] ?: 0) + 1
            }
            for ((key, variants) in spellings) names.putIfAbsent(key, variants.maxBy { it.value }.key)
            for ((text, where) in unreadable) issues.problem(where, "ребёнок «$text» — нужны фамилия и имя")
            return tables.map { ImportRules.nameKey(it.name) }.toSet()
        }

        private fun applyMerges() {
            for (line in mapping.merges.filter { it.decision == Decision.YES }) {
                val variant = ImportRules.name(line.variant)
                val target = ImportRules.name(line.target)
                if (variant == null || target == null) {
                    issues.problem(Mapping.FILE_NAME, "«${line.variant} → ${line.target}»: нужны фамилия и имя с обеих сторон")
                    continue
                }
                val variantKey = ImportRules.nameKey(variant)
                val targetKey = ImportRules.nameKey(target)
                if (variantKey == targetKey) continue
                merged[variantKey] = targetKey
                names.putIfAbsent(targetKey, target)
            }
        }

        /** Ключ после объединений: вариант → правильное имя, цепочкой. */
        fun final(key: String): String {
            var current = key
            val seen = mutableSetOf<String>()
            while (current in merged && seen.add(current)) current = merged.getValue(current)
            return current
        }

        /** Похожие имена (8.3, 5.8) — предложение объединить; пока не решено — проблема. */
        private fun suggestSimilar(tableKeys: Set<String>) {
            val keys = names.keys.map { final(it) }.distinct()
            val pairs = mutableSetOf<Set<String>>()
            for (key in keys) {
                val name = names.getValue(key)
                for (other in ClientRules.similar(name, keys.filter { it != key }) { names.getValue(it) }) pairs += setOf(key, other)
            }
            for (pair in pairs) {
                val (a, b) = pair.toList()
                val nameA = names.getValue(a).short
                val nameB = names.getValue(b).short
                val decision = mapping.merge(nameA, nameB) ?: mapping.merge(nameB, nameA)
                when (decision) {
                    Decision.NO, Decision.YES -> Unit
                    Decision.ASK -> issues.problem(Mapping.FILE_NAME, "похожие имена «$nameA» и «$nameB» — решите в разделе «${Mapping.MERGES}»")
                    null -> {
                        // Опечатка — то, что встречается реже; таблица клиента всегда правильнее списка группы.
                        val aFirst = (a in tableKeys) > (b in tableKeys) || ((a in tableKeys) == (b in tableKeys) && (uses[a] ?: 0) >= (uses[b] ?: 0))
                        val (target, variant) = if (aFirst) nameA to nameB else nameB to nameA
                        merges += MergeLine(variant, target, Decision.ASK)
                        issues.problem(Mapping.FILE_NAME, "похожие имена «$variant» и «$target» — решите в разделе «${Mapping.MERGES}»")
                    }
                }
            }
        }

        private fun combine(same: List<ClientTable>): ClientTable = same.reduce { a, b ->
            a.copy(
                birth = a.birth ?: b.birth,
                photo = a.photo ?: b.photo,
                note = listOfNotNull(a.note, b.note).joinToString("\n").ifEmpty { null },
                trainings = a.trainings + b.trainings,
                results = a.results + b.results,
            )
        }

        private fun typePlace(name: ClientName, out: MutableMap<String, String>): Pair<String, String> {
            val written = mapping.clients.entries.firstOrNull { Mapping.same(it.key, name.short) }?.value
            val value = written ?: "${ImportRules.DEFAULT_TYPE}, ${ImportRules.DEFAULT_PLACE}"
            out[name.short] = value
            val parts = value.split(',').map { it.trim() }
            val type = ReferenceRules.defaultTypes.firstOrNull { it.shortName.equals(parts.getOrNull(0), ignoreCase = true) }
            val place = ReferenceRules.defaultPlaces.firstOrNull { it.shortName.equals(parts.getOrNull(1), ignoreCase = true) }
            if (type == null || place == null) {
                val types = ReferenceRules.defaultTypes.joinToString { it.shortName }
                val places = ReferenceRules.defaultPlaces.joinToString { it.shortName }
                issues.problem(Mapping.FILE_NAME, "«${name.short} = $value»: нужно «тип, место»; типы — $types; места — $places")
                return ImportRules.DEFAULT_TYPE to ImportRules.DEFAULT_PLACE
            }
            return type.shortName to place.shortName
        }

        private fun duplicates(client: ImportClient) {
            client.trainings.groupBy { LocalDateTime(it.date, it.time) }.filter { it.value.size > 1 }.forEach { (start, same) ->
                issues.problem(same.joinToString(", ") { it.where }, "две тренировки ${DateFormat.full(start.date)} в ${DateFormat.time(start.time)}")
            }
        }

        private fun series(client: ImportClient, last: LocalDate, out: MutableMap<String, Decision>): List<SeriesLine> {
            val starts = client.trainings.map { LocalDateTime(it.date, it.time) }
            return ImportRules.series(starts, last).map { found ->
                val label = "${client.name.short} · ${DateFormat.weekdayShort(found.slot.weekday)} ${DateFormat.time(found.slot.start)}"
                val decision = mapping.series.entries.firstOrNull { Mapping.same(it.key, label) }?.value ?: Decision.YES
                out[label] = decision
                if (decision == Decision.ASK) issues.problem(Mapping.FILE_NAME, "серия «$label»: да или нет")
                SeriesLine(client.name, found, decision == Decision.YES)
            }
        }

        private fun groups(last: LocalDate): List<ImportGroup> = groupRows
            .groupBy { Triple(it.date.dayOfWeek, it.time, it.codeText.uppercase()) }
            .map { (slotKey, rows) ->
                val trainings = rows.sortedBy { it.date }.map { row ->
                    val children = row.children.mapNotNull { ImportRules.name(it) }
                        .map { names.getValue(final(ImportRules.nameKey(it))) }
                        .distinctBy { ImportRules.nameKey(it) }
                    GroupTraining(row.date, children)
                }
                val members = ImportRules.members(trainings.flatMap { t -> t.children.map { t.date to it } }, last)
                    .sortedBy { ClientRules.sortKey(it) }
                ImportGroup(Slot(slotKey.first, slotKey.second), rows.first().code, slotKey.third, trainings, members)
            }
            .sortedWith(compareBy({ it.slot.weekday }, { it.slot.start }))
    }
}
