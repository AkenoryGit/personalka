package com.akenory.personalka.ui.ios

import androidx.compose.ui.geometry.Offset
import com.akenory.personalka.ui.components.TiltSource
import com.akenory.personalka.ui.components.tiltOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/**
 * Наклон для блика на «Деньгах» (шаг 5.12д): гравитация из CoreMotion, 15 замеров в секунду. Пока приложение
 * свёрнуто, iPhone замеры не присылает; отписка «Главной» их выключает.
 */
@OptIn(ExperimentalForeignApi::class)
object IosTilt : TiltSource {
    private val manager = CMMotionManager()

    override fun tilt(): Flow<Offset> = callbackFlow {
        if (!manager.deviceMotionAvailable) {
            close()
            return@callbackFlow
        }
        manager.deviceMotionUpdateInterval = 1.0 / 15
        manager.startDeviceMotionUpdatesToQueue(NSOperationQueue.mainQueue) { motion, _ ->
            motion?.gravity?.useContents { trySend(tiltOf(x.toFloat(), (-y).toFloat())) }
        }
        awaitClose { manager.stopDeviceMotionUpdates() }
    }

    override fun reduceMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
}
