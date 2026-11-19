package com.akenory.personalka.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.Flow

/**
 * Наклон телефона для блика на карточке «Деньги» (шаг 5.12д, просьба Дмитрия 19.11.2026). [tilt] — поток наклона:
 * `x` — вбок (правый край вниз — плюс), `y` — от себя / к себе (держим как обычно, ~45°, — около нуля), оба от −1 до 1.
 * Подписка включает датчик, отписка — выключает; телефон его сам выключает, пока приложение свёрнуто.
 */
interface TiltSource {
    fun tilt(): Flow<Offset>

    /** Системное «Уменьшить движение» / «Убрать анимацию»: блик стоит на месте. */
    fun reduceMotion(): Boolean
}

/** Датчик наклона платформы; `null` — нет его (тесты экранов): блик стоит на месте. */
val LocalTilt = staticCompositionLocalOf<TiltSource?> { null }

/** Из нормированной гравитации телефона (вбок и вдоль, обычный хват — вдоль ≈ 0,7) — наклон для блика. */
fun tiltOf(side: Float, along: Float): Offset =
    Offset((side * 2f).coerceIn(-1f, 1f), ((along - 0.7f) * 2.5f).coerceIn(-1f, 1f))
