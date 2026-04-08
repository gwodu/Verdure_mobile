package com.verdure.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Room database for persistent notification storage.
 * Stores all notifications for 24 hours (including dismissed ones).
 */
@Database(
    entities = [
        StoredNotification::class,
        EmbeddingEntity::class,
        EntityMentionEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class NotificationDatabase : RoomDatabase() {
    
    abstract fun notificationDao(): NotificationDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun entityMentionDao(): EntityMentionDao
    
    companion object {
        private const val TAG = "NotificationDatabase"
        private const val DB_NAME = "verdure_notifications.db"
        @Volatile
        private var INSTANCE: NotificationDatabase? = null
        
        fun getInstance(context: Context): NotificationDatabase {
            return INSTANCE ?: synchronized(this) {
                val driver = BundledSQLiteDriver()
                if (SQLiteVecLoader.registerOnDriver(context, driver)) {
                    Log.d(TAG, "sqlite-vec extension registered on BundledSQLiteDriver")
                } else {
                    Log.e(TAG, "sqlite-vec extension registration failed")
                }

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NotificationDatabase::class.java,
                    DB_NAME
                )
                .setDriver(driver)
                .fallbackToDestructiveMigration()  // For prototype - wipe DB on schema changes
                .addCallback(
                    object : Callback() {
                        override fun onOpen(connection: androidx.sqlite.SQLiteConnection) {
                            super.onOpen(connection)
                            try {
                                connection.execSQL(
                                    """
                                    CREATE VIRTUAL TABLE IF NOT EXISTS notification_embeddings_vec
                                    USING vec0(
                                        embedding_id INTEGER PRIMARY KEY,
                                        embedding float[384] distance_metric=cosine
                                    )
                                    """.trimIndent()
                                )
                                Log.d(TAG, "sqlite-vec virtual table ready (vec0 flat index, HNSW TODO)")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed creating sqlite-vec virtual table", e)
                            }
                        }
                    }
                )
                .build()
                
                INSTANCE = instance
                instance
            }
        }
    }
}
