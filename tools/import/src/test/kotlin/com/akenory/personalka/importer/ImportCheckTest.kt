package com.akenory.personalka.importer

import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.SwimStyle
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Режим проверки утилиты импорта на придуманных таблицах (шаг 1.17, ТЗ 9.1, 9.2, 9.4). */
class ImportCheckTest {
    private val folder: File = Files.createTempDirectory("import").toFile()
    private val today = LocalDate(2026, 10, 23)

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    /** Ячейка с датой Excel. */
    private data class D(val date: LocalDateTime)

    private fun d(day: Int, month: Int = 10, year: Int = 2026) = D(LocalDateTime(year, month, day, 0, 0))

    /** Книга xlsx: лист → строки ячеек (текст, число или дата). */
    private fun xlsx(name: String, sheets: Map<String, List<List<Any?>>>): File {
        val file = File(folder, name)
        XSSFWorkbook().use { book ->
            val dateStyle = book.createCellStyle().apply { dataFormat = book.createDataFormat().getFormat("dd.mm.yyyy") }
            for ((sheetName, rows) in sheets) {
                val sheet = book.createSheet(sheetName)
                rows.forEachIndexed { r, cells ->
                    val row = sheet.createRow(r)
                    cells.forEachIndexed { c, value ->
                        when (value) {
                            null -> Unit
                            is String -> row.createCell(c).setCellValue(value)
                            is Number -> row.createCell(c).setCellValue(value.toDouble())
                            is D -> row.createCell(c).apply {
                                setCellValue(java.time.LocalDateTime.of(value.date.year, value.date.month.ordinal + 1, value.date.day, value.date.hour, value.date.minute))
                                cellStyle = dateStyle
                            }
                        }
                    }
                }
            }
            file.outputStream().use { book.write(it) }
        }
        return file
    }

    private val trainingHeader = listOf("Дата", "Время", "Статус", "План", "Факт", "Поведение")

    private fun client(
        name: String,
        trainings: List<List<Any?>>,
        results: List<List<Any?>> = emptyList(),
        info: List<List<Any?>> = listOf(listOf("ФИО", name), listOf("Дата рождения", d(12, 5, 2017)), listOf("Заметка", "Боится глубины")),
    ) = xlsx(
        "$name.xlsx",
        mapOf(
            "Общая информация" to info,
            "Тренировки" to listOf(trainingHeader) + trainings,
            "Результаты" to listOf(listOf("Дата", "Дистанция", "Время", "Тип")) + results,
        ),
    )

    private fun salary(groups: List<List<Any?>>, month: List<List<Any?>> = emptyList()) = xlsx(
        "Зарплата.xlsx",
        buildMap {
            put("Октябрь группы", listOf(listOf("Дата", "Время", "Тип", "Дети")) + groups)
            if (month.isNotEmpty()) put("Октябрь", month)
            put("Настройки", listOf(listOf("ПТ", 1500), listOf("Группа за ребёнка", 300)))
        },
    )

    private fun run(mapping: Mapping = Mapping()) = Check.run(Check.tables(folder), mapping, today)

    @Test
    fun clientTableBecomesClientTrainingsAndResults() {
        client(
            "Смирнова Ольга Петровна",
            listOf(
                listOf(d(6), "10.00", "Проведено", "Кроль 4×25", "Проплыла 100", "Старалась"),
                listOf(d(8), 9.3, "Отменено"),
                listOf(d(13), "10:00", "Отмена со списанием"),
                // «10.05» Google превратил в дату 10 мая — это 10:05.
                listOf(d(15), d(10, 5, 2026), "Запланировано"),
                listOf(d(20), "", "Проведено"),
                listOf(null, null, "Запланировано"),
            ),
            results = listOf(
                listOf(d(6), "50 в/ст", "52.16", "тренировка"),
                listOf(d(13), "100", "1.03,17", "к/п"),
                listOf(d(14), "50 н/сп", "56,99 (50м бассейн)", "соревнования"),
                listOf(d(15), "50 брасс", "сошёл", ""),
            ),
        )
        salary(emptyList())
        val result = run()
        val olga = result.clients.single()
        assertEquals("Смирнова Ольга Петровна", olga.name.full)
        assertEquals(Sex.FEMALE, olga.sex)
        assertEquals(LocalDate(2017, 5, 12), olga.table?.birth)
        assertEquals("Боится глубины", olga.table?.note)
        assertEquals("ПТ" to "ББ", olga.typeShort to olga.placeShort)
        assertEquals(
            listOf(LocalTime(10, 0), LocalTime(9, 30), LocalTime(10, 0), LocalTime(10, 5)),
            olga.trainings.map { it.time },
        )
        assertEquals(
            listOf(ParticipationStatus.ATTENDED, ParticipationStatus.CANCELLED, ParticipationStatus.CANCELLED_CHARGED, ParticipationStatus.EXPECTED),
            olga.trainings.map { it.status.participation },
        )
        assertEquals(Triple("Кроль 4×25", "Проплыла 100", "Старалась"), olga.trainings[0].let { Triple(it.plan, it.fact, it.behavior) })
        assertEquals(listOf(5216, 6317, 5699), olga.results.map { it.time.cs })
        assertEquals(listOf(SwimStyle.FREESTYLE, SwimStyle.MEDLEY, SwimStyle.BACKSTROKE), olga.results.map { it.style })
        assertEquals(listOf(25, 25, 50), olga.results.map { it.time.poolM })
        assertEquals(ResultKind.COMPETITION, olga.results[2].kind)
        assertEquals(
            listOf(
                "Смирнова Ольга Петровна.xlsx · Тренировки · строка 6: пустое время",
                "Смирнова Ольга Петровна.xlsx · Результаты · строка 5: результат: не понять время «сошёл»",
            ),
            result.issues.problems,
        )
        assertEquals(mapOf("Смирнова Ольга" to "ПТ, ББ"), result.mapping.clients)
    }

    @Test
    fun infoSheetWithHeaderRowAndNameFromFile() {
        client("Ковалёв Артём", emptyList(), info = listOf(listOf("Дата рождения", "Фото"), listOf("03.02.2016", "https://example.com/a.jpg")))
        salary(emptyList())
        val artem = run().clients.single()
        assertEquals("Ковалёв Артём", artem.name.short)
        assertEquals(LocalDate(2016, 2, 3), artem.table?.birth)
        assertEquals("https://example.com/a.jpg", artem.table?.photo)
        assertEquals(Sex.MALE, artem.sex)
    }

    @Test
    fun groupsFromSalarySheetWithYearTypoAndMergedDates() {
        client("Дельфенурова Алиса", emptyList())
        salary(
            listOf(
                listOf(d(1), "17.00", "Д", "Ковалёв Артём, Дельфенурова Алиса"),
                listOf(null, "18.00", "Л+Д", "Ли Мин"),
                listOf(null, null, null, "Шевченко Ника"),
                listOf(d(8), "17.00", "Д", "Ковалёв Артём"),
                listOf(d(15, year = 2023), "17.00", "Д", "Делфенурова Алиса, Ковалёв Артём"),
                listOf(d(22), "17.00", "Д+Л", "Ковалёв Артём"),
                listOf(d(22), "19.00", "Д", "Артём"),
            ),
        )
        val first = run()
        assertEquals(
            listOf("Зарплата.xlsx · Октябрь группы · строка 6: год 2023 исправлен на 2026: 15.10.2026"),
            first.issues.fixed,
        )
        assertEquals(
            listOf(
                "Зарплата.xlsx · Октябрь группы · строка 8: ребёнок «Артём» — нужны фамилия и имя",
                "сопоставление.txt: похожие имена «Делфенурова Алиса» и «Дельфенурова Алиса» — решите в разделе «Похожие имена»",
            ),
            first.issues.problems,
        )
        assertEquals(listOf(MergeLine("Делфенурова Алиса", "Дельфенурова Алиса", Decision.ASK)), first.mapping.merges)

        val thursdayD = first.groups.first { it.slot == com.akenory.personalka.domain.importing.Slot(DayOfWeek.THURSDAY, LocalTime(17, 0)) && it.codeText == "Д" }
        assertEquals(3, thursdayD.trainings.size)
        val lena = first.groups.single { it.codeText == "Л+Д" }
        assertEquals(Owner.COLLEAGUE to ConductedBy.SELF_AND_COLLEAGUE, lena.code.owner to lena.code.conductedBy)
        assertEquals(listOf("Ли Мин", "Шевченко Ника"), lena.trainings.single().children.map { it.short })
        assertTrue(first.withColleague)

        // Тренер отвечает «да» — опечатка становится той же Алисой, проблема уходит.
        val second = run(Mapping.parse(Mapping.write(first.mapping).replace("= ?", "= да")))
        assertEquals(listOf("Зарплата.xlsx · Октябрь группы · строка 8: ребёнок «Артём» — нужны фамилия и имя"), second.issues.problems)
        val alisa = second.clients.single { it.name.short == "Дельфенурова Алиса" }
        assertEquals(2, alisa.groupVisits)
        assertEquals(null, second.clients.firstOrNull { it.name.short == "Делфенурова Алиса" })
        // Последний день — 22.10: окно с 25.09, 1.10 входит, поэтому Алиса в составе группы чт 17:00.
        val thursday = second.groups.single { it.slot.start == LocalTime(17, 0) && it.codeText == "Д" }
        assertEquals(listOf("Дельфенурова Алиса", "Ковалёв Артём"), thursday.members.map { it.short })
        assertEquals(setOf(null), second.clients.filter { it.name.lastName in setOf("Ли", "Шевченко") }.map { it.sex }.toSet())
    }

    @Test
    fun seriesFromRegularSlotAndCanBeDeclined() {
        client(
            "Смирнова Ольга",
            listOf(1, 8, 15, 22).map { listOf(d(it), "17.00", "Проведено") } + listOf(listOf(d(29), "17.00", "Запланировано")),
        )
        salary(emptyList())
        val first = run()
        val line = first.series.single()
        assertEquals(DayOfWeek.THURSDAY, line.found.slot.weekday)
        assertEquals(LocalDate(2026, 10, 30), line.found.from)
        assertEquals(4, line.found.times)
        assertTrue(line.confirmed)
        assertEquals(mapOf("Смирнова Ольга · чт 17:00" to Decision.YES), first.mapping.series)

        val declined = run(first.mapping.copy(series = mapOf("Смирнова Ольга · чт 17:00" to Decision.NO)))
        assertEquals(false, declined.series.single().confirmed)
    }

    @Test
    fun monthTotalsCountedFromDaysNotFromItogo() {
        salary(
            emptyList(),
            month = listOf(
                listOf("План", 120000),
                listOf("Дата", "ПТ", "Группы", "Заработано"),
            ) + (1..30).map { listOf(d(it), 2, 1, 3300) } + listOf(
                // В таблице «ИТОГО» суммирует дни только до 28-го.
                listOf("ИТОГО", 56, 28, 92400),
                listOf("Аванс", 30000),
            ),
        )
        val month = run().months.single()
        assertEquals(30, month.days)
        assertEquals(2026, month.year)
        assertEquals(
            listOf(ColumnTotal("ПТ", 60.0, 56.0), ColumnTotal("Группы", 30.0, 28.0), ColumnTotal("Заработано", 99000.0, 92400.0)),
            month.totals,
        )
        assertEquals(listOf("План" to "120000", "Аванс" to "30000"), month.values.map { it.first to it.second.replace(Regex("\\s"), "") })
    }

    @Test
    fun mappingKeepsCoachValuesAndChecksTypeAndPlace() {
        client("Смирнова Ольга", listOf(listOf(d(6), "10.00", "Проведено")))
        client("Ковалёв Артём", listOf(listOf(d(6), "11.00", "Проведено")))
        salary(emptyList())
        val mapping = Mapping(
            transitionMonth = "13.2026",
            clients = mapOf("смирнова ольга" to "Груд, ДБ", "Ковалёв Артём" to "ПТ, Луна"),
        )
        val result = run(mapping)
        assertEquals("Груд" to "ДБ", result.clients.single { it.name.firstName == "Ольга" }.let { it.typeShort to it.placeShort })
        assertEquals(
            listOf(
                "сопоставление.txt: «Ковалёв Артём = ПТ, Луна»: нужно «тип, место»; типы — ПТ, Сплит, Мини, Группа, СПТ, Деж, Груд, Гидро, Кинезио; места — ББ, ДБ, Зал",
                "сопоставление.txt: месяц перехода «13.2026» — нужно ММ.ГГГГ, например 12.2026",
            ),
            result.issues.problems,
        )
        val text = Mapping.write(result.mapping)
        assertTrue("Смирнова Ольга = Груд, ДБ" in text)
        assertTrue("месяц = 13.2026" in text)
        assertEquals(result.mapping, Mapping.parse(text))
    }

    @Test
    fun brokenAndUnknownFilesGoToReport() {
        File(folder, "битый.xlsx").writeText("не таблица")
        File(folder, "~\$Смирнова Ольга.xlsx").writeText("временный файл Excel")
        xlsx("Список покупок.xlsx", mapOf("Лист1" to listOf(listOf("Хлеб"))))
        client("Смирнова Ольга", listOf(listOf(d(6), "10.00", "Проведено")))
        salary(listOf(listOf(d(1), "17.00", "Д", "Ковалёв Артём")))
        val result = run()
        assertEquals(4, result.files.size)
        assertEquals(listOf("Список покупок.xlsx (листы: Лист1)"), result.unknownFiles)
        assertTrue(result.issues.problems.single().startsWith("битый.xlsx: не открыть файл"))

        val report = Report.text(result, folder.path, LocalDateTime(2026, 10, 23, 15, 40))
        assertTrue("Клиенты: 2 — с таблицей 1, только из групп 1" in report, report)
        assertTrue("Тренировки клиентов: 1 — проведено 1" in report, report)
        assertTrue("Группы: 1; тренировок групп 1, детей на них 1" in report, report)
        assertTrue("ПРОБЛЕМЫ: 1." in report, report)
        assertTrue("ПТ — 1" in report, report)
    }
}
