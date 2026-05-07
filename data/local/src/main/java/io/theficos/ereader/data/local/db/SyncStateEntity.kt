package io.theficos.ereader.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val tableName: String,
    val lastPulledAt: Long,
)
