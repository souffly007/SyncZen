package fr.bonobo.synczen.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.map

class NasConfigRepository(
    private val db: AppDatabase,
    private val cipher: CredentialCipher = AndroidKeystoreCredentials.cipher
) {
    private val dao = db.nasConfigDao()
    fun observeConfig() = dao.observeConfig().map { it?.let(::decode) }
    suspend fun getConfig(): NasConfig? = dao.getConfig()?.let(::decode)

    suspend fun upsert(config: NasConfig) = db.withTransaction {
        // An unreadable existing row must NEVER be replaced by a blank UI model.
        val existing = dao.getConfig()?.let(::decode)
        dao.upsert(StoredNasConfig(
            config.id,
            config.serverType,
            config.address,
            cipher.encrypt(config.username, "${config.id}:username"),
            cipher.encrypt(config.password, "${config.id}:password"),
            config.sourceFolderUris,
            config.wifiSsid,
            config.requireCharging,
            config.conflictResolutionStrategy,
            existing?.lastSyncTimestamp ?: config.lastSyncTimestamp
        ))
    }

    private fun decode(row: StoredNasConfig) = NasConfig(
        row.id,
        row.serverType,
        row.address,
        cipher.decrypt(row.usernameEncrypted, "${row.id}:username"),
        cipher.decrypt(row.passwordEncrypted, "${row.id}:password"),
        row.sourceFolderUris,
        row.wifiSsid,
        row.requireCharging,
        row.conflictResolutionStrategy,
        row.lastSyncTimestamp
    )
}
