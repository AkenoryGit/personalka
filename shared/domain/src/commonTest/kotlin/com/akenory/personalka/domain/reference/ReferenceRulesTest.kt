package com.akenory.personalka.domain.reference

import com.akenory.personalka.domain.format.DurationFormat
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
        // Цвет — у места, у каждого свой; у типа — оттенок 1–4 (шаг 1.13в).
        assertEquals(listOf("blue", "pink", "brown"), ReferenceRules.defaultPlaces.map { it.color })
        assertTrue(ReferenceRules.defaultPlaces.all { it.color in ReferenceRules.COLORS })
        assertTrue(types.all { it.shade in ReferenceRules.SHADES })
        assertEquals(listOf(1, 2, 3, 4), listOf("ПТ", "Сплит", "Мини-группа", "Группа").map { type(it).shade })
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
    fun kindChangeFillsPresetButKeepsNameAndShade() {
        val aqua = ReferenceRules.newType(usedShades = listOf(1, 2))
            .copy(name = "Аквааэробика", shortName = "Аква", durationMin = 45)
        assertEquals(TrainingKind.PERSONAL, aqua.kind)

        val group = aqua.withKind(TrainingKind.GROUP)
        assertEquals("Аквааэробика", group.name)
        assertEquals("Аква", group.shortName)
        assertEquals(3, group.shade)
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
    fun newTypeTakesFirstFreeShadeAndNewPlaceFirstFreeColor() {
        assertEquals(3, ReferenceRules.newType(listOf(1, 2, 4)).shade)
        assertEquals(1, ReferenceRules.newType(types.map { it.shade }).shade, "Все заняты — светлый")

        val places = ReferenceRules.defaultPlaces
        assertEquals("red", ReferenceRules.newPlace(places.map { it.color }).color)
        assertEquals("sky", ReferenceRules.newPlace(ReferenceRules.COLORS - "sky").color)
        assertEquals("slate", ReferenceRules.newPlace(ReferenceRules.COLORS).color, "Все заняты — повтор разрешён")
        assertEquals(places[1], ReferenceRules.sameColor("pink", places))
        assertNull(ReferenceRules.sameColor("red", places))
    }

    @Test
    fun shadesAndPlaceColorsForDataWithoutThem() {
        // Переход базы на версию 2: свои типы — по имени из первого запуска, иначе по виду.
        assertEquals(1, ReferenceRules.defaultShade("ПТ", TrainingKind.PERSONAL))
        assertEquals(3, ReferenceRules.defaultShade(" мини-группа ", TrainingKind.GROUP))
        assertEquals(2, ReferenceRules.defaultShade("Кинезио", TrainingKind.PERSONAL))
        assertEquals(4, ReferenceRules.defaultShade("Сб 9:00", TrainingKind.GROUP))
        assertEquals(2, ReferenceRules.defaultShade("Тройка", TrainingKind.OTHER))
        assertEquals(3, ReferenceRules.defaultShade("Старт", TrainingKind.SPT))

        // ББ, ДБ, Зал — синий, розовый, коричневый; своё место — первый свободный, второе своё — следующий.
        assertEquals(
            listOf("pink", "blue", "red", "brown", "orange"),
            ReferenceRules.defaultPlaceColors(listOf("ДБ", "ББ", "Ул", "зал", "ОВ")),
        )
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

        assertEquals(s("90_мин"), DurationFormat.minutes(90), "У типа длительность минутами, а не «1 ч 30 мин»")
        assertEquals(s("3_места · 9_типов"), TypeFormat.placesAndTypes(3, 9))
        // Оттенки в конструкторе типа (шаг 1.13в).
        assertEquals(listOf("Светлый", "Средний", "Яркий", "Тёмный"), ReferenceRules.SHADES.map(TypeFormat::shade))
        assertEquals(listOf("ПТ", "Сплит", "Мини", "Группа"), TypeFormat.shadeSamples(types))
        assertEquals(listOf("ПТ", "Средний", "Яркий", "Тёмный"), TypeFormat.shadeSamples(types.take(1) + type("Дежурство")))
        assertNull(TypeFormat.sameShade(emptyList()))
        assertEquals("Такой же оттенок у типа «Группа»", TypeFormat.sameShade(listOf("Группа")))
        assertEquals(
            "Такой же оттенок у типов «Сплит», «Грудничковое» и «Кинезио»",
            TypeFormat.sameShade(listOf("Сплит", "Грудничковое", "Кинезио")),
        )
        assertEquals(s("1_место · 1_тип"), TypeFormat.placesAndTypes(1, 1))
        assertEquals(
            "Длительность, оплата и дневник заполнены как у группы — ниже их можно поправить.",
            TypeFormat.presetNote(TrainingKind.GROUP),
        )
    }
}
