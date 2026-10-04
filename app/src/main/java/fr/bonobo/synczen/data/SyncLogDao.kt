package fr.bonobo.synczen.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncLogDao {
    @Query("SELECT * FROM sync_log ORDER BY timestamp DESC LIMIT 50")
    fun observeRecent(): Flow<List<SyncLogEntry>>

    @Insert
    suspend fun insert(entry: SyncLogEntry): Long
}
