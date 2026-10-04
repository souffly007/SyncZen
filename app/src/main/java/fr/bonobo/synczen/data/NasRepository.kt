package fr.bonobo.synczen.data

import android.content.Context
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.Properties

sealed class NasResult {
    data class Success(val message: String) : NasResult()
    data class Failure(val message: String) : NasResult()
}

/**
 * Gère la communication réseau de base avec la cible de sauvegarde :
 * test de connexion et envoi de fichiers, en SMB (JCIFS-NG) ou WebDAV (OkHttp).
 *
 * [appContext] est utilisé pour forcer les connexions à passer par le Wi-Fi
 * actif (voir NetworkUtils) — indispensable pour joindre un NAS local quand
 * les données mobiles sont également actives sur le téléphone. Passer null
 * revient au comportement précédent (routage par défaut du système), ce qui
 * suffit sur émulateur mais échoue en pratique sur un vrai téléphone avec
 * données mobiles activées.
 */
class NasRepository(
    private val config: NasConfig,
    private val appContext: Context? = null
) {

    private val httpClient by lazy { OkHttpClient() }

    private fun smbContext(): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
        }
        val base = BaseContext(PropertyConfiguration(props))
        return base.withCredentials(
            NtlmPasswordAuthenticator("", config.username, config.password)
        )
    }

    /** Force le process à utiliser le Wi-Fi actif avant toute opération réseau. */
    private fun ensureWifiBinding() {
        appContext?.let { NetworkUtils.bindToWifiIfAvailable(it) }
    }

    suspend fun testConnection(): NasResult = withContext(Dispatchers.IO) {
        ensureWifiBinding()
        try {
            when (config.serverType) {
                ServerType.SMB -> {
                    val smbFile = SmbFile(normalizeSmbAddress(config.address), smbContext())
                    if (smbFile.exists() && smbFile.isDirectory) {
                        NasResult.Success("Connexion SMB réussie")
                    } else {
                        NasResult.Failure("Le partage SMB est introuvable ou inaccessible")
                    }
                }
                ServerType.WEBDAV -> {
                    val request = Request.Builder()
                        .url(ensureTrailingSlash(config.address))
                        .method("PROPFIND", null)
                        .header("Depth", "0")
                        .header("Authorization", basicAuthHeader())
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful || response.code == 207) {
                            NasResult.Success("Connexion WebDAV réussie")
                        } else {
                            NasResult.Failure("Erreur WebDAV : HTTP ${response.code}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            NasResult.Failure(describeError(e))
        }
    }

    /**
     * Envoie le contenu d'un flux (typiquement ouvert depuis une URI SAF via
     * ContentResolver) vers la racine du dossier distant configuré, sous le nom
     * [fileName]. Le flux est entièrement chargé en mémoire pour le PUT WebDAV
     * (OkHttp ne propose pas de RequestBody streaming simple sans dépendance
     * supplémentaire) : à surveiller pour de très gros fichiers.
     */
    suspend fun uploadFile(fileName: String, input: InputStream): NasResult = withContext(Dispatchers.IO) {
        ensureWifiBinding()
        try {
            when (config.serverType) {
                ServerType.SMB -> {
                    val remotePath = normalizeSmbAddress(config.address) + fileName
                    val smbFile = SmbFile(remotePath, smbContext())
                    smbFile.outputStream.use { out -> input.copyTo(out) }
                    NasResult.Success("Fichier envoyé : $fileName")
                }
                ServerType.WEBDAV -> {
                    val url = ensureTrailingSlash(config.address) + fileName
                    val bytes = input.readBytes()
                    val body: RequestBody = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                    val request = Request.Builder()
                        .url(url)
                        .put(body)
                        .header("Authorization", basicAuthHeader())
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            NasResult.Success("Fichier envoyé : $fileName")
                        } else {
                            NasResult.Failure("Échec envoi $fileName : HTTP ${response.code}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            NasResult.Failure("Échec envoi $fileName : ${describeError(e)}")
        } finally {
            input.close()
        }
    }

    private fun basicAuthHeader(): String = Credentials.basic(config.username, config.password)

    /** Accepte smb://, \\PC\partage ou simplement PC/partage saisi par l'utilisateur. */
    private fun normalizeSmbAddress(value: String): String {
        val cleaned = value.trim().replace('\\', '/')
        val withScheme = if (cleaned.startsWith("smb://", ignoreCase = true)) cleaned else "smb://$cleaned"
        return ensureTrailingSlash(withScheme)
    }

    private fun ensureTrailingSlash(path: String): String =
        if (path.endsWith("/")) path else "$path/"

    /** Ajoute un indice utile dans les cas d'erreur réseau les plus fréquents. */
    private fun describeError(e: Exception): String {
        val raw = e.message ?: e.javaClass.simpleName
        return when {
            appContext == null ->
                "$raw (le process n'est pas forcé sur le Wi-Fi — vérifie que NasRepository reçoit bien un Context)"
            raw.contains("timed out", ignoreCase = true) || raw.contains("unreachable", ignoreCase = true) ->
                "$raw — vérifie que le téléphone et le NAS sont sur le même Wi-Fi, et que l'isolation AP est désactivée sur le routeur"
            else -> raw
        }
    }
}
