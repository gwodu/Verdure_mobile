package com.verdure.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for persistent notification storage.
 * Stores all notification data for 24 hours even after dismissal.
 */
@Entity(
    tableName = "notifications",
    indices = [Index(value = ["systemKey"], unique = true)]
)
data class StoredNotification(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val systemKey: String,
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val timestamp: Long,
    val isClearable: Boolean,
    val category: String?,
    val priority: Int,
    
    // Metadata for enhanced scoring
    val hasActions: Boolean = false,
    val hasImage: Boolean = false,
    val isOngoing: Boolean = false,
    
    // Dismissal tracking
    val isDismissed: Boolean = false,
    val dismissedAt: Long? = null,
    
    // Cached priority score (calculated when stored)
    val priorityScore: Int = 0
) {
    /**
     * Convert to NotificationData for compatibility with existing code.
     * Note: contentIntent is lost (can't serialize PendingIntent).
     */
    fun toNotificationData(): NotificationData {
        return NotificationData(
            id = id,
            systemKey = systemKey,
            packageName = packageName,
            appName = appName,
            title = title,
            text = text,
            timestamp = timestamp,
            isClearable = isClearable,
            category = category,
            priority = priority,
            contentIntent = null,  // Can't persist PendingIntent
            hasActions = hasActions,
            hasImage = hasImage,
            isOngoing = isOngoing
        )
    }
    
    /**
     * Get a human-readable timestamp string.
     */
    fun getFormattedTime(): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> "${diff / 3600_000}h ago"
            else -> "${diff / 86400_000}d ago"
        }
    }
    
    companion object {
        /**
         * Convert from NotificationData (what listener captures)
         * to StoredNotification (what Room persists).
         */
        fun fromNotificationData(data: NotificationData, priorityScore: Int): StoredNotification {
            return StoredNotification(
                // Let Room generate IDs; systemKey uniqueness handles upsert semantics.
                id = 0,
                systemKey = data.systemKey,
                packageName = data.packageName,
                appName = data.appName,
                title = data.title,
                text = data.text,
                timestamp = data.timestamp,
                isClearable = data.isClearable,
                category = data.category,
                priority = data.priority,
                hasActions = data.hasActions,
                hasImage = data.hasImage,
                isOngoing = data.isOngoing,
                priorityScore = priorityScore
            )
        }
    }
}

fun StoredNotification.toNotificationDataForScoring(): NotificationData {
    return NotificationData(
        id = id,
        systemKey = systemKey,
        packageName = packageName,
        appName = appName,
        title = title,
        text = text,
        timestamp = timestamp,
        isClearable = isClearable,
        category = category,
        priority = priority,
        contentIntent = null,
        hasActions = hasActions,
        hasImage = hasImage,
        isOngoing = isOngoing
    )
}
