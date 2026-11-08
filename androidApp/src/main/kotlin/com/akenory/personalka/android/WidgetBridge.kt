package com.akenory.personalka.android

import android.content.Context
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.widget.WidgetCodec
import com.akenory.personalka.domain.widget.WidgetDay
import com.akenory.personalka.ui.widget.WidgetSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate

/**
 * Виджеты на экране телефона (шаг 5.9): приложение собирает [WidgetDay] — расписание на 3 дня и «Деньги» теми же
 * расчётами, что «Главная», — и кладёт текстом в настройки телефона ([KEY]). Виджеты рисуются из этого текста
 * ([Widgets]), сами решают, что сейчас идёт, и просят будильник на следующую смену. Пока процесс жив — после каждой
 * правки; по будильнику процесс поднимается и пересчитывает деньги ([refresh]).
 */
class WidgetBridge(
    private val context: Context,
    private val app: PersonalkaApplication,
    private val prefs: DevicePrefs,
) {
    private var started = false

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        val dates = flow {
            while (true) {
                emit(ClubTime.today(System.currentTimeMillis()))
                delay(60_000L)
            }
        }.distinctUntilChanged()
        scope.launch {
            dates.flatMapLatest { day(it) }.collect { save(it) }
        }
    }

    /** Будильник виджета: пересчитать день из базы (деньги после конца тренировки) и перерисовать. */
    suspend fun refresh() {
        val today = ClubTime.today(System.currentTimeMillis())
        val fresh = withTimeoutOrNull(TIMEOUT_MS) { day(today).first() }
        if (fresh != null) save(fresh) else Widgets.updateAll(context)
    }

    /** Последнее сохранённое для виджетов; нет или чужая версия — `null`. */
    fun stored(): WidgetDay? = prefs.string(KEY)?.let(WidgetCodec::decode)

    private fun save(day: WidgetDay) {
        val text = WidgetCodec.encode(day)
        if (prefs.string(KEY) != text) prefs.putString(KEY, text)
        Widgets.updateAll(context)
    }

    private fun day(today: LocalDate): Flow<WidgetDay> = WidgetSource.day(app.calendar, app.settings, today, app.watch.todo)

    companion object {
        const val KEY = "widget.day"
        private const val TIMEOUT_MS = 8_000L
    }
}
