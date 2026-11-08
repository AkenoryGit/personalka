package com.akenory.personalka.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.akenory.personalka.R
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.widget.WidgetCodec
import com.akenory.personalka.domain.widget.WidgetMoney
import com.akenory.personalka.domain.widget.WidgetPhase
import com.akenory.personalka.domain.widget.WidgetRow
import com.akenory.personalka.domain.widget.WidgetRules
import com.akenory.personalka.domain.widget.WidgetView
import com.akenory.personalka.ui.reminders.AppLink
import com.akenory.personalka.ui.theme.PlaceColor

/**
 * Рисование виджетов (шаг 5.9) из текста, который положило приложение ([WidgetBridge]). Виджет не ходит в базу:
 * всё, что нужно, уже в тексте, а «сейчас» он считает сам. Нажатие на тренировку — её карточка, на остальное —
 * «Главная».
 */
object Widgets {
    private enum class Kind(val provider: Class<*>, val layout: Int) {
        LARGE(WidgetLarge::class.java, R.layout.widget_large),
        MEDIUM(WidgetMedium::class.java, R.layout.widget_medium),
        SMALL_NOW(WidgetSmallNow::class.java, R.layout.widget_small_now),
        SMALL_MONTH(WidgetSmallMonth::class.java, R.layout.widget_small_month),
    }

    /** Перерисовать все виджеты на экране и поставить будильник на следующую смену. */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val text = (context.applicationContext as PersonalkaApplication).prefs.string(WidgetBridge.KEY)
        val day = text?.let(WidgetCodec::decode)
        val now = ClubTime.now(System.currentTimeMillis())
        val views = mutableMapOf<Int, WidgetView?>()
        var any = false
        Kind.entries.forEach { kind ->
            manager.getAppWidgetIds(ComponentName(context, kind.provider)).forEach { id ->
                any = true
                // Большой виджет показывает столько следующих тренировок, сколько влезает по его высоте (шаг 5.9а).
                val rows = if (kind == Kind.LARGE) rowsFor(manager.getAppWidgetOptions(id)) else WidgetRules.NEXT_ROWS
                val view = views.getOrPut(rows) { day?.let { WidgetRules.view(it, now, rows) } }
                val remote = RemoteViews(context.packageName, kind.layout)
                render(context, kind, remote, view, rows)
                manager.updateAppWidget(id, remote)
            }
        }
        // Яркие виджеты (шаг 5.9б) — из того же расписания и денег.
        if (BrightWidgets.updateAll(context, manager, day?.let { WidgetRules.view(it, now) }, now.date)) any = true
        if (any && day != null) schedule(context, ClubTime.toMillis(WidgetRules.nextUpdate(day, now)))
    }

    /**
     * Строк под «Сейчас» по высоте виджета: «Деньги», черта, «Сейчас» и «ещё …» занимают около [BASE_DP], каждая
     * строка — [ROW_DP]. Высота неизвестна — как у наименьшего.
     */
    private fun rowsFor(options: Bundle): Int {
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (height <= 0) return WidgetRules.NEXT_ROWS
        return ((height - BASE_DP) / ROW_DP).coerceIn(1, WidgetRules.MAX_ROWS)
    }

    private val ROWS = listOf(
        intArrayOf(R.id.next1, R.id.next1_time, R.id.next1_stripe, R.id.next1_name, R.id.next1_sub),
        intArrayOf(R.id.next2, R.id.next2_time, R.id.next2_stripe, R.id.next2_name, R.id.next2_sub),
        intArrayOf(R.id.next3, R.id.next3_time, R.id.next3_stripe, R.id.next3_name, R.id.next3_sub),
        intArrayOf(R.id.next4, R.id.next4_time, R.id.next4_stripe, R.id.next4_name, R.id.next4_sub),
        intArrayOf(R.id.next5, R.id.next5_time, R.id.next5_stripe, R.id.next5_name, R.id.next5_sub),
        intArrayOf(R.id.next6, R.id.next6_time, R.id.next6_stripe, R.id.next6_name, R.id.next6_sub),
        intArrayOf(R.id.next7, R.id.next7_time, R.id.next7_stripe, R.id.next7_name, R.id.next7_sub),
        intArrayOf(R.id.next8, R.id.next8_time, R.id.next8_stripe, R.id.next8_name, R.id.next8_sub),
    )

    private fun render(context: Context, kind: Kind, v: RemoteViews, view: WidgetView?, rows: Int) {
        v.setOnClickPendingIntent(R.id.widget_root, open(context, AppLink.Home))
        when (kind) {
            Kind.LARGE -> {
                money(v, view?.money, withChip = true)
                block(context, v, view, rows)
            }
            Kind.MEDIUM -> money(v, view?.money, withChip = false)
            Kind.SMALL_NOW -> smallNow(context, v, view)
            Kind.SMALL_MONTH -> smallMonth(v, view?.money)
        }
    }

    private fun money(v: RemoteViews, m: WidgetMoney?, withChip: Boolean) {
        if (m == null) {
            v.setViewVisibility(R.id.money, View.GONE)
            v.setViewVisibility(R.id.money_unset, View.VISIBLE)
            v.setTextViewText(R.id.money_unset, WidgetRules.SALARY_NOT_SET)
            return
        }
        v.setViewVisibility(R.id.money, View.VISIBLE)
        v.setViewVisibility(R.id.money_unset, View.GONE)
        v.setTextViewText(R.id.money_title, m.title)
        if (withChip) {
            text(v, R.id.money_chip, m.tierChip)
        } else {
            // Средний: справа вместо плашки ступени — короткая оценка, строк под полосой нет.
            v.setViewVisibility(R.id.money_chip, View.GONE)
            text(v, R.id.money_right, m.paceShort)
            v.setViewVisibility(R.id.money_right_dot, if (m.paceShort != null) View.VISIBLE else View.GONE)
            v.setImageViewResource(R.id.money_right_dot, if (m.behind) R.drawable.widget_dot_charged else R.drawable.widget_dot_done)
        }
        v.setTextViewText(R.id.today_amount, m.today)
        v.setTextViewText(R.id.today_will, m.todayWill)
        v.setTextViewText(R.id.today_pt, m.todayPt)
        v.setTextViewText(R.id.month_amount, m.month)
        v.setTextViewText(R.id.month_will, m.monthWill)
        v.setTextViewText(R.id.month_pt, m.monthPt)
        if (withChip) {
            text(v, R.id.today_cancel, m.todayCancelled)
            text(v, R.id.month_cancel, m.monthCancelled)
        }
        bar(v, m)
        pace(v, if (withChip) m.pace else null, m.behind)
    }

    private fun bar(v: RemoteViews, m: WidgetMoney) {
        val done = m.done
        val forecast = m.forecast
        if (done == null || forecast == null) {
            v.setViewVisibility(R.id.plan_bar, View.GONE)
            return
        }
        v.setViewVisibility(R.id.plan_bar, View.VISIBLE)
        v.setProgressBar(R.id.bar_forecast, 1000, forecast, false)
        val bonus = m.tier > 1
        v.setViewVisibility(R.id.bar_done, if (bonus) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.bar_bonus, if (bonus) View.VISIBLE else View.GONE)
        v.setProgressBar(if (bonus) R.id.bar_bonus else R.id.bar_done, 1000, done, false)
    }

    private fun pace(v: RemoteViews, text: String?, behind: Boolean) {
        if (text == null) {
            v.setViewVisibility(R.id.pace_row, View.GONE)
            return
        }
        v.setViewVisibility(R.id.pace_row, View.VISIBLE)
        v.setTextViewText(R.id.pace_text, text)
        v.setViewVisibility(R.id.pace_dot_ok, if (behind) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.pace_dot_behind, if (behind) View.VISIBLE else View.GONE)
    }

    private fun block(context: Context, v: RemoteViews, view: WidgetView?, rows: Int) {
        if (view == null) {
            v.setTextViewText(R.id.now_header, WidgetRules.NO_DATA)
            v.setTextViewText(R.id.now_right, "")
            v.setViewVisibility(R.id.now_row, View.GONE)
            (ROWS.map { it[0] } + listOf(R.id.more, R.id.todo_row)).forEach { v.setViewVisibility(it, View.GONE) }
            return
        }
        v.setTextViewText(R.id.now_header, view.header)
        v.setTextViewText(R.id.now_right, view.right.orEmpty())
        val id = view.id
        if (id == null) {
            v.setViewVisibility(R.id.now_row, View.GONE)
        } else {
            v.setViewVisibility(R.id.now_row, View.VISIBLE)
            v.setTextViewText(R.id.now_name, view.name)
            v.setTextViewText(R.id.now_sub, view.typePlace)
            stripe(context, v, R.id.now_stripe, view.color)
            v.setOnClickPendingIntent(R.id.now_row, open(context, AppLink.Card(id)))
        }
        ROWS.forEachIndexed { i, ids -> row(context, v, view.next.getOrNull(i), ids[0], ids[1], ids[2], ids[3], ids[4]) }
        text(v, R.id.more, view.more)
        // «Нужно сделать» — когда под «Ближайшей» есть место: вечером и днём без следующих.
        val todo = view.todo
        if (todo != null && view.next.size < rows) {
            v.setViewVisibility(R.id.todo_row, View.VISIBLE)
            v.setTextViewText(R.id.todo_count, todo.toString())
            v.setOnClickPendingIntent(R.id.todo_row, open(context, AppLink.Home))
        } else {
            v.setViewVisibility(R.id.todo_row, View.GONE)
        }
    }

    private fun row(context: Context, v: RemoteViews, r: WidgetRow?, box: Int, time: Int, stripe: Int, name: Int, sub: Int) {
        if (r == null) {
            v.setViewVisibility(box, View.GONE)
            return
        }
        v.setViewVisibility(box, View.VISIBLE)
        v.setTextViewText(time, r.time)
        v.setTextViewText(name, r.name)
        v.setTextViewText(sub, r.typePlace)
        stripe(context, v, stripe, r.color)
        v.setOnClickPendingIntent(box, open(context, AppLink.Card(r.id)))
    }

    private fun smallNow(context: Context, v: RemoteViews, view: WidgetView?) {
        val id = view?.id
        if (view == null || id == null) {
            v.setTextViewText(R.id.small_label, view?.label ?: WidgetRules.NO_DATA)
            v.setTextViewText(R.id.now_name, "")
            v.setTextViewText(R.id.now_sub, "")
            v.setTextViewText(R.id.small_when, "")
            v.setTextViewText(R.id.small_count, "")
            v.setViewVisibility(R.id.small_progress, View.GONE)
            return
        }
        v.setTextViewText(R.id.small_label, view.label)
        v.setTextViewText(R.id.now_name, view.name)
        v.setTextViewText(R.id.now_sub, view.typePlace)
        v.setTextViewText(R.id.small_when, view.whenText.orEmpty())
        v.setTextViewText(R.id.small_count, view.count.orEmpty())
        stripe(context, v, R.id.now_stripe, view.color)
        val progress = view.progress
        if (view.phase == WidgetPhase.NOW && progress != null) {
            v.setViewVisibility(R.id.small_progress, View.VISIBLE)
            v.setProgressBar(R.id.small_progress, 1000, progress, false)
        } else {
            v.setViewVisibility(R.id.small_progress, View.GONE)
        }
        v.setOnClickPendingIntent(R.id.widget_root, open(context, AppLink.Card(id)))
    }

    private fun smallMonth(v: RemoteViews, m: WidgetMoney?) {
        if (m == null) {
            v.setTextViewText(R.id.month_name, "")
            v.setTextViewText(R.id.month_amount, "")
            v.setTextViewText(R.id.month_will, WidgetRules.SALARY_NOT_SET)
            v.setViewVisibility(R.id.plan_bar, View.GONE)
            v.setViewVisibility(R.id.pace_row, View.GONE)
            v.setTextViewText(R.id.month_pt, "")
            return
        }
        v.setTextViewText(R.id.month_name, m.monthName)
        v.setTextViewText(R.id.month_amount, m.month)
        v.setTextViewText(R.id.month_will, m.monthWill)
        v.setTextViewText(R.id.month_pt, m.ptTier)
        bar(v, m)
        pace(v, m.paceShort, m.behind)
    }

    private fun text(v: RemoteViews, id: Int, value: String?) {
        if (value == null) {
            v.setViewVisibility(id, View.GONE)
        } else {
            v.setViewVisibility(id, View.VISIBLE)
            v.setTextViewText(id, value)
        }
    }

    /** Полоска цвета места, как в расписании «Главной»; с Android 12 — свой тон в светлой и тёмной теме сам. */
    private fun stripe(context: Context, v: RemoteViews, id: Int, key: String?) {
        val color = PlaceColor.of(key)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setColorInt(id, "setBackgroundColor", color.tone(false).stripe.toArgb(), color.tone(true).stripe.toArgb())
        } else {
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            v.setInt(id, "setBackgroundColor", color.tone(dark).stripe.toArgb())
        }
    }

    fun open(context: Context, link: AppLink): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_BASE + link.code.hashCode(),
        Intent(context, MainActivity::class.java)
            .putExtra(AndroidReminders.EXTRA_LINK, link.code)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Будильник на следующую смену: неточный, без особого разрешения — опоздание на минуту виджету не мешает. */
    private fun schedule(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            TICK_REQUEST,
            Intent(context, WidgetTick::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.setAndAllowWhileIdle(AlarmManager.RTC, at, intent)
    }

    private const val BASE_DP = 310
    private const val ROW_DP = 27
    private const val TICK_REQUEST = 5_900
    private const val REQUEST_BASE = 59_000
}

/** Будильник виджетов: поднять процесс, пересчитать деньги из базы и перерисовать. */
class WidgetTick : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as PersonalkaApplication
        val pending = goAsync()
        app.refreshWidgets { pending.finish() }
    }
}

/** Общее для всех виджетов: система просит обновить — рисуем из сохранённого и пересчитываем. */
abstract class PersonalkaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Widgets.updateAll(context)
        val pending = goAsync()
        (context.applicationContext as PersonalkaApplication).refreshWidgets { pending.finish() }
    }

    /** Размер виджета поменяли — у большого меняется число строк. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        Widgets.updateAll(context)
    }
}

class WidgetLarge : PersonalkaWidget()

class WidgetMedium : PersonalkaWidget()

class WidgetSmallNow : PersonalkaWidget()

class WidgetSmallMonth : PersonalkaWidget()
