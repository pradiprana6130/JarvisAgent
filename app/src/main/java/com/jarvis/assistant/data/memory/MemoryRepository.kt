package com.jarvis.assistant.data.memory

import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val memoryDao: MemoryDao) {

    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = memoryDao.getAllMemoriesFlow()

    suspend fun getAllMemories(): List<MemoryEntity> = memoryDao.getAllMemories()

    fun getMemoriesByCategoryFlow(category: String): Flow<List<MemoryEntity>> {
        return if (category == "All") {
            memoryDao.getAllMemoriesFlow()
        } else {
            memoryDao.getMemoriesByCategoryFlow(category)
        }
    }

    fun searchMemoriesFlow(query: String): Flow<List<MemoryEntity>> = memoryDao.searchMemoriesFlow(query)

    suspend fun searchMemories(query: String): List<MemoryEntity> = memoryDao.searchMemories(query)

    suspend fun getMemoryByKey(key: String, userScope: String = MemoryEntity.SCOPE_OWNER): MemoryEntity? {
        return memoryDao.getMemoryByKey(key, userScope)
    }

    suspend fun getAllMemoriesForScope(userScope: String = MemoryEntity.SCOPE_OWNER): List<MemoryEntity> {
        return memoryDao.getAllMemoriesForScope(userScope)
    }

    suspend fun saveMemory(
        rawText: String,
        category: String = MemoryEntity.CATEGORY_NOTES,
        key: String = "",
        value: String = "",
        userScope: String = MemoryEntity.SCOPE_OWNER,
        dateSemantics: String? = null
    ): Long {
        val calculatedKey = key.ifBlank {
            rawText.take(40).trim()
        }
        val calculatedValue = value.ifBlank { rawText }

        // Deduplication: update existing record for userScope + key if present
        val existing = memoryDao.getMemoryByKey(calculatedKey, userScope)
        return if (existing != null) {
            val updated = existing.copy(
                category = category,
                value = calculatedValue,
                rawText = rawText,
                timestamp = System.currentTimeMillis(),
                dateSemantics = dateSemantics ?: existing.dateSemantics,
                status = MemoryEntity.STATUS_ACTIVE
            )
            memoryDao.updateMemory(updated)
            existing.id
        } else {
            val entity = MemoryEntity(
                category = category,
                key = calculatedKey,
                value = calculatedValue,
                rawText = rawText,
                timestamp = System.currentTimeMillis(),
                userScope = userScope,
                dateSemantics = dateSemantics,
                status = MemoryEntity.STATUS_ACTIVE
            )
            memoryDao.insertMemory(entity)
        }
    }

    suspend fun updateMemory(memory: MemoryEntity) {
        memoryDao.updateMemory(memory)
    }

    suspend fun getMemoryById(id: Long): MemoryEntity? {
        return memoryDao.getMemoryById(id)
    }

    suspend fun deleteMemoryById(id: Long) {
        memoryDao.deleteMemoryById(id)
    }

    suspend fun deleteMemoriesByIds(ids: List<Long>): Int {
        return memoryDao.deleteMemoriesByIds(ids)
    }

    suspend fun deleteMemoryByExactKey(exactKey: String): Int {
        return memoryDao.deleteMemoryByExactKey(exactKey)
    }

    suspend fun clearAll() {
        memoryDao.clearAllMemories()
    }
}
