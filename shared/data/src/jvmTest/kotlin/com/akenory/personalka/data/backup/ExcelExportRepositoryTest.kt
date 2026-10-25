package com.akenory.personalka.data.backup

import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.ParticipationEntity
import com.akenory.personalka.data.db.entity.ResultEntity
import com.akenory.personalka.data.db.entity.TrainingEntity
import com.akenory.personalka.data.db.inMemoryPersonalkaDatabase
import com.akenory.personalka.data.prefs.MemoryPrefs
import com.akenory.personalka.data.settings.SettingsRepository
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.CoachCategory
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.model.TrainingStatus
import com.akenory.personalka.domain.money.RateAmounts
import com.akenory.personalka.domain.money.SalarySetupDraft
import com.akenory.personalka.domain.money.TierDraft
import com.akenory.personalka.domain.reference.CoachName
import com.akenory.personalka.domain.reference.ReferenceRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.YearMonth
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Выгрузка в Excel (7.6, шаг 3.8в): книга из базы, листы читаются как XML, зарплата совпадает с «Годом». */
class ExcelExportRepositoryTest {
    private lateinit var db: PersonalkaDatabase
    private val now = 1_792_500_000_000L // 20.10.2026
    private val today = LocalDate(2026, 10, 20)
    private lateinit var settings: SettingsRepository

    private object NoFolder : BackupFolder {
        override suspend fun write(folder: String, name: String, bytes: ByteArray) = false
        override suspend fun list(folder: String): List<String>? = null
        override suspend fun delete(folder: String, name: String) = Unit
    }

    @BeforeTest
    fun open() {
        db = inMemoryPersonalkaDatabase()
        settings = SettingsRepository(db) { now }
        runBlocking { settings.completeFirstRun(CoachName("Соколов", "Андрей", null), ReferenceRules.defaultPlaces, ReferenceRules.defaultTypes) }
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private fun rub(r: Long) = r * 100

    @Test
    fun workbookFromDatabase(): Unit = runBlocking {
        val coach = db.coachDao().currentCoach()!!.id
        val pt = settings.types.first().single { it.shortName == "ПТ" }.id
        val bb = settings.places.first().single { it.shortName == "ББ" }.id
        settings.salary.finish(
            SalarySetupDraft(
                category = CoachCategory.MASTER,
                scheme = PaySchemeKind.FULL_OFFICIAL,
                advance = rub(10_000),
                schemeFrom = YearMonth(2026, 1),
                rates = mapOf(pt to mapOf(bb to RateAmounts(rub(920), rub(960), rub(1000)))),
                services = emptyMap(),
                tiers = TierDraft(true, 101, 151, setOf(pt)),
            ),
            today,
        )
        db.clientDao().upsertClient(ClientEntity("c1", SyncFields.new(coach, now), "Орлов", "Миша", birthDate = LocalDate(2016, 3, 5)))
        db.clientDao().upsertClient(ClientEntity("c2", SyncFields.new(coach, now), "Белова", "Аня", status = ClientStatus.ARCHIVED, privateNote = "A & B <тест>"))
        val days = listOf(LocalDateTime(2026, 9, 10, 9, 0), LocalDateTime(2026, 10, 15, 9, 0), LocalDateTime(2026, 10, 28, 9, 0))
        days.forEachIndexed { i, start ->
            val status = if (start.date < today) TrainingStatus.DONE else TrainingStatus.PLANNED
            db.trainingDao().upsertTraining(TrainingEntity("t$i", SyncFields.new(coach, now), pt, bb, start, 50, status = status, fact = "Кроль"))
            val mark = if (status == TrainingStatus.DONE) ParticipationStatus.ATTENDED else ParticipationStatus.EXPECTED
            db.trainingDao().upsertParticipation(ParticipationEntity("p$i", SyncFields.new(coach, now), "t$i", "c1", mark))
        }
        db.trainingDao().upsertResult(
            ResultEntity("r1", SyncFields.new(coach, now), "c1", LocalDate(2026, 10, 15), SwimStyle.FREESTYLE, 50, 25, 6317, ResultKind.TRAINING),
        )

        val made = BackupRepository(db, MemoryPrefs(), NoFolder) { now }.excel()
        assertEquals("trener-data-20261020.xlsx", made.name)
        val files = unzip(made.bytes)
        assertEquals(
            listOf("Клиенты", "Тренировки", "Результаты", "Зарплата по месяцам"),
            elements(files.getValue("xl/workbook.xml"), "sheet").map { it.getAttribute("name") },
        )
        files.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach { parse(it) }

        // Клиенты: занимающиеся — сверху, у Миши два проведённых занятия, последнее — 15.10 (число дней Excel).
        val clients = rows(files.getValue("xl/worksheets/sheet1.xml"))
        assertEquals(listOf("Фамилия", "Имя"), clients[0].take(2))
        assertEquals(listOf("Орлов", "Миша"), clients[1].take(2))
        assertEquals("2", clients[1][8])
        assertEquals((LocalDate(2026, 10, 15).toEpochDays() + 25569).toString(), clients[1][9])
        assertEquals("A & B <тест>", clients[2][11])

        val trainings = rows(files.getValue("xl/worksheets/sheet2.xml"))
        assertEquals(4, trainings.size)
        assertEquals(listOf("чт", "09:00", "50"), trainings[1].subList(1, 4))
        assertEquals("Орлов Миша", trainings[1][7])
        assertEquals("проведена", trainings[1][8])

        val results = rows(files.getValue("xl/worksheets/sheet3.xml"))
        assertEquals(listOf("Орлов Миша", "50", "вольный стиль", "25", "1:03,17", "63.17"), results[1].subList(1, 7))

        // Зарплата: те же суммы, что во вкладке «Год», и строка «Итого 2026» — их сумма.
        val year = settings.months.year(2026, today).first()
        val salary = rows(files.getValue("xl/worksheets/sheet4.xml"))
        assertEquals(listOf("Сентябрь", "Октябрь", "Итого 2026"), salary.drop(1).map { it[1] })
        assertEquals(year.rows.map { "%d.%02d".format(it.earned / 100, it.earned % 100) }, salary.subList(1, 3).map { it[2] })
        assertEquals("%d.%02d".format(year.earned / 100, year.earned % 100), salary[3][2])
        assertEquals(rub(920 + 920), year.earned)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }

    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun elements(bytes: ByteArray, tag: String): List<Element> {
        val list = parse(bytes).getElementsByTagNameNS("*", tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    /** Строки листа: значения ячеек по столбцам, пустые — "". */
    private fun rows(bytes: ByteArray): List<List<String>> = elements(bytes, "row").map { row ->
        val cells = row.getElementsByTagNameNS("*", "c")
        val out = mutableListOf<String>()
        (0 until cells.length).forEach { i ->
            val c = cells.item(i) as Element
            val col = c.getAttribute("r").takeWhile { it.isLetter() }.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
            while (out.size < col) out += ""
            out += c.textContent
        }
        out
    }
}
