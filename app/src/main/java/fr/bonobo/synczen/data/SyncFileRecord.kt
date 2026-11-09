package fr.bonobo.synczen.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "sync_file", primaryKeys = ["destinationId", "pathKey"])
data class SyncFileRecord(
    val destinationId: String,
    val pathKey: String,
    val remotePath: String,
    val sha256: String,
    val size: Long,
    val published: Boolean
)

@Dao
interface SyncFileDao {
    @Query("SELECT * FROM sync_file WHERE destinationId = :destination AND pathKey = :path")
    suspend fun get(destination: String, path: String): SyncFileRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: SyncFileRecord)
}

class SyncFileMigration : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_file (
            destinationId TEXT NOT NULL, pathKey TEXT NOT NULL, remotePath TEXT NOT NULL,
            sha256 TEXT NOT NULL, size INTEGER NOT NULL, published INTEGER NOT NULL,
            PRIMARY KEY(destinationId, pathKey))""")
    }
}
