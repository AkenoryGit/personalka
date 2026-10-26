package com.akenory.personalka.domain.reference

import com.akenory.personalka.domain.model.PayUnit
import com.akenory.personalka.domain.model.TrainingKind

// ТЗ 3.3, 5.16, 5.18. Что тренер задаёт у мест, типов и в своём ФИО — без служебных полей базы.

/** ФИО тренера (5.18): родители видят имя и фамилию, в согласие входит полное ФИО. */
data class CoachName(val lastName: String, val firstName: String, val middleName: String? = null) {
    /** Фамилия и имя есть — можно идти дальше; отчество необязательно. */
    val isComplete: Boolean get() = lastName.isNotBlank() && firstName.isNotBlank()

    /** Без пробелов по краям, пустое отчество — `null`. */
    fun trimmed(): CoachName = CoachName(
        lastName = lastName.trim(),
        firstName = firstName.trim(),
        middleName = middleName?.trim()?.takeIf { it.isNotEmpty() },
    )

    /** «Дудник Дмитрий Викторович» — для согласия и «Профиля». */
    val full: String get() = listOfNotNull(lastName, firstName, middleName).joinToString(" ")
}

/**
 * Место (3.3): «Большой бассейн», «ББ» и цвет — ключ палитры 16 цветов. Тренировки места красятся его цветом,
 * а оттенок выбирает тип (шаг 1.13в): по цвету тренер сразу видит, в каком бассейне работает.
 */
data class PlaceDraft(val name: String, val shortName: String, val color: String = ReferenceRules.FALLBACK_COLOR)

/**
 * Тип тренировки (3.3) — всё, что задаётся в конструкторе (5.16). Своего цвета у типа нет: блок красится цветом
 * места, а тип выбирает оттенок — от 1 (светлый) до 4 (тёмный).
 */
data class TypeDraft(
    val name: String,
    val shortName: String,
    val kind: TrainingKind,
    val shade: Int,
    val durationMin: Int,
    /** 1 — персональная и стартовая, 2 — сплит, `null` — сколько угодно, 0 — дежурство. */
    val maxClients: Int?,
    val payUnit: PayUnit,
    val countsTowardPt: Boolean,
    val tierDependent: Boolean,
    val diaryPlan: Boolean,
    val diaryFact: Boolean,
    val diaryBehavior: Boolean,
    /** Только дежурство: свой цвет в календаре (ключ палитры); `null` — цвет места (шаг 3.9е). */
    val dutyColor: String? = null,
) {
    /**
     * Другой вид — длительность, клиенты, оплата, «как ПТ», ступени и дневник заполняются как у этого вида
     * («Длительность, оплата и дневник заполнены как у группы»); название и оттенок остаются.
     */
    fun withKind(kind: TrainingKind): TypeDraft =
        TypePresets.of(kind).copy(name = name, shortName = shortName, shade = shade)
}

/** Поля формы, под которыми показывается ошибка. */
enum class DraftField { NAME, SHORT_NAME, DURATION, MAX_CLIENTS }

/** Заготовки по видам (5.16): что подставляется, когда тренер выбирает «Как работает». */
object TypePresets {
    /** Длительность по умолчанию у нового типа (ТЗ 3.3, решение шага 0.4). */
    const val DEFAULT_DURATION = 50

    /** Дежурство — обычно смена в несколько часов (15:00–19:00 в макетах). */
    const val DUTY_DURATION = 240

    fun of(kind: TrainingKind): TypeDraft = when (kind) {
        TrainingKind.PERSONAL -> base(kind, maxClients = 1, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true)
        TrainingKind.SPLIT -> base(kind, maxClients = 2, PayUnit.PER_SESSION, countsTowardPt = true, tierDependent = true)
        // У групп поведение в дневнике не ведут (3.3).
        TrainingKind.GROUP -> base(kind, maxClients = null, PayUnit.PER_CLIENT).copy(diaryBehavior = false)
        TrainingKind.SPT -> base(kind, maxClients = 1, PayUnit.PER_SESSION)
        TrainingKind.DUTY -> base(kind, maxClients = 0, PayUnit.PER_HOUR).copy(
            durationMin = DUTY_DURATION,
            diaryPlan = false,
            diaryFact = false,
            diaryBehavior = false,
        )
        TrainingKind.OTHER -> base(kind, maxClients = 1, PayUnit.PER_SESSION)
    }

    private fun base(
        kind: TrainingKind,
        maxClients: Int?,
        payUnit: PayUnit,
        countsTowardPt: Boolean = false,
        tierDependent: Boolean = false,
    ) = TypeDraft(
        name = "",
        shortName = "",
        kind = kind,
        shade = ReferenceRules.SHADE_LIGHT,
        durationMin = DEFAULT_DURATION,
        maxClients = maxClients,
        payUnit = payUnit,
        countsTowardPt = countsTowardPt,
        tierDependent = tierDependent,
        diaryPlan = true,
        diaryFact = true,
        diaryBehavior = true,
    )

    /** Сколько клиентов задаёт сам тренер: у персональной, сплита, стартовой и дежурства число задано видом. */
    fun clientsEditable(kind: TrainingKind): Boolean = kind == TrainingKind.GROUP || kind == TrainingKind.OTHER

    /** У дежурства нет клиентов, дневника, счёта ПТ и ступеней — эти поля не показываются. */
    fun hasClients(kind: TrainingKind): Boolean = kind != TrainingKind.DUTY
}

/** Места и типы по умолчанию для первого запуска (3.3) и проверки форм. */
object ReferenceRules {
    /** Короткое имя места — до 4 знаков (3.3): «ББ», «ДБ», «Зал». */
    const val PLACE_SHORT_MAX = 4

    /** Короткое имя типа — до 8 знаков: «ПТ», «Груд», «Кинезио». Его видно в блоке календаря и в списке клиентов. */
    const val TYPE_SHORT_MAX = 8

    const val DURATION_MIN = 5
    const val DURATION_MAX = 600

    /** Длительности на чипах конструктора; другая — «Своя». */
    val DURATION_CHIPS = listOf(30, 45, 50, 60, 90)

    /** 16 ключей палитры в порядке показа (дизайн-система 0.2). */
    val COLORS = listOf(
        "red", "orange", "amber", "mustard", "lime", "green", "emerald", "teal",
        "sky", "blue", "indigo", "violet", "magenta", "pink", "brown", "slate",
    )

    /** Цвет места, если ключ потерялся: нейтральный сланцевый. */
    const val FALLBACK_COLOR = "slate"

    /**
     * Оттенки типа (шаг 1.13в): 1 — светлый … 4 — тёмный. Больше четырёх оттенков одного цвета на ходу не различить,
     * поэтому типы с одним оттенком отличаются надписью в блоке.
     */
    const val SHADE_LIGHT = 1
    const val SHADE_DARK = 4
    val SHADES = SHADE_LIGHT..SHADE_DARK

    val defaultPlaces: List<PlaceDraft> = listOf(
        PlaceDraft("Большой бассейн", "ББ", "blue"),
        PlaceDraft("Детский бассейн", "ДБ", "pink"),
        PlaceDraft("Тренажёрный зал", "Зал", "brown"),
    )

    /**
     * Девять типов первого запуска (3.3). Оттенки — чтобы в одном бассейне чаще всего стоящие рядом типы
     * различались: ПТ светлая, сплит и грудничковое средние, мини-группа, СПТ и гидро — яркие, группа — тёмная.
     * Грудничковое, гидрореабилитация и кинезио — персональные со своим именем: идут в счёт ПТ и получают надбавку
     * по ступеням; грудничковое длится 30 минут.
     */
    val defaultTypes: List<TypeDraft> = listOf(
        preset(TrainingKind.PERSONAL, "ПТ", "ПТ", shade = 1),
        preset(TrainingKind.SPLIT, "Сплит", "Сплит", shade = 2),
        preset(TrainingKind.GROUP, "Мини-группа", "Мини", shade = 3),
        preset(TrainingKind.GROUP, "Группа", "Группа", shade = 4).copy(durationMin = 60),
        preset(TrainingKind.SPT, "СПТ", "СПТ", shade = 3),
        preset(TrainingKind.DUTY, "Дежурство", "Деж", shade = 1),
        preset(TrainingKind.PERSONAL, "Грудничковое", "Груд", shade = 2).copy(durationMin = 30),
        preset(TrainingKind.PERSONAL, "Гидрореабилитация", "Гидро", shade = 3),
        preset(TrainingKind.PERSONAL, "Кинезио", "Кинезио", shade = 2),
    )

    private fun preset(kind: TrainingKind, name: String, shortName: String, shade: Int) =
        TypePresets.of(kind).copy(name = name, shortName = shortName, shade = shade)

    /** Новый тип в конструкторе: персональная, первый оттенок, которого ещё нет у типов; заняты все — светлый. */
    fun newType(usedShades: Collection<Int>): TypeDraft =
        TypePresets.of(TrainingKind.PERSONAL).copy(shade = SHADES.firstOrNull { it !in usedShades } ?: SHADE_LIGHT)

    /** Новое место: первый цвет палитры, которого ещё нет у мест; заняты все — сланцевый. */
    fun newPlace(usedColors: Collection<String>): PlaceDraft =
        PlaceDraft("", "", COLORS.firstOrNull { it !in usedColors } ?: FALLBACK_COLOR)

    /**
     * Оттенок типа, у которого его ещё нет (переход базы на версию 2, шаг 1.13в; импорт таблиц): у типов первого
     * запуска — как в [defaultTypes], у своих — по виду: персональная и дежурство светлые, сплит и «другая» —
     * средние, СПТ — яркая, группа — тёмная.
     */
    fun defaultShade(name: String, kind: TrainingKind): Int =
        defaultTypes.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.shade ?: when (kind) {
            TrainingKind.PERSONAL, TrainingKind.DUTY -> 1
            TrainingKind.SPLIT, TrainingKind.OTHER -> 2
            TrainingKind.SPT -> 3
            TrainingKind.GROUP -> 4
        }

    /**
     * Цвета мест, у которых их ещё нет, по порядку тренера: ББ, ДБ и Зал — как в [defaultPlaces] (синий, розовый,
     * коричневый), остальные — по очереди первым свободным цветом палитры.
     */
    fun defaultPlaceColors(shortNames: List<String>): List<String> {
        val known = shortNames.map { short -> defaultPlaces.firstOrNull { it.shortName.equals(short.trim(), ignoreCase = true) }?.color }
        val used = known.filterNotNull().toMutableList()
        return known.map { it ?: newPlace(used).color.also { color -> used += color } }
    }

    /**
     * Ошибки формы места словами тренера. [others] — другие действующие места: короткое имя не должно
     * повторяться, иначе в календаре два «ББ».
     */
    fun placeProblems(draft: PlaceDraft, others: List<PlaceDraft>): Map<DraftField, String> = buildMap {
        val name = draft.name.trim()
        val short = draft.shortName.trim()
        if (name.isEmpty()) put(DraftField.NAME, "Введите название")
        when {
            short.isEmpty() -> put(DraftField.SHORT_NAME, "Введите короткое имя")
            short.length > PLACE_SHORT_MAX -> put(DraftField.SHORT_NAME, "Не больше $PLACE_SHORT_MAX знаков")
            else -> others.firstOrNull { it.shortName.trim().equals(short, ignoreCase = true) }
                ?.let { put(DraftField.SHORT_NAME, "Уже есть у места «${it.name}»") }
        }
    }

    /** Ошибки формы типа словами тренера. [others] — другие действующие типы: название не повторяется. */
    fun typeProblems(draft: TypeDraft, others: List<TypeDraft>): Map<DraftField, String> = buildMap {
        val name = draft.name.trim()
        val short = draft.shortName.trim()
        when {
            name.isEmpty() -> put(DraftField.NAME, "Введите название")
            others.any { it.name.trim().equals(name, ignoreCase = true) } -> put(DraftField.NAME, "Такой тип уже есть")
        }
        when {
            short.isEmpty() -> put(DraftField.SHORT_NAME, "Введите короткое имя")
            short.length > TYPE_SHORT_MAX -> put(DraftField.SHORT_NAME, "Не больше $TYPE_SHORT_MAX знаков")
        }
        if (draft.durationMin !in DURATION_MIN..DURATION_MAX) {
            put(DraftField.DURATION, "От $DURATION_MIN до $DURATION_MAX минут")
        }
        val max = draft.maxClients
        if (TypePresets.clientsEditable(draft.kind) && max != null && max < 1) {
            put(DraftField.MAX_CLIENTS, "Хотя бы 1 клиент")
        }
    }

    /**
     * Место с таким же цветом среди [others] — форма места предупреждает, но не запрещает: два бассейна одного
     * цвета в календаре не различить.
     */
    fun sameColor(color: String, others: List<PlaceDraft>): PlaceDraft? = others.firstOrNull { it.color == color }

    /**
     * В какой тип превращается сплит, когда пришёл один клиент (3.3): персональная «ПТ», иначе первая
     * персональная, которая считается как ПТ. Индекс в [types] или `null`, если такой нет.
     */
    fun splitFallback(types: List<TypeDraft>): Int? {
        val personal = types.withIndex().filter { it.value.kind == TrainingKind.PERSONAL }
        return (personal.firstOrNull { it.value.shortName == "ПТ" } ?: personal.firstOrNull { it.value.countsTowardPt })?.index
    }
}
