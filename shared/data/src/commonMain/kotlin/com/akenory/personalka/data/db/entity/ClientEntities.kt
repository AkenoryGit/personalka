package com.akenory.personalka.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.akenory.personalka.data.db.SyncFields
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.ConductedBy
import com.akenory.personalka.domain.model.ConsentSource
import com.akenory.personalka.domain.model.Owner
import com.akenory.personalka.domain.model.RecordSource
import com.akenory.personalka.domain.model.RelationKind
import com.akenory.personalka.domain.model.Sex
import com.akenory.personalka.domain.model.TimeRange
import com.akenory.personalka.domain.model.WaitStatus
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// ТЗ 3.5. Клиенты, группы, лист ожидания, родственные связи.
// В кабинет не передаются: privateNote, поля «Без записи», пол, связи, лист ожидания (6.2).

/** Клиент. Моменты — миллисекунды UTC, даты — дни в поясе клуба. */
@Entity(tableName = "client", indices = [Index("coachId", "status"), Index("lastName")])
data class ClientEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val lastName: String,
    val firstName: String,
    val middleName: String? = null,
    val birthDate: LocalDate? = null,
    /** Автоматически при 18+, иначе вручную. */
    val isAdult: Boolean = false,
    val sex: Sex? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    /** Ссылка на фото и видео (Яндекс Диск, Облако Mail.ru…). */
    val mediaUrl: String? = null,
    /** Внутренняя заметка; в кабинет не передаётся никогда. */
    val privateNote: String? = null,
    val name1c: String? = null,
    val cabinetEnabled: Boolean = false,
    val consentAt: Long? = null,
    val consentSource: ConsentSource? = null,
    /** «Написал» в списке «Без записи» (4.15), момент UTC. */
    val followupContactedAt: Long? = null,
    val followupPausedUntil: LocalDate? = null,
    val followupPauseNote: String? = null,
    /** «Убрать из списка» «Без записи» (шаг 3.9н), момент UTC: скрыт до новой индивидуальной тренировки. */
    val followupDismissedAt: Long? = null,
    /** План, которому не нашлось следующей тренировки (4.3). */
    val pendingPlan: String? = null,
    val status: ClientStatus = ClientStatus.ACTIVE,
    val source: RecordSource = RecordSource.MANUAL,
)

/** Постоянная группа: «Сб 9:00 ББ». Таблица `client_group`: слово GROUP занято в SQL. */
@Entity(tableName = "client_group", indices = [Index("coachId")])
data class GroupEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val name: String,
    val typeId: String,
    val locationId: String,
    /** На ком числится в 1С. */
    val owner: Owner = Owner.SELF,
    val ownerColleagueId: String? = null,
    val defaultConductedBy: ConductedBy = ConductedBy.SELF,
    val defaultColleagueId: String? = null,
    val pendingPlan: String? = null,
)

/** Участник группы с датами: ребёнок может уйти и вернуться — тогда записей несколько. */
@Entity(tableName = "group_member", indices = [Index("groupId"), Index("clientId")])
data class GroupMemberEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val groupId: String,
    val clientId: String,
    val fromDate: LocalDate,
    /** Пусто — в группе сейчас. */
    val toDate: LocalDate? = null,
    /** Вопрос «Не ходит 30 дней» (шаг 3.9у): когда последний раз нажали «Оставить» и сколько раз; после двух — не спрашивать. */
    val absenceKeptOn: LocalDate? = null,
    @ColumnInfo(defaultValue = "0") val absenceKeeps: Int = 0,
)

/**
 * Запись листа ожидания (4.16). Освободившееся время: [matchDate] — дата разового или дата,
 * с которой освободилось постоянное (день недели — по ней), [matchSlot] — время начала.
 */
@Entity(tableName = "wait_entry", indices = [Index("coachId", "status"), Index("clientId")])
data class WaitEntryEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val clientId: String,
    /** Ждёт постоянное расписание. */
    val wantsSeries: Boolean,
    /** Ждёт разовое посещение. Хотя бы одно из двух — true. */
    val wantsOneOff: Boolean,
    /** Пусто — любой день. */
    val days: Set<DayOfWeek> = emptySet(),
    /** Промежутки времени начала; пусто — любое время. */
    val timeRanges: List<TimeRange> = emptyList(),
    /** Пусто — любое место. */
    val locationId: String? = null,
    val typeId: String,
    /** Срок для разового. */
    val until: LocalDate? = null,
    val note: String? = null,
    val contactedAt: Long? = null,
    val matchOneOff: Boolean = false,
    val matchSlot: LocalTime? = null,
    val matchDate: LocalDate? = null,
    val status: WaitStatus = WaitStatus.WAITING,
    val closedAt: Long? = null,
    /** «Напомнить с» (шаг 3.9х): до этого дня клиента нет — уехал, занят; с него — «Пора записать». */
    val remindFrom: LocalDate? = null,
    /** До [remindFrom] строки не видно в «Ждут время». */
    @ColumnInfo(defaultValue = "0") val hideUntilRemind: Boolean = false,
)

/**
 * Родственная связь (4.17): одна запись на пару, обе карточки читают её со своей стороны.
 * Для PARENT, GRANDPARENT, AUNT_UNCLE клиент A — старший. Подписи — только для OTHER.
 * Связь с самим собой и вторую запись на ту же пару в обратном порядке не пускает код, который пишет связи (1.7).
 */
@Entity(
    tableName = "client_relation",
    indices = [Index("clientAId", "clientBId", unique = true), Index("clientBId")],
)
data class ClientRelationEntity(
    @PrimaryKey val id: String,
    @Embedded val sync: SyncFields,
    val clientAId: String,
    val clientBId: String,
    val kind: RelationKind,
    /** Кем A приходится B: «крёстная». */
    val labelA: String? = null,
    /** Кем B приходится A: «крестник». */
    val labelB: String? = null,
)
