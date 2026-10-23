package com.akenory.personalka.importer

import com.akenory.personalka.domain.ClubTime
import kotlinx.datetime.toLocalDateTime
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Clock

/**
 * Утилита импорта (ТЗ 9.4): `java -jar personalka-import.jar <папка с xlsx>`.
 * Шаг 1.17 — режим проверки: читает все таблицы папки, пишет рядом «отчёт-импорта.txt» и «сопоставление.txt».
 * Сборка файла копии — шаг 1.18.
 */
fun main(args: Array<String>) {
    // Apache POI пишет в журнал через Log4j; без настройки он ругается в консоль на каждом запуске.
    System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    System.setProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF")
    System.setProperty("log4j2.simplelogLevel", "ERROR")

    val folder = File(args.firstOrNull() ?: ".").absoluteFile
    if (!folder.isDirectory) {
        println("Нет папки $folder. Запуск: java -jar personalka-import.jar <папка с таблицами xlsx>")
        exitProcess(2)
    }
    val files = Check.tables(folder)
    if (files.isEmpty()) {
        println("В папке $folder нет файлов xlsx. Выгрузите Google-таблицы: Файл → Скачать → Microsoft Excel (.xlsx).")
        exitProcess(2)
    }
    val mappingFile = File(folder, Mapping.FILE_NAME)
    val mapping = if (mappingFile.exists()) Mapping.parse(mappingFile.readText()) else Mapping()
    val now = Clock.System.now().toLocalDateTime(ClubTime.defaultZone)

    println("Читаю ${files.size} файлов из $folder …")
    val result = Check.run(files, mapping, now.date)
    mappingFile.writeText(Mapping.write(result.mapping))
    val reportFile = File(folder, Report.FILE_NAME)
    val report = Report.text(result, folder.path, now)
    reportFile.writeText(report)

    println(report.substringBefore("\nПРОБЛЕМЫ").substringBefore("\nПРОБЛЕМ НЕТ").trimEnd())
    println()
    println(if (result.issues.problems.isEmpty()) "Проблем нет." else "Проблем: ${result.issues.problems.size}.")
    println("Отчёт: $reportFile")
    println("Сопоставление: $mappingFile")
}
