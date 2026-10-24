package com.nirzor.voicebubble.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class VoiceHistoryRepository(
    private val dao: VoiceHistoryDao,
    private val preferences: AppPreferences
) {
    val pagedHistory: Flow<PagingData<VoiceHistoryPreviewDto>> = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false)
    ) {
        dao.getPagedHistory()
    }.flow

    val pagedFavoriteHistory: Flow<PagingData<VoiceHistoryPreviewDto>> = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false)
    ) {
        dao.getPagedFavoriteHistory()
    }.flow

    val recentHistory: Flow<List<VoiceHistoryEntity>> = dao.getRecentHistory()
    val totalCount: Flow<Int> = dao.getTotalCount()
    val favoriteCount: Flow<Int> = dao.getFavoriteCount()
    val totalWords: Flow<Int?> = dao.getTotalWords()
    val totalChars: Flow<Int?> = dao.getTotalChars()

    fun sanitizeQuery(q: String): String =
        q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun searchHistoryPreviews(query: String): Flow<List<VoiceHistoryPreviewDto>> =
        dao.searchHistoryPreviews(sanitizeQuery(query))

    suspend fun getById(id: Long): VoiceHistoryEntity? = dao.getById(id)

    suspend fun getAllEntriesSync(): List<VoiceHistoryEntity> = dao.getAllEntriesSync()

    suspend fun exportToJson(): String = withContext(Dispatchers.IO) {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val type = Types.newParameterizedType(List::class.java, VoiceHistoryEntity::class.java)
        val adapter = moshi.adapter<List<VoiceHistoryEntity>>(type)
        val items = dao.getAllEntriesSync()
        adapter.toJson(items)
    }

    suspend fun importFromJson(jsonString: String): Int = withContext(Dispatchers.IO) {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val type = Types.newParameterizedType(List::class.java, VoiceHistoryEntity::class.java)
        val adapter = moshi.adapter<List<VoiceHistoryEntity>>(type)
        val parsedItems = adapter.fromJson(jsonString).orEmpty()
        val normalized = parsedItems.map { it.copy(id = 0) }
        val insertedIds = dao.insertAllIgnoreConflicts(normalized)
        insertedIds.count { it != -1L }
    }

    suspend fun insert(item: VoiceHistoryEntity): Long {
        val id = dao.insert(item)
        pruneIfRequired()
        return id
    }

    suspend fun update(item: VoiceHistoryEntity) = dao.update(item)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteByIds(ids: List<Long>) = dao.deleteByIds(ids)

    suspend fun clearAll() = dao.clearAll()

    suspend fun toggleFavorite(id: Long, isFavorite: Boolean) = dao.updateFavorite(id, isFavorite)

    suspend fun pruneIfRequired() {
        val retentionDays = preferences.historyRetentionDays.value
        if (retentionDays > 0) {
            val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 24L * 60L * 60L * 1000L)
            dao.pruneOlderThan(cutoff)
        }
    }
}
