package fr.bonobo.synczen.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** Private cache only. Must be used while holding SyncExecutionGate. No remote cleanup. */
internal class LocalStaging(cache: File) {
    private val directory = File(cache, "synczen-staging-v1")
    private val ownedName = Regex("stage-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part")
    init {
        if (Files.isSymbolicLink(directory.toPath()) || (!directory.isDirectory && !directory.mkdir()))
            throw SyncConflict("Cache de transfert inaccessible ; aucun nettoyage effectué")
    }
    fun removeOrphans() {
        val entries = directory.listFiles() ?: throw SyncConflict("Cache de transfert illisible")
        for (entry in entries) {
            if (ownedName.matches(entry.name) && Files.isRegularFile(entry.toPath(), NOFOLLOW_LINKS) && !entry.delete())
                throw SyncConflict("Temporaire local interrompu impossible à libérer")
        }
    }
    fun create(expectedSize: Long?): File {
        if (expectedSize != null && expectedSize > directory.usableSpace)
            throw SyncConflict("Espace local insuffisant pour l'instantané du fichier ; aucun transfert lancé")
        val file = File(directory, "stage-${UUID.randomUUID()}.part")
        if (!file.createNewFile()) throw SyncConflict("Création exclusive du temporaire local refusée")
        return file
    }
}
