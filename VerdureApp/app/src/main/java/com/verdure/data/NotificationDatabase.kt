package com.verdure.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for persistent notification storage.
 * Stores all notifications for 24 hours (including dismissed ones).
 */
@Database(
    entities = [StoredNotification::class],
    version = 1,
    exportSchema = false
)
abstract class NotificationDatabase : RoomDatabase() {
    
    abstract fun notificationDao(): NotificationDao
    
    companion object {
        @Volatile
        private var INSTANCE: NotificationDatabase? = null
        
        fun getInstance(context: Context): NotificationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NotificationDatabase::class.java,
                    "verdure_notifications.db"
                )
                .fallbackToDestructiveMigration()  // For prototype - wipe DB on schema changes
                .build()
                
                INSTANCE = instance
                instance
            }
        }
    }
}
