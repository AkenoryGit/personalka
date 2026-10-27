package com.akenory.personalka.data.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.akenory.personalka.data.db.entity.ClientAlias1cEntity
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
    fun version7KeepsMonthAndGetsDutyCheck(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v7", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            // База шагов 3.8–3.12: октябрь сверен, план задан.
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion(raw, 7)
                raw.exec(
                    "INSERT INTO month_record (id, month, planAmount, advanceActual, closed, coachId, createdAt, updatedAt, version, deletedAt) " +
                        "VALUES ('m1', '2026-10', 21000000, NULL, 1, 'c1', 1, 1, 1, NULL)",
                )
            }

            val db = personalkaDatabase(path)
            try {
                val money = db.moneyDao()
                val record = money.monthRecord(YearMonth(2026, 10))!!
                assertEquals(21000000L to true, record.planAmount to record.closed, "План и «Месяц сверен» не тронуты")
                assertNull(record.dutiesCheckedMin, "Дежурства прежних месяцев не сверены")
                money.upsertMonthRecord(record.copy(dutiesCheckedMin = 1440))
                assertEquals(1440, money.monthRecord(YearMonth(2026, 10))!!.dutiesCheckedMin)
            } finally {
                db.close()
            }
        } finally {
            deleteDatabase(path)
        }
    }

    @Test
    fun version8KeepsTypesAndGetsDutyColor(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v8", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            // База шагов 3.9в–3.9д: дежурство красится цветом места.
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion(raw, 8)
                raw.exec(
                    "INSERT INTO training_type (id, name, shortName, kind, shade, defaultDurationMin, maxClients, payUnit, countsTowardPt, " +
                        "tierDependent, diaryPlan, diaryFact, diaryBehavior, splitFallbackTypeId, archived, coachId, createdAt, updatedAt, version, deletedAt) " +
                        "VALUES ('t1', 'Дежурство', 'Деж', 'DUTY', 1, 240, 0, 'PER_HOUR', 0, 0, 0, 0, 0, NULL, 0, 'c1', 1, 1, 1, NULL)",
                )
            }

            val db = personalkaDatabase(path)
            try {
                val reference = db.referenceDao()
                val duty = reference.trainingType("t1")!!
                assertEquals("Дежурство" to 240, duty.name to duty.defaultDurationMin, "Тип не тронут")
                assertNull(duty.dutyColor, "У прежнего дежурства своего цвета нет — цвет места")
                reference.upsertTrainingType(duty.copy(dutyColor = "slate"))
                assertEquals("slate", reference.trainingType("t1")!!.dutyColor)
            } finally {
                db.close()
            }
        } finally {
            deleteDatabase(path)
        }
    }

    @Test
    fun version9KeepsClientsAndGetsDismissed(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v9", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            // База шагов 3.9е–3.9м: убрать из «Не записаны дальше» можно было только в архив.
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion(raw, 9)
                raw.exec(
                    "INSERT INTO client (id, lastName, firstName, middleName, birthDate, isAdult, sex, contactName, contactPhone, mediaUrl, " +
                        "privateNote, name1c, cabinetEnabled, consentAt, consentSource, followupContactedAt, followupPausedUntil, followupPauseNote, " +
                        "pendingPlan, status, source, coachId, createdAt, updatedAt, version, deletedAt) " +
                        "VALUES ('k1', 'Белова', 'Аня', NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL, " +
                        "NULL, '2026-11-01', 'отпуск', NULL, 'ACTIVE', 'MANUAL', 'c1', 1, 1, 1, NULL)",
                )
            }

            val db = personalkaDatabase(path)
            try {
                val clients = db.clientDao()
                val client = clients.client("k1")!!
                assertEquals("Белова" to "отпуск", client.lastName to client.followupPauseNote, "Клиент не тронут")
                assertNull(client.followupDismissedAt, "Прежнего клиента из списка не убирали")
                clients.upsertClient(client.copy(followupDismissedAt = 5L))
                assertEquals(5L, clients.client("k1")!!.followupDismissedAt)
            } finally {
                db.close()
            }
        } finally {
            deleteDatabase(path)
        }
    }

    @Test
    fun version10KeepsClientsAndGetsReport1c(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v10", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            // База шагов 3.9н–4.2: сверки с 1С ещё нет.
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion(raw, 10)
                raw.exec(
                    "INSERT INTO client (id, lastName, firstName, middleName, birthDate, isAdult, sex, contactName, contactPhone, mediaUrl, " +
                        "privateNote, name1c, cabinetEnabled, consentAt, consentSource, followupContactedAt, followupPausedUntil, followupPauseNote, " +
                        "pendingPlan, status, source, followupDismissedAt, coachId, createdAt, updatedAt, version, deletedAt) " +
                        "VALUES ('k1', 'Белова', 'Аня', NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL, " +
                        "NULL, NULL, NULL, NULL, 'ACTIVE', 'MANUAL', NULL, 'c1', 1, 1, 1, NULL)",
                )
            }

            val db = personalkaDatabase(path)
            try {
                assertEquals("Белова", db.clientDao().client("k1")!!.lastName, "Клиент не тронут")
                val report = db.report1cDao()
                assertTrue(report.services().isEmpty() && report.aliases().isEmpty(), "Услуг и имён 1С ещё нет")
                report.upsertAlias(ClientAlias1cEntity("a1", SyncFields.new("c1", 1), "белова аня", "k1"))
                assertEquals("k1", report.aliases().single().clientId)
            } finally {
                db.close()
            }
        } finally {
            deleteDatabase(path)
        }
    }

    @Test
    fun version11KeepsGroupMembersAndGetsKeeps(): Unit = runBlocking {
        val file = File.createTempFile("personalka-v11", ".db")
        file.delete()
        val path = file.absolutePath
        try {
            // База шагов 4.2а–3.9т: состав групп без «Оставить».
            BundledSQLiteDriver().open(path).useConnection { raw ->
                createVersion(raw, 11)
                raw.exec(
                    "INSERT INTO group_member (id, groupId, clientId, fromDate, toDate, coachId, createdAt, updatedAt, version, deletedAt) " +
                        "VALUES ('m1', 'g1', 'k1', '2026-09-01', NULL, 'c1', 1, 1, 1, NULL), " +
                        "('m2', 'g1', 'k2', '2026-09-01', '2026-10-15', 'c1', 1, 1, 1, NULL)",
                )
            }

            val db = personalkaDatabase(path)
            try {
                val members = db.clientDao().membersOf("g1").sortedBy { it.id }
                assertEquals(listOf("k1" to null, "k2" to LocalDate(2026, 10, 15)), members.map { it.clientId to it.toDate }, "Состав не тронут")
                assertEquals(listOf(0, 0), members.map { it.absenceKeeps }, "Прежних ни разу не оставляли")
                assertNull(members.first().absenceKeptOn)
                db.clientDao().upsertGroupMember(members.first().copy(absenceKeptOn = LocalDate(2026, 10, 27), absenceKeeps = 1))
                assertEquals(1, db.clientDao().groupMember("m1")!!.absenceKeeps)
            } finally {
                db.close()
            }
        } finally {
            deleteDatabase(path)
        }
    }

    @Test
    fun newDatabaseIsVersion12() {
        assertEquals(12, PersonalkaDatabase.VERSION)
        assertTrue((1..11).all { schemaFile(it).exists() }, "Схемы версий 1–11 хранятся: по ним строятся переходы")
    }
}
