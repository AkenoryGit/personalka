package com.akenory.personalka.domain.format

import kotlin.test.Test
import kotlin.test.assertEquals

/** Строки «Услуг и имён из 1С» (шаг 4.2г). */
class ReconcileFormatTest {
    @Test
    fun mappings() {
        assertEquals("6${RuText.NBSP}услуг · 2${RuText.NBSP}имени", ReconcileFormat.mappingsNote(6, 2))
        assertEquals("1${RuText.NBSP}услуга", ReconcileFormat.mappingsNote(1, 0))
        assertEquals("5${RuText.NBSP}имён", ReconcileFormat.mappingsNote(0, 5))
        assertEquals("Ганеева Кира", ReconcileFormat.name1c("ганеева кира"))
        assertEquals("Римская-Корсакова Анна", ReconcileFormat.name1c("римская-корсакова анна"))
    }
}
