package com.opencode.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
@Dao
interface ServerConfigDao {

    @Query("SELECT * FROM servers ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ServerConfigEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getById(id: String): ServerConfigEntity?

    @Upsert
    suspend fun upsert(entity: ServerConfigEntity)

    @Delete
    suspend fun delete(entity: ServerConfigEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE servers SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long)
}

@Dao
interface OpenedDirectoryDao {

    @Query("SELECT * FROM opened_directories WHERE serverId = :serverId ORDER BY openedAt DESC")
    fun observeByServer(serverId: String): Flow<List<OpenedDirectoryEntity>>

    @Query("SELECT * FROM opened_directories WHERE serverId = :serverId ORDER BY openedAt DESC")
    suspend fun getByServer(serverId: String): List<OpenedDirectoryEntity>

    @Upsert
    suspend fun upsert(entity: OpenedDirectoryEntity)

    @Query("DELETE FROM opened_directories WHERE serverId = :serverId AND directory = :directory")
    suspend fun delete(serverId: String, directory: String)

    @Query("DELETE FROM opened_directories WHERE serverId = :serverId")
    suspend fun deleteAllForServer(serverId: String)
}
