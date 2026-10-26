package com.akenory.personalka.ui.clients

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Мессенджер Макс на телефоне (шаг 3.9ж): кнопка «Написать в Макс» рядом со звонком в карточке клиента.
 * Ссылки «чат по номеру», как wa.me у WhatsApp, у Макса нет (dev.max.ru/help/deeplinks — только боты,
 * мини-приложения и «Поделиться»), поэтому [open] кладёт номер в буфер обмена и открывает Макс —
 * тренер вставляет номер в поиск по номеру.
 */
interface MaxMessenger {
    /** Макс установлен; нет — кнопки нет (ТЗ 10.7: выключенное не показывается). */
    fun installed(): Boolean

    /** Скопировать [phone] и открыть Макс; `false` — открыть не вышло. */
    fun open(phone: String): Boolean
}

/** Макс на телефоне; `null` — платформа его не знает (тесты, iPhone до шага 5.1): кнопки нет. */
val LocalMaxMessenger = staticCompositionLocalOf<MaxMessenger?> { null }

internal const val MAX_TAG = "client-max"

/** Подсказка после «Написать в Макс»: в Максе нет ссылки на чат по номеру, номер вставляется вручную. */
internal const val MAX_COPIED = "Номер скопирован — в Максе: «Контакты» → «+» → «Найти по номеру»"
