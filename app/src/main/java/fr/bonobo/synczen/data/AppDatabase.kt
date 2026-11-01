package fr.bonobo.synczen.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun fromServerType(value: ServerType): String = value.name
    @TypeConverter
    fun toServerType(value: String): ServerType = ServerType.valueOf(value)

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name
    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)

    @TypeConverter
    fun fromConflictResolutionStrategy(value: ConflictResolutionStrategy): String = value.name
    @TypeConverter
    fun toConflictResolutionStrategy(value: String): ConflictResolutionStrategy = ConflictResolutionStrategy.valueOf(value)
}

@Database(
    entities = [StoredNasConfig::class, SyncLogEntry::class, SyncFileRecord::class],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nasConfigDao(): NasConfigDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun syncFileDao(): SyncFileDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "synczen.db"
                ).addMigrations(
                    CredentialMigration(AndroidKeystoreCredentials.cipher),
                    SyncFileMigration(),
                    ConflictResolutionMigration()
                )
                    .addCallback(CredentialStorageCleanup())
                    .build().also { INSTANCE = it }
            }
    }
}
