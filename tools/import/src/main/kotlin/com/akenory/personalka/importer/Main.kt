package com.akenory.personalka.importer

import com.akenory.personalka.domain.ClubTime
import kotlinx.datetime.toLocalDateTime
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JTextArea
import kotlin.system.exitProcess
import kotlin.time.Clock

/**
 * Утилита импорта (ТЗ 9.4): `java -jar personalka-import.jar <папка с xlsx>`.
 * Двойной щелчок по jar (без папки в команде) — окно выбора папки и итог в окне, без командной строки.
 * Шаг 1.17 — режим проверки: читает все таблицы папки, пишет рядом «отчёт-импорта.txt» и «сопоставление.txt».
 * Сборка файла копии — шаг 1.18.
 */
fun main(args: Array<String>) {
    // Apache POI пишет в журнал через Log4j; без настройки он ругается в консоль на каждом запуске.
    System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    System.setProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF")
    System.setProperty("log4j2.simplelogLevel", "ERROR")

    val windows = args.isEmpty() && !GraphicsEnvironment.isHeadless()
    val say: (String) -> Unit = if (windows) Dialogs::message else ::println
    val folder = if (windows) {
        Dialogs.chooseFolder() ?: exitProcess(0)
    } else {
        File(args.firstOrNull() ?: ".").absoluteFile
    }
    try {
        say(check(folder))
    } catch (e: Throwable) {
        say("Утилита остановилась с ошибкой: ${e.javaClass.simpleName}: ${e.message}\nПришлите этот текст в чат шага.")
        if (!windows) exitProcess(1)
    }
    if (windows) exitProcess(0)
}

/** Проверка папки: итог для человека или что не так с папкой. */
private fun check(folder: File): String {
    if (!folder.isDirectory) return "Нет папки $folder. Запуск: java -jar personalka-import.jar <папка с таблицами xlsx>"
    val files = Check.tables(folder)
    if (files.isEmpty()) {
        return "В папке $folder нет файлов xlsx. Выгрузите Google-таблицы: Файл → Скачать → Microsoft Excel (.xlsx)."
    }
    val mappingFile = File(folder, Mapping.FILE_NAME)
    val mapping = if (mappingFile.exists()) Mapping.parse(mappingFile.readText()) else Mapping()
    val now = Clock.System.now().toLocalDateTime(ClubTime.defaultZone)

    val result = Check.run(files, mapping, now.date)
    mappingFile.writeText(Mapping.write(result.mapping))
    val reportFile = File(folder, Report.FILE_NAME)
    val report = Report.text(result, folder.path, now)
    reportFile.writeText(report)

    return buildString {
        appendLine(report.substringBefore("\nПРОБЛЕМЫ").substringBefore("\nПРОБЛЕМ НЕТ").trimEnd())
        appendLine()
        appendLine(if (result.issues.problems.isEmpty()) "Проблем нет." else "Проблем: ${result.issues.problems.size} — список в отчёте.")
        appendLine("Отчёт: $reportFile")
        append("Сопоставление: $mappingFile")
    }
}

/** Окна для запуска двойным щелчком: на Mac — системный выбор папки, на Windows и Linux — окно Java. */
private object Dialogs {
    private val mac = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")
    private const val TITLE = "Импорт таблиц «Персоналки»"

    fun chooseFolder(): File? {
        if (mac) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            val dialog = FileDialog(null as Frame?, "Папка с таблицами xlsx", FileDialog.LOAD)
            dialog.isVisible = true
            return dialog.file?.let { File(dialog.directory, it) }
        }
        val chooser = JFileChooser().apply {
            dialogTitle = "Папка с таблицами xlsx"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    fun message(text: String) {
        val area = JTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            columns = 70
            rows = minOf(24, text.lines().size + 1)
        }
        JOptionPane.showMessageDialog(null, JScrollPane(area), TITLE, JOptionPane.INFORMATION_MESSAGE)
    }
}
