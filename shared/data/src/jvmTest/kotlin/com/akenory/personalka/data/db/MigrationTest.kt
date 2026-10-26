package com.akenory.personalka.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Переходы базы со старых версий: файл версии 1 (до шага 1.13в), 2 (до «За кого» у дежурства), 3 (до очереди
 * изменений), 4 (до таблиц денег, шаг 3.1), 5 (до отметок «Рассчитались», шаг 3.8) и 6 (до «Предупредить
 * об отпуске», шаг 3.12) — как на телефоне
 * до новой сборки — открываются нынешней версией без потерь. Таблицы старой версии строятся по её схеме
 * из `shared/data/schemas`, которую когда-то записал Room ([createVersion]).
 */
class MigrationTest {

    @Test
    fun version1GetsPlaceColorsAndTypeShades(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v1", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion(raw, 1)
                // Места — в порядке тренера: ДБ первым, своё место «Ул» — третьим.
                raw.exec(place("p1", "ДБ", "Детский бассейн", sort = 0))
                raw.exec(place("p2", "ББ", "Большой бассейн", sort = 1))
                raw.exec(place("p3", "Ул", "Улица", sort = 2))
                raw.exec(type("t1", "ПТ", "PERSONAL", "blue"))
                raw.exec(type("t2", "Мини-группа", "GROUP", "lime"))
                raw.exec(type("t3", "Группа", "GROUP", "green"))
                raw.exec(type("t4", "Аквааэробика", "GROUP", "sky"))
                raw.exec(type("t5", "Тройка", "OTHER", "brown"))
            }

            val db = personalkaDatabase(path)
            try {
                val places = db.referenceDao().locations()
                assertEquals(listOf("ДБ" to "pink", "ББ" to "blue", "Ул" to "red"), places.map { it.shortName to it.color })
                assertEquals("Детский бассейн", places.first().name, "Остальное в месте не тронуто")

                val shades = db.referenceDao().trainingTypes().associate { it.name to it.shade }
                assertEquals(mapOf("ПТ" to 1, "Мини-группа" to 3, "Группа" to 4, "Аквааэробика" to 4, "Тройка" to 2), shades)
            } finally {
                db.close()
            }

            BundledSQLiteDriver().open(path).useConnection { raw ->
                assertEquals(PersonalkaDatabase.VERSION.toLong(), raw.long("PRAGMA user_version"))
                assertEquals(0L, raw.long("SELECT COUNT(*) FROM pragma_table_info('training_type') WHERE name = 'color'"), "Цвет типа удалён")
            }
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach { File(path + it).delete() }
        }
    }

    @Test
    fun version2KeepsDutiesWithoutCoverFor(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v2", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion(raw, 2)
                raw.exec(
                    "INSERT INTO training (id, typeId, locationId, start, durationMin, seriesId, seriesDetached, groupId, status, plan, fact, " +
                        "owner, ownerColleagueId, conductedBy, colleagueId, splitHalf, cancelReason, overRestriction, planCarriedFromId, " +
                        "planCarriedText, doneAt, coachId, createdAt, updatedAt, version, deletedAt) " +
                        "VALUES ('d1', 't1', 'p1', '2026-10-20T11:00', 240, NULL, 0, NULL, 'PLANNED', NULL, NULL, " +
                        "'SELF', NULL, 'SELF', NULL, 0, NULL, 0, NULL, NULL, NULL, 'c1', 1, 1, 1, NULL)",
                )
            }

            val db = personalkaDatabase(path)
            try {
                val duty = db.trainingDao().training("d1")!!
                assertEquals(240, duty.durationMin, "Дежурство не тронуто")
                assertNull(duty.coverFor, "Прежние дежурства — свои")
                db.trainingDao().upsertTraining(duty.copy(coverFor = "Александра"))
                assertEquals("Александра", db.trainingDao().training("d1")!!.coverFor)
            } finally {
                db.close()
            }

            BundledSQLiteDriver().open(path).useConnection { raw ->
                assertEquals(PersonalkaDatabase.VERSION.toLong(), raw.long("PRAGMA user_version"))
            }
        } finally {
            listOf("", "-wal", "-shm", "-journal").forEach { File(path + it).delete() }
        }
    }

    @Test
    fun version3GetsAnEmptyQueueThatFillsWithEdits(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v3", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion(raw, 3)
                raw.exec(place("p1", "ББ", "Большой бассейн", sort = 0).replace("archived, ", "archived, color, ").replace("0, 'c1'", "0, 'blue', 'c1'"))
            }

            val db = personalkaDatabase(path)
            try {
                assertEquals(0, db.syncDao().pending(), "Прежние записи в очередь не ложатся: отправлять некуда")
                val place = db.referenceDao().locations().single()
                db.referenceDao().upsertLocation(place.copy(name = "Большой", sync = place.sync.touched(2)))
                assertEquals(1, db.syncDao().pending(), "Правка после перехода — в очереди")
            } finally {
                db.close()
            }
        } finally {
            deleteDatabase(path)
        }
    }

    @Test
    fun version4KeepsEverythingAndGetsMoneyTables(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v4", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            // База этапов 1–2: место, тип, ставка, клиент, тренировка с участием, дневник и правка в очереди.
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion(raw, 4)
                raw.exec(place("p1", "ББ", "Большой бассейн", sort = 0).replace("archived, ", "archived, color, ").replace("0, 'c1'", "0, 'blue', 'c1'"))
                raw.exec(
                    "INSERT INTO training_type (id, name, shortName, kind, shade, defaultDurationMin, maxClients, payUnit, countsTowardPt, " +
                        "tierDependent, diaryPlan, diaryFact, diaryBehavior, splitFallbackTypeId, archived, coachId, createdAt, updatedAt, version, deletedAt) " +
                        "VALUES ('t1', 'Персональная', 'ПТ', 'PERSONAL', 1, 50, 1, 'PER_SESSION', 1, 1, 1, 1, 1, NULL, 0, 'c1', 1, 1, 1, NULL)",
                )
                raw.exec(
                    "INSERT INTO rate_version (id, typeId, locationId, validFrom, amountT1, amountT2, amountT3, sptCloseBonus, " +
                        "coachId, createdAt, updatedAt, version, deletedAt) " +
                        "VALUES ('r1', 't1', 'p1', '2026-01-01', 92000, 96000, 100000, NULL, 'c1', 1, 1, 1, NULL)",
                )
                raw.exec(
                    "INSERT INTO training (id, typeId, locationId, start, durationMin, seriesId, seriesDetached, groupId, status, plan, fact, " +
                        "owner, ownerColleagueId, conductedBy, colleagueId, splitHalf, cancelReason, overRestriction, planCarriedFromId, " +
                        "planCarriedText, doneAt, coverFor, coachId, createdAt, updatedAt, version, deletedAt) " +
                        "VALUES ('x1', 't1', 'p1', '2026-10-20T17:00', 50, NULL, 0, NULL, 'DONE', 'Кроль 4×25', 'Проплыл', " +
                        "'SELF', NULL, 'SELF', NULL, 0, NULL, 0, NULL, NULL, 2, NULL, 'c1', 1, 2, 2, NULL)",
                )
                raw.exec("INSERT INTO sync_outbox (entity, rowId, updatedAt) VALUES ('training', 'x1', 2)")
            }

            val db = personalkaDatabase(path)
            try {
                val rate = db.referenceDao().rateVersions("t1").single()
                assertEquals(listOf(92000L, 96000L, 100000L), listOf(rate.amountT1, rate.amountT2, rate.amountT3), "Ставка не тронута")
                assertNull(rate.clubService, "Прежние ставки — свои, без услуги сетки")
                val training = db.trainingDao().training("x1")!!
                assertEquals("Кроль 4×25" to "Проплыл", training.plan to training.fact, "Дневник не тронут")
                assertEquals(1, db.syncDao().pending(), "Очередь изменений не потеряна")
                assertEquals("Большой бассейн", db.referenceDao().locations().single().name)

                val money = db.moneyDao()
                assertTrue(money.colleagues().isEmpty() && money.monthRecords().isEmpty() && money.archivedMonths().isEmpty(), "Новые таблицы пустые")
                money.upsertColleague(Samples.colleague("Лена"))
                assertEquals(2, db.syncDao().pending(), "Новые таблицы пишут в очередь изменений")
            } finally {
                db.close()
            }

            BundledSQLiteDriver().open(path).useConnection { raw ->
                assertEquals(PersonalkaDatabase.VERSION.toLong(), raw.long("PRAGMA user_version"))
            }
        } finally {
            deleteDatabase(path)
        }
    }

    @Test
    fun version5KeepsMoneyAndGetsSettlements(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v5", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            // База шагов 3.1–3.7: коллега и доплата месяца.
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion(raw, 5)
                raw.exec(
                    "INSERT INTO colleague (id, name, linkedCoachId, archived, coachId, createdAt, updatedAt, version, deletedAt) " +
                        "VALUES ('lena', 'Лена', NULL, 0, 'c1', 1, 1, 1, NULL)",
                )
                raw.exec(
                    "INSERT INTO month_adjustment (id, month, amount, comment, inClubAccrual, coachId, createdAt, updatedAt, version, deletedAt) " +
                        "VALUES ('a1', '2026-10', 120000, 'Соревнования', 1, 'c1', 1, 1, 1, NULL)",
                )
            }

            val db = personalkaDatabase(path)
            try {
                val money = db.moneyDao()
                assertEquals("Лена", money.colleague("lena")!!.name, "Коллега не тронут")
                assertEquals(120000L, money.adjustments(YearMonth(2026, 10)).single().amount, "Доплата не тронута")
                assertNull(money.settlement(YearMonth(2026, 10), "lena"), "Отметок «Рассчитались» ещё нет")
                money.upsertSettlement(Samples.settlement("lena", 88358))
                assertEquals(88358L, money.settlement(YearMonth(2026, 10), "lena")!!.amount)
                assertEquals(1, db.syncDao().pending(), "Новая таблица пишет в очередь изменений")
            } finally {
                db.close()
            }
        } finally {
            deleteDatabase(path)
        }
    }

    @Test
    fun version6KeepsSettlementsAndGetsVacationNotices(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v6", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            // База шагов 3.8–3.9: отметка «Рассчитались».
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion(raw, 6)
                raw.exec(
                    "INSERT INTO colleague_settlement (id, month, colleagueId, amount, coachId, createdAt, updatedAt, version, deletedAt) " +
                        "VALUES ('s1', '2026-10', 'lena', 88358, 'c1', 1, 1, 1, NULL)",
                )
            }

            val db = personalkaDatabase(path)
            try {
                assertEquals(88358L, db.moneyDao().settlement(YearMonth(2026, 10), "lena")!!.amount, "Отметка не тронута")
                val schedule = db.scheduleDao()
                val today = LocalDate(2026, 10, 26)
                assertEquals(emptyList(), schedule.observeOpenVacationNotices(today).first(), "Предупреждать ещё некого")
                schedule.upsertVacationNotice(Samples.vacationNotice("orlova", LocalDate(2026, 11, 2), LocalDate(2026, 11, 8)))
                assertEquals(listOf(LocalDate(2026, 11, 3)), schedule.observeOpenVacationNotices(today).first().single().dates)
                assertEquals(1, db.syncDao().pending(), "Новая таблица пишет в очередь изменений")
            } finally {
                db.close()
            }
        } finally {
            deleteDatabase(path)
        }
    }

    @Test
    fun newDatabaseIsVersion7() {
        assertEquals(7, PersonalkaDatabase.VERSION)
        assertTrue((1..6).all { schemaFile(it).exists() }, "Схемы версий 1–6 хранятся: по ним строятся переходы")
    }
}
