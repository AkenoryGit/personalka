package com.akenory.personalka.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.akenory.personalka.ui.components.TiltSource
import com.akenory.personalka.ui.components.tiltOf
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Наклон для блика на «Деньгах» (шаг 5.12д): датчик гравитации (нет его — акселерометр), 60 мс между замерами.
 * Датчик слушается, только пока экран на виду ([lifecycle] не ниже STARTED) и «Главная» подписана.
 */
class AndroidTilt(private val context: Context, private val lifecycle: Lifecycle) : TiltSource {
    override fun tilt(): Flow<Offset> = callbackFlow {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val g = SensorManager.GRAVITY_EARTH
                trySend(tiltOf(-event.values[0] / g, event.values[1] / g))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> manager.registerListener(listener, sensor, 60_000)
                Lifecycle.Event.ON_STOP -> manager.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        awaitClose {
            manager.unregisterListener(listener)
            lifecycle.removeObserver(observer)
        }
    }

    override fun reduceMotion(): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
