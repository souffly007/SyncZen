package fr.bonobo.synczen.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ServerType { SMB, WEBDAV }

/**
 * Configuration de la cible de sauvegarde (NAS / serveur) et des dossiers
 * locaux à surveiller. Une seule ligne active à la fois pour le MVP.
 */
@Entity(tableName = "nas_config")
data class NasConfig(
    @PrimaryKey val id: Int = 1,
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
    val lastSyncTimestamp: Long = 0L
) {
    fun sourceFolderUriList(): List<String> =
        sourceFolderUris.split("|").filter { it.isNotBlank() }
}
