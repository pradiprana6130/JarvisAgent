package com.jarvis.assistant.data.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY timestamp DESC")
    fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE rawText LIKE '%' || :query || '%' OR `key` LIKE '%' || :query || '%' OR `value` LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE rawText LIKE '%' || :query || '%' OR `key` LIKE '%' || :query || '%' OR `value` LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMemories(query: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: Long): MemoryEntity?

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM memories WHERE id IN (:ids)")
    suspend fun deleteMemoriesByIds(ids: List<Long>): Int

    @Query("SELECT * FROM memories WHERE `key` = :key AND userScope = :userScope LIMIT 1")
    suspend fun getMemoryByKey(key: String, userScope: String = MemoryEntity.SCOPE_OWNER): MemoryEntity?

    @Query("SELECT * FROM memories WHERE userScope = :userScope ORDER BY timestamp DESC")
    suspend fun getAllMemoriesForScope(userScope: String = MemoryEntity.SCOPE_OWNER): List<MemoryEntity>

    @Query("DELETE FROM memories WHERE `key` = :exactKey")
    suspend fun deleteMemoryByExactKey(exactKey: String): Int

    @Query("DELETE FROM memories WHERE `key` = :key AND userScope = :userScope")
    suspend fun deleteMemoryByKeyAndScope(key: String, userScope: String = MemoryEntity.SCOPE_OWNER): Int

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()
}
