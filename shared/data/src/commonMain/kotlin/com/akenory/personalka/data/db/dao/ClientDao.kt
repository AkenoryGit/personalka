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

    @Upsert
    suspend fun upsertGroup(item: GroupEntity)

    @Query("SELECT * FROM client_group WHERE id = :id")
    suspend fun group(id: String): GroupEntity?

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

    @Upsert
    suspend fun upsertWaitEntry(item: WaitEntryEntity)

    @Query("SELECT * FROM wait_entry WHERE id = :id")
    suspend fun waitEntry(id: String): WaitEntryEntity?

    @Query("SELECT * FROM wait_entry WHERE status = :status AND deletedAt IS NULL ORDER BY createdAt")
    suspend fun waitEntries(status: WaitStatus): List<WaitEntryEntity>

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
}
