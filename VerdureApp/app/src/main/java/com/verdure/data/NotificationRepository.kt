package com.verdure.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for notification persistence and retrieval.
 * Provides clean API for storing, querying, and managing notifications.
 */
class NotificationRepository(context: Context) {
    
    private val database = NotificationDatabase.getInstance(context)
    private val dao = database.notificationDao()
    
    companion object {
        private const val TAG = "NotificationRepo"
        private const val RETENTION_PERIOD_MS = 24 * 60 * 60 * 1000L  // 24 hours
        
        @Volatile
        private var INSTANCE: NotificationRepository? = null
        
        fun getInstance(context: Context): NotificationRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = NotificationRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
    
    /**
     * Store a new notification with its priority score.
     */
    suspend fun storeNotification(
        notificationData: NotificationData,
        priorityScore: Int
    ) = withContext(Dispatchers.IO) {
        try {
            val stored = StoredNotification.fromNotificationData(notificationData, priorityScore)
            dao.insertNotification(stored)
            Log.d(TAG, "Stored notification: ${notificationData.appName} (score: $priorityScore)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store notification", e)
        }
    }
    
    /**
     * Get all recent notifications (last 24 hours).
     */
    suspend fun getRecentNotifications(limit: Int = 50): List<StoredNotification> = 
        withContext(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - RETENTION_PERIOD_MS
                val notifications = dao.getRecentNotifications(cutoff)
                Log.d(TAG, "Retrieved ${notifications.size} recent notifications")
                notifications.take(limit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get recent notifications", e)
                emptyList()
            }
        }
    
    /**
     * Get priority notifications (score >= 2) from last 24 hours.
     */
    suspend fun getPriorityNotifications(
        minScore: Int = 2,
        limit: Int = 50
    ): List<StoredNotification> = withContext(Dispatchers.IO) {
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_PERIOD_MS
            val notifications = dao.getPriorityNotifications(cutoff, minScore, limit)
            Log.d(TAG, "Retrieved ${notifications.size} priority notifications")
            notifications
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get priority notifications", e)
            emptyList()
        }
    }
    
    /**
     * Search notifications by keyword.
     */
    suspend fun searchNotifications(
        query: String,
        limit: Int = 20
    ): List<StoredNotification> = withContext(Dispatchers.IO) {
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_PERIOD_MS
            val notifications = dao.searchNotifications(query, cutoff, limit)
            Log.d(TAG, "Search '$query' found ${notifications.size} notifications")
            notifications
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search notifications", e)
            emptyList()
        }
    }
    
    /**
     * Mark notification as dismissed in database.
     * Notification persists in DB for 24h but flagged as dismissed.
     */
    suspend fun markDismissed(systemKey: String) = withContext(Dispatchers.IO) {
        try {
            dao.markDismissed(systemKey, System.currentTimeMillis())
            Log.d(TAG, "Marked notification as dismissed: $systemKey")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark notification as dismissed", e)
        }
    }
    
    /**
     * Mark multiple notifications as dismissed.
     */
    suspend fun markMultipleDismissed(systemKeys: List<String>) = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            systemKeys.forEach { systemKey ->
                dao.markDismissed(systemKey, now)
            }
            Log.d(TAG, "Marked ${systemKeys.size} notifications as dismissed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark notifications as dismissed", e)
        }
    }
    
    /**
     * Clean up notifications older than 24 hours.
     * Should be called periodically (e.g., on app startup).
     */
    suspend fun cleanupOldNotifications() = withContext(Dispatchers.IO) {
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_PERIOD_MS
            val deleted = dao.deleteOldNotifications(cutoff)
            if (deleted > 0) {
                Log.d(TAG, "Cleaned up $deleted old notifications")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old notifications", e)
        }
    }
    
    /**
     * Get database statistics (for debugging).
     */
    suspend fun getStats(): NotificationStats = withContext(Dispatchers.IO) {
        try {
            val total = dao.getCount()
            val dismissed = dao.getDismissedCount()
            NotificationStats(total, dismissed, total - dismissed)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get stats", e)
            NotificationStats(0, 0, 0)
        }
    }
    
    data class NotificationStats(
        val total: Int,
        val dismissed: Int,
        val active: Int
    )
}
