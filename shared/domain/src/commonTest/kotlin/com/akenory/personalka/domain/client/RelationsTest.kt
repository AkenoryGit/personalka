package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.model.RelationKind
import com.akenory.personalka.domain.model.Sex
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Родственные связи 4.17: слова с обеих сторон, запись пары, чипы, объединение. */
class RelationsTest {
    private val artem = "artem"
    private val natalia = "natalia"
    private val alisa = "alisa"

    private fun word(viewer: String, stored: StoredRelation, relativeSex: Sex?): String {
        val link = Relations.linkFor(viewer, "r1", stored)!!
        return Relations.word(link.role, relativeSex, link.label)
    }

    @Test
    fun fatherInOneCardIsSonInTheOther() {
        // В карточке Артёма отмечено: Сергей — отец.
        val stored = Relations.orient(clientId = artem, relativeId = "sergey", role = RelationRole(RelationKind.PARENT))
        assertEquals("Отец", word(artem, stored, Sex.MALE))
        assertEquals("Сын", word("sergey", stored, Sex.MALE))
        // То же, если связь добавили со стороны отца: «Артём — сын».
        val fromFather = Relations.orient(clientId = "sergey", relativeId = artem, role = RelationRole(RelationKind.PARENT, senior = false))
        assertEquals(stored, fromFather)
    }

    @Test
    fun motherAndChildrenLikeMockups() {
        val mother = Relations.orient(artem, natalia, RelationRole(RelationKind.PARENT))
        assertEquals(natalia, mother.aId)
        assertEquals("Мать", word(artem, mother, Sex.FEMALE))
        assertEquals("Сын", word(natalia, mother, Sex.MALE))
        assertEquals("Дочь", word(natalia, Relations.orient(alisa, natalia, RelationRole(RelationKind.PARENT)), Sex.FEMALE))
        val sister = Relations.orient(artem, alisa, RelationRole(RelationKind.SIBLING))
        assertEquals("Сестра", word(artem, sister, Sex.FEMALE))
        assertEquals("Брат", word(alisa, sister, Sex.MALE))
    }

    @Test
    fun wordsByTableIncludingUnknownSex() {
        val expected = mapOf(
            RelationRole(RelationKind.PARENT) to listOf("Отец", "Мать", "Родитель"),
            RelationRole(RelationKind.PARENT, false) to listOf("Сын", "Дочь", "Ребёнок"),
            RelationRole(RelationKind.GRANDPARENT) to listOf("Дедушка", "Бабушка", "Бабушка или дедушка"),
            RelationRole(RelationKind.GRANDPARENT, false) to listOf("Внук", "Внучка", "Внук или внучка"),
            RelationRole(RelationKind.AUNT_UNCLE) to listOf("Дядя", "Тётя", "Дядя или тётя"),
            RelationRole(RelationKind.AUNT_UNCLE, false) to listOf("Племянник", "Племянница", "Племянник или племянница"),
            RelationRole(RelationKind.SIBLING) to listOf("Брат", "Сестра", "Брат или сестра"),
            RelationRole(RelationKind.COUSIN) to listOf("Двоюродный брат", "Двоюродная сестра", "Двоюродный брат или сестра"),
            RelationRole(RelationKind.SPOUSE) to listOf("Муж", "Жена", "Супруг"),
        )
        expected.forEach { (role, words) ->
            assertEquals(words, listOf(Sex.MALE, Sex.FEMALE, null).map { Relations.word(role, it) }, role.toString())
        }
    }

    @Test
    fun reverseSwapsSidesOnlyWhereThereAreSides() {
        assertEquals(RelationRole(RelationKind.GRANDPARENT, false), RelationRole(RelationKind.GRANDPARENT).reverse)
        assertEquals(RelationRole(RelationKind.AUNT_UNCLE), RelationRole(RelationKind.AUNT_UNCLE, false).reverse)
        assertEquals(RelationRole(RelationKind.COUSIN), RelationRole(RelationKind.COUSIN).reverse)
        assertEquals(RelationRole(RelationKind.SPOUSE), RelationRole(RelationKind.SPOUSE).reverse)
    }

    @Test
    fun otherKeepsBothLabels() {
        val stored = Relations.orient(artem, "olga", RelationRole(RelationKind.OTHER), relativeLabel = "крёстная", clientLabel = "крестник")
        assertEquals("Крёстная", word(artem, stored, Sex.FEMALE))
        assertEquals("Крестник", word("olga", stored, Sex.MALE))
        val noReverse = Relations.orient(artem, "olga", RelationRole(RelationKind.OTHER), relativeLabel = "крёстная", clientLabel = " ")
        assertEquals("Родственник", word("olga", noReverse, Sex.MALE))
        assertEquals("Родственница", word(artem, noReverse.copy(labelA = null), Sex.FEMALE))
        // У видов из таблицы подписи не хранятся.
        assertNull(Relations.orient(artem, alisa, RelationRole(RelationKind.SIBLING), "сестрёнка", "братик").labelA)
    }

    @Test
    fun foreignRelationIsNotRead() {
        val stored = Relations.orient(artem, natalia, RelationRole(RelationKind.PARENT))
        assertNull(Relations.linkFor(alisa, "r1", stored))
        val link = Relations.linkFor(natalia, "r1", stored)!!
        assertEquals(RelativeLink("r1", artem, RelationRole(RelationKind.PARENT, false), null), link)
        // У пары без старшего сторона одна и та же.
        val sister = Relations.orient(artem, alisa, RelationRole(RelationKind.SIBLING))
        assertEquals(RelationRole(RelationKind.SIBLING), Relations.linkFor(alisa, "r2", sister)!!.role)
        assertEquals(RelationRole(RelationKind.SIBLING), Relations.linkFor(artem, "r2", sister)!!.role)
    }

    @Test
    fun choicesLikeMockupForAdultWomanAndBoy() {
        // Наталье 41 год, Артёму 10: чипы — в женском роде и только старшие (макет ClRelAdd).
        val older = Relations.older(LocalDate(1985, 6, 23), true, LocalDate(2016, 2, 14), false)
        assertEquals(true, older)
        assertEquals(
            listOf("Мать", "Бабушка", "Сестра", "Тётя", "Двоюродная сестра"),
            Relations.choices(Sex.FEMALE, older, bothAdults = false).map { it.word },
        )
        assertEquals(
            listOf("Сын", "Внук", "Брат", "Племянник", "Двоюродный брат", "Муж"),
            Relations.choices(Sex.MALE, older = false, bothAdults = true).map { it.word },
        )
    }

    @Test
    fun choicesWithUnknownSexAndAgeShowBothGenders() {
        val words = Relations.choices(null, null, bothAdults = false).map { it.word }
        assertEquals(16, words.size)
        assertEquals(listOf("Отец", "Мать", "Сын", "Дочь", "Дедушка", "Бабушка"), words.take(6))
        val mother = Relations.choices(null, null, false).single { it.word == "Мать" }
        assertEquals(Sex.FEMALE, mother.sex)
        assertTrue("Муж" !in words)
    }

    @Test
    fun olderByDatesOrAdulthood() {
        assertEquals(false, Relations.older(LocalDate(2014, 1, 1), false, LocalDate(2012, 1, 1), false))
        assertNull(Relations.older(LocalDate(2014, 1, 1), false, LocalDate(2014, 1, 1), false))
        assertEquals(true, Relations.older(null, true, null, false))
        assertEquals(false, Relations.older(null, false, LocalDate(1990, 1, 1), true))
        assertNull(Relations.older(null, false, null, false))
    }

    @Test
    fun mergeDropsLinkBetweenDuplicatesAndRepeats() {
        // Дубль Артёма (artem2) связан с матерью, с сестрой и с самим Артёмом; у Артёма мать уже есть.
        val keep = listOf("k1" to Relations.orient(artem, natalia, RelationRole(RelationKind.PARENT)))
        val gone = listOf(
            "g1" to Relations.orient("artem2", natalia, RelationRole(RelationKind.PARENT)),
            "g2" to Relations.orient("artem2", alisa, RelationRole(RelationKind.SIBLING)),
            "g3" to Relations.orient("artem2", artem, RelationRole(RelationKind.SIBLING)),
        )
        assertEquals(setOf("g1", "g3"), Relations.mergeDrops(artem, "artem2", keep, gone))
    }
}
