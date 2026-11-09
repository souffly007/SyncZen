package fr.bonobo.synczen.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal data class FileFingerprint(val sha256: String, val size: Long) {
    companion object {
        suspend fun read(input: InputStream): FileFingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count); total += count
            }
            return FileFingerprint(digest.digest().joinToString("") { "%02x".format(it) }, total)
        }
    }
}

internal interface SafeRemoteTransport {
    suspend fun verifyPublicationSupport()
    suspend fun fingerprint(path: RemotePath): FileFingerprint?
    suspend fun ensureParents(path: RemotePath)
    suspend fun uploadExclusive(path: RemotePath, file: File)
    suspend fun publishNoReplace(temporary: RemotePath, final: RemotePath)
    suspend fun publishWithOverwrite(temporary: RemotePath, final: RemotePath)
    suspend fun delete(path: RemotePath)
}

internal interface PublicationJournal {
    suspend fun get(destination: String, pathKey: String): SyncFileRecord?
    suspend fun put(record: SyncFileRecord)
}

internal class RoomPublicationJournal(private val dao: SyncFileDao) : PublicationJournal {
    override suspend fun get(destination: String, pathKey: String) = dao.get(destination, pathKey)
    override suspend fun put(record: SyncFileRecord) = dao.put(record)
}

/**
 * Publie les fichiers vers un serveur distant de manière sûre et atomique.
 * 
 * Fonctionnalités :
 * - Vérification de l'intégrité via SHA-256
 * - Publication atomique (upload vers temporaire puis rename)
 * - Gestion des conflits selon la stratégie configurée
 * - Journalisation des publications pour reprise après erreur
 * 
 * @param destinationId Identifiant unique de la destination (généré à partir de l'adresse du serveur)
 * @param remote Transport pour les opérations distantes (SMB ou WebDAV)
 * @param journal Journal de persistence pour le suivi des fichiers publiés
 * @param conflictStrategy Stratégie à appliquer en cas de conflit de fichier existant
 */
internal class SafeFilePublisher(
    private val destinationId: String,
    private val remote: SafeRemoteTransport,
    private val journal: PublicationJournal,
    private val conflictStrategy: ConflictResolutionStrategy = ConflictResolutionStrategy.SKIP
) {
    /**
     * Publie un fichier vers le serveur distant.
     * 
     * @param path Chemin de destination pour le fichier
     * @param file Fichier local à publier (doit exister et être lisible)
     * @param expectedSize Taille attendue du fichier (pour validation)
     * @return Résultat de la publication (succès, échec ou sauté)
     */
    suspend fun publish(path: RemotePath, file: File, expectedSize: Long? = null): NasResult {
        var temporary: RemotePath? = null
        try {
            val content = file.inputStream().use { FileFingerprint.read(it) }
            if (expectedSize != null && content.size != expectedSize) {
                return NasResult.Failure("Transfert interrompu : taille source incohérente ; aucun fichier publié.")
            }
            val previous = journal.get(destinationId, path.collisionKey)
            if (previous != null && previous.remotePath != path.value) {
                return NasResult.Failure("Conflit de nom/casse/Unicode : ${path.value} ; aucun écrasement.")
            }
            val existing = remote.fingerprint(path)
            if (existing != null) {
                // Fichier existe déjà, appliquer la stratégie de résolution
                return handleExistingFile(path, file, content, existing, previous, conflictStrategy)
            }
            remote.verifyPublicationSupport()
            remote.ensureParents(path)
            // Record intent BEFORE sending: a lost publication reply can be recovered by hashing the final file.
            val record = SyncFileRecord(destinationId, path.collisionKey, path.value, content.sha256, content.size, false)
            journal.put(record)
            currentCoroutineContext().ensureActive()
            temporary = path.temporary()
            remote.uploadExclusive(temporary, file)
            if (remote.fingerprint(temporary) != content) {
                throw SyncConflict("Transfert incomplet ou altéré : publication refusée")
            }
            currentCoroutineContext().ensureActive()
            remote.publishNoReplace(temporary, path)
            if (remote.fingerprint(path) != content) {
                throw SyncConflict("Publication non vérifiée : fichier conservé, intervention nécessaire")
            }
            journal.put(record.copy(published = true))
            return NasResult.Success("Fichier publié sans écrasement : ${path.value}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = when (e) {
                is SyncConflict, is WebDavSecurityException -> e.message.orEmpty()
                is javax.net.ssl.SSLHandshakeException, is javax.net.ssl.SSLPeerUnverifiedException ->
                    "Connexion WebDAV TLS refusée : vérifiez le certificat et son nom d'hôte ; validation TLS conservée"
                else -> "Transfert interrompu ou publication incertaine ; une relance vérifiera la destination"
            }
            return NasResult.Failure("$reason${temporary?.let { " ; temporaire conservé : ${it.value}" } ?: ""}")
        }
    }

    /**
     * Gère les cas où un fichier existe déjà sur le serveur.
     * Applique la stratégie de résolution de conflits configurée.
     */
    private suspend fun handleExistingFile(
        path: RemotePath,
        file: File,
        content: FileFingerprint,
        existing: FileFingerprint,
        previous: SyncFileRecord?,
        strategy: ConflictResolutionStrategy
    ): NasResult {
        // Si le fichier est identique à celui déjà publié, marquer comme vérifié
        if (previous != null && existing == content &&
            previous.sha256 == content.sha256 && previous.size == content.size) {
            journal.put(previous.copy(published = true))
            return NasResult.Skipped("Déjà publié et vérifié : ${path.value}")
        }

        return when (strategy) {
            ConflictResolutionStrategy.SKIP -> {
                NasResult.Skipped("Fichier existant sauté : ${path.value}")
            }
            ConflictResolutionStrategy.OVERWRITE -> {
                // Écraser le fichier existant
                publishWithOverwrite(path, file, content)
            }
            ConflictResolutionStrategy.RENAME -> {
                // Renommer avec un timestamp
                publishWithRename(path, file, content)
            }
        }
    }

    /**
     * Publie un fichier en écrasant l'existant.
     */
    private suspend fun publishWithOverwrite(path: RemotePath, file: File, content: FileFingerprint): NasResult {
        var temporary: RemotePath? = null
        try {
            // Supprimer l'existant d'abord si possible
            try {
                remote.ensureParents(path)
                // Pour WebDAV, on peut utiliser DELETE puis PUT
                // Pour SMB, renameTo avec overwrite
                // On va utiliser uploadExclusive qui va échouer si le fichier existe
                // Donc on va devoir faire un upload + rename avec overwrite
            } catch (e: Exception) {
                // Ignorer, on va essayer de publier quand même
            }
            
            // Créer un temporaire et publier
            temporary = path.temporary()
            val record = SyncFileRecord(destinationId, path.collisionKey, path.value, content.sha256, content.size, false)
            journal.put(record)
            
            remote.uploadExclusive(temporary, file)
            
            // Pour l'overwrite, on va essayer de publier avec écrasement
            try {
                // Essayer de publier avec écrasement
                remote.publishWithOverwrite(temporary, path)
            } catch (e: Exception) {
                // Si publishWithOverwrite n'existe pas, essayer de supprimer puis publier
                try {
                    remote.delete(path)
                    remote.publishNoReplace(temporary, path)
                } catch (e2: Exception) {
                    throw SyncConflict("Impossible d'écraser le fichier existant: ${e2.message}")
                }
            }
            
            if (remote.fingerprint(path) != content) {
                throw SyncConflict("Publication non vérifiée après écrasement")
            }
            
            journal.put(record.copy(published = true))
            return NasResult.Success("Fichier écrasé : ${path.value}")
        } catch (e: Exception) {
            return NasResult.Failure("Erreur lors de l'écrasement : ${e.message}")
        }
    }

    /**
     * Publie un fichier avec un nom modifié (ajout d'un timestamp).
     */
    private suspend fun publishWithRename(path: RemotePath, file: File, content: FileFingerprint): NasResult {
        var temporary: RemotePath? = null
        try {
            // Créer un nouveau chemin avec un timestamp
            val timestamp = System.currentTimeMillis()
            val newName = "${path.segments.last()}_$timestamp"
            val newPath = path.sibling(newName)
            
            remote.verifyPublicationSupport()
            remote.ensureParents(newPath)
            
            val record = SyncFileRecord(destinationId, newPath.collisionKey, newPath.value, content.sha256, content.size, false)
            journal.put(record)
            
            temporary = newPath.temporary()
            remote.uploadExclusive(temporary, file)
            
            if (remote.fingerprint(temporary) != content) {
                throw SyncConflict("Transfert incomplet ou altéré")
            }
            
            remote.publishNoReplace(temporary, newPath)
            
            if (remote.fingerprint(newPath) != content) {
                throw SyncConflict("Publication non vérifiée")
            }
            
            journal.put(record.copy(published = true))
            return NasResult.Success("Fichier renommé et publié : ${newPath.value}")
        } catch (e: Exception) {
            return NasResult.Failure("Erreur lors du renommage : ${e.message}")
        }
    }
}
