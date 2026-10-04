package fr.bonobo.synczen.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncStatus { SUCCESS, FAILURE, RUNNING }

@Entity(tableName = "sync_log")
data class SyncLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val status: SyncStatus,
    val message: String,
    val fileCount: Int = 0
)
