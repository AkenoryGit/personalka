package com.akenory.personalka.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.reference.ReferenceRules

/** Переходы базы со старых версий: телефон с прежней сборкой открывает базу без потерь. */
internal val PersonalkaMigrations: Array<Migration> = arrayOf(Migration1To2, Migration2To3, Migration3To4)

/**
 * Что сделать с базой старой версии перед первым переходом (ТЗ 10.2): копия всех таблиц ([connection] — база ещё
 * версии [from]). Ошибка внутри — переход не начинается: Room откатывает открытие, и база остаётся прежней версии.
 */
fun interface BeforeMigration {
    suspend fun copy(connection: SQLiteConnection, from: Int)
}

/**
 * Переходы, первый из которых сначала зовёт [before]. Room проходит путь от версии файла до нынешней одним открытием,
 * поэтому копия — одна, той версии, что была в телефоне.
 */
internal fun migrationsWithCopy(before: BeforeMigration?): Array<Migration> {
    if (before == null) return PersonalkaMigrations
    var copied = false
    return PersonalkaMigrations.map { inner ->
        object : Migration(inner.startVersion, inner.endVersion) {
            override suspend fun migrate(connection: SQLiteConnection) {
                if (!copied) {
                    before.copy(connection, startVersion)
                    copied = true
                }
                inner.migrate(connection)
            }
        }
    }.toTypedArray()
}

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

/** Версия 3: у дежурства — за кого тренер дежурит (ТЗ 3.5, 5.5). Прежние дежурства — без пометки. */
internal object Migration2To3 : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec("ALTER TABLE `training` ADD COLUMN `coverFor` TEXT")
    }
}

/**
 * Версия 4 (шаг 2.10): очередь изменений для синхронизации (ТЗ 7.2, 3.8). Пустая: в режиме «Только телефон» отправлять
 * некуда, при подключении сервера в неё сразу ложится вся база. Триггеры, которые её пишут, ставятся при открытии
 * базы (`SyncOutbox.install`).
 */
internal object Migration3To4 : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec(
            "CREATE TABLE IF NOT EXISTS `sync_outbox` (`entity` TEXT NOT NULL, `rowId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`entity`, `rowId`))",
        )
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
