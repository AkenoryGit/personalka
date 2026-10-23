package com.akenory.personalka.importer

import com.akenory.personalka.domain.importing.ImportRules

// ТЗ 9.4. Файл сопоставления — обычный текст, тренер правит его в Блокноте: месяц перехода, тип и место клиентов,
// объединение похожих имён, серии. Утилита переписывает его при каждой проверке, сохраняя значения тренера
// и дописывая новое. Строки с «#» — пояснения, их утилита не читает.

/** Решение по паре похожих имён: объединить вариант в правильное имя, оставить разными или ещё не решено. */
enum class Decision { YES, NO, ASK }

data class MergeLine(val variant: String, val target: String, val decision: Decision)

data class Mapping(
    /** «12.2026» — как написано; пустое — месяц перехода выберут на этапе 3. */
    val transitionMonth: String = "",
    /** Клиент → «тип, место» как написано. */
    val clients: Map<String, String> = emptyMap(),
    val merges: List<MergeLine> = emptyList(),
    /** «Смирнова Ольга · вт 17:00» → да / нет. */
    val series: Map<String, Decision> = emptyMap(),
) {
    fun merge(variant: String, target: String): Decision? = merges.firstOrNull {
        same(it.variant, variant) && same(it.target, target)
    }?.decision

    companion object {
        const val FILE_NAME = "сопоставление.txt"
        const val MONTH = "Месяц перехода"
        const val CLIENTS = "Клиенты: тип и место"
        const val MERGES = "Похожие имена"
        const val SERIES = "Серии"

        fun parse(text: String): Mapping {
            var section = ""
            var month = ""
            val clients = linkedMapOf<String, String>()
            val merges = mutableListOf<MergeLine>()
            val series = linkedMapOf<String, Decision>()
            for (raw in text.lines()) {
                val line = raw.trim().removePrefix("﻿")
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.removeSurrounding("[", "]").trim()
                    continue
                }
                val (left, right) = line.split('=', limit = 2).map { it.trim() }.let { it[0] to it.getOrElse(1) { "" } }
                when (section) {
                    MONTH -> month = right
                    CLIENTS -> clients[left] = right
                    MERGES -> {
                        val parts = left.split('→', limit = 2).map { it.trim() }
                        if (parts.size == 2) merges += MergeLine(parts[0], parts[1], decision(right))
                    }
                    SERIES -> series[left] = decision(right)
                }
            }
            return Mapping(month, clients, merges, series)
        }

        private fun decision(text: String): Decision = when (ImportRules.key(text)) {
            "да", "+", "объединить" -> Decision.YES
            "нет", "-", "разные" -> Decision.NO
            else -> Decision.ASK
        }

        private fun word(decision: Decision): String = when (decision) {
            Decision.YES -> "да"
            Decision.NO -> "нет"
            Decision.ASK -> "?"
        }

        fun same(a: String, b: String): Boolean =
            ImportRules.key(a) == ImportRules.key(b)

        fun write(mapping: Mapping): String = buildString {
            appendLine("# Сопоставление для импорта таблиц в «Персоналку» (ТЗ 9.4).")
            appendLine("# Утилита переписывает файл при каждой проверке: ваши значения после «=» сохраняются, новое дописывается.")
            appendLine("# Строки с # — пояснения. Не удаляйте файл до конца переноса: он нужен и для второго запуска (ТЗ 9.5).")
            appendLine()
            appendLine("[$MONTH]")
            appendLine("# Первый месяц, зарплату за который считает приложение, — ММ.ГГГГ. Пусто — решим на этапе 3,")
            appendLine("# тогда все месяцы из таблицы зарплаты — архив «из таблицы».")
            appendLine("месяц = ${mapping.transitionMonth}")
            appendLine()
            appendLine("[$CLIENTS]")
            appendLine("# Клиент = тип, место — короткие имена из приложения. По умолчанию ПТ, ББ; длительность 50 минут.")
            for ((client, value) in mapping.clients) appendLine("$client = $value")
            appendLine()
            appendLine("[$MERGES]")
            appendLine("# Вариант → правильное имя = да (объединить), нет (это разные люди) или ? (не решено — проблема в отчёте).")
            appendLine("# Если правильное имя слева — поменяйте имена местами.")
            for (line in mapping.merges) appendLine("${line.variant} → ${line.target} = ${word(line.decision)}")
            appendLine()
            appendLine("[$SERIES]")
            appendLine("# Постоянное расписание: 3 раза и чаще в одно время за последние 4 недели. да — создать серию, нет — не создавать.")
            for ((slot, decision) in mapping.series) appendLine("$slot = ${word(decision)}")
        }
    }
}
