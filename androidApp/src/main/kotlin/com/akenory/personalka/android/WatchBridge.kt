package com.akenory.personalka.android

import android.content.Context
import com.akenory.personalka.data.calendar.CalendarTraining
import com.akenory.personalka.data.prefs.DevicePrefs
import com.akenory.personalka.domain.ClubTime
import com.akenory.personalka.domain.money.SalarySetupRules
import com.akenory.personalka.domain.watch.WatchCodec
import com.akenory.personalka.domain.watch.WatchDay
import com.akenory.personalka.domain.watch.WatchRules
import com.akenory.personalka.domain.watch.WatchSlot
import com.akenory.personalka.ui.watch.WatchTools
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.datetime.LocalDate
import kotlinx.datetime.yearMonth

/**
 * Часы Galaxy Watch (шаг 5.3т): пока процесс жив, телефон держит на часах расписание на 3 дня, деньги дня и счётчик
 * дел. Данные уходят через Wearable Data Layer (Google Play Services) одним элементом [PATH]; часы с тем же пакетом и
 * подписью получают его сами, даже если их подключат позже. Отправка — только когда текст изменился. Нет Play Services
 * или часов — молча ничего не происходит: ошибка часов не должна стать отчётом об ошибке приложения.
 */
class WatchBridge(
    private val context: Context,
    private val app: PersonalkaApplication,
    private val prefs: DevicePrefs,
) : WatchTools {
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    private val _names = MutableStateFlow(prefs.boolean(NAMES) ?: true)
    override val names: StateFlow<Boolean> = _names

    /** Счётчик дел «Главной» — его же берут виджеты (шаг 5.9). */
    val todo = MutableStateFlow(prefs.string(TODO)?.toIntOrNull() ?: 0)
    private var last: String? = null
    private var started = false

    override fun setNames(on: Boolean) {
        prefs.putBoolean(NAMES, on)
        _names.value = on
    }

    override fun todoCount(count: Int) {
        if (todo.value == count) return
        prefs.putString(TODO, count.toString())
        todo.value = count
    }

    /** Часы попросили данные заново (только что установлены или пустые): отправить, даже если текст тот же. */
    fun resend() {
        last = null
        tick.value++
    }

    private val tick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch { watchConnected() }
        // Новый день — новые запросы; внутри дня раз в 15 минут заново: деньги растут, прошедшее уходит.
        val minutes = flow {
            while (true) {
                emit(ClubTime.now(System.currentTimeMillis()))
                delay(60_000L)
            }
        }
        scope.launch {
            minutes.map { it.date }.distinctUntilChanged().flatMapLatest { today -> day(today) }.collect { day ->
                publish(WatchCodec.encode(day))
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun day(today: LocalDate) = combine(
        app.calendar.homeTrainings(today),
        app.settings.salary.schemes,
        app.settings.months.month(today.yearMonth, today),
        combine(_names, todo, tick) { names, todo, _ -> names to todo },
        quarterTicks(),
    ) { trainings, schemes, month, (names, todo), _ ->
        val now = ClubTime.now(System.currentTimeMillis())
        val view = month.view.takeIf { !SalarySetupRules.needed(schemes) && it.month == today.yearMonth }
        WatchDay(
            date = now.date,
            slots = WatchRules.slots(trainings.filter { !it.cancelled }.map { it.slot() }, now, names),
            earned = view?.today,
            ahead = view?.todayAhead,
            percent = view?.percent,
            forecastPercent = view?.forecastPercent,
            todo = todo,
        )
    }

    private fun quarterTicks() = flow {
        while (true) {
            emit(Unit)
            delay(QUARTER_MS)
        }
    }

    private suspend fun publish(text: String) {
        if (text == last) return
        try {
            val request = PutDataMapRequest.create(PATH).apply {
                dataMap.putString(KEY, text)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
            last = text
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Нет Play Services или часов: попробуем со следующим изменением.
        }
    }

    private suspend fun watchConnected() {
        try {
            val client = Wearable.getCapabilityClient(context)
            val info = client.getCapability(CAPABILITY, CapabilityClient.FILTER_ALL).await()
            _connected.value = info.nodes.isNotEmpty()
            client.addListener(CapabilityClient.OnCapabilityChangedListener { changed: CapabilityInfo -> _connected.value = changed.nodes.isNotEmpty() }, CAPABILITY)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _connected.value = false
        }
    }

    private fun CalendarTraining.slot() =
        WatchSlot(id, start, durationMin, typeShort, placeShort, title, group = group, marked = marked)

    companion object {
        /** Элемент Data Layer с расписанием — тот же путь читают часы. */
        const val PATH = "/personalka/day"
        const val KEY = "day"

        /** Сообщения с часов: «Проведено» (данные — id тренировки) и «пришлите данные». */
        const val MARK = "/personalka/mark"
        const val HELLO = "/personalka/hello"

        /** Часы с Персоналкой объявляют это умение (`res/values/wear.xml` модуля `wearApp`). */
        const val CAPABILITY = "personalka_watch"

        private const val NAMES = "watch.names"
        private const val TODO = "watch.todo"
        private const val QUARTER_MS = 15 * 60_000L
    }
}
