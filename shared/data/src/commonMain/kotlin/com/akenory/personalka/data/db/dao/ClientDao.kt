package com.akenory.personalka.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.akenory.personalka.data.db.entity.ClientEntity
import com.akenory.personalka.data.db.entity.ClientRelationEntity
import com.akenory.personalka.data.db.entity.GroupEntity
import com.akenory.personalka.data.db.entity.GroupMemberEntity
import com.akenory.personalka.data.db.entity.WaitEntryEntity
import com.akenory.personalka.domain.model.ClientStatus
import com.akenory.personalka.domain.model.WaitStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * Клиенты, группы и их состав, лист ожидания, родственные связи (ТЗ 3.5).
 * Порядок по алфавиту — в коде, а не в SQL: SQLite ставит «Ё» после «Я».
 */
@Dao
interface ClientDao {
    @Upsert
    suspend fun upsertClient(item: ClientEntity)

    @Query("SELECT * FROM client WHERE id = :id")
    suspend fun client(id: String): ClientEntity?

    @Query("SELECT * FROM client WHERE status = :status AND deletedAt IS NULL")
    fun observeClients(status: ClientStatus): Flow<List<ClientEntity>>

    /** Все клиенты — и действующие, и в архиве: список с фильтрами и проверка похожих (5.8). */
    @Query("SELECT * FROM client WHERE deletedAt IS NULL")
    fun observeAllClients(): Flow<List<ClientEntity>>

    @Query("SELECT * FROM client WHERE id = :id")
    fun observeClient(id: String): Flow<ClientEntity?>

    /** «Удалить полностью» (3.1): данные клиента стираются сразу, без отметки об удалении. */
    @Query("DELETE FROM client WHERE id = :id")
    suspend fun eraseClient(id: String)

    @Upsert
    suspend fun upsertGroup(item: GroupEntity)

    @Query("SELECT * FROM client_group WHERE id = :id")
    suspend fun group(id: String): GroupEntity?

    /** Группы тренера (5.10). Порядок — в коде. */
    @Query("SELECT * FROM client_group WHERE deletedAt IS NULL")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM client_group WHERE deletedAt IS NULL")
    suspend fun groups(): List<GroupEntity>

    @Upsert
    suspend fun upsertGroupMember(item: GroupMemberEntity)

    /** Состав группы на дату: пришёл не позже даты и не ушёл раньше неё. */
    @Query(
        """
        SELECT * FROM group_member
        WHERE groupId = :groupId AND deletedAt IS NULL
          AND fromDate <= :date AND (toDate IS NULL OR toDate >= :date)
        """
    )
    suspend fun membersOn(groupId: String, date: LocalDate): List<GroupMemberEntity>

    /** Состав всех групп, кто в группе на [date] или придёт позже: расписание в списке клиентов. */
    @Query("SELECT * FROM group_member WHERE deletedAt IS NULL AND (toDate IS NULL OR toDate >= :date)")
    fun observeMembersFrom(date: LocalDate): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_member WHERE clientId = :clientId AND deletedAt IS NULL")
    suspend fun membershipsOf(clientId: String): List<GroupMemberEntity>

    /** Состав всех групп с датами, и ушедшие: число детей в списке групп, состав в карточке (5.10). */
    @Query("SELECT * FROM group_member WHERE deletedAt IS NULL")
    fun observeAllMembers(): Flow<List<GroupMemberEntity>>

    /** Все записи состава группы с датами. */
    @Query("SELECT * FROM group_member WHERE groupId = :groupId AND deletedAt IS NULL")
    suspend fun membersOf(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_member WHERE id = :id")
    suspend fun groupMember(id: String): GroupMemberEntity?

    @Query("DELETE FROM group_member WHERE clientId = :clientId")
    suspend fun eraseMembershipsOf(clientId: String)

    @Upsert
    suspend fun upsertWaitEntry(item: WaitEntryEntity)

    @Query("SELECT * FROM wait_entry WHERE id = :id")
    suspend fun waitEntry(id: String): WaitEntryEntity?

    @Query("SELECT * FROM wait_entry WHERE status = :status AND deletedAt IS NULL ORDER BY createdAt")
    suspend fun waitEntries(status: WaitStatus): List<WaitEntryEntity>

    @Query("SELECT * FROM wait_entry WHERE clientId = :clientId AND deletedAt IS NULL")
    suspend fun waitEntriesOf(clientId: String): List<WaitEntryEntity>

    @Query("DELETE FROM wait_entry WHERE clientId = :clientId")
    suspend fun eraseWaitEntriesOf(clientId: String)

    @Upsert
    suspend fun upsertRelation(item: ClientRelationEntity)

    /** Связи клиента с обеих сторон пары. */
    @Query(
        """
        SELECT * FROM client_relation
        WHERE (clientAId = :clientId OR clientBId = :clientId) AND deletedAt IS NULL
        """
    )
    suspend fun relationsOf(clientId: String): List<ClientRelationEntity>

    /** То же наблюдением: связь, добавленная в одной карточке, сразу видна во второй (4.17). */
    @Query(
        """
        SELECT * FROM client_relation
        WHERE (clientAId = :clientId OR clientBId = :clientId) AND deletedAt IS NULL
        """
    )
    fun observeRelationsOf(clientId: String): Flow<List<ClientRelationEntity>>

    @Query("SELECT * FROM client_relation WHERE id = :id")
    suspend fun relation(id: String): ClientRelationEntity?

    /**
     * Запись связи пары в любом порядке, и удалённая тоже: уникальный индекс помнит удалённые записи,
     * поэтому связь, убранную и добавленную снова, пишет та же запись.
     */
    @Query(
        """
        SELECT * FROM client_relation
        WHERE (clientAId = :x AND clientBId = :y) OR (clientAId = :y AND clientBId = :x)
        """
    )
    suspend fun relationRowsBetween(x: String, y: String): List<ClientRelationEntity>

    @Query("DELETE FROM client_relation WHERE clientAId = :clientId OR clientBId = :clientId")
    suspend fun eraseRelationsOf(clientId: String)
}
