package com.akenory.personalka.data.db.entity

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.ParticipationStatus
import com.akenory.personalka.domain.model.ResultKind
import com.akenory.personalka.domain.model.SptCaseStatus
import com.akenory.personalka.domain.model.SwimStyle
import com.akenory.personalka.domain.model.TrainingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

// ТЗ 3.5. Тренировки, участие, результаты, стартовые тренировки.

/** Тренировка или дежурство. [start] — локальное время в поясе клуба. */
@Entity(
    tableName = "training",
    indices = [Index("coachId", "start"), Index("seriesId"), Index("groupId")],
)
data class TrainingEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val typeId: String,
    val locationId: String,
    val start: LocalDateTime,
    /** От 5 до 600 минут. */
    val durationMin: Int,
    val seriesId: String? = null,
    /** Изменена отдельно от серии: правка серии её не перезаписывает. */
    val seriesDetached: Boolean = false,
    val groupId: String? = null,
    val status: TrainingStatus = TrainingStatus.PLANNED,
    /** Дневник; у групп общий для всех. */
    val plan: String? = null,
    val fact: String? = null,
    /** Для негрупповых всегда SELF. */
    val owner: Owner = Owner.SELF,
    val ownerColleagueId: String? = null,
    val conductedBy: ConductedBy = ConductedBy.SELF,
    /** С кем провёл или кто заменил. */
    val colleagueId: String? = null,
    /** Сплит с одним клиентом засчитать как пол-сплита (4.7). */
    val splitHalf: Boolean = false,
    /** Причина отмены всей тренировки. */
    val cancelReason: String? = null,
    /** Поставлена поверх запрета; пересчитывается при каждом изменении. */
    val overRestriction: Boolean = false,
    /** Откуда и какой текст плана перенесён — для отката (4.3). */
    val planCarriedFromId: String? = null,
    val planCarriedText: String? = null,
    /** Когда отмечена проведённой, момент UTC. */
    val doneAt: Long? = null,
)

/** Участие клиента. У ПТ и СПТ одно, у сплита до двух, у группы сколько угодно, у дежурства ни одного. */
@Entity(
    tableName = "participation",
    indices = [Index("trainingId", "clientId", unique = true), Index("clientId")],
)
data class ParticipationEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val trainingId: String,
    val clientId: String,
    val status: ParticipationStatus = ParticipationStatus.EXPECTED,
    /** Только если поведение включено у типа. */
    val behavior: String? = null,
    val cancelReason: String? = null,
    /** Когда предупредили об отмене — локальное время клуба. */
    val notifiedAt: LocalDateTime? = null,
    /** Статус списания изменён вручную. */
    val chargeOverridden: Boolean = false,
)

/** Результат на дистанции. Время — сотые доли секунды: 52,16 с = 5216. */
@Entity(tableName = "result", indices = [Index("clientId", "date")])
data class ResultEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val clientId: String,
    val date: LocalDate,
    val style: SwimStyle,
    /** 25, 50, 100, 200, 400, 800, 1500. */
    val distanceM: Int,
    /** Бассейн 25 или 50 м. */
    val poolM: Int,
    val timeCs: Int,
    val kind: ResultKind,
    val eventName: String? = null,
    val note: String? = null,
)

/** Стартовая тренировка и её закрытие покупкой; окно — до той же даты следующего месяца. */
@Entity(tableName = "spt_case", indices = [Index("clientId"), Index("coachId", "status")])
data class SptCaseEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val clientId: String,
    val sptTrainingId: String,
    val sptDate: LocalDate,
    val windowUntil: LocalDate,
    val purchaseDate: LocalDate? = null,
    val status: SptCaseStatus = SptCaseStatus.OPEN,
)
