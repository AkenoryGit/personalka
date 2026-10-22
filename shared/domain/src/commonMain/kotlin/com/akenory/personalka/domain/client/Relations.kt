package com.akenory.personalka.domain.client

import com.akenory.personalka.domain.format.RuText
import com.akenory.personalka.domain.model.RelationKind
import com.akenory.personalka.domain.model.Sex
import kotlinx.datetime.LocalDate

// ТЗ 4.17. Связь хранится одной записью на пару (ClientRelation, 3.5): клиент A и клиент B, A — старший
// у «родитель», «бабушка или дедушка», «дядя или тётя». Каждая карточка читает запись со своей стороны.

/**
 * Кем один клиент приходится другому: вид связи и сторона. [senior] — старший в паре (мать, а не сын);
 * у брата, двоюродного брата и супруга сторон нет — там всегда `true`. У «Другое» сторона говорит,
 * какая из двух подписей связи — этого клиента.
 */
data class RelationRole(val kind: RelationKind, val senior: Boolean = true) {
    /** Кем второй приходится первому: мать → сын или дочь; брат → брат или сестра. */
    val reverse: RelationRole get() = if (kind.symmetric) this else RelationRole(kind, !senior)
}

/** У связи нет старшего: брат и сестра, двоюродные, супруги. */
val RelationKind.symmetric: Boolean
    get() = this == RelationKind.SIBLING || this == RelationKind.COUSIN || this == RelationKind.SPOUSE

/** Связь со стороны одного клиента: кто второй, кем он приходится и подпись «Другое». */
data class RelativeLink(val relationId: String, val relativeId: String, val role: RelationRole, val label: String?)

/** Связь в том виде, как она пишется в базу: [aId] — старший, у каждого своя подпись «Другое». */
data class StoredRelation(val aId: String, val bId: String, val kind: RelationKind, val labelA: String?, val labelB: String?)

/** Чип «Кем приходится»: роль и пол, который она задаёт, — выбрана «Мать», значит родственник женского пола. */
data class RelationChoice(val role: RelationRole, val sex: Sex) {
    val word: String get() = Relations.word(role, sex)
}

/** Слова и стороны родственных связей (4.17). */
object Relations {
    /**
     * Кем приходится — по полу того, о ком слово (таблица 4.17): «Мать», «Сын»; пол неизвестен — слово без
     * рода: «Родитель», «Брат или сестра». У «Другое» — подпись тренера.
     */
    fun word(role: RelationRole, sex: Sex?, label: String? = null): String {
        val senior = role.senior
        return when (role.kind) {
            RelationKind.PARENT ->
                if (senior) RuText.bySex(sex, "Отец", "Мать", "Родитель") else RuText.bySex(sex, "Сын", "Дочь", "Ребёнок")
            RelationKind.GRANDPARENT ->
                if (senior) RuText.bySex(sex, "Дедушка", "Бабушка", "Бабушка или дедушка")
                else RuText.bySex(sex, "Внук", "Внучка", "Внук или внучка")
            RelationKind.AUNT_UNCLE ->
                if (senior) RuText.bySex(sex, "Дядя", "Тётя", "Дядя или тётя")
                else RuText.bySex(sex, "Племянник", "Племянница", "Племянник или племянница")
            RelationKind.SIBLING -> RuText.bySex(sex, "Брат", "Сестра", "Брат или сестра")
            RelationKind.COUSIN -> RuText.bySex(sex, "Двоюродный брат", "Двоюродная сестра", "Двоюродный брат или сестра")
            RelationKind.SPOUSE -> RuText.bySex(sex, "Муж", "Жена", "Супруг")
            RelationKind.OTHER -> label?.trim()?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercaseChar() }
                ?: RuText.bySex(sex, "Родственник", "Родственница", "Родственник")
        }
    }

    /** Запись связи со стороны клиента [viewerId]; связь чужой пары — `null`. */
    fun linkFor(viewerId: String, relationId: String, stored: StoredRelation): RelativeLink? = when (viewerId) {
        stored.bId -> RelativeLink(relationId, stored.aId, RelationRole(stored.kind, senior = true), stored.labelA)
        stored.aId -> RelativeLink(relationId, stored.bId, RelationRole(stored.kind, senior = stored.kind.symmetric), stored.labelB)
        else -> null
    }

    /**
     * Клиенту [clientId] добавлен родственник [relativeId], который приходится ему [role]. У «Другое»
     * [relativeLabel] — кем приходится родственник («Крёстная»), [clientLabel] — кем клиент ему («Крестник»).
     * Старший в паре становится A, у пары без старшего A — родственник.
     */
    fun orient(
        clientId: String,
        relativeId: String,
        role: RelationRole,
        relativeLabel: String? = null,
        clientLabel: String? = null,
    ): StoredRelation {
        val labels = role.kind == RelationKind.OTHER
        val relative = relativeLabel?.trim()?.takeIf { labels && it.isNotEmpty() }
        val client = clientLabel?.trim()?.takeIf { labels && it.isNotEmpty() }
        return if (role.senior || role.kind.symmetric) {
            StoredRelation(relativeId, clientId, role.kind, relative, client)
        } else {
            StoredRelation(clientId, relativeId, role.kind, client, relative)
        }
    }

    /**
     * Старше ли родственник клиента: по датам рождения, без них — взрослый старше ребёнка. Неизвестно — `null`.
     */
    fun older(relativeBirth: LocalDate?, relativeAdult: Boolean, clientBirth: LocalDate?, clientAdult: Boolean): Boolean? = when {
        relativeBirth != null && clientBirth != null -> if (relativeBirth == clientBirth) null else relativeBirth < clientBirth
        relativeAdult != clientAdult -> relativeAdult
        else -> null
    }

    /**
     * Чипы «Кем приходится» (4.17) — в роде родственника [sex]; пол неизвестен — оба рода рядом: «Отец», «Мать».
     * Старшему ([older]) — родители, бабушки и дедушки, дяди и тёти; младшему — дети, внуки, племянники;
     * неизвестно — все. Братья, сёстры и двоюродные — всегда; «Муж / Жена» — только если оба взрослые.
     * «Другое…» — отдельный чип со своей подписью.
     */
    fun choices(sex: Sex?, older: Boolean?, bothAdults: Boolean): List<RelationChoice> {
        val parent = RelationKind.PARENT
        val grand = RelationKind.GRANDPARENT
        val aunt = RelationKind.AUNT_UNCLE
        val sibling = RelationRole(RelationKind.SIBLING)
        val cousin = RelationRole(RelationKind.COUSIN)
        val roles = when (older) {
            true -> listOf(RelationRole(parent), RelationRole(grand), sibling, RelationRole(aunt), cousin)
            false -> listOf(RelationRole(parent, false), RelationRole(grand, false), sibling, RelationRole(aunt, false), cousin)
            null -> listOf(
                RelationRole(parent), RelationRole(parent, false), RelationRole(grand), RelationRole(grand, false),
                sibling, RelationRole(aunt), RelationRole(aunt, false), cousin,
            )
        } + if (bothAdults) listOf(RelationRole(RelationKind.SPOUSE)) else emptyList()
        val sexes = if (sex != null) listOf(sex) else listOf(Sex.MALE, Sex.FEMALE)
        return roles.flatMap { role -> sexes.map { RelationChoice(role, it) } }
    }

    /**
     * Связи, которые надо убрать при объединении [goneId] с [keepId] (4.17): связь двух дублей между собой
     * и повтор связи с тем же человеком — у оставшейся карточки уже есть своя. Остальные переходят к ней.
     * Возвращает id связей [gone], которые удаляются.
     */
    fun mergeDrops(keepId: String, goneId: String, keep: List<Pair<String, StoredRelation>>, gone: List<Pair<String, StoredRelation>>): Set<String> {
        val keepRelatives = keep.mapNotNull { (id, r) -> linkFor(keepId, id, r)?.relativeId }.toSet()
        return gone.filter { (id, r) ->
            val other = linkFor(goneId, id, r)?.relativeId
            other == null || other == keepId || other in keepRelatives
        }.map { it.first }.toSet()
    }
}
