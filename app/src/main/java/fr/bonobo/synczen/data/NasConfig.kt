package fr.bonobo.synczen.data


/**
 * Type de serveur supporté par SyncZen.
 */
enum class ServerType {
    /** Server Message Block - Protocole standard pour le partage de fichiers en réseau local */
    SMB,
    /** Web Distributed Authoring and Versioning - Protocole HTTP pour l'accès aux fichiers */
    WEBDAV
}

/**
 * Stratégie de résolution des conflits lorsque le fichier existe déjà sur le serveur.
 * 
 * @property SKIP Sauter le fichier (comportement par défaut, le plus sûr)
 * @property OVERWRITE Écraser le fichier existant avec le nouveau
 * @property RENAME Renommer le nouveau fichier avec un suffixe timestamp pour éviter les conflits
 */
enum class ConflictResolutionStrategy {
    /** Sauter le fichier (comportement par défaut, le plus sûr) */
    SKIP,
    /** Écraser le fichier existant avec le nouveau */
    OVERWRITE,
    /** Renommer le nouveau fichier avec un suffixe timestamp */
    RENAME
}

/**
 * Configuration de la cible de sauvegarde (NAS / serveur) et des dossiers
 * locaux à surveiller. Une seule ligne active à la fois pour le MVP.
 */
data class NasConfig(
    val id: Int = 1,
    val serverType: ServerType = ServerType.SMB,
    val address: String = "",       // ex: smb://192.168.1.50/partage ou http://192.168.1.50:8080/webdav
    val username: String = "",
    val password: String = "",
    /** URI de dossiers (arbres SAF, content://...) choisis via le sélecteur système,
     *  séparés par "|". Nécessaire car les chemins fixes type DCIM/Pictures ne sont
     *  plus fiables avec le stockage scopé (API 29+). */
    val sourceFolderUris: String = "",
    val wifiSsid: String? = null,   // null = n'importe quel Wi-Fi
    val requireCharging: Boolean = false,
    val conflictResolutionStrategy: ConflictResolutionStrategy = ConflictResolutionStrategy.SKIP,
    val lastSyncTimestamp: Long = 0L
) {
    fun sourceFolderUriList(): List<String> =
        sourceFolderUris.split("|").filter { it.isNotBlank() }
}
