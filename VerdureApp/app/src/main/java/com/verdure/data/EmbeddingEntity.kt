package com.verdure.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "embeddings",
    foreignKeys = [
        ForeignKey(
            entity = StoredNotification::class,
            parentColumns = ["id"],
            childColumns = ["sourceNotificationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sourceNotificationId"]),
        Index(value = ["createdAt"])
    ]
)
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceNotificationId: Int,
    val summaryText: String,
    val createdAt: Long = System.currentTimeMillis(),
    val modelVersion: String
)
