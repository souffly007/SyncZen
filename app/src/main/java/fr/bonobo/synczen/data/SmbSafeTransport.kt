package fr.bonobo.synczen.data

import jcifs.CIFSContext
import jcifs.SmbConstants
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import java.io.File
import java.net.URI

internal class SmbSafeTransport(private val base: String, private val context: CIFSContext) : SafeRemoteTransport {
    init {
        val uri = URI(base)
        require(uri.scheme.equals("smb", true) && !uri.host.isNullOrEmpty() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null) { "Destination SMB invalide" }
        val segments = uri.rawPath.split('/').filter { it.isNotEmpty() }.map { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        require(segments.isNotEmpty()) { "Partage SMB obligatoire" }
        segments.forEach { RemotePath.validateSegment(it) }
    }
    private fun resource(segments: List<String>, directory: Boolean = false): SmbFile {
        segments.forEach { RemotePath.validateSegment(it, true) }
        return SmbFile(base + segments.joinToString("/") + if (directory) "/" else "", context)
    }
    override suspend fun verifyPublicationSupport() {
        // smbContext restricts negotiation to SMB2/SMB3. The library maps O_EXCL to FILE_CREATE
        // and renameTo(..., false) to FileRenameInformation2.replaceIfExists=false.
        check(context.config.minimumVersion.isSMB2) { "Publication sûre exige SMB2 ou SMB3" }
    }
    override suspend fun fingerprint(path: RemotePath): FileFingerprint? = resource(path.segments).use {
        if (!it.exists()) return null
        if (!it.isFile) throw SyncConflict("Conflit SMB : destination existante non régulière")
        it.inputStream.useCancellable { input -> FileFingerprint.read(input) }
    }
    override suspend fun ensureParents(path: RemotePath) {
        path.parents.forEach { segments ->
            resource(segments, true).use { directory ->
                if (!directory.exists()) {
                    try { directory.mkdir() } catch (e: SmbException) {
                        // A concurrent creation is acceptable ONLY if this is now a directory.
                        if (!directory.exists() || !directory.isDirectory) throw e
                    }
                }
                if (!directory.isDirectory) throw SyncConflict("Conflit SMB : parent non répertoire")
            }
        }
    }
    override suspend fun uploadExclusive(path: RemotePath, file: File) {
        require(path.isTemporary) { "Le contenu source doit être envoyé uniquement vers un temporaire" }
        resource(path.segments).use { temporary ->
            temporary.openOutputStream(false, SmbConstants.O_WRONLY or SmbConstants.O_CREAT or SmbConstants.O_EXCL,
                SmbConstants.FILE_WRITE_DATA, SmbConstants.FILE_NO_SHARE).useCancellable { output ->
                file.inputStream().use { input -> copyBounded(input, output) }
            }
        }
    }
    override suspend fun publishNoReplace(temporary: RemotePath, final: RemotePath) {
        resource(temporary.segments).use { source -> resource(final.segments).use { target ->
            try { source.renameTo(target, false) } catch (e: SmbException) {
                throw SyncConflict("Conflit ou publication SMB sans écrasement refusée ; temporaire conservé")
            }
        } }
    }

    override suspend fun publishWithOverwrite(temporary: RemotePath, final: RemotePath) {
        resource(temporary.segments).use { source -> resource(final.segments).use { target ->
            try {
                // Supprimer la cible si elle existe
                if (target.exists()) {
                    target.delete()
                }
                // Renommer le temporaire vers la cible
                source.renameTo(target, false)
            } catch (e: SmbException) {
                throw SyncConflict("Impossible d'écraser le fichier SMB: ${e.message}")
            }
        } }
    }

    override suspend fun delete(path: RemotePath) {
        resource(path.segments).use { file ->
            if (file.exists()) {
                try {
                    file.delete()
                } catch (e: SmbException) {
                    throw SyncConflict("Impossible de supprimer le fichier SMB: ${e.message}")
                }
            }
        }
    }
}
