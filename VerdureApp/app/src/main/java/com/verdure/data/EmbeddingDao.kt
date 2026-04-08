package com.verdure.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(embedding: EmbeddingEntity): Long

    @Query("DELETE FROM embeddings WHERE createdAt < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long): Int

    @Query("SELECT * FROM embeddings WHERE sourceNotificationId = :sourceId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getBySourceId(sourceId: Int): EmbeddingEntity?

    @Query("SELECT * FROM embeddings WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<EmbeddingEntity>
}
