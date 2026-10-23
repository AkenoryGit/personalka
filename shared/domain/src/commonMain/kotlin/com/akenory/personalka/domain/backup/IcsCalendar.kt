package com.akenory.personalka.domain.backup

import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.training.TrainingTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Тренировка в выгрузке: [start] — по часам клуба, [title] — «ПТ · Орлов М.», [place] — «ББ». */
data class IcsEvent(
    val id: String,
    val start: LocalDateTime,
    val durationMin: Int,
    val title: String,
    val place: String,
    val cancelled: Boolean = false,
)

/**
 * Календарь .ics (RFC 5545) для просмотра в любом календаре (7.6). Время — в UTC: календарь телефона сам
 * покажет его в своём поясе, и тренировка в 15:00 по Тюмени не съедет.
 */
object IcsCalendar {
    fun build(events: List<IcsEvent>, nowMillis: Long): String {
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Personalka//RU", "CALSCALE:GREGORIAN", "X-WR-CALNAME:Персоналка")
        val stamp = utc(nowMillis)
        events.sortedBy { it.start }.forEach { e ->
            lines += "BEGIN:VEVENT"
            lines += "UID:${e.id}@personalka"
            lines += "DTSTAMP:$stamp"
            lines += "DTSTART:${utc(ClubTime.toMillis(e.start))}"
            lines += "DTEND:${utc(ClubTime.toMillis(TrainingTime.end(e.start, e.durationMin)))}"
            lines += "SUMMARY:${escape(e.title)}"
            if (e.place.isNotBlank()) lines += "LOCATION:${escape(e.place)}"
            if (e.cancelled) lines += "STATUS:CANCELLED"
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("") { fold(it) + "\r\n" }
    }

    /** Запятая, точка с запятой, обратная косая черта и перевод строки — с экранированием. */
    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    /** Строки длиннее 75 байт переносятся: продолжение — с пробела. Кириллица — по 2 байта, буквы не режутся. */
    private fun fold(line: String): String {
        val out = StringBuilder()
        var bytes = 0
        line.forEach { c ->
            val size = c.toString().encodeToByteArray().size
            if (bytes + size > 75) {
                out.append("\r\n ")
                bytes = 1
            }
            out.append(c)
            bytes += size
        }
        return out.toString()
    }

    @OptIn(ExperimentalTime::class)
    private fun utc(millis: Long): String {
        val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${t.year}${two(t.month.ordinal + 1)}${two(t.day)}T${two(t.hour)}${two(t.minute)}${two(t.second)}Z"
    }
}
