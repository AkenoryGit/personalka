package com.akenory.personalka.ui.ios

import androidx.compose.ui.graphics.toArgb
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.widget.WidgetDay
import com.akenory.personalka.domain.widget.WidgetMood
import com.akenory.personalka.domain.widget.WidgetRules
import com.akenory.personalka.domain.widget.WidgetView
import com.akenory.personalka.ui.shell.systemMillis
import com.akenory.personalka.ui.theme.PlaceColor
import com.akenory.personalka.ui.widget.WidgetSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import platform.Foundation.NSUserDefaults

/**
 * Виджеты iPhone (шаг 5.10). iPhone не будит приложение по будильнику, поэтому приложение заранее готовит кадры
 * на сегодня и два следующих дня ([WidgetRules.timeline]): в каждом — то, что виджет покажет с этой минуты, готовыми
 * строками ([WidgetRules.view], те же правила, что на Android). Кадры — JSON в общей папке приложения и виджета
 * (App Group [GROUP]), после записи Swift просит систему перерисовать виджеты ([reload]). Виджет на Swift ничего
 * не считает — только выбирает кадр по времени и рисует.
 */
object IosWidgets {
    const val GROUP = "group.com.akenory.personalka"
    const val KEY = "widget.timeline"

    /** Большой виджет iPhone одного размера: под «Сейчас» помещаются три тренировки, остальное — «ещё N». */
    private const val IPHONE_ROWS = 3

    /** Swift: `WidgetCenter.shared.reloadAllTimelines()` — WidgetKit из Kotlin не виден. */
    var reload: (() -> Unit)? = null

    private var started = false
    private var last: String? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        val dates = flow {
            while (true) {
                emit(ClubTime.today(systemMillis()))
                delay(60_000L)
            }
        }.distinctUntilChanged()
        scope.launch {
            // Счётчика дел на iPhone в виджете нет: его считает экран «Главной», а часов на iPhone нет.
            dates.flatMapLatest { WidgetSource.day(IosApp.calendar, IosApp.settings, it, flowOf(0)) }.collect { write(it) }
        }
    }

    private var lastDay: WidgetDay? = null

    /** Приложение снова на экране: кадры с этой минуты заново — прошедшие уже не нужны. */
    fun refresh() {
        lastDay?.let {
            last = null
            write(it)
        }
    }

    private fun write(day: WidgetDay) {
        lastDay = day
        val now = ClubTime.now(systemMillis())
        val text = json(day, now)
        if (text == last) return
        last = text
        NSUserDefaults(suiteName = GROUP).setObject(text, forKey = KEY)
        reload?.invoke()
    }

    // --- JSON: имена полей читает iosApp/PersonalkaWidgets/Model.swift. ---

    private fun json(day: WidgetDay, now: LocalDateTime): String = obj {
        field("v", 1)
        val m = day.money
        if (m != null) {
            raw(
                "money",
                obj {
                    field("title", m.title); field("monthName", m.monthName); field("tierChip", m.tierChip); field("tier", m.tier)
                    field("today", m.today); field("todayWill", m.todayWill); field("todayPt", m.todayPt); field("todayCancelled", m.todayCancelled)
                    field("month", m.month); field("monthWill", m.monthWill); field("monthPt", m.monthPt); field("monthCancelled", m.monthCancelled)
                    field("done", m.done); field("forecast", m.forecast); field("pace", m.pace); field("paceShort", m.paceShort)
                    field("behind", m.behind); field("ptTier", m.ptTier); field("todaySigned", m.todaySigned)
                    field("planPercent", m.planPercent); field("poolNote", m.poolNote); field("planDone", m.planDone)
                    WidgetRules.bonus(m)?.let { (part, big, small) -> field("bonusPart", part); field("bonusBig", big); field("bonusSmall", small) }
                },
            )
        }
        raw("entries", WidgetRules.timeline(day, now).joinToString(",", "[", "]") { t -> entry(day, t) })
    }

    private fun entry(day: WidgetDay, t: LocalDateTime): String {
        val v = WidgetRules.view(day, t, IPHONE_ROWS)
        val s = v.id?.let { id -> day.slots.firstOrNull { it.slot.id == id }?.slot }
        return obj {
            field("t", ClubTime.toMillis(t) / 1000)
            field("phase", v.phase.name)
            field("header", v.header); field("right", v.right); field("label", v.label)
            field("id", v.id); field("name", v.name); field("typePlace", v.typePlace)
            v.color?.let { colors(it) }
            field("whenText", v.whenText); field("progress", v.progress)
            s?.let { field("start", ClubTime.toMillis(it.start) / 1000); field("end", ClubTime.toMillis(it.end) / 1000) }
            raw(
                "next",
                v.next.joinToString(",", "[", "]") { r ->
                    obj { field("id", r.id); field("time", r.time); field("name", r.name); field("typePlace", r.typePlace); colors(r.color) }
                },
            )
            field("more", v.more); field("count", v.count)
            field("todayTotal", v.todayTotal); field("todayDone", v.todayDone)
            field("laneLabel", WidgetRules.laneLabel(v.phase)); field("lane", WidgetRules.laneProgress(v))
            field("laneSub", v.laneSub); field("dayLine", v.dayLine)
            field("moneyToday", v.money != null)
            v.mascot?.let { m ->
                raw("mascot", obj { field("sleep", m.mood == WidgetMood.SLEEP); field("title", m.title); field("line", m.line); field("pill", m.pill) })
            }
            payday(v, t)
        }
    }

    private fun Obj.payday(v: WidgetView, t: LocalDateTime) {
        val m = v.money ?: return
        val date = m.paydayDate ?: return
        val (big, unit) = WidgetRules.paydayDays(date, t.date)
        field("payBig", big); field("payUnit", unit); field("payLine", WidgetRules.paydayLine(m))
        WidgetRules.paydayBar(m, t.date)?.let { (total, passed) -> field("payTotal", total); field("payPassed", passed) }
    }

    /** Полоска места в светлой и тёмной теме — как в расписании «Главной». */
    private fun Obj.colors(key: String) {
        val c = PlaceColor.of(key)
        field("light", hex(c.tone(false).stripe.toArgb()))
        field("dark", hex(c.tone(true).stripe.toArgb()))
    }

    private fun hex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

    private class Obj {
        val parts = mutableListOf<String>()

        fun field(name: String, value: String?) {
            if (value != null) parts += quote(name) + ":" + quote(value)
        }

        fun field(name: String, value: Int?) {
            if (value != null) parts += quote(name) + ":" + value
        }

        fun field(name: String, value: Long) {
            parts += quote(name) + ":" + value
        }

        fun field(name: String, value: Boolean) {
            parts += quote(name) + ":" + value
        }

        fun raw(name: String, json: String) {
            parts += quote(name) + ":" + json
        }
    }

    private fun obj(build: Obj.() -> Unit): String = Obj().apply(build).parts.joinToString(",", "{", "}")
}

/** Строка JSON в кавычках. */
private fun quote(s: String): String = buildString {
    append('"')
    s.forEach { ch ->
        when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch.code < 0x20 -> append("\\u" + ch.code.toString(16).padStart(4, '0'))
            else -> append(ch)
        }
    }
    append('"')
}
