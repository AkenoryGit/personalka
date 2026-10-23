package com.akenory.personalka.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.reference.ReferenceRules

/** Переходы базы со старых версий: телефон с прежней сборкой открывает базу без потерь. */
internal val PersonalkaMigrations: Array<Migration> = arrayOf(Migration1To2)

/**
 * Версия 2 (шаг 1.13в): цвет — у места, у типа — оттенок 1–4. Тренировки ББ синие, ДБ розовые, а ПТ, сплит
 * и группа в одном бассейне различаются светлым и тёмным оттенком (ТЗ 3.3, 5.3).
 *
 * Местам ББ, ДБ и Зал достаются синий, розовый и коричневый, остальным — по очереди свободные цвета палитры;
 * типам первого запуска — их оттенки, своим — по виду (`ReferenceRules.defaultShade`). Прежний цвет типа
 * удаляется: он больше нигде не показывается.
 */
internal object Migration1To2 : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec("ALTER TABLE `location` ADD COLUMN `color` TEXT NOT NULL DEFAULT '${ReferenceRules.FALLBACK_COLOR}'")
        val places = connection.rows("SELECT `id`, `shortName` FROM `location` ORDER BY `sort`, `id`")
        ReferenceRules.defaultPlaceColors(places.map { it[1] }).forEachIndexed { i, color ->
            connection.exec("UPDATE `location` SET `color` = ? WHERE `id` = ?", color, places[i][0])
        }

        connection.exec("ALTER TABLE `training_type` ADD COLUMN `shade` INTEGER NOT NULL DEFAULT ${ReferenceRules.SHADE_LIGHT}")
        connection.rows("SELECT `id`, `name`, `kind` FROM `training_type`").forEach { (id, name, kind) ->
            val parsed = TrainingKind.entries.firstOrNull { it.name == kind } ?: TrainingKind.OTHER
            connection.exec("UPDATE `training_type` SET `shade` = ? WHERE `id` = ?", ReferenceRules.defaultShade(name, parsed), id)
        }
        connection.exec("ALTER TABLE `training_type` DROP COLUMN `color`")
    }
}

/** Выполнить [sql] с параметрами по порядку: строки и целые числа. */
private fun SQLiteConnection.exec(sql: String, vararg args: Any) {
    val statement = prepare(sql)
    try {
        args.forEachIndexed { i, value ->
            when (value) {
                is Int -> statement.bindLong(i + 1, value.toLong())
                is Long -> statement.bindLong(i + 1, value)
                else -> statement.bindText(i + 1, value.toString())
            }
        }
        while (statement.step()) Unit
    } finally {
        statement.close()
    }
}

/** Строки ответа текстом: столбец за столбцом. */
private fun SQLiteConnection.rows(sql: String): List<List<String>> {
    val statement = prepare(sql)
    try {
        val result = mutableListOf<List<String>>()
        while (statement.step()) {
            result += (0 until statement.getColumnCount()).map { statement.getText(it) }
        }
        return result
    } finally {
        statement.close()
    }
}
