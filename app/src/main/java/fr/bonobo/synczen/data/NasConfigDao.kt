package fr.bonobo.synczen.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NasConfigDao {
    @Query("SELECT * FROM nas_config WHERE id = 1")
    fun observeConfig(): Flow<NasConfig?>

    @Query("SELECT * FROM nas_config WHERE id = 1")
    suspend fun getConfig(): NasConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: NasConfig)

    @Query("UPDATE nas_config SET lastSyncTimestamp = :timestamp WHERE id = 1")
    suspend fun updateLastSync(timestamp: Long)
}
