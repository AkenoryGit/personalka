package com.akenory.personalka.data.db.entity

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.domain.model.SwimStyle
import kotlinx.datetime.LocalDate

// ТЗ 3.5, 5.21 (шаг 5.38): соревнования — сами соревнования, участники и их старты. В кабинет не передаются.

/**
 * Соревнование. [rules] — «кто допускается» (`M 2010 2015;F 2008 2013`), [items] — «что собрать» строками
 * «id\tназвание\tкопейки»; разбирает и собирает их `CompetitionRules`.
 */
@Entity(tableName = "competition", indices = [Index("date")])
data class CompetitionEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val name: String,
    /** День соревнования (первый день многодневных). */
    val date: LocalDate,
    /** Бассейн 25 или 50 м. */
    val poolM: Int,
    val place: String? = null,
    val rules: String = "",
    val items: String = "",
)

/** Участник соревнования: клиент и id сданных пунктов «Что собрать» через пробел. */
@Entity(tableName = "competition_entry", indices = [Index("competitionId"), Index("clientId")])
data class CompetitionEntryEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val competitionId: String,
    val clientId: String,
    val handedIn: String = "",
)

/**
 * Старт участника: дистанция в бассейне соревнования. [entryCs] — заявочное время, введённое вручную (пусто —
 * лучшее до дня соревнования), [resultCs] и [place] — итог, [resultId] — результат в карточке клиента.
 */
@Entity(tableName = "competition_start", indices = [Index("entryId"), Index("competitionId"), Index("clientId")])
data class CompetitionStartEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val competitionId: String,
    val entryId: String,
    val clientId: String,
    val style: SwimStyle,
    val distanceM: Int,
    val entryCs: Int? = null,
    val resultCs: Int? = null,
    val place: Int? = null,
    val resultId: String? = null,
)
