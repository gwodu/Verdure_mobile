package com.verdure.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "entities",
    foreignKeys = [
        ForeignKey(
            entity = StoredNotification::class,
            parentColumns = ["id"],
            childColumns = ["notificationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("notificationId"), Index("value")]
)
data class EntityMentionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notificationId: Int,
    val type: String,
    val value: String,
    val createdAt: Long = System.currentTimeMillis()
)
