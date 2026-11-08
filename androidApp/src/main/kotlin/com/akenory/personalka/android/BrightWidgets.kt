package com.akenory.personalka.android

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.akenory.personalka.R
import com.akenory.personalka.domain.widget.WidgetMood
import com.akenory.personalka.domain.widget.WidgetPhase
import com.akenory.personalka.domain.widget.WidgetRules
import com.akenory.personalka.domain.widget.WidgetView
import com.akenory.personalka.ui.reminders.AppLink
import kotlinx.datetime.LocalDate

/**
 * Яркие виджеты (шаг 5.9б): меньше цифр, крупная картинка и настроение. Рисуются из того же [WidgetView], что и
 * обычные ([Widgets]); картинка — бассейн, дорожка, кольцо, капли и полоска дней — рисуется в код картинкой под
 * размер виджета, а текст остаётся текстом (крупный шрифт телефона, экранный диктор).
 */
object BrightWidgets {
    private enum class Kind(val provider: Class<*>, val layout: Int) {
        POOL(BrightPool::class.java, R.layout.bright_pool),
        LANE(BrightLane::class.java, R.layout.bright_lane),
        TYULYA(BrightTyulya::class.java, R.layout.bright_tyulya),
        BONUS(BrightBonus::class.java, R.layout.bright_bonus),
        DAY(BrightDay::class.java, R.layout.bright_day),
        PAYDAY(BrightPayday::class.java, R.layout.bright_payday),
    }

    /** Перерисовать все яркие виджеты; вернуть, есть ли хоть один на экране. */
    fun updateAll(context: Context, manager: AppWidgetManager, view: WidgetView?, today: LocalDate): Boolean {
        var any = false
        Kind.entries.forEach { kind ->
            manager.getAppWidgetIds(ComponentName(context, kind.provider)).forEach { id ->
                any = true
                val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: DEFAULT_WIDTH
                val v = RemoteViews(context.packageName, kind.layout)
                v.setOnClickPendingIntent(R.id.bright_root, Widgets.open(context, AppLink.Home))
                when (kind) {
                    Kind.POOL -> pool(context, v, view)
                    Kind.LANE -> lane(context, v, view, width - 36f)
                    Kind.TYULYA -> tyulya(context, v, view)
                    Kind.BONUS -> bonus(context, v, view)
                    Kind.DAY -> day(context, v, view, width - 28f)
                    Kind.PAYDAY -> payday(context, v, view, today, width - 32f)
                }
                manager.updateAppWidget(id, v)
            }
        }
        return any
    }

    private fun pool(context: Context, v: RemoteViews, view: WidgetView?) {
        val m = view?.money
        if (m == null) {
            v.setTextViewText(R.id.bright_label, "")
            v.setTextViewText(R.id.bright_big, "")
            v.setTextViewText(R.id.bright_sub, if (view == null) WidgetRules.NO_DATA else WidgetRules.SALARY_NOT_SET)
            v.setViewVisibility(R.id.bright_chip, View.GONE)
            v.setImageViewBitmap(R.id.bright_image, poolImage(context, 0, false))
            return
        }
        v.setTextViewText(R.id.bright_label, m.monthName)
        v.setTextViewText(R.id.bright_big, m.month)
        v.setTextViewText(R.id.bright_sub, m.planPercent ?: WidgetRules.NO_PLAN)
        text(v, R.id.bright_chip, m.poolNote)
        v.setImageViewBitmap(R.id.bright_image, poolImage(context, m.done ?: 0, m.planDone))
    }

    private fun lane(context: Context, v: RemoteViews, view: WidgetView?, width: Float) {
        if (view == null) {
            v.setTextViewText(R.id.bright_label, "")
            v.setTextViewText(R.id.bright_right, "")
            v.setTextViewText(R.id.bright_big, WidgetRules.NO_DATA)
            v.setTextViewText(R.id.bright_sub, "")
            v.setImageViewBitmap(R.id.bright_image, laneImage(context, 0, width))
            return
        }
        v.setTextViewText(R.id.bright_label, WidgetRules.laneLabel(view.phase))
        v.setTextViewText(R.id.bright_right, view.right.orEmpty())
        v.setTextViewText(R.id.bright_big, view.name ?: WidgetRules.TODAY_DONE)
        v.setTextViewText(R.id.bright_sub, view.laneSub.orEmpty())
        v.setImageViewBitmap(R.id.bright_image, laneImage(context, WidgetRules.laneProgress(view), width))
        view.id?.let { v.setOnClickPendingIntent(R.id.bright_root, Widgets.open(context, AppLink.Card(it))) }
    }

    private fun tyulya(context: Context, v: RemoteViews, view: WidgetView?) {
        val m = view?.mascot
        if (m == null) {
            v.setImageViewResource(R.id.bright_image, R.drawable.tyulya_wave)
            v.setTextViewText(R.id.bright_big, "Привет!")
            v.setTextViewText(R.id.bright_sub, WidgetRules.NO_DATA)
            v.setViewVisibility(R.id.bright_chip, View.GONE)
            return
        }
        v.setImageViewResource(R.id.bright_image, if (m.mood == WidgetMood.SLEEP) R.drawable.tyulya_sleep else R.drawable.tyulya_wave)
        v.setTextViewText(R.id.bright_big, m.title)
        v.setTextViewText(R.id.bright_sub, m.line)
        text(v, R.id.bright_chip, m.pill)
        if (view.phase == WidgetPhase.NOW || view.phase == WidgetPhase.NEXT) {
            view.id?.let { v.setOnClickPendingIntent(R.id.bright_root, Widgets.open(context, AppLink.Card(it))) }
        }
    }

    private fun bonus(context: Context, v: RemoteViews, view: WidgetView?) {
        val m = view?.money
        val b = m?.let(WidgetRules::bonus)
        when {
            m == null -> {
                v.setTextViewText(R.id.bright_big, "")
                v.setTextViewText(R.id.bright_sub, if (view == null) WidgetRules.NO_DATA else WidgetRules.SALARY_NOT_SET)
                v.setImageViewBitmap(R.id.bright_image, ringImage(context, 0, 1))
            }
            b == null -> {
                v.setTextViewText(R.id.bright_big, WidgetRules.NO_TIERS)
                v.setTextViewText(R.id.bright_sub, m.ptTier)
                v.setImageViewBitmap(R.id.bright_image, ringImage(context, 0, 1))
            }
            else -> {
                v.setTextViewText(R.id.bright_big, b.second)
                v.setTextViewText(R.id.bright_sub, b.third)
                v.setImageViewBitmap(R.id.bright_image, ringImage(context, b.first, m.tier))
            }
        }
    }

    private fun day(context: Context, v: RemoteViews, view: WidgetView?, width: Float) {
        val m = view?.money
        v.setTextViewText(R.id.bright_label, WidgetRules.TODAY)
        v.setTextViewText(R.id.bright_big, m?.todaySigned ?: "")
        v.setTextViewText(R.id.bright_sub, m?.todayWill ?: if (view == null) WidgetRules.NO_DATA else WidgetRules.SALARY_NOT_SET)
        v.setTextViewText(R.id.bright_chip, view?.dayLine ?: WidgetRules.NO_TRAININGS_TODAY)
        v.setImageViewBitmap(R.id.bright_image, dropsImage(context, view?.todayDone ?: 0, view?.todayTotal ?: 0, width))
    }

    private fun payday(context: Context, v: RemoteViews, view: WidgetView?, today: LocalDate, width: Float) {
        val m = view?.money
        val date = m?.paydayDate
        v.setTextViewText(R.id.bright_label, WidgetRules.PAYDAY)
        if (m == null || date == null) {
            v.setTextViewText(R.id.bright_big, "")
            v.setTextViewText(R.id.bright_right, "")
            v.setTextViewText(
                R.id.bright_sub,
                when {
                    view == null -> WidgetRules.NO_DATA
                    m == null -> WidgetRules.SALARY_NOT_SET
                    else -> WidgetRules.NO_PAYDAY
                },
            )
            v.setViewVisibility(R.id.bright_image, View.GONE)
            return
        }
        val (big, unit) = WidgetRules.paydayDays(date, today)
        v.setTextViewText(R.id.bright_big, big)
        v.setViewVisibility(R.id.bright_big, if (big.isEmpty()) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.bright_right, unit)
        v.setTextViewText(R.id.bright_sub, WidgetRules.paydayLine(m).orEmpty())
        val bar = WidgetRules.paydayBar(m, today)
        if (bar == null) {
            v.setViewVisibility(R.id.bright_image, View.GONE)
        } else {
            v.setViewVisibility(R.id.bright_image, View.VISIBLE)
            v.setImageViewBitmap(R.id.bright_image, barsImage(context, bar.first, bar.second, width))
        }
    }

    private fun text(v: RemoteViews, id: Int, value: String?) {
        if (value == null) {
            v.setViewVisibility(id, View.GONE)
        } else {
            v.setViewVisibility(id, View.VISIBLE)
            v.setTextViewText(id, value)
        }
    }

    // --- Картинки: рисуются в dp, на холсте с масштабом плотности экрана. ---

    private fun image(context: Context, wDp: Float, hDp: Float, draw: Canvas.() -> Unit): Bitmap {
        val d = context.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap((wDp * d).toInt().coerceAtLeast(1), (hDp * d).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            scale(d, d)
            draw()
        }
        return bitmap
    }

    private fun paint(color: Int, stroke: Float? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (stroke != null) {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
    }

    private fun Canvas.seal(context: Context, cx: Float, cy: Float, scale: Float = 1f) {
        val d = ContextCompat.getDrawable(context, R.drawable.tyulya_swim) ?: return
        val w = 56f * scale
        val h = 30f * scale
        d.setBounds(0, 0, w.toInt(), h.toInt())
        save()
        translate(cx - w / 2, cy - h / 2)
        d.draw(this)
        restore()
    }

    private fun wave(left: Float, right: Float, top: Float, bottom: Float, amp: Float): Path = Path().apply {
        moveTo(left, top)
        var x = left
        var up = true
        while (x < right) {
            quadTo(x + 6.5f, top + if (up) -amp else amp, x + 13f, top)
            x += 13f
            up = !up
        }
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
    }

    /** «Бассейн плана»: вода до доли плана [done] (тысячные); план выполнен — вода у самого края. */
    private fun poolImage(context: Context, done: Int, planDone: Boolean): Bitmap = image(context, 104f, 134f) {
        val tank = RectF(2f, 2f, 102f, 132f)
        drawRoundRect(tank, 14f, 14f, paint(0x1AFFFFFF))
        val level = if (planDone) 10f else 132f - 110f * done.coerceIn(0, 1000) / 1000f
        save()
        clipPath(Path().apply { addRoundRect(tank, 14f, 14f, Path.Direction.CW) })
        drawPath(wave(0f, 104f, level, 134f, 3.5f), paint(0xFF4FC3F7.toInt()))
        drawPath(wave(0f, 104f, level + 6f, 134f, 3f), paint(0xFF29A8E8.toInt()))
        restore()
        drawRoundRect(tank, 14f, 14f, paint(0x99FFFFFF.toInt(), 2f))
        drawLine(2f, 22f, 102f, 22f, paint(0xFFFFFFFF.toInt(), 1.5f).apply { pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f) })
        drawText("план", 8f, 17f, paint(0xFFFFFFFF.toInt()).apply { textSize = 10f })
        seal(context, 52f, (level - 2f).coerceAtLeast(14f), 0.95f)
    }

    /** «Дорожка»: Тюля на доле [progress] (тысячные) пути от старта до финиша. */
    private fun laneImage(context: Context, progress: Int, width: Float): Bitmap {
        val w = width.coerceAtLeast(160f)
        return image(context, w, 40f) {
            drawRoundRect(RectF(0f, 4f, w, 36f), 10f, 10f, paint(0xFF26A69A.toInt()))
            val x = 30f + (w - 70f) * progress.coerceIn(0, 1000) / 1000f
            drawRoundRect(RectF(0f, 4f, x, 36f), 10f, 10f, paint(0x38FFFFFF))
            var bx = 8f
            var i = 0
            while (bx < w) {
                drawCircle(bx, 4f, 3f, paint(if (i % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFFFD54A.toInt()))
                bx += 12f
                i++
            }
            val wake = paint(0xB3FFFFFF.toInt(), 2f)
            drawLine(x - 50f, 16f, x - 36f, 16f, wake)
            drawLine(x - 58f, 22f, x - 40f, 22f, wake)
            drawLine(x - 46f, 28f, x - 36f, 28f, wake)
            drawText("финиш", w - 8f, 25f, paint(0xFFFFFFFF.toInt()).apply { textSize = 11f; textAlign = Paint.Align.RIGHT })
            seal(context, x, 21f, 0.95f)
        }
    }

    /** «Заряд бонуса»: кольцо на [part] тысячных и молния; со ступени 2 — зелёное. */
    private fun ringImage(context: Context, part: Int, tier: Int): Bitmap = image(context, 86f, 86f) {
        val color = if (tier >= 2) 0xFF7CE495.toInt() else 0xFFFFD54A.toInt()
        drawCircle(43f, 43f, 34f, paint(0x33FFFFFF, 10f))
        if (part > 0) drawArc(RectF(9f, 9f, 77f, 77f), -90f, 360f * part.coerceIn(0, 1000) / 1000f, false, paint(color, 10f))
        val bolt = Path().apply {
            moveTo(47f, 22f)
            lineTo(32f, 47f)
            lineTo(42f, 47f)
            lineTo(38f, 64f)
            lineTo(54f, 38f)
            lineTo(44f, 38f)
            close()
        }
        drawPath(bolt, paint(color))
    }

    /** «День»: капля на каждую тренировку дня, проведённые — жёлтые; больше 12 — в два ряда. */
    private fun dropsImage(context: Context, done: Int, total: Int, width: Float): Bitmap {
        val w = width.coerceAtLeast(80f)
        return image(context, w, 22f) {
            if (total <= 0) return@image
            val rows = if (total > 12) 2 else 1
            val perRow = (total + rows - 1) / rows
            val gap = 4f
            val cell = (w - gap * (perRow - 1)) / perRow
            val top0 = if (rows == 1) 6.5f else 0f
            for (i in 0 until total) {
                val r = i / perRow
                val c = i % perRow
                val left = c * (cell + gap)
                val top = top0 + r * 13f
                drawRoundRect(RectF(left, top, left + cell, top + 9f), 4.5f, 4.5f, paint(if (i < done) 0xFFFFD54A.toInt() else 0xFF3E9A65.toInt()))
            }
        }
    }

    /** «До получки»: день между выплатами — клетка; прошедшие бледные, оставшиеся жёлтые. */
    private fun barsImage(context: Context, total: Int, passed: Int, width: Float): Bitmap {
        val w = width.coerceAtLeast(80f)
        return image(context, w, 14f) {
            val n = total.coerceIn(1, 31)
            val gap = 2f
            val cell = (w - gap * (n - 1)) / n
            for (i in 0 until n) {
                val left = i * (cell + gap)
                drawRoundRect(RectF(left, 0f, left + cell, 14f), 3f, 3f, paint(if (i < passed) 0xFFF3B6D6.toInt() else 0xFFFFD54A.toInt()))
            }
        }
    }

    private const val DEFAULT_WIDTH = 160
}

class BrightPool : PersonalkaWidget()

class BrightLane : PersonalkaWidget()

class BrightTyulya : PersonalkaWidget()

class BrightBonus : PersonalkaWidget()

class BrightDay : PersonalkaWidget()

class BrightPayday : PersonalkaWidget()
