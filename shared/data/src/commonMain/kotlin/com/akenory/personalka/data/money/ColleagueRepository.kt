package com.akenory.personalka.data.money

import androidx.room3.withWriteTransaction
import com.akenory.personalka.data.db.PersonalkaDatabase
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.data.db.entity.ColleagueEntity
import com.akenory.personalka.data.db.entity.ColleagueSchemeVersionEntity
import com.akenory.personalka.domain.Ids
import com.akenory.personalka.domain.client.ClientRules
import com.akenory.personalka.domain.model.PaySchemeKind
import com.akenory.personalka.domain.money.CoCoachingRules
import com.akenory.personalka.domain.money.SalarySetupRules
import com.akenory.personalka.domain.money.VersionEdit
import com.akenory.personalka.domain.money.Versions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.LocalDate

/** Коллега в «Коллегах» (5.16): [kind] — схема на сегодня, [schemes] — все версии по дате начала. */
data class ColleagueRow(
    val colleague: ColleagueEntity,
    val kind: PaySchemeKind?,
    val schemes: List<ColleagueSchemeVersionEntity>,
)

/**
 * Коллеги и их схемы выплат версиями (ТЗ 3.6, 4.8, 5.16, шаг 3.7г): с кем тренер ведёт группы и кто его заменяет.
 * Коллегу не удаляют — убирают в архив: история групп и взаиморасчёт помнят его имя.
 *
 * @param clock миллисекунды UTC; тесты подставляют свои часы.
 */
class ColleagueRepository(private val db: PersonalkaDatabase, private val clock: () -> Long) {
    private val money = db.moneyDao()
    private val coaches = db.coachDao()

    /** Коллеги по имени, со схемой на [today]; архивные — тоже, экран показывает их отдельно. */
    fun rows(today: LocalDate): Flow<List<ColleagueRow>> =
        combine(money.observeColleagues(), money.observeColleagueSchemes()) { colleagues, schemes ->
            val byColleague = schemes.groupBy { it.colleagueId }
            colleagues.sortedBy { ClientRules.key(it.name) }.map { c ->
                val own = byColleague[c.id].orEmpty()
                ColleagueRow(c, Versions.activeOn(own, today) { it.validFrom }?.kind, own)
            }
        }

    /** Новый коллега: схема — с 1 января ([CoCoachingRules.firstFrom]). Возвращает id. */
    suspend fun add(name: String, kind: PaySchemeKind, today: LocalDate): String {
        val coach = coaches.currentCoach() ?: error("Коллеги появляются после первого запуска")
        val now = clock()
        val id = Ids.newId(now)
        db.withWriteTransaction {
            money.upsertColleague(ColleagueEntity(id, SyncFields.new(coach.id, now), name.trim()))
            money.upsertColleagueScheme(
                ColleagueSchemeVersionEntity(Ids.newId(now), SyncFields.new(coach.id, now), id, CoCoachingRules.firstFrom(today), kind),
            )
        }
        return id
    }

    /**
     * Имя и схема с даты [from] (1-е число месяца): та же схема, что действует, — версии нет; версия того же месяца
     * правится.
     */
    suspend fun update(id: String, name: String, kind: PaySchemeKind, from: LocalDate) {
        val coach = coaches.currentCoach() ?: return
        val now = clock()
        db.withWriteTransaction {
            val colleague = money.colleague(id) ?: return@withWriteTransaction
            if (colleague.name != name.trim()) money.upsertColleague(colleague.copy(sync = colleague.sync.touched(now), name = name.trim()))
            val versions = money.colleagueSchemes().filter { it.colleagueId == id }
            when (SalarySetupRules.edit(versions, from, { it.validFrom }) { it.kind == kind }) {
                VersionEdit.NONE -> Unit
                VersionEdit.REPLACE -> versions.last { it.validFrom == from }.let {
                    money.upsertColleagueScheme(it.copy(sync = it.sync.touched(now), kind = kind))
                }
                VersionEdit.ADD -> money.upsertColleagueScheme(
                    ColleagueSchemeVersionEntity(Ids.newId(now), SyncFields.new(coach.id, now), id, from, kind),
                )
            }
        }
    }

    /** В архив и обратно: архивного нет в выборе коллеги, но прошлые группы и взаиморасчёт его помнят. */
    suspend fun archive(id: String, archived: Boolean) {
        val colleague = money.colleague(id) ?: return
        if (colleague.archived == archived) return
        money.upsertColleague(colleague.copy(sync = colleague.sync.touched(clock()), archived = archived))
    }
}
