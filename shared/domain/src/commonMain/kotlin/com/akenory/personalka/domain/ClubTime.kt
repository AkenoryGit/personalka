package com.akenory.personalka.domain

import kotlinx.datetime.TimeZone

/**
 * Время клуба (ТЗ 3.1, 4.14). Время тренировок хранится локальным — в часовом поясе клуба,
 * а не телефона; служебные моменты — в UTC.
 */
object ClubTime {
    /** Пояс по умолчанию для настройки тренера `timezone` (ТЗ 3.2). */
    const val DEFAULT_ZONE_ID = "Asia/Yekaterinburg"

    val defaultZone: TimeZone get() = TimeZone.of(DEFAULT_ZONE_ID)
}
