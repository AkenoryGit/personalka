package com.akenory.personalka.domain.reference

import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.format.TypeFormat
import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Места и типы по умолчанию (ТЗ 3.3), заготовки конструктора и проверки форм (5.16, 5.18). */
class ReferenceRulesTest {

    private fun s(text: String) = text.replace('_', RuText.NBSP)

    private val types = ReferenceRules.defaultTypes
    private fun type(name: String) = types.single { it.name == name }

    @Test
    fun defaultsAreThreePlacesAndNineTypes() {
        assertEquals(listOf("ББ", "ДБ", "Зал"), ReferenceRules.defaultPlaces.map { it.shortName })
        assertEquals(
            listOf("ПТ", "Сплит", "Мини-группа", "Группа", "СПТ", "Дежурство", "Грудничковое", "Гидрореабилитация", "Кинезио"),
            types.map { it.name },
        )
        assertEquals(types.size, types.map { it.color }.toSet().size, "У каждого типа свой цвет")
        assertTrue(types.all { it.color in ReferenceRules.COLORS })
        assertTrue(types.all { ReferenceRules.typeProblems(it, types - it).isEmpty() }, "Заготовки проходят свою проверку")
        assertTrue(ReferenceRules.defaultPlaces.all { ReferenceRules.placeProblems(it, ReferenceRules.defaultPlaces - it).isEmpty() })
    }

    @Test
    fun defaultTypesFollowSpec() {
        // ТЗ 3.3: max_clients 1 для PERSONAL и SPT, 2 для SPLIT, пусто для GROUP, 0 для DUTY.
        assertEquals(1, type("ПТ").maxClients)
        assertEquals(2, type("Сплит").maxClients)
        assertNull(type("Группа").maxClients)
        assertNull(type("Мини-группа").maxClients)
        assertEquals(1, type("СПТ").maxClients)
        assertEquals(0, type("Дежурство").maxClients)

        // ПТ и сплиты считаются как ПТ; группы, СПТ, дежурства — нет.
        assertTrue(type("ПТ").countsTowardPt && type("Сплит").countsTowardPt)
        assertFalse(type("Группа").countsTowardPt || type("СПТ").countsTowardPt || type("Дежурство").countsTowardPt)

        // Грудничковое, гидрореабилитация и кинезио — персональные: в счёт ПТ и с надбавкой по ступеням.
        for (name in listOf("Грудничковое", "Гидрореабилитация", "Кинезио")) {
            val t = type(name)
            assertEquals(TrainingKind.PERSONAL, t.kind, name)
            assertTrue(t.countsTowardPt && t.tierDependent, name)
        }
        assertEquals(30, type("Грудничковое").durationMin)
        assertEquals(60, type("Группа").durationMin)
        assertEquals(50, type("ПТ").durationMin)

        // У групп поведение не ведётся, у дежурства нет дневника и оплата за час.
        assertFalse(type("Группа").diaryBehavior)
        assertTrue(type("Группа").diaryPlan && type("Группа").diaryFact)
        assertEquals(PayUnit.PER_HOUR, type("Дежурство").payUnit)
        assertFalse(type("Дежурство").diaryPlan)
        assertEquals(PayUnit.PER_CLIENT, type("Мини-группа").payUnit)
    }

    @Test
    fun kindChangeFillsPresetButKeepsNameAndColor() {
        val aqua = ReferenceRules.newType(usedColors = types.map { it.color })
            .copy(name = "Аквааэробика", shortName = "Аква", durationMin = 45)
        assertEquals(TrainingKind.PERSONAL, aqua.kind)

        val group = aqua.withKind(TrainingKind.GROUP)
        assertEquals("Аквааэробика", group.name)
        assertEquals("Аква", group.shortName)
        assertEquals(aqua.color, group.color)
        assertEquals(50, group.durationMin, "Длительность — как у группы")
        assertNull(group.maxClients)
        assertEquals(PayUnit.PER_CLIENT, group.payUnit)
        assertFalse(group.countsTowardPt)
        assertFalse(group.diaryBehavior)

        val duty = aqua.withKind(TrainingKind.DUTY)
        assertEquals(0, duty.maxClients)
        assertEquals(PayUnit.PER_HOUR, duty.payUnit)
        assertFalse(TypePresets.hasClients(TrainingKind.DUTY))
        assertTrue(TypePresets.clientsEditable(TrainingKind.GROUP))
        assertFalse(TypePresets.clientsEditable(TrainingKind.SPLIT))
    }

    @Test
    fun newTypeTakesFirstFreeColor() {
        assertEquals("red", ReferenceRules.newType(types.map { it.color }).color)
        val allButSky = ReferenceRules.COLORS - "sky"
        assertEquals("sky", ReferenceRules.newType(allButSky).color)
        assertEquals("slate", ReferenceRules.newType(ReferenceRules.COLORS).color, "Все заняты — повтор разрешён")
        assertEquals(type("Группа"), ReferenceRules.sameColor("green", types))
        assertNull(ReferenceRules.sameColor("red", types))
    }

    @Test
    fun typeFormChecks() {
        val draft = ReferenceRules.newType(emptyList()).copy(name = "Аквааэробика", shortName = "Аква")
        assertTrue(ReferenceRules.typeProblems(draft, types).isEmpty())

        val empty = draft.copy(name = " ", shortName = "")
        assertEquals(
            mapOf(DraftField.NAME to "Введите название", DraftField.SHORT_NAME to "Введите короткое имя"),
            ReferenceRules.typeProblems(empty, types),
        )
        assertEquals("Такой тип уже есть", ReferenceRules.typeProblems(draft.copy(name = " группа "), types)[DraftField.NAME])
        assertEquals("Не больше 8 знаков", ReferenceRules.typeProblems(draft.copy(shortName = "Аквааэроб"), types)[DraftField.SHORT_NAME])
        assertEquals("От 5 до 600 минут", ReferenceRules.typeProblems(draft.copy(durationMin = 601), types)[DraftField.DURATION])
        assertEquals("От 5 до 600 минут", ReferenceRules.typeProblems(draft.copy(durationMin = 0), types)[DraftField.DURATION])
        val group = draft.withKind(TrainingKind.GROUP)
        assertEquals("Хотя бы 1 клиент", ReferenceRules.typeProblems(group.copy(maxClients = 0), types)[DraftField.MAX_CLIENTS])
        assertTrue(ReferenceRules.typeProblems(group.copy(maxClients = 8), types).isEmpty())
    }

    @Test
    fun placeFormChecks() {
        val places = ReferenceRules.defaultPlaces
        assertTrue(ReferenceRules.placeProblems(PlaceDraft("Улица", "Ул"), places).isEmpty())
        assertEquals("Не больше 4 знаков", ReferenceRules.placeProblems(PlaceDraft("Улица", "Улица"), places)[DraftField.SHORT_NAME])
        assertEquals(
            "Уже есть у места «Детский бассейн»",
            ReferenceRules.placeProblems(PlaceDraft("Другой", "дб"), places)[DraftField.SHORT_NAME],
        )
        assertEquals("Введите название", ReferenceRules.placeProblems(PlaceDraft("", "Ул"), places)[DraftField.NAME])
        // Своё же имя при правке места не мешает: оно не входит в «другие».
        val big = places.first()
        assertTrue(ReferenceRules.placeProblems(big.copy(name = "Бассейн 50 м"), places - big).isEmpty())
    }

    @Test
    fun splitFallsBackToPt() {
        assertEquals(0, ReferenceRules.splitFallback(types))
        val withoutPt = types.filterNot { it.name == "ПТ" }
        assertEquals("Грудничковое", withoutPt[ReferenceRules.splitFallback(withoutPt)!!].name)
        assertNull(ReferenceRules.splitFallback(types.filter { it.kind == TrainingKind.GROUP }))
    }

    @Test
    fun coachName() {
        val name = CoachName("  Дудник ", "Дмитрий", "  ").trimmed()
        assertEquals(CoachName("Дудник", "Дмитрий", null), name)
        assertTrue(name.isComplete)
        assertEquals("Дудник Дмитрий", name.full)
        assertEquals("Дудник Дмитрий Викторович", name.copy(middleName = "Викторович").full)
        assertFalse(CoachName("Дудник", " ").isComplete, "Без имени дальше нельзя")
    }

    @Test
    fun linesLikeMockups() {
        // «Настройки · места и типы» (TypesList).
        assertEquals(s("Персональная · 50_мин · как ПТ"), TypeFormat.summary(type("ПТ")))
        assertEquals(s("Сплит · 50_мин · как ПТ"), TypeFormat.summary(type("Сплит")))
        assertEquals(s("Группа · 50_мин · за клиента"), TypeFormat.summary(type("Мини-группа")))
        assertEquals(s("Группа · 60_мин · за клиента"), TypeFormat.summary(type("Группа")))
        assertEquals(s("Стартовая · 50_мин · бонус за блок"), TypeFormat.summary(type("СПТ")))
        assertEquals("Дежурство · за час", TypeFormat.summary(type("Дежурство")))
        assertEquals(s("Персональная · 30_мин · как ПТ"), TypeFormat.summary(type("Грудничковое")))

        // «Места и типы» первого запуска (FrSetup).
        assertEquals(s("50_мин · 1_клиент"), TypeFormat.setupLine(type("ПТ")))
        assertEquals(s("50_мин · 2_клиента"), TypeFormat.setupLine(type("Сплит")))
        assertEquals(s("60_мин · группа"), TypeFormat.setupLine(type("Группа")))
        assertEquals(s("50_мин · пробная"), TypeFormat.setupLine(type("СПТ")))
        assertEquals("оплата за час", TypeFormat.setupLine(type("Дежурство")))
        assertEquals(s("30_мин · 1_клиент"), TypeFormat.setupLine(type("Грудничковое")))
        assertEquals(s("45_мин · до 8_клиентов"), TypeFormat.setupLine(type("Группа").copy(durationMin = 45, maxClients = 8)))

        assertEquals(s("3_места · 9_типов"), TypeFormat.placesAndTypes(3, 9))
        assertEquals(s("1_место · 1_тип"), TypeFormat.placesAndTypes(1, 1))
        assertEquals(
            "Длительность, оплата и дневник заполнены как у группы — ниже их можно поправить.",
            TypeFormat.presetNote(TrainingKind.GROUP),
        )
    }
}
