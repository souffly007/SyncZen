package fr.bonobo.synczen.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Only ciphertext crosses the Room boundary. NasConfig is an in-memory model. */
@Entity(tableName = "nas_config")
data class StoredNasConfig(
    @PrimaryKey val id: Int,
    val serverType: ServerType,
    val address: String,
    val usernameEncrypted: String,
    val passwordEncrypted: String,
    val sourceFolderUris: String,
    val wifiSsid: String?,
    val requireCharging: Boolean,
    val conflictResolutionStrategy: ConflictResolutionStrategy,
    val lastSyncTimestamp: Long
)
