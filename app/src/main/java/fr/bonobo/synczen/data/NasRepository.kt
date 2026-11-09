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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.Properties
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

sealed class NasResult {
    data class Success(val message: String) : NasResult()
    data class Failure(val message: String) : NasResult()
    data class Skipped(val message: String) : NasResult()
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
class NasRepository internal constructor(
    private val config: NasConfig,
    private val appContext: Context? = null,
    private val webDavClientFactory: () -> OkHttpClient = { OkHttpClient() }
) {

    private val httpClient by lazy { WebDavSecurity.client(webDavClientFactory()) }

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
                    val url = WebDavSecurity.requireHttpsUrl(ensureTrailingSlash(config.address))
                    val request = Request.Builder()
                        .url(url)
                        .method("PROPFIND", null)
                        .header("Depth", "0")
                        .header("Authorization", basicAuthHeader())
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.code in 300..399) {
                            NasResult.Failure(WebDavSecurity.redirectMessage(response.code))
                        } else if (response.isSuccessful || response.code == 207) {
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

    /** Legacy flat uploads are intentionally unavailable: a safe path and persistent journal are required. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun uploadFile(fileName: String, input: InputStream): NasResult = withContext(Dispatchers.IO) {
        try {
            if (config.serverType == ServerType.WEBDAV) WebDavSecurity.requireHttpsUrl(config.address)
            NasResult.Failure("Publication refusée : utilisez un chemin source sécurisé et le suivi local ; ancien envoi à plat désactivé.")
        } catch (e: Exception) {
            NasResult.Failure(describeError(e))
        } finally { input.close() }
    }

    internal fun safeTransport(): SafeRemoteTransport {
        ensureWifiBinding()
        return when (config.serverType) {
            ServerType.SMB -> SmbSafeTransport(normalizeSmbAddress(config.address), smbContext())
            ServerType.WEBDAV -> {
                WebDavSecurity.requireHttpsUrl(config.address)
                WebDavSafeTransport(config.address, httpClient, basicAuthHeader())
            }
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
        if (e is WebDavSecurityException) return e.message.orEmpty()
        if (config.serverType == ServerType.WEBDAV &&
            (e is SSLHandshakeException || e is SSLPeerUnverifiedException)) {
            return "Connexion WebDAV TLS refusée : vérifiez le certificat du serveur et son nom d'hôte. Aucun contournement de validation TLS."
        }
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
