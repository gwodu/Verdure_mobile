package com.verdure.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EntityMentionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EntityMentionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<EntityMentionEntity>)

    @Query("SELECT * FROM entities WHERE notificationId = :notificationId ORDER BY id ASC")
    suspend fun getByNotificationId(notificationId: Int): List<EntityMentionEntity>

    @Query("DELETE FROM entities WHERE notificationId = :notificationId")
    suspend fun deleteByNotificationId(notificationId: Int)
}
