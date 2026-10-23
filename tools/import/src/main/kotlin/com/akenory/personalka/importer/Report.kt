package com.akenory.personalka.importer

import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.SwimStyle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import java.util.Locale

// ТЗ 9.4, шаг 2: отчёт проверки — сколько чего перенесётся и список проблем. Числа отсюда сверяются
// на телефоне после восстановления копии (шаг 1.18), поэтому они считаются по тем же данным, что пойдут в копию.

object Report {
    const val FILE_NAME = "отчёт-импорта.txt"

    fun text(result: CheckResult, folder: String, now: LocalDateTime): String = buildString {
        val issues = result.issues
        appendLine("Отчёт импорта таблиц в «Персоналку» — ${DateFormat.full(now.date)} ${DateFormat.time(now.time)}")
        appendLine("Папка: $folder")
        appendLine("Файлов xlsx: ${result.files.size} — таблиц клиентов ${result.clientFiles}, зарплаты ${result.salaryFiles}" +
            if (result.unknownFiles.isEmpty()) "" else ", не узнано ${result.unknownFiles.size}")
        appendLine("Последний день в таблицах: ${DateFormat.full(result.last)} — от него считаются 4 недели для составов групп и серий")
        appendLine()

        appendLine("ИТОГО")
        val fromTables = result.clients.count { it.table != null }
        val sexKnown = result.clients.count { it.sex != null }
        appendLine("  Клиенты: ${n(result.clients.size)} — с таблицей ${n(fromTables)}, только из групп ${n(result.clients.size - fromTables)}; пол понятен у ${n(sexKnown)}")
        val trainings = result.trainings
        val by = trainings.groupingBy { it.status.participation }.eachCount()
        appendLine(
            "  Тренировки клиентов: ${n(trainings.size)} — проведено ${n(by[ParticipationStatus.ATTENDED])}, " +
                "отменено ${n(by[ParticipationStatus.CANCELLED])}, со списанием ${n(by[ParticipationStatus.CANCELLED_CHARGED])}, " +
                "запланировано ${n(by[ParticipationStatus.EXPECTED])}",
        )
        val results = result.results
        appendLine("  Результаты: ${n(results.size)} — на тренировках ${n(results.count { it.kind == ResultKind.TRAINING })}, на соревнованиях ${n(results.count { it.kind == ResultKind.COMPETITION })}")
        appendLine("  Группы: ${n(result.groups.size)}; тренировок групп ${n(result.groupTrainings)}, детей на них ${n(result.groupParticipations)}")
        if (result.withColleague) appendLine("  Коллега: Лена (группы Д+Л и Л+Д)")
        appendLine("  Серии: ${n(result.series.count { it.confirmed })}" + result.series.count { !it.confirmed }.let { if (it > 0) " (ещё $it — «нет» в файле сопоставления)" else "" })
        appendLine("  Месяцы зарплаты: ${n(result.months.size)}" + if (result.mapping.transitionMonth.isEmpty()) " — все архивом «из таблицы»" else "; месяц перехода ${result.mapping.transitionMonth}")
        appendLine()

        if (issues.problems.isEmpty()) {
            appendLine("ПРОБЛЕМ НЕТ — можно собирать файл копии.")
        } else {
            appendLine("ПРОБЛЕМЫ: ${issues.problems.size}. Исправьте в таблицах или в файле «${Mapping.FILE_NAME}» и запустите проверку снова.")
            appendLine("Строка с проблемой не переносится, пока её не исправить.")
            issues.problems.forEachIndexed { i, text -> appendLine("  ${i + 1}. $text") }
        }
        appendLine()
        if (result.unknownFiles.isNotEmpty()) {
            appendLine("НЕ УЗНАНЫ — нет листов «Тренировки», «Общая информация», «<Месяц> группы» или «Настройки», файлы пропущены:")
            result.unknownFiles.forEach { appendLine("  $it") }
            appendLine()
        }
        if (issues.fixed.isNotEmpty()) {
            appendLine("ИСПРАВЛЕНО САМО: ${issues.fixed.size}")
            issues.fixed.forEach { appendLine("  $it") }
            appendLine()
        }

        appendLine("СЕРИИ — проверьте; лишнюю отметьте «нет» в разделе «${Mapping.SERIES}»")
        if (result.series.isEmpty()) appendLine("  нет")
        for (line in result.series) {
            val slot = "${DateFormat.weekdayShort(line.found.slot.weekday)} ${DateFormat.time(line.found.slot.start)}"
            val mark = if (line.confirmed) "" else " — не создаётся"
            appendLine("  ${line.client.short} · $slot · ${line.found.times} раза за 4 недели · с ${DateFormat.full(line.found.from)}$mark")
        }
        appendLine()

        appendLine("ГРУППЫ")
        if (result.groups.isEmpty()) appendLine("  нет")
        for (group in result.groups) {
            val who = when {
                group.code.owner == Owner.COLLEAGUE -> "группа Лены, ведём вместе"
                group.code.conductedBy == ConductedBy.SELF_AND_COLLEAGUE -> "моя, вместе с Леной"
                else -> "моя, веду один"
            }
            val dates = group.trainings.map { it.date }
            appendLine(
                "  ${DateFormat.weekdayShort(group.slot.weekday)} ${DateFormat.time(group.slot.start)} · ${group.codeText} ($who) · " +
                    "${group.trainings.size} трен. с ${DateFormat.full(dates.min())} по ${DateFormat.full(dates.max())}",
            )
            appendLine("    состав (${group.members.size}): " + group.members.joinToString { it.short }.ifEmpty { "никто не приходил за 4 недели" })
        }
        appendLine()

        appendLine("КЛИЕНТЫ")
        for (client in result.clients) {
            val parts = buildList {
                if (client.table != null) {
                    add("${client.typeShort} · ${client.placeShort}")
                    add("тренировок ${client.trainings.size}")
                    if (client.results.isNotEmpty()) add("результатов ${client.results.size}")
                    client.table.birth?.let { add("р. ${DateFormat.full(it)}") }
                } else {
                    add("только в группах")
                }
                if (client.groupVisits > 0) add("в группах ${client.groupVisits}")
                add(
                    when (client.sex) {
                        null -> "пол не понятен"
                        Sex.MALE -> "м"
                        Sex.FEMALE -> "ж"
                    },
                )
            }
            appendLine("  ${client.name.full} — ${parts.joinToString(", ")}")
        }
        appendLine()

        appendLine("РЕЗУЛЬТАТЫ ПО СТИЛЯМ")
        val styles = results.groupingBy { it.style }.eachCount()
        appendLine("  " + SwimStyle.entries.joinToString(", ") { "${style(it)} ${n(styles[it])}" })
        appendLine()

        appendLine("МЕСЯЦЫ ЗАРПЛАТЫ — итоги посчитаны по строкам дней, а не взяты из «ИТОГО»")
        if (result.months.isEmpty()) appendLine("  нет")
        for (month in result.months) {
            appendLine("  ${month.where}: ${month.year?.let { DateFormat.monthYear(LocalDate(it, month.month, 1)) } ?: "год не понять"}, дней ${month.days}")
            for (total in month.totals) {
                val itogo = total.itogo?.takeIf { kotlin.math.abs(it - total.days) > 0.005 }?.let { " (в «ИТОГО» ${number(it)})" }.orEmpty()
                appendLine("    ${total.title}: ${number(total.days)}$itogo")
            }
            for ((label, value) in month.values) appendLine("    $label — $value")
        }
        appendLine()

        appendLine("СТАВКИ — лист «Настройки», войдут в ставки с 01.01.2026 (сверить с сеткой клуба)")
        if (result.rates.isEmpty()) appendLine("  нет")
        for ((label, value) in result.rates) appendLine("  $label — $value")
    }

    private fun n(value: Int?): String = RuText.digits((value ?: 0).toLong())

    private fun number(value: Double): String =
        if (value == Math.floor(value)) RuText.digits(value.toLong()) else String.format(Locale.forLanguageTag("ru"), "%.2f", value)

    private fun style(style: SwimStyle): String = when (style) {
        SwimStyle.FREESTYLE -> "вольный"
        SwimStyle.BACKSTROKE -> "на спине"
        SwimStyle.BREASTSTROKE -> "брасс"
        SwimStyle.BUTTERFLY -> "баттерфляй"
        SwimStyle.MEDLEY -> "комплекс"
    }
}
