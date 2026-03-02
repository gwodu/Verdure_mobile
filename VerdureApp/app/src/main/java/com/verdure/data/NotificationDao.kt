package com.verdure.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Data Access Object for notification persistence.
 * Provides efficient queries for notification retrieval and search.
 */
@Dao
interface NotificationDao {
    
    /**
     * Insert a new notification (or replace if systemKey exists).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: StoredNotification): Long
    
    /**
     * Insert multiple notifications in batch.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotifications(notifications: List<StoredNotification>)
    
    /**
     * Update an existing notification (for dismissal tracking).
     */
    @Update
    suspend fun updateNotification(notification: StoredNotification)
    
    /**
     * Get all notifications from last 24 hours, sorted by priority score.
     * @param cutoffTime - Timestamp cutoff (System.currentTimeMillis() - 24h)
     */
    @Query("""
        SELECT * FROM notifications 
        WHERE timestamp > :cutoffTime 
        ORDER BY priorityScore DESC, timestamp DESC
    """)
    suspend fun getRecentNotifications(cutoffTime: Long): List<StoredNotification>
    
    /**
     * Get only priority notifications (score >= threshold) from last 24 hours.
     */
    @Query("""
        SELECT * FROM notifications 
        WHERE timestamp > :cutoffTime 
        AND priorityScore >= :minScore
        ORDER BY priorityScore DESC, timestamp DESC
        LIMIT :limit
    """)
    suspend fun getPriorityNotifications(
        cutoffTime: Long, 
        minScore: Int = 2,
        limit: Int = 50
    ): List<StoredNotification>
    
    /**
     * Search notifications by keyword in title or text.
     * @param query - Search term (case-insensitive)
     */
    @Query("""
        SELECT * FROM notifications 
        WHERE timestamp > :cutoffTime
        AND (
            title LIKE '%' || :query || '%' 
            OR text LIKE '%' || :query || '%'
            OR appName LIKE '%' || :query || '%'
        )
        ORDER BY priorityScore DESC, timestamp DESC
        LIMIT :limit
    """)
    suspend fun searchNotifications(
        query: String, 
        cutoffTime: Long,
        limit: Int = 20
    ): List<StoredNotification>
    
    /**
     * Get a specific notification by system key.
     */
    @Query("SELECT * FROM notifications WHERE systemKey = :systemKey LIMIT 1")
    suspend fun getBySystemKey(systemKey: String): StoredNotification?
    
    /**
     * Mark notification as dismissed.
     */
    @Query("""
        UPDATE notifications 
        SET isDismissed = 1, dismissedAt = :dismissedAt 
        WHERE systemKey = :systemKey
    """)
    suspend fun markDismissed(systemKey: String, dismissedAt: Long)
    
    /**
     * Delete notifications older than cutoff time (24h cleanup).
     */
    @Query("DELETE FROM notifications WHERE timestamp < :cutoffTime")
    suspend fun deleteOldNotifications(cutoffTime: Long): Int
    
    /**
     * Get count of notifications in database (for debugging).
     */
    @Query("SELECT COUNT(*) FROM notifications")
    suspend fun getCount(): Int
    
    /**
     * Get count of dismissed vs active notifications.
     */
    @Query("SELECT COUNT(*) FROM notifications WHERE isDismissed = 1")
    suspend fun getDismissedCount(): Int
}
