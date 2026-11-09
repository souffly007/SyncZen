package fr.bonobo.synczen.data

import androidx.documentfile.provider.DocumentFile

internal data class SourceFileCandidate(val document: DocumentFile, val path: RemotePath)

/** Validates an entire root before returning any candidates for that source. No network operations. */
internal object SourceTreeScanner {
    fun scan(sourceUri: String, root: DocumentFile): List<SourceFileCandidate> {
        val result = mutableListOf<SourceFileCandidate>()
        val seen = mutableSetOf<String>()
        fun visit(dir: DocumentFile, relative: List<String>) {
            if (!dir.exists() || !dir.isDirectory || !dir.canRead()) throw SyncConflict("Dossier source inaccessible : aucun fichier supprimé")
            if (!seen.add(dir.uri.toString())) throw SyncConflict("Cycle ou alias de dossier SAF")
            require(relative.size < 62) { "Arborescence trop profonde" }
            for (child in dir.listFiles()) {
                val name = child.name ?: throw SyncConflict("Nom source absent : chemin non publiable")
                RemotePath.validateSegment(name)
                val path = relative + name
                when {
                    child.isDirectory -> visit(child, path)
                    child.isFile -> {
                        if (!child.canRead()) throw SyncConflict("Fichier source illisible")
                        result.add(SourceFileCandidate(child, RemotePath.source(sourceUri, path)))
                    }
                }
            }
        }
        visit(root, emptyList())
        return result
    }
}
