package com.akenory.personalka.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.format.DateFormat
import com.akenory.personalka.domain.format.WatchFormat
import com.akenory.personalka.domain.watch.WatchDay
import com.akenory.personalka.domain.watch.WatchRules
import com.akenory.personalka.domain.watch.WatchSlot
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import java.time.Instant
import java.util.concurrent.TimeUnit

private fun text(s: String): ComplicationText = PlainComplicationText.Builder(s).build()

/** Отсчёт до начала считают сами часы: «12 мин» без обновлений с телефона. */
private fun countdown(start: LocalDateTime): ComplicationText =
    TimeDifferenceComplicationText.Builder(
        TimeDifferenceStyle.SHORT_SINGLE_UNIT,
        CountDownTimeReference(Instant.ofEpochMilli(ClubTime.toMillis(start))),
    ).setMinimumTimeUnit(TimeUnit.MINUTES).build()

/** Ячейки читают одно и то же: расписание с телефона и «сейчас» по времени клуба. */
abstract class WatchComplicationService : SuspendingComplicationDataSourceService() {
    final override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val day = WatchStore.fetch(this) ?: return NoDataComplicationData()
        return data(request.complicationType, day, WatchStore.now()) ?: NoDataComplicationData()
    }

    abstract fun data(type: ComplicationType, day: WatchDay, now: LocalDateTime): ComplicationData?

    /** Пример для списка ячеек — придуманные данные. */
    final override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val today = LocalDate(2026, 10, 20)
        val slot = WatchSlot("preview", LocalDateTime(today.year, today.month, today.day, 16, 0), 50, "ПТ", "ББ", "Никитин А.", group = false, marked = false)
        val day = WatchDay(today, listOf(slot), earned = 678_000, ahead = 581_500, percent = 60, forecastPercent = 97, todo = 3)
        return data(type, day, LocalDateTime(today.year, today.month, today.day, 15, 48))
    }
}

/** «Следующая тренировка»: короткая — «16:00» и «ПТ», длинная — «16:00 ПТ · Никитин А. · ББ» и отсчёт. */
class NextComplicationService : WatchComplicationService() {
    override fun data(type: ComplicationType, day: WatchDay, now: LocalDateTime): ComplicationData? {
        val slot = WatchRules.nearest(day, now) ?: return null
        val description = text(WatchFormat.long(slot))
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(DateFormat.time(slot.start.time)), description)
                .setTitle(text(slot.typeShort))
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(WatchFormat.long(slot)), description)
                .apply { if (now < slot.start) setTitle(countdown(slot.start)) }
                .build()
            else -> null
        }
    }
}

/** «Деньги сегодня»: кольцо «заработано из того, что будет к вечеру», в центре — к вечеру без «₽». */
class MoneyComplicationService : WatchComplicationService() {
    override fun data(type: ComplicationType, day: WatchDay, now: LocalDateTime): ComplicationData? {
        val money = WatchRules.money(day, now.date) ?: return null
        val description = text(WatchFormat.money(money.earned, money.evening))
        val value = text(WatchFormat.moneyShort(money.evening))
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                money.earned.toFloat(),
                0f,
                money.evening.coerceAtLeast(1).toFloat(),
                description,
            ).setText(value).setTitle(text(WatchFormat.EVENING)).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(value, description).setTitle(text(WatchFormat.EVENING)).build()
            else -> null
        }
    }
}

/** «План месяца»: «60 %», кольцо до 100 %. */
class PlanComplicationService : WatchComplicationService() {
    override fun data(type: ComplicationType, day: WatchDay, now: LocalDateTime): ComplicationData? {
        val percent = WatchRules.percent(day, now.date) ?: return null
        val value = text(WatchFormat.percent(percent))
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(percent.coerceIn(0, 100).toFloat(), 0f, 100f, value)
                .setText(value).setTitle(text(WatchFormat.PLAN)).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(value, value).setTitle(text(WatchFormat.PLAN)).build()
            else -> null
        }
    }
}

/** «Нужно сделать»: число дел «Главной». */
class TodoComplicationService : WatchComplicationService() {
    override fun data(type: ComplicationType, day: WatchDay, now: LocalDateTime): ComplicationData? {
        val todo = WatchRules.todo(day, now.date) ?: return null
        if (type != ComplicationType.SHORT_TEXT) return null
        return ShortTextComplicationData.Builder(text(WatchFormat.todo(todo)), text(WatchFormat.todoDescription(todo)))
            .setTitle(text(WatchFormat.TODO))
            .build()
    }
}
