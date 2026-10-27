package com.akenory.personalka.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import com.akenory.personalka.domain.model.TrainingKind
import com.akenory.personalka.domain.reference.ReferenceRules

/** Переходы базы со старых версий: телефон с прежней сборкой открывает базу без потерь. */
internal val PersonalkaMigrations: Array<Migration> = arrayOf(Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6, Migration6To7, Migration7To8, Migration8To9, Migration9To10, Migration10To11, Migration11To12, Migration12To13)

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

/**
 * Версия 5 (шаг 3.1): деньги (ТЗ 3.6) — коллеги и их схемы, данные месяцев, доплаты и удержания, итоги прошлых месяцев;
 * у ставки — услуга сетки клуба, по которой она заполнена (ТЗ 3.3). Новые таблицы пустые, прежние записи не меняются:
 * у прежних ставок услуги нет — они свои. Таблицы — точно такие, какие строит Room (схема `5.json`).
 */
internal object Migration4To5 : Migration(4, 5) {
    private const val SYNC = "`coachId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
        "`version` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`)"

    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec("ALTER TABLE `rate_version` ADD COLUMN `clubService` TEXT")
        connection.exec(
            "CREATE TABLE IF NOT EXISTS `colleague` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `linkedCoachId` TEXT, " +
                "`archived` INTEGER NOT NULL, $SYNC)",
        )
        connection.exec("CREATE INDEX IF NOT EXISTS `index_colleague_coachId` ON `colleague` (`coachId`)")
        connection.exec(
            "CREATE TABLE IF NOT EXISTS `colleague_scheme_version` (`id` TEXT NOT NULL, `colleagueId` TEXT NOT NULL, " +
                "`validFrom` TEXT NOT NULL, `kind` TEXT NOT NULL, $SYNC)",
        )
        connection.exec(
            "CREATE INDEX IF NOT EXISTS `index_colleague_scheme_version_colleagueId_validFrom` ON `colleague_scheme_version` " +
                "(`colleagueId`, `validFrom`)",
        )
        connection.exec(
            "CREATE TABLE IF NOT EXISTS `month_record` (`id` TEXT NOT NULL, `month` TEXT NOT NULL, `planAmount` INTEGER, " +
                "`advanceActual` INTEGER, `closed` INTEGER NOT NULL, $SYNC)",
        )
        connection.exec("CREATE INDEX IF NOT EXISTS `index_month_record_month` ON `month_record` (`month`)")
        connection.exec(
            "CREATE TABLE IF NOT EXISTS `month_adjustment` (`id` TEXT NOT NULL, `month` TEXT NOT NULL, `amount` INTEGER NOT NULL, " +
                "`comment` TEXT NOT NULL, `inClubAccrual` INTEGER NOT NULL, $SYNC)",
        )
        connection.exec("CREATE INDEX IF NOT EXISTS `index_month_adjustment_month` ON `month_adjustment` (`month`)")
        connection.exec(
            "CREATE TABLE IF NOT EXISTS `archived_month` (`id` TEXT NOT NULL, `month` TEXT NOT NULL, `earned` INTEGER NOT NULL, " +
                "`tax` INTEGER NOT NULL, `received` INTEGER NOT NULL, `plan` INTEGER, `ptCount` INTEGER NOT NULL, " +
                "`splitCount` INTEGER NOT NULL, `groupChildren` INTEGER NOT NULL, `dutyMinutes` INTEGER NOT NULL, $SYNC)",
        )
        connection.exec("CREATE INDEX IF NOT EXISTS `index_archived_month_month` ON `archived_month` (`month`)")
    }
}

/**
 * Версия 6 (шаг 3.8): отметки «Рассчитались» с коллегами за месяц (ТЗ 4.11, 5.12). Новая таблица пустая, прежние
 * записи не меняются. Таблица — точно такая, какую строит Room (схема `6.json`).
 */
internal object Migration5To6 : Migration(5, 6) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec(
            "CREATE TABLE IF NOT EXISTS `colleague_settlement` (`id` TEXT NOT NULL, `month` TEXT NOT NULL, " +
                "`colleagueId` TEXT NOT NULL, `amount` INTEGER NOT NULL, `coachId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
        )
        connection.exec("CREATE INDEX IF NOT EXISTS `index_colleague_settlement_month` ON `colleague_settlement` (`month`)")
    }
}

/**
 * Версия 7 (шаг 3.12): кого предупредить об отпуске (ТЗ 5.16). Новая таблица пустая, прежние записи не меняются.
 * Таблица — точно такая, какую строит Room (схема `7.json`).
 */
internal object Migration6To7 : Migration(6, 7) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec(
            "CREATE TABLE IF NOT EXISTS `vacation_notice` (`id` TEXT NOT NULL, `vacationFrom` TEXT NOT NULL, " +
                "`vacationTo` TEXT NOT NULL, `clientId` TEXT NOT NULL, `dates` TEXT NOT NULL, `notifiedAt` INTEGER, " +
                "`coachId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
                "`deletedAt` INTEGER, PRIMARY KEY(`id`))",
        )
        connection.exec("CREATE INDEX IF NOT EXISTS `index_vacation_notice_vacationTo` ON `vacation_notice` (`vacationTo`)")
    }
}

/**
 * Версия 8 (шаг 3.9в): отметка «Дежурства сверены» в записи месяца (ТЗ 5.14, 3.6) — часы, на которых сверили.
 * У прежних месяцев отметки нет.
 */
internal object Migration7To8 : Migration(7, 8) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec("ALTER TABLE `month_record` ADD COLUMN `dutiesCheckedMin` INTEGER")
    }
}

/** Версия 9 (шаг 3.9е): свой цвет дежурства у типа (ТЗ 3.3, 5.3); у прежних типов его нет — цвет места. */
internal object Migration8To9 : Migration(8, 9) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec("ALTER TABLE `training_type` ADD COLUMN `dutyColor` TEXT")
    }
}

/** Версия 10 (шаг 3.9н): «Убрать из списка» «Без записи» у клиента (ТЗ 4.15); у прежних клиентов — пусто. */
internal object Migration9To10 : Migration(9, 10) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec("ALTER TABLE `client` ADD COLUMN `followupDismissedAt` INTEGER")
    }
}

/**
 * Версия 11 (шаг 4.2а): сверка с 1С (ТЗ 3.7, 8) — услуги, имена, сверки и их пункты. Новые таблицы пустые, прежние
 * записи не меняются. Таблицы — точно такие, какие строит Room (схема `11.json`).
 */
internal object Migration10To11 : Migration(10, 11) {
    private const val SYNC = "`coachId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
        "`version` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))"

    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec(
            "CREATE TABLE IF NOT EXISTS `service_mapping_1c` (`id` TEXT NOT NULL, `name1c` TEXT NOT NULL, `typeId` TEXT NOT NULL, " +
                "`locationId` TEXT NOT NULL, $SYNC",
        )
        connection.exec("CREATE INDEX IF NOT EXISTS `index_service_mapping_1c_name1c` ON `service_mapping_1c` (`name1c`)")
        connection.exec("CREATE TABLE IF NOT EXISTS `client_alias_1c` (`id` TEXT NOT NULL, `nameKey` TEXT NOT NULL, `clientId` TEXT NOT NULL, $SYNC")
        connection.exec("CREATE INDEX IF NOT EXISTS `index_client_alias_1c_nameKey` ON `client_alias_1c` (`nameKey`)")
        connection.exec(
            "CREATE TABLE IF NOT EXISTS `reconciliation` (`id` TEXT NOT NULL, `fileName` TEXT NOT NULL, `periodFrom` TEXT NOT NULL, " +
                "`periodTo` TEXT NOT NULL, `generatedAt` TEXT, `coachName1c` TEXT, `rowsTotal` INTEGER NOT NULL, `matched` INTEGER NOT NULL, " +
                "`checkedUntil` TEXT NOT NULL, $SYNC",
        )
        connection.exec("CREATE INDEX IF NOT EXISTS `index_reconciliation_periodFrom_periodTo` ON `reconciliation` (`periodFrom`, `periodTo`)")
        connection.exec(
            "CREATE TABLE IF NOT EXISTS `reconciliation_item` (`id` TEXT NOT NULL, `reconciliationId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`itemKey` TEXT NOT NULL, `at` TEXT NOT NULL, `trainingId` TEXT, `clientId` TEXT, `statusDiffers` INTEGER NOT NULL, " +
                "`serviceDiffers` INTEGER NOT NULL, `rowAt` TEXT, `rowServiceName` TEXT, `rowClient` TEXT, `rowStatus` TEXT, " +
                "`resolution` TEXT NOT NULL, `resolvedAt` INTEGER, $SYNC",
        )
        connection.exec("CREATE INDEX IF NOT EXISTS `index_reconciliation_item_reconciliationId` ON `reconciliation_item` (`reconciliationId`)")
        connection.exec("CREATE INDEX IF NOT EXISTS `index_reconciliation_item_resolution` ON `reconciliation_item` (`resolution`)")
    }
}

/**
 * Версия 12 (шаг 3.9у): «Оставить» на вопрос «Не ходит 30 дней» у места в составе группы (ТЗ 5.10). Составы
 * не меняются: у прежних записей — ни разу не оставляли.
 */
internal object Migration11To12 : Migration(11, 12) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec("ALTER TABLE `group_member` ADD COLUMN `absenceKeptOn` TEXT")
        connection.exec("ALTER TABLE `group_member` ADD COLUMN `absenceKeeps` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Версия 13 (шаг 3.9х): «Напомнить с» и «Скрыть до этого дня» у записи листа ожидания (4.16). Прежние записи — без
 * дня: видны и ждут, как раньше.
 */
internal object Migration12To13 : Migration(12, 13) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec("ALTER TABLE `wait_entry` ADD COLUMN `remindFrom` TEXT")
        connection.exec("ALTER TABLE `wait_entry` ADD COLUMN `hideUntilRemind` INTEGER NOT NULL DEFAULT 0")
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
